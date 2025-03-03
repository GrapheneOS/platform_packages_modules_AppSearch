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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.appsearch.util.ExceptionUtil;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.system.virtualmachine.VirtualMachineManager;
import android.util.ArrayMap;
import android.util.Log;

import com.android.appsearch.flags.Flags;
import com.android.internal.annotations.GuardedBy;
import com.android.server.appsearch.ServiceAppSearchConfig;

import com.google.android.icing.IcingSearchEngineInterface;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Manages the isolated storage service and provides related services. */
public final class IsolatedStorageServiceManager {
    private static final String TAG = "IsolatedStorageServiceM";

    // TODO: b/389105038 - remove the temporary workaround for binder transaction limit.
    // Binder RPC max transaction allocation is 600 KiB. We use 512 KiB here since the current
    // max doc size is 512 KiB, and also leave some room for non-page fields in the response protos.
    public static final int DEFAULT_MAX_PAGE_BYTES_LIMIT_FOR_ISOLATED_STORAGE = 512 * 1024;

    /**
     * The default threshold to decide whether to use {@link android.os.SharedMemory SharedMemory}
     * for icing data passing between the isolated storage service and AppSearch.
     *
     * <p>This is a cautious value set to half of {@link android.os.IBinder#MAX_IPC_SIZE}.
     */
    public static final int DEFAULT_ICING_DATA_UNION_SIZE_THRESHOLD_BYTES = 32 * 1024;

    public static final String SYSTEM_PROPERTY_ENABLE_ISOLATED_STORAGE =
            "appsearch.feature.enable_isolated_storage";
    public static final long DEFAULT_MEMORY_BYTES = 1_000_000_000;
    private static final String ISOLATED_STORAGE_SERVICE =
            "com.android.appsearch.ISOLATED_STORAGE_SERVICE";
    private static final String ISOLATED_STORAGE_SERVICE_CLASS_NAME =
            "com.android.server.appsearch.isolated_storage_service.IsolatedStorageService";
    private static final int FUTURE_WAIT_TIMEOUT_SECONDS = 5;

    private final Context mContext;
    private final ServiceAppSearchConfig mAppSearchConfig;

    private final AtomicReference<IIsolatedStorageService> mIsolatedStorageServiceLocked =
            new AtomicReference<>();

    @GuardedBy("mIcingInstancesLocked")
    private final Map<UserHandle, IcingSearchEngineInterface> mIcingInstancesLocked =
            new ArrayMap<>();

    public IsolatedStorageServiceManager(
            @NonNull Context context, @NonNull ServiceAppSearchConfig appSearchConfig) {
        mContext = Objects.requireNonNull(context);
        mAppSearchConfig = Objects.requireNonNull(appSearchConfig);
    }

    /** Gets whether isolated storage should be used. */
    public static boolean useIsolatedStorage(Context context) {
        return isolatedStorageFlagsSet() && deviceSupportsVms(context);
    }

    /** Gets whether isolated storage flags are all set. */
    public static boolean isolatedStorageFlagsSet() {
        return Flags.enableIsolatedStorage()
                && SystemProperties.getBoolean(
                        SYSTEM_PROPERTY_ENABLE_ISOLATED_STORAGE, /* def= */ false);
    }

    private static boolean deviceSupportsVms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false;
        VirtualMachineManager vmm = context.getSystemService(VirtualMachineManager.class);
        // Devices that support AVF are not required to support protected VMs.
        return (vmm != null)
                && ((vmm.getCapabilities() & VirtualMachineManager.CAPABILITY_PROTECTED_VM) != 0);
    }

    /** Starts the isolated storage service if not already. */
    @WorkerThread
    public void startIsolatedStorageService() {
        if (mIsolatedStorageServiceLocked.get() != null) {
            return;
        }

        String packageName = maybeGetPackageName(mContext);
        if (packageName == null) {
            return;
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
            future.get(FUTURE_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "Unable to bind to " + ISOLATED_STORAGE_SERVICE, e);
            ExceptionUtil.handleException(e);
            return;
        }
        if (mIsolatedStorageServiceLocked.get() == null) {
            return;
        }
        waitForVmPayloadReady();
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

    private void waitForVmPayloadReady() {
        try {
            mIsolatedStorageServiceLocked.get().setup(createServiceConfig());
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to wait for pVM to be ready", e);
            ExceptionUtil.handleRemoteException(e);
        }
    }

    private ServiceConfig createServiceConfig() {
        ServiceConfig config = new ServiceConfig();
        config.pVmMemoryBytes = mAppSearchConfig.getIsolatedStorageMemoryBytes();
        config.icingDataUnionSizeThresholdBytes =
                mAppSearchConfig.getIsolatedStorageIcingDataUnionSizeThresholdBytes();
        return config;
    }

    /** Gets isolated storage backed icing instance for user. */
    @WorkerThread
    public @Nullable IcingSearchEngineInterface getIcingInstance(
            @NonNull UserHandle userHandle, @NonNull ServiceAppSearchConfig config) {
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(config);

        if (mIsolatedStorageServiceLocked.get() == null) {
            return null;
        }

        IcingSearchEngineInterface instance;
        synchronized (mIcingInstancesLocked) {
            instance = mIcingInstancesLocked.get(userHandle);
            if (instance == null) {
                Log.i(TAG, "getting isolated icing instance for user " + userHandle);
                try {
                    instance =
                            new IcingSearchEngine(
                                    mIsolatedStorageServiceLocked
                                            .get()
                                            .getIcingSearchEngine(userHandle.getIdentifier()),
                                    config.toIcingSearchEngineOptions(/* baseDir= */ "appsearch"),
                                    config.getIsolatedStorageIcingDataUnionSizeThresholdBytes());
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
            mIsolatedStorageServiceLocked.set(IIsolatedStorageService.Stub.asInterface(service));
            Log.i(TAG, "IsolatedStorageService connected");
            mFuture.complete(null);
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            Log.i(TAG, "IsolatedStorageService disconnected");
            mIsolatedStorageServiceLocked.set(null);
            mFuture.cancel(/* mayInterruptIfRunning= */ true);
        }

        @Override
        public void onBindingDied(ComponentName className) {
            Log.i(TAG, "IsolatedStorageService binding died");
            mIsolatedStorageServiceLocked.set(null);
            mFuture.cancel(/* mayInterruptIfRunning= */ true);
        }

        @Override
        public void onNullBinding(ComponentName className) {
            Log.i(TAG, "IsolatedStorageService null binding");
            mIsolatedStorageServiceLocked.set(null);
            mFuture.cancel(/* mayInterruptIfRunning= */ true);
        }
    }
}
