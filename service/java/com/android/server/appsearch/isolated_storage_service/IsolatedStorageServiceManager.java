/*
 * Copyright 2025 The Android Open Source Project
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
package com.android.server.appsearch.isolated_storage_service;

import static android.app.appsearch.AppSearchResult.RESULT_INTERNAL_ERROR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.util.ExceptionUtil;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.system.virtualmachine.VirtualMachine;
import android.system.virtualmachine.VirtualMachineManager;
import android.util.ArrayMap;
import android.util.Log;

import com.android.appsearch.flags.Flags;
import com.android.internal.annotations.GuardedBy;
import com.android.server.appsearch.ServiceAppSearchConfig;

import com.google.android.icing.IcingSearchEngineInterface;
import com.google.android.icing.proto.ResetResultProto;
import com.google.android.icing.proto.StatusProto;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Manages the isolated storage service and provides related services. */
public final class IsolatedStorageServiceManager {
    private static final String TAG = "IsolatedStorageServiceM";

    // TODO: b/389105038 - remove the temporary workaround for binder transaction limit.
    // Binder RPC max transaction allocation is 600 KiB. We use 512 KiB here since the current
    // max doc size is 512 KiB, and also leave some room for non-page fields in the response protos.
    public static final int DEFAULT_MAX_PAGE_BYTES_LIMIT_FOR_ISOLATED_STORAGE = 512 * 1024;

    public static final String SYSTEM_PROPERTY_ENABLE_ISOLATED_STORAGE =
            "ro.appsearch.feature.enable_isolated_storage";
    public static final long DEFAULT_MEMORY_BYTES = 512_000_000;
    public static final boolean DEFAULT_ISOLATED_STORAGE_ENABLED = true;
    public static final boolean DEFAULT_ISOLATED_STORAGE_MIGRATION_ENABLED = false;
    private static final String ISOLATED_STORAGE_SERVICE =
            "com.android.appsearch.ISOLATED_STORAGE_SERVICE";
    private static final String ISOLATED_STORAGE_SERVICE_CLASS_NAME =
            "com.android.server.appsearch.isolated_storage_service.IsolatedStorageService";
    private static final int BINDING_WAIT_TIMEOUT_SECONDS = 10;
    private static final int PAYLOAD_WAIT_TIMEOUT_SECONDS = 20;
    private static final int MAX_VM_START_RETRIES = 3;

    private final Context mContext;
    private final ServiceAppSearchConfig mAppSearchConfig;
    private final VmStateSignaler mVmStateSignaler;

    // The isolated storage service implemented by the apk to manage VM and pass VM connections.
    private volatile IIsolatedStorageService mIsolatedStorageService;
    // The isolated storage service implemented by the VM to access icing.
    private volatile com.android.isolated_storage_service.IIsolatedStorageService
            mVmIsolatedStorageService;

    @GuardedBy("mIcingInstancesLocked")
    private final Map<UserHandle, IcingSearchEngineInterface> mIcingInstancesLocked =
            new ArrayMap<>();

    public IsolatedStorageServiceManager(
            @NonNull Context context, @NonNull ServiceAppSearchConfig appSearchConfig) {
        mContext = Objects.requireNonNull(context);
        mAppSearchConfig = Objects.requireNonNull(appSearchConfig);
        mVmStateSignaler = new VmStateSignaler();
    }

