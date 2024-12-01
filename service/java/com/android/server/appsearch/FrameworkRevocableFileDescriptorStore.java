/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.appsearch;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.server.appsearch.external.localstorage.RevocableFileDescriptorStore;

import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Framework implementation of {@link RevocableFileDescriptorStore}.
 *
 * <p>We need to pass {@link ParcelFileDescriptor} cross binder to the SDK side in the framework.
 * When sending a {@link ParcelFileDescriptor} cross the binder, the system will generate a
 * duplicate {@link ParcelFileDescriptor} and pass that dup to the other process. AppSearch will
 * lose the control of that dup. We need wrap the {@link ParcelFileDescriptor} to a {@link
 * FrameworkRevocableFileDescriptor} to give us the ability the control the sending {@link
 * ParcelFileDescriptor}.
 *
 * <p>This class needs {@link ProxyFileDescriptorCallback} and a {@link Handler} to build revocable
 * file descriptors in AppSearch's server. Those could allow AppSearch's server side retain control
 * of the original {@link ParcelFileDescriptor} and generate a revocable {@link
 * ParcelFileDescriptor}. This RevocableFileDescriptor will be sent to the client. All operations
 * performed on the RevocableFileDescriptor will not directly affect the actual file. Instead,
 * AppSearch will monitor these operations on the RevocableFileDescriptor and apply them to the
 * actual file. Consequently, AppSearch can revoke and disable the RevocableFileDescriptor on the
 * client side at any time. These is not needed in the Jetpack since sent {@link
 * ParcelFileDescriptor} to caller won't cross the Binder.
 */
public class FrameworkRevocableFileDescriptorStore implements RevocableFileDescriptorStore {
    private static final Object sLock = new Object();
    // The name of the background thread for close listeners to run.
    private static final String HANDLER_NAME = "AppSearchBlobCloseListener";

    @GuardedBy("sLock")
    private static volatile Handler sRevocableFdHandler;

    /**
     * By default, when using a RevocableFileDescriptor, callbacks will be sent to the process' main
     * looper. In this case that would be system_server's main looper, which is a heavily contended
     * thread. It can also cause deadlocks, because the volume daemon 'vold' holds a lock while
     * making these callbacks to the system_server, while at the same time the system_server main
     * thread can make a call into vold, which requires that same vold lock. To avoid these issues,
     * use a separate thread for the RevocableFileDescriptor's requests, so that it can make
     * progress independently of system_server.
     */
    @NonNull
    static Handler getRevocableFdHandler() {
        synchronized (sLock) {
            if (sRevocableFdHandler != null) {
                return sRevocableFdHandler;
            }
            final HandlerThread t = new HandlerThread(HANDLER_NAME);
            t.start();
            sRevocableFdHandler = new Handler(t.getLooper());

            return sRevocableFdHandler;
        }
    }

    @GuardedBy("sLock")
    // <packageName, List<sent fds> map to tracking all sent fds.
    private final Map<String, List<FrameworkRevocableFileDescriptor>>
            mSentAppSearchParcelFileDescriptorsLocked = new ArrayMap<>();

    @NonNull private final Context mContext;

    @NonNull private final ServiceAppSearchConfig mConfig;

    public FrameworkRevocableFileDescriptorStore(
            @NonNull Context context, @NonNull ServiceAppSearchConfig config) {
        mContext = Objects.requireNonNull(context);
        mConfig = Objects.requireNonNull(config);
    }

    /**
     * Wraps the given {@link ParcelFileDescriptor} to {@link FrameworkRevocableFileDescriptor} to
     * allow AppSearch to control the sending {@link ParcelFileDescriptor}'s life cycle.
     *
     * <p>AppSearch will retain control of the original {@link ParcelFileDescriptor} and generate a
     * revocable {@link ParcelFileDescriptor} using {@link
     * FrameworkRevocableFileDescriptor#getRevocableFileDescriptor()}. This RevocableFileDescriptor
     * will be sent to the client. All operations performed on the RevocableFileDescriptor will not
     * directly affect the actual file. Instead, AppSearch will monitor these operations on the
     * RevocableFileDescriptor and apply them to the actual file. Consequently, AppSearch can revoke
     * and disable the RevocableFileDescriptor on the client side at any time.
     *
     * @param packageName The package name requesting the revocable file descriptor.
     * @param parcelFileDescriptor The original ParcelFileDescriptor to be wrapped.
     */
    @NonNull
    @Override
    public ParcelFileDescriptor wrapToRevocableFileDescriptor(
            @NonNull String packageName, ParcelFileDescriptor parcelFileDescriptor)
            throws IOException {
        FrameworkRevocableFileDescriptor revocableFileDescriptor =
                new FrameworkRevocableFileDescriptor(
                        mContext,
                        parcelFileDescriptor.getFileDescriptor(),
                        getRevocableFdHandler());
        addOnCloseListener(revocableFileDescriptor, packageName);
        addToSentAppSearchParcelFileDescriptorMap(revocableFileDescriptor, packageName);
        return revocableFileDescriptor.getRevocableFileDescriptor();
    }

    @Override
    public void revokeAll() throws IOException {
        synchronized (sLock) {
            for (String packageName : mSentAppSearchParcelFileDescriptorsLocked.keySet()) {
                revokeForPackage(packageName);
            }
        }
    }

    @Override
    public void revokeForPackage(@NonNull String packageName) throws IOException {
        synchronized (sLock) {
            List<FrameworkRevocableFileDescriptor> rfds =
                    mSentAppSearchParcelFileDescriptorsLocked.get(packageName);
            if (rfds != null) {
                for (int i = rfds.size() - 1; i >= 0; i--) {
                    rfds.get(i).revoke();
                }
            }
        }
    }

