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
import static android.app.appsearch.AppSearchResult.RESULT_UNAVAILABLE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.util.ExceptionUtil;
import android.app.appsearch.util.LogUtil;
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
import com.android.isolated_storage_service.IIcingSearchEngine;
import com.android.server.appsearch.AppSearchComponentFactory;
import com.android.server.appsearch.InternalAppSearchLogger;
import com.android.server.appsearch.ServiceAppSearchConfig;
import com.android.server.appsearch.external.localstorage.stats.VmInitializationStats;
import com.android.server.appsearch.external.localstorage.stats.VmStartAttemptStats;

import com.google.android.icing.proto.IcingSearchEngineOptions;
import com.google.android.icing.proto.InitializeResultProto;
import com.google.android.icing.proto.StatusProto;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Manages the isolated storage service and provides related services. */
public final class IsolatedStorageServiceManager {
    private static final String TAG = "IsolatedStorageServiceM";

    // TODO: b/389105038 - remove the temporary workaround for binder transaction limit.
    // Binder RPC max transaction allocation is 600 KiB.
    // - We use 512 KiB here. This will leave some room for non-page fields in the response protos
    // - The current max doc size is 512 KiB, but the result page retrieval logic will add at least
    //   one document into the page even if it exceeds this limit, so we're still able to get a
    //   single giant document in a single page.
    public static final int DEFAULT_MAX_PAGE_BYTES_LIMIT_FOR_ISOLATED_STORAGE = 512 * 1024;

    public static final String SYSTEM_PROPERTY_ENABLE_ISOLATED_STORAGE =
            "ro.appsearch.feature.enable_isolated_storage";
    public static final String SYSTEM_PROPERTY_ENABLE_NONPROTECTED_APPSEARCH_VM =
            "ro.enable.nonprotected_appsearch_vm";
    public static final long DEFAULT_MEMORY_BYTES = 96 * 1024 * 1024;
    public static final boolean DEFAULT_ISOLATED_STORAGE_DISABLED = false;
    public static final boolean DEFAULT_ISOLATED_STORAGE_MIGRATION_DISABLED = false;
    public static final boolean DEFAULT_ISOLATED_STORAGE_DELETE_CE_VMS = false;
    private static final String ISOLATED_STORAGE_SERVICE =
            "com.android.appsearch.ISOLATED_STORAGE_SERVICE";
    private static final String ISOLATED_STORAGE_SERVICE_CLASS_NAME =
            "com.android.server.appsearch.isolated_storage_service.IsolatedStorageService";
    private static final int BINDING_WAIT_TIMEOUT_SECONDS = 10;
    private static final int PAYLOAD_WAIT_TIMEOUT_SECONDS = 61;
    private static final int MAX_VM_START_RETRIES = 3;
    private static final int MAX_REINITIALIZATION_RETRIES = 9;
    private static final int MAX_ICING_INITIALIZATION_RETRIES = 3;
    private static final long VM_STATUS_CHECK_INITIAL_DELAY_SECONDS = 120; // 2 minutes
    private static final long VM_STATUS_CHECK_INTERVAL_SECONDS = 60; // 1 minute

    private static final UserHandle ISOLATED_STORAGE_USER = UserHandle.SYSTEM;

    private final Context mContext;
    private final ServiceAppSearchConfig mAppSearchConfig;
    private final ScheduledExecutorService mScheduledExecutorService;
    private final VmStateSignaler mVmStateSignaler;
    private final IsolatedStorageServiceDeathRecipient mIsolatedStorageServiceDeathRecipient =
            new IsolatedStorageServiceDeathRecipient();
    private final VmIsolatedStorageServiceDeathRecipient mVmIsolatedStorageServiceDeathRecipient =
            new VmIsolatedStorageServiceDeathRecipient();
    private final ReentrantLock mLock = new ReentrantLock();

    private final VmDataUnlocker mVmUnlocker = new VmDataUnlocker();