    /** Gets whether isolated storage should be used. */
    public static boolean useIsolatedStorage(
            @NonNull Context context, @NonNull ServiceAppSearchConfig appSearchConfig) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(appSearchConfig);
        return appSearchConfig.getIsolatedStorageEnabled()
                && isolatedStorageFlagsSet()
                && deviceSupportsVmsAndNewApis(context);
    }

    /** Gets whether isolated storage flags are all set. */
    public static boolean isolatedStorageFlagsSet() {
        return Flags.enableIsolatedStorage()
                && SystemProperties.getBoolean(
                        SYSTEM_PROPERTY_ENABLE_ISOLATED_STORAGE, /* def= */ false);
    }

    /** Checks whether the device supports protect VMs, and new FD->IBinder VM APIs. */
    private static boolean deviceSupportsVmsAndNewApis(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            Log.i(
                    TAG,
                    "API level too low to support isolated storage service: "
                            + Build.VERSION.SDK_INT);
            return false;
        }
        VirtualMachineManager vmm = context.getSystemService(VirtualMachineManager.class);
        // Devices that support AVF are not required to support protected VMs.
        return (vmm != null)
                && ((vmm.getCapabilities() & VirtualMachineManager.CAPABILITY_PROTECTED_VM) != 0);
    }

    /** Removes the icing instance for the corresponding userHandle */
    public void removeUserInstance(UserHandle userHandle) {
        synchronized (mIcingInstancesLocked) {
            IcingSearchEngineInterface instance = mIcingInstancesLocked.remove(userHandle);
            if (instance != null) {
                // Delete the corresponding user data in isolated storage
                ResetResultProto result = instance.clearAndDestroy();
                if (result.getStatus().getCode() != StatusProto.Code.OK) {
                    Log.i(
                            TAG,
                            "Error while deleting isolated storage data for user: " + userHandle);
                }
                try {
                    // Remove the Icing connection from the VM Isolated Storage Service
                    mVmIsolatedStorageService.removeIcingConnection(userHandle.getIdentifier());
                } catch (RemoteException e) {
                    Log.e(
                            TAG,
                            "Unable to remove Isolated Icing connection for user: " + userHandle,
                            e);
                }
            }
        }
    }

    /** Binds to the isolated storage service if not already. */
    @WorkerThread
    private void bindIsolatedStorageService() throws AppSearchException {
        if (mIsolatedStorageService != null) {
            Log.i(TAG, "Isolated storage service already bound");
            return;
        }
        Log.i(TAG, "Binding to " + ISOLATED_STORAGE_SERVICE);

        String packageName = maybeGetPackageName(mContext);
        if (packageName == null) {
            throw new AppSearchException(
                    RESULT_INTERNAL_ERROR, "Unable to get isolated storage service package name");
        }

        Intent intent = new Intent();
        intent.setClassName(packageName, ISOLATED_STORAGE_SERVICE_CLASS_NAME);
        CompletableFuture<Void> future = new CompletableFuture<>();
        mContext.bindServiceAsUser(
                intent,
                new IsolatedStorageServiceConnection(future),
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                UserHandle.SYSTEM);
        try {
            future.get(BINDING_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "Unable to bind to " + ISOLATED_STORAGE_SERVICE, e);
            throw new AppSearchException(
                    RESULT_INTERNAL_ERROR, "Unable to bind to " + ISOLATED_STORAGE_SERVICE, e);
        }
        if (mIsolatedStorageService == null) {
            Log.e(TAG, "Unable to bind to " + ISOLATED_STORAGE_SERVICE);
            throw new AppSearchException(
                    RESULT_INTERNAL_ERROR, "Unable to bind to " + ISOLATED_STORAGE_SERVICE);
        }
    }

    /** Connects to the VM isolated storage service if not already. */
    @WorkerThread
    private void connectToVmIsolatedStorageService() throws AppSearchException {
        if (mVmIsolatedStorageService != null) {
            Log.i(TAG, "VM already connected");
            return;
        }
        Log.i(TAG, "Connecting to vm");
        waitForVmPayloadReady();
        try {
            IBinder iBinder =
                    VirtualMachine.binderFromPreconnectedClient(
                            () -> {
                                try {
                                    ParcelFileDescriptor pfd = getVmConnection();
                                    return pfd;
                                } catch (Exception e) {
                                    Log.e(TAG, "Unable to get vm connection", e);
                                    throw new RuntimeException(e);
                                }
                            });
            if (iBinder == null) {
                throw new NullPointerException("Null binder when connecting to VM");
            }
            mVmIsolatedStorageService =
                    com.android.isolated_storage_service.IIsolatedStorageService.Stub.asInterface(
                            iBinder);
        } catch (Exception e) {
            Log.e(TAG, "Unable to connect to vm", e);
            throw new AppSearchException(RESULT_INTERNAL_ERROR, "Unable to connect to vm", e);
        }
        if (mVmIsolatedStorageService == null) {
            Log.e(TAG, "Failed to connect to vm");
            throw new AppSearchException(RESULT_INTERNAL_ERROR, "Unable to connect to vm");
        }
        Log.i(TAG, "Successfully connected to vm");
    }

    /**
     * Gets the package name via service action name.
     *
     * <p>Return {@code null} if service not found.
     */
    private static @Nullable String maybeGetPackageName(@NonNull Context context) {
        Objects.requireNonNull(context);

        PackageManager pm = context.getPackageManager();
        Intent serviceIntent = new Intent(ISOLATED_STORAGE_SERVICE);
        List<ResolveInfo> resolveInfos =
                pm.queryIntentServices(
                        serviceIntent,
                        // Matches services from system applications that are direct boot aware
                        // or unaware.
                        PackageManager.GET_SERVICES
                                | PackageManager.MATCH_SYSTEM_ONLY
                                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        if (resolveInfos.isEmpty()) {
            Log.e(TAG, "Service " + ISOLATED_STORAGE_SERVICE + " not found");
            return null;
        }
        return resolveInfos.get(0).serviceInfo.packageName;
    }

    private void waitForVmPayloadReady() throws AppSearchException {
        boolean vmStarted = false;
        ServiceConfig serviceConfig = createServiceConfig();
        try {
            for (int i = 0; i < MAX_VM_START_RETRIES; i++) {
                if (mIsolatedStorageService.startVm(serviceConfig, PAYLOAD_WAIT_TIMEOUT_SECONDS)) {
                    vmStarted = true;
                    break;
                }
                Log.w(TAG, "Unable to wait for payload ready, retrying");
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to wait for pVM to be ready", e);
            throw new AppSearchException(
                    RESULT_INTERNAL_ERROR, "Failed to start VM and load payload");
        }
        if (!vmStarted) {
            throw new AppSearchException(
                    RESULT_INTERNAL_ERROR, "Unable to wait for payload ready after retries");
        }
    }

    @NonNull
    private ParcelFileDescriptor getVmConnection()
            throws RemoteException, NullPointerException, IllegalStateException {
        ParcelFileDescriptor pfd = mIsolatedStorageService.getVmConnection();
        if (pfd == null) {
            throw new NullPointerException("Null PFD VM connection");
        } else if (pfd.getFd() < 0) {
            throw new IllegalStateException("PFD VM connection is an invalid FD: " + pfd.getFd());
        }
        return pfd;
    }

    private ServiceConfig createServiceConfig() {
        ServiceConfig config = new ServiceConfig();
        config.pVmMemoryBytes = mAppSearchConfig.getIsolatedStorageMemoryBytes();
        config.pCachedSamplingInterval = mAppSearchConfig.getCachedSamplingIntervalDefault();
        config.pCachedMinTimeIntervalBetweenSamplesMillis =
                mAppSearchConfig.getCachedMinTimeIntervalBetweenSamplesMillis();
        return config;
    }

    /** Gets isolated storage backed icing instance for user. */
    @WorkerThread
    public @Nullable IcingSearchEngineInterface getIcingInstance(
            @NonNull UserHandle userHandle, @NonNull ServiceAppSearchConfig config)
            throws AppSearchException {
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(config);

        IcingSearchEngineInterface instance;
        synchronized (mIcingInstancesLocked) {
            if (mIsolatedStorageService == null) {
                bindIsolatedStorageService();
            }
            if (mVmIsolatedStorageService == null) {
                connectToVmIsolatedStorageService();
            }

            instance = mIcingInstancesLocked.get(userHandle);
            if (instance == null) {
                Log.i(TAG, "getting isolated icing instance for user " + userHandle);
                try {
                    instance =
                            new IcingSearchEngine(
                                    mVmIsolatedStorageService.getOrCreateIcingConnection(
                                            userHandle.getIdentifier()),
                                    config.toIcingSearchEngineOptions(
                                            /* baseDir= */ "appsearch", /* isVMEnabled= */ true),
                                    mVmStateSignaler);
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to get icing instance for " + userHandle, e);
                    ExceptionUtil.handleRemoteException(e);
                }

                if (instance != null) {
                    mIcingInstancesLocked.put(userHandle, instance);
                }
            }
        }
        return instance;
    }

    /** A connection to the isolated storage service. */
    private class IsolatedStorageServiceConnection implements ServiceConnection {
        private final CompletableFuture<Void> mFuture;

        IsolatedStorageServiceConnection(@NonNull CompletableFuture<Void> future) {
            mFuture = Objects.requireNonNull(future);
        }

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mIsolatedStorageService = IIsolatedStorageService.Stub.asInterface(service);
            Log.i(TAG, "IsolatedStorageService connected");
            mFuture.complete(null);
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            Log.i(TAG, "IsolatedStorageService disconnected");
            mIsolatedStorageService = null;
            mFuture.cancel(/* mayInterruptIfRunning= */ true);
        }

        @Override
        public void onBindingDied(ComponentName className) {
            Log.i(TAG, "IsolatedStorageService binding died");
            mIsolatedStorageService = null;
            mFuture.cancel(/* mayInterruptIfRunning= */ true);
        }

        @Override
        public void onNullBinding(ComponentName className) {
            Log.i(TAG, "IsolatedStorageService null binding");
            mIsolatedStorageService = null;
            mFuture.cancel(/* mayInterruptIfRunning= */ true);
        }
    }
}