    @Override
    public void checkBlobStoreLimit(@NonNull String packageName) throws AppSearchException {
        synchronized (sLock) {
            List<FrameworkRevocableFileDescriptor> rfdsForPackage =
                    mSentAppSearchParcelFileDescriptorsLocked.get(packageName);
            if (rfdsForPackage == null) {
                return;
            }
            if (rfdsForPackage.size() >= mConfig.getMaxOpenBlobCount()) {
                throw new AppSearchException(
                        AppSearchResult.RESULT_OUT_OF_SPACE,
                        "Package \""
                                + packageName
                                + "\" exceeded limit of "
                                + mConfig.getMaxOpenBlobCount()
                                + " opened file descriptors. Some file descriptors "
                                + "must be closed to open additional ones.");
            }
        }
    }

    private void addOnCloseListener(
            @NonNull FrameworkRevocableFileDescriptor revocableFileDescriptor,
            @NonNull String packageName) {
        revocableFileDescriptor.addOnCloseListener(
                e -> {
                    synchronized (sLock) {
                        List<FrameworkRevocableFileDescriptor> fdsForPackage =
                                mSentAppSearchParcelFileDescriptorsLocked.get(packageName);
                        if (fdsForPackage != null) {
                            fdsForPackage.remove(revocableFileDescriptor);
                            if (fdsForPackage.isEmpty()) {
                                mSentAppSearchParcelFileDescriptorsLocked.remove(packageName);
                            }
                        }
                    }
                });
    }

    private void addToSentAppSearchParcelFileDescriptorMap(
            @NonNull FrameworkRevocableFileDescriptor revocableFileDescriptor,
            @NonNull String packageName) {
        synchronized (sLock) {
            List<FrameworkRevocableFileDescriptor> rfdsForPackage =
                    mSentAppSearchParcelFileDescriptorsLocked.get(packageName);
            if (rfdsForPackage == null) {
                rfdsForPackage = new ArrayList<>();
                mSentAppSearchParcelFileDescriptorsLocked.put(packageName, rfdsForPackage);
            }
            rfdsForPackage.add(revocableFileDescriptor);
        }
    }

    /**
     * The Variant of {@link ParcelFileDescriptor} that allows its creator to revoke all access to
     * the underlying resource.
     *
     * <p>This is useful when the code that originally opened a file needs to strongly assert that
     * any clients are completely hands-off for security purposes.
     *
     * <p>Copy from frameworks/base/core/java/android/os/RevocableFileDescriptor.java. We cannot use
     * the RevocableFileDescriptor directly because it is not a public API.
     */
    static class FrameworkRevocableFileDescriptor {
        private ParcelFileDescriptor.OnCloseListener mOnCloseListener;

        private final FileDescriptor mInner;
        private final ParcelFileDescriptor mOuter;

        private volatile boolean mRevoked;

        FrameworkRevocableFileDescriptor(
                @NonNull Context context, @NonNull FileDescriptor fd, @NonNull Handler handler)
                throws IOException {
            mInner = fd;
            StorageManager sm = context.getSystemService(StorageManager.class);
            mOuter =
                    sm.openProxyFileDescriptor(
                            ParcelFileDescriptor.MODE_READ_WRITE, mCallback, handler);
        }

        ParcelFileDescriptor getRevocableFileDescriptor() {
            return mOuter;
        }

        /**
         * Revoke all future access to the {@link ParcelFileDescriptor} returned by {@link
         * #getRevocableFileDescriptor()}. From this point forward, all operations will fail with
         * {@link OsConstants#EPERM}.
         */
        void revoke() {
            mRevoked = true;
            IoUtils.closeQuietly(mInner);
        }

        /**
         * Callback for indicating that {@link ParcelFileDescriptor} passed to the client process
         * ({@link #getRevocableFileDescriptor()}) has been closed.
         */
        void addOnCloseListener(ParcelFileDescriptor.OnCloseListener onCloseListener) {
            mOnCloseListener = onCloseListener;
        }

        public boolean isRevoked() {
            return mRevoked;
        }

        private final ProxyFileDescriptorCallback mCallback =
                new ProxyFileDescriptorCallback() {
                    private void checkRevoked() throws ErrnoException {
                        if (mRevoked) {
                            throw new ErrnoException("TAG", OsConstants.EPERM);
                        }
                    }

                    @Override
                    public long onGetSize() throws ErrnoException {
                        checkRevoked();
                        return Os.fstat(mInner).st_size;
                    }

                    @Override
                    public int onRead(long offset, int size, byte[] data) throws ErrnoException {
                        checkRevoked();
                        int n = 0;
                        while (n < size) {
                            try {
                                n += Os.pread(mInner, data, n, size - n, offset + n);
                                break;
                            } catch (InterruptedIOException e) {
                                n += e.bytesTransferred;
                            }
                        }
                        return n;
                    }

                    @Override
                    public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
                        checkRevoked();
                        int n = 0;
                        while (n < size) {
                            try {
                                n += Os.pwrite(mInner, data, n, size - n, offset + n);
                                break;
                            } catch (InterruptedIOException e) {
                                n += e.bytesTransferred;
                            }
                        }
                        return n;
                    }

                    @Override
                    public void onFsync() throws ErrnoException {
                        checkRevoked();
                        Os.fsync(mInner);
                    }

                    @Override
                    public void onRelease() {
                        mRevoked = true;
                        IoUtils.closeQuietly(mInner);
                        if (mOnCloseListener != null) {
                            mOnCloseListener.onClose(null);
                        }
                    }
                };
    }
}