    // The isolated storage service implemented by the apk to manage VM and pass VM connections.
    private volatile IIsolatedStorageService mIsolatedStorageService;
    private volatile boolean mIsReconnecting = false;

    // The isolated storage service implemented by the VM to access icing.
    @GuardedBy("mLock")
    private com.android.isolated_storage_service.IIsolatedStorageService
            mVmIsolatedStorageServiceLocked;

    @GuardedBy("mLock")
    private final Map<UserHandle, UserInstance> mUserInstancesLocked = new ArrayMap<>();

    private InternalAppSearchLogger mLogger;

    public IsolatedStorageServiceManager(
            @NonNull Context context,
            @NonNull ServiceAppSearchConfig appSearchConfig,
            @NonNull ScheduledExecutorService scheduledExecutorService) {
        mContext = Objects.requireNonNull(context);
        mAppSearchConfig = Objects.requireNonNull(appSearchConfig);
        mScheduledExecutorService = Objects.requireNonNull(scheduledExecutorService);
        mVmStateSignaler = new VmStateSignaler(mScheduledExecutorService);
        if (LogUtil.INFO) {
            Log.d(TAG, "Scheduling VM status check");
            mScheduledExecutorService.scheduleAtFixedRate(
                    this::checkVmStatus,
                    VM_STATUS_CHECK_INITIAL_DELAY_SECONDS,
                    VM_STATUS_CHECK_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
        }

        // TODO(b/422197198): consider using logger from the user instance instead of creating a new
        //   one here. For now, only 1 user is allowed to use the vm, but we should plan for
        //   multiple users in the future.
        mLogger = AppSearchComponentFactory.createLoggerInstance(mContext, appSearchConfig);
    }

    private void checkVmStatus() {
        if (mLock.tryLock()) {
            try {
                if (mIsolatedStorageService == null) {
                    Log.i(TAG, "Isolated storage service not connected");
                } else {
                    int status = mIsolatedStorageService.getVmStatus();
                    Log.i(TAG, "Isolated storage service VM status: " + status);
                }
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "Unable to get VM status", e);
            } finally {
                mLock.unlock();
            }
        } else {
            Log.d(TAG, "Unable to lock mLock in checkVmStatus");
        }
    }

    /** Gets whether isolated storage should be used. */
    public static boolean useIsolatedStorage(
            @NonNull Context context, @NonNull ServiceAppSearchConfig appSearchConfig) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(appSearchConfig);
        return !appSearchConfig.getIsolatedStorageDisabled()
                && isolatedStorageFlagsSet()
                && deviceSupportsVmsAndNewApis(context);
    }

    /** Gets whether isolated storage flags are all set. */
    private static boolean isolatedStorageFlagsSet() {
        return Flags.enableIsolatedStorage()
                && SystemProperties.getBoolean(
                        SYSTEM_PROPERTY_ENABLE_ISOLATED_STORAGE, /* def= */ false);
    }

    /** Checks whether the device supports protect VMs, and new FD->IBinder VM APIs. */
    public static boolean deviceSupportsVmsAndNewApis(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            Log.i(
                    TAG,
                    "API level too low to support isolated storage service: "
                            + Build.VERSION.SDK_INT);
            return false;
        }
        VirtualMachineManager vmm = context.getSystemService(VirtualMachineManager.class);
        // Devices that support AVF are not required to support protected VMs.
        if (vmm == null) {
            return false;
        }

        boolean protectedAppSearchVmEnabled =
                !SystemProperties.getBoolean(
                        SYSTEM_PROPERTY_ENABLE_NONPROTECTED_APPSEARCH_VM, /* def= */ false);
        return protectedAppSearchVmEnabled
                ? ((vmm.getCapabilities() & VirtualMachineManager.CAPABILITY_PROTECTED_VM) != 0)
                : true;
    }

    /** Cleans up the isolated storage service related data. */
    public static void cleanUp(@NonNull Context context) {
        String packageName = maybeGetPackageName(context);
        if (packageName == null) {
            Log.e(TAG, "Unable to get isolated storage service package name");
            return;
        }
        Intent intent = new Intent();
        intent.setClassName(packageName, ISOLATED_STORAGE_SERVICE_CLASS_NAME);
        ServiceConnection connection =
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        try {
                            IIsolatedStorageService.Stub.asInterface(service).deleteVm();
                            Log.i(TAG, "Deleted the VM");
                        } catch (Exception e) {
                            Log.e(TAG, "Unable to delete VM", e);
                        }
                        context.unbindService(this);
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {}
                };
        try {
            context.bindServiceAsUser(
                    intent,
                    connection,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                    ISOLATED_STORAGE_USER);
        } catch (Exception e) {
            Log.e(TAG, "Unable to bind to " + ISOLATED_STORAGE_SERVICE, e);
        }
    }

    /**
     * Initializes the isolated storage service if not already.
     *
     * <p>This will bind to the isolated storage service, start the VM, and connects to the VM if
     * not already.
     */
    public void initialize() throws AppSearchException {
        VmInitializationStats.Builder statsBuilder =
                new VmInitializationStats.Builder(VmInitializationStats.VM_INIT_TYPE_BOOTING);
        try {
            initialize(/* forceVmRestart= */ false, MAX_VM_START_RETRIES, statsBuilder);
        } finally {
            if (mLogger != null) {
                mLogger.logStats(statsBuilder.build());
            }
        }
    }

    /**
     * Initializes the isolated storage service if not already.
     *
     * <p>This will bind to the isolated storage service, start the VM, and connects to the VM if
     * not already.
     *
     * @param forceVmRestart Whether to force restarting the VM.
     * @param numRetries Number of retries when starting the VM.
     * @param statsBuilder nullable {@link VmInitializationStats.Builder} for stats report.
     */
    private void initialize(
            boolean forceVmRestart,
            int numRetries,
            @Nullable VmInitializationStats.Builder statsBuilder)
            throws AppSearchException {
        synchronized (mLock) {
            if (mIsolatedStorageService == null) {
                bindIsolatedStorageServiceLocked();
            }
            if (mVmIsolatedStorageServiceLocked == null) {
                connectToVmIsolatedStorageServiceLocked(forceVmRestart, numRetries, statsBuilder);
            }
        }
    }

    /** Called when the user unlocks the device. */
    public void onUserUnlocking(
            @NonNull ServiceAppSearchConfig appSearchConfig, @NonNull UserHandle userHandle) {
        Objects.requireNonNull(appSearchConfig);
        Objects.requireNonNull(userHandle);

        if (!isUserAllowed(userHandle)) {
            // Currently the Isolated Storage Service only stores data for the primary
            // user. Ignore calls for other users. In the future, when the Isolated Storage
            // service supports multiple users, we may handle this call appropriately.
            Log.i(TAG, "ignoring onUserUnlocking call for disallowed user " + userHandle);
            return;
        }

        Log.i(TAG, "onUserUnlocking");
        mVmStateSignaler.scheduleEnablement();
        mVmUnlocker.onUserUnlocking();

        synchronized (mLock) {
            if (mIsolatedStorageService != null
                    && appSearchConfig.getIsolatedStorageDeleteCeVms()) {
                try {
                    mIsolatedStorageService.cleanUpOldVms();
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to clean up old VMs", e);
                }
            }
        }
    }

    /** Removes the icing instance for the corresponding userHandle */
    public void removeUserInstance(UserHandle userHandle) {
        synchronized (mLock) {
            UserInstance userInstanceToRemove = mUserInstancesLocked.remove(userHandle);
            if (userInstanceToRemove != null && userInstanceToRemove.getEngine() != null) {
                try {
                    // Remove the Icing connection from the VM Isolated Storage Service
                    mVmIsolatedStorageServiceLocked.removeIcingConnection(
                            userHandle.getIdentifier());
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
    @GuardedBy("mLock")
    private void bindIsolatedStorageServiceLocked() throws AppSearchException {
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
                ISOLATED_STORAGE_USER);
        try {
            future.get(BINDING_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "Unable to bind to " + ISOLATED_STORAGE_SERVICE, e);
            throw new AppSearchException(
                    RESULT_UNAVAILABLE, "Unable to bind to " + ISOLATED_STORAGE_SERVICE, e);
        }
        if (mIsolatedStorageService == null) {
            Log.e(TAG, "Unable to bind to " + ISOLATED_STORAGE_SERVICE);
            throw new AppSearchException(
                    RESULT_UNAVAILABLE, "Unable to bind to " + ISOLATED_STORAGE_SERVICE);
        }
    }

    /** Connects to the VM isolated storage service if not already. */
    @WorkerThread
    @GuardedBy("mLock")
    private void connectToVmIsolatedStorageServiceLocked(
            boolean forceVmRestart,
            int numRetries,
            @Nullable VmInitializationStats.Builder statsBuilder)
            throws AppSearchException {
        if (mVmIsolatedStorageServiceLocked != null) {
            Log.i(TAG, "VM already connected");
            return;
        }
        Log.i(TAG, "Connecting to vm");
        waitForVmPayloadReadyLocked(forceVmRestart, numRetries, statsBuilder);
        try {
            IBinder iBinder =
                    VirtualMachine.binderFromPreconnectedClient(
                            () -> {
                                try {
                                    ParcelFileDescriptor pfd = getVmConnectionLocked();
                                    return pfd;
                                } catch (Exception e) {
                                    Log.e(TAG, "Unable to get vm connection", e);
                                    throw new RuntimeException(e);
                                }
                            });
            if (iBinder == null) {
                throw new NullPointerException("Null binder when connecting to VM");
            }
            mVmIsolatedStorageServiceLocked =
                    com.android.isolated_storage_service.IIsolatedStorageService.Stub.asInterface(
                            iBinder);
            iBinder.linkToDeath(mVmIsolatedStorageServiceDeathRecipient, /* flags= */ 0);
            mVmUnlocker.onVmAvailable();
        } catch (Exception e) {
            mVmIsolatedStorageServiceLocked = null;
            Log.e(TAG, "Unable to connect to vm", e);
            throw new AppSearchException(RESULT_UNAVAILABLE, "Unable to connect to vm", e);
        }
        if (mVmIsolatedStorageServiceLocked == null) {
            Log.e(TAG, "Failed to connect to vm");
            throw new AppSearchException(RESULT_UNAVAILABLE, "Unable to connect to vm");
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

    @GuardedBy("mLock")
    private void waitForVmPayloadReadyLocked(
            boolean forceVmRestart,
            int numRetries,
            @Nullable VmInitializationStats.Builder statsBuilder)
            throws AppSearchException {
        boolean vmStarted = false;
        ServiceConfig serviceConfig = createServiceConfig();

        try {
            for (int i = 0; i < numRetries; i++) {
                VmStartResult result =
                        mIsolatedStorageService.startVm(
                                serviceConfig, PAYLOAD_WAIT_TIMEOUT_SECONDS, forceVmRestart);
                if (statsBuilder != null) {
                    statsBuilder.addStartAttemptStats(
                            new VmStartAttemptStats.Builder()
                                    .setStatusCode(result.pStatusCode)
                                    .setLatencyMillis(result.pTotalLatencyMillis)
                                    .build());
                }

                if (result.pStatusCode == VmStartAttemptStats.VM_START_STATUS_SUCCESS) {
                    vmStarted = true;
                    break;
                }
                Log.w(TAG, "Unable to wait for payload ready, retrying");
                forceVmRestart = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to wait for pVM to be ready", e);
            if (statsBuilder != null) {
                statsBuilder.addStartAttemptStats(
                        new VmStartAttemptStats.Builder()
                                .setStatusCode(VmStartAttemptStats.VM_START_STATUS_ERROR)
                                .build());
            }
            throw new AppSearchException(RESULT_UNAVAILABLE, "Failed to start VM and load payload");
        }
        if (!vmStarted) {
            throw new AppSearchException(
                    RESULT_UNAVAILABLE, "Unable to wait for payload ready after retries");
        }
    }

    @NonNull
    @GuardedBy("mLock")
    private ParcelFileDescriptor getVmConnectionLocked()
            throws RemoteException, NullPointerException, IllegalStateException {
        ParcelFileDescriptor pfd = mIsolatedStorageService.getVmConnection();
        if (pfd == null) {
            throw new NullPointerException("Null PFD VM connection");
        } else if (pfd.getFd() < 0) {
            throw new IllegalStateException("PFD VM connection is an invalid FD: " + pfd.getFd());
        }
        return pfd;
    }

    /**
     * Return true iff data from this user can be stored in isolatedStorage.
     *
     * <p>To ensure that data is encrypted with the appropriate credential encryption (CE) keys, we
     * restrict Isolated Storage usage to only users running with the same ID as the Isolated
     * Storage Service user. Other users should store their data in conventionally protected
     * storage, which has equivalent security properties.
     */
    public static boolean isUserAllowed(@NonNull UserHandle id) {
        return ISOLATED_STORAGE_USER.equals(id);
    }

    private ServiceConfig createServiceConfig() {
        ServiceConfig config = new ServiceConfig();
        config.pVmMemoryBytes = mAppSearchConfig.getIsolatedStorageMemoryBytes();
        config.pCachedSamplingInterval = mAppSearchConfig.getCachedSamplingIntervalDefault();
        config.pCachedMinTimeIntervalBetweenSamplesMillis =
                mAppSearchConfig.getCachedMinTimeIntervalBetweenSamplesMillis();
        return config;
    }

    /** Signals that a VM activity starts. */
    void signalActivityStarts() {
        mVmStateSignaler.signalActivityStarts();
    }

    /** Signals that a VM activity ends. */
    void signalActivityEnds() {
        mVmStateSignaler.signalActivityEnds();
    }

    void setIcingSearchEngineOptions(
            @NonNull UserHandle userHandle, @NonNull IcingSearchEngineOptions options) {
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(options);
        synchronized (mLock) {
            mUserInstancesLocked.put(userHandle, new UserInstance(options));
        }
    }

    boolean isReconnecting() {
        return mIsReconnecting;
    }

    @NonNull
    Future<com.android.isolated_storage_service.IIsolatedStorageService>
            getVmIsolatedStorageServiceAsync() {
        CompletableFuture<com.android.isolated_storage_service.IIsolatedStorageService> future =
                new CompletableFuture<>();
        mScheduledExecutorService.execute(
                () -> {
                    synchronized (mLock) {
                        try {
                            // initialize the isolated storage service if not already
                            initialize();
                        } catch (AppSearchException e) {
                            future.completeExceptionally(e);
                            return;
                        }
                        future.complete(mVmIsolatedStorageServiceLocked);
                    }
                });
        return future;
    }

    @Nullable
    IIcingSearchEngine getVmIcingInstanceOrNull(@NonNull UserHandle userHandle) {
        synchronized (mLock) {
            UserInstance userInstance = mUserInstancesLocked.get(userHandle);
            return userInstance == null ? null : userInstance.getEngine();
        }
    }

    @NonNull
    Future<IIcingSearchEngine> getOrCreateVmIcingInstanceAsync(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);

        CompletableFuture<IIcingSearchEngine> future = new CompletableFuture<>();
        mScheduledExecutorService.execute(
                () -> {
                    IIcingSearchEngine instance;
                    synchronized (mLock) {
                        try {
                            instance = getOrCreateVmIcingInstanceLocked(userHandle);
                        } catch (Exception e) {
                            future.completeExceptionally(e);
                            return;
                        }
                        future.complete(instance);
                    }
                });
        return future;
    }

    @GuardedBy("mLock")
    @NonNull
    private IIcingSearchEngine getOrCreateVmIcingInstanceLocked(UserHandle userHandle)
            throws RemoteException, AppSearchException {
        // initialize the isolated storage service if not already
        initialize();
        UserInstance instance = mUserInstancesLocked.get(userHandle);
        if (instance.getEngine() == null) {
            instance.setEngine(
                    mVmIsolatedStorageServiceLocked.getOrCreateIcingConnection(
                            userHandle.getIdentifier()));
        }
        return instance.getEngine();
    }

    private static final class UserInstance {
        private final IcingSearchEngineOptions mOptions;
        private IIcingSearchEngine mEngine;

        UserInstance(IcingSearchEngineOptions options) {
            mOptions = options;
        }

        @NonNull
        IcingSearchEngineOptions getOptions() {
            return mOptions;
        }

        @Nullable
        IIcingSearchEngine getEngine() {
            return mEngine;
        }

        void setEngine(@NonNull IIcingSearchEngine engine) {
            mEngine = Objects.requireNonNull(engine);
        }
    }

    /** A connection to the isolated storage service. */
    private final class IsolatedStorageServiceConnection implements ServiceConnection {
        private final CompletableFuture<Void> mFuture;

        IsolatedStorageServiceConnection(@NonNull CompletableFuture<Void> future) {
            mFuture = Objects.requireNonNull(future);
        }

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mIsolatedStorageService = IIsolatedStorageService.Stub.asInterface(service);
            IBinder binder = mIsolatedStorageService.asBinder();
            try {
                binder.linkToDeath(mIsolatedStorageServiceDeathRecipient, /* flags= */ 0);
            } catch (RemoteException e) {
                Log.e(
                        TAG,
                        "failed to register a recipient of died IsolatedStorageService binder",
                        e);
            }
            Log.i(TAG, "IsolatedStorageService connected");
            mFuture.complete(null);
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            Log.i(TAG, "IsolatedStorageService disconnected");
            synchronized (mLock) {
                mIsolatedStorageService = null;
            }
            mFuture.cancel(/* mayInterruptIfRunning= */ true);
        }

        @Override
        public void onBindingDied(ComponentName className) {
            // TODO(b/416509934): properly handle this when it's correctly triggered
            Log.i(TAG, "IsolatedStorageService binding died");
            synchronized (mLock) {
                mIsolatedStorageService = null;
            }
            mFuture.cancel(/* mayInterruptIfRunning= */ true);
        }

        @Override
        public void onNullBinding(ComponentName className) {
            Log.i(TAG, "IsolatedStorageService null binding");
            mFuture.cancel(/* mayInterruptIfRunning= */ true);
        }
    }

    private final class IsolatedStorageServiceDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            Log.w(TAG, "binderDied: IsolatedStorageService");
            synchronized (mLock) {
                mIsolatedStorageService = null;
            }
            mScheduledExecutorService.execute(() -> replaceVmIcingInstances());
        }
    }

    private final class VmIsolatedStorageServiceDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            Log.w(TAG, "binderDied: VmIsolatedStorageService");
            synchronized (mLock) {
                mVmIsolatedStorageServiceLocked = null;
                mVmUnlocker.onVmUnavailable();
            }
            mScheduledExecutorService.execute(() -> replaceVmIcingInstances());
        }
    }

    private void replaceVmIcingInstances() {
        synchronized (mLock) {
            try {
                mIsReconnecting = true;
                VmInitializationStats.Builder statsBuilder =
                        new VmInitializationStats.Builder(
                                VmInitializationStats.VM_INIT_TYPE_RECONNECTING);
                try {
                    // TODO(b/421272017): add logs that will cover how many retries we're attempting
                    // and whether they actually recover eventually.
                    initialize(
                            /* forceVmRestart= */ true, MAX_REINITIALIZATION_RETRIES, statsBuilder);
                } catch (AppSearchException e) {
                    Log.e(
                            TAG,
                            "failed to re-initialize after "
                                    + MAX_REINITIALIZATION_RETRIES
                                    + " retries");
                    return;
                } finally {
                    if (mLogger != null) {
                        mLogger.logStats(statsBuilder.build());
                    }
                }

                for (UserHandle userHandle : mUserInstancesLocked.keySet()) {
                    try {
                        UserInstance userInstance = mUserInstancesLocked.get(userHandle);
                        IIcingSearchEngine newEngine =
                                mVmIsolatedStorageServiceLocked.getOrCreateIcingConnection(
                                        userHandle.getIdentifier());
                        userInstance.setEngine(newEngine);
                        initializeIcingWithRetryLocked(newEngine, userInstance.getOptions());
                    } catch (RemoteException e) {
                        Log.e(TAG, "failed to get vm icing instance for user " + userHandle, e);
                    }
                }
            } finally {
                mIsReconnecting = false;
            }
        }
    }

    @GuardedBy("mLock")
    private void initializeIcingWithRetryLocked(
            IIcingSearchEngine instance, IcingSearchEngineOptions options) {
        boolean succeeded = false;
        for (int i = 0; i < MAX_ICING_INITIALIZATION_RETRIES; i++) {
            if (initializeIcingLocked(instance, options)) {
                succeeded = true;
                break;
            }
        }
        if (succeeded) {
            Log.i(TAG, "successfully initialized icing instance");
        } else {
            Log.e(
                    TAG,
                    "failed to initialize icing after "
                            + MAX_ICING_INITIALIZATION_RETRIES
                            + " retries");
            // TODO(b/416509382): consider resetting the icing instance
        }
    }

    @GuardedBy("mLock")
    private boolean initializeIcingLocked(
            IIcingSearchEngine instance, IcingSearchEngineOptions options) {
        boolean succeeded = false;
        try {
            mVmStateSignaler.signalActivityStarts();
            byte[] resultBytes = instance.initialize(options.toByteArray());
            InitializeResultProto result = InitializeResultProto.parseFrom(resultBytes);
            if (result.getStatus().getCode() == StatusProto.Code.OK) {
                succeeded = true;
            } else {
                Log.e(
                        TAG,
                        "failed to initialize icing: "
                                + result.getStatus().getCode().getNumber()
                                + " "
                                + result.getStatus().getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "failed to initialize icing", e);
        } finally {
            mVmStateSignaler.signalActivityEnds();
        }
        return succeeded;
    }

    private final class VmDataUnlocker {
        @GuardedBy("mLock")
        private boolean mIsUserUnlocked = false;

        @GuardedBy("mLock")
        private boolean mIsVmAvailable = false;

        private void onVmAvailable() {
            synchronized (mLock) {
                mIsVmAvailable = true;
                tryVmDataUnlock();
            }
        }

        private void onVmUnavailable() {
            synchronized (mLock) {
                mIsVmAvailable = false;
            }
        }

        private void onUserUnlocking() {
            synchronized (mLock) {
                mIsUserUnlocked = true;
                try {
                    tryVmDataUnlock();
                } catch (Exception e) {
                    Log.e(TAG, "tryVmDataUnlock failed", e);
                }
            }
        }

        @GuardedBy("mLock")
        private void tryVmDataUnlock() {
            if (!mIsVmAvailable || !mIsUserUnlocked) {
                Log.i(
                        TAG,
                        "not ready to unlock isVmAvailable="
                                + mIsVmAvailable
                                + " isUserUnlocked="
                                + mIsUserUnlocked);
                return;
            }

            Log.i(TAG, "signaling VM to unlock");
            try {
                mVmIsolatedStorageServiceLocked.onUserUnlocking();
            } catch (RemoteException e) {
                Log.e(TAG, "onUserUnlocking VM notify failure", e);
                ExceptionUtil.handleRemoteException(e);
            }
        }
    }
}
