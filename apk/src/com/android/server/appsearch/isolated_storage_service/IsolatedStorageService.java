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

import static com.android.server.appsearch.stats.VMPayloadStats.CALLBACK_TYPE_ERROR;
import static com.android.server.appsearch.stats.VMPayloadStats.CALLBACK_TYPE_FINISH;
import static com.android.server.appsearch.stats.VMPayloadStats.CALLBACK_TYPE_READY;
import static com.android.server.appsearch.stats.VMPayloadStats.CALLBACK_TYPE_START;
import static com.android.server.appsearch.stats.VMPayloadStats.CALLBACK_TYPE_STOP;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.system.virtualmachine.VirtualMachine;
import android.system.virtualmachine.VirtualMachineCallback;
import android.system.virtualmachine.VirtualMachineConfig;
import android.system.virtualmachine.VirtualMachineException;
import android.system.virtualmachine.VirtualMachineManager;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.server.appsearch.stats.IsolateStorageServiceLogger;
import com.android.server.appsearch.stats.VMPayloadStats;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Service that provides isolated storage.
 *
 * <p>This services manages the pVM that hosts the isolated storage, and implements the {@link
 * IIsolatedStorageService} interface.
 */
public class IsolatedStorageService extends Service {
    private static final String TAG = "IsolatedStorageService";

    private static final String VM_NAME = "isolated_storage_service_vm";
    private static final String PAYLOAD_BINARY_NAME = "libicing_anywhere.so";

    private static final String SYSTEM_PROPERTY_ENABLE_DEBUG_BUILD = "ro.debuggable";
    private static final String SYSTEM_PROPERTY_ENABLE_NONPROTECTED_APPSEARCH_VM =
            "ro.enable.nonprotected_appsearch_vm";
    private static final boolean IS_DEBUG_BUILD =
            SystemProperties.getInt(SYSTEM_PROPERTY_ENABLE_DEBUG_BUILD, /* def= */ 0) == 1;

    /* Constant large storage size used for the VM's encrypted storage. The VM grows storage
     * as needed and choosing a reasonably large storage size avoids costly storage resizing
     * in the VM.
     */
    private static final long DEFAULT_ENCRYPTED_STORAGE_BYTES = 2_000_000_000L; // 2GB

    /**
     * The threshold to delete vm after encountering consecutive {@link
     * VirtualMachineCallback#ERROR_PAYLOAD_CHANGED} VM errors when trying to start the vm.
     */
    private static final int CONSECUTIVE_VM_PAYLOAD_CHANGED_ERROR_THRESHOLD = 4;

    /**
     * Status codes for vm start result. Needs to be kept consistent with status codes defined in
     * VmStartAttemptStats under packages/modules/AppSearch/service.
     */
    @IntDef(
            value = {
                VM_START_STATUS_UNKNOWN,
                VM_START_STATUS_SUCCESS,
                VM_START_STATUS_TIMEOUT,
                VM_START_STATUS_ERROR,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VmStartStatus {}

    // TODO(b/422197198): refactor these constants to a common class for both IsolatedStorageService
    //   and VmStartAttemptStats usage.
    private static final int VM_START_STATUS_UNKNOWN = 0;
    private static final int VM_START_STATUS_SUCCESS = 1;
    private static final int VM_START_STATUS_TIMEOUT = 2;
    private static final int VM_START_STATUS_ERROR = 3;

    private final ExecutorService mExecutorService = Executors.newFixedThreadPool(1);
    private final IsolatedStorageServiceStub mIsolatedStorageServiceStub =
            new IsolatedStorageServiceStub();

    private final Object mLock = new Object();

    /**
     * Number of consecutive {@link VirtualMachineCallback#ERROR_PAYLOAD_CHANGED} VM errors when
     * starting the VM.
     */
    @GuardedBy("mLock")
    private int mNumConsecutivePayloadChangedErrors = 0;

    /**
     * Future object for payload ready state checking and waiting. IsolatedStorageService uses this
     * object to determine if the payload is ready or not (see {@link #isPayloadReadyLocked}). If
     * any unexpected error happens and requires to restart the vm, then we should set this member
     * to null.
     */
    @GuardedBy("mLock")
    private CompletableFuture<Void> mPayloadReadyFuture;

    @GuardedBy("mLock")
    private VirtualMachine mVm;

    @GuardedBy("mLock")
    private com.android.isolated_storage_service.IIsolatedStorageService mVmIsolatedStorageService;

    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand. flags = " + flags + ", startId = " + startId);
        return START_STICKY;
    }

    @Override
    public void onTrimMemory(int level) {
        synchronized (mLock) {
            onTrimMemoryLocked();
        }
    }

    @GuardedBy("mLock")
    private void onTrimMemoryLocked() {
        if (mVmIsolatedStorageService != null) {
            try {
                mVmIsolatedStorageService.trimMemory();
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to trim memory", e);
            }
        }
    }

    /** Restarts the vm instance, regardless of whether {@code forceRestart} is true or not. */
    @GuardedBy("mLock")
    private void restartVmLocked(ServiceConfig serviceConfig, boolean forceRestart)
            throws VirtualMachineException, NullPointerException {
        Context context = createDeviceProtectedStorageContext();
        VirtualMachineConfig config = getVmConfig(context, serviceConfig);
        if (mVm == null) {
            mVm = maybeCreateVm(context, config);
            if (mVm == null) {
                throw new NullPointerException("VM instance is null");
            }
            if (mVm.getStatus() == VirtualMachine.STATUS_RUNNING) {
                // If other services are allowed to create the vm instance (i.e. other classes
                // called vmm.getOrCreate() with the same vm name and started the instance), then
                // a running vm instance will be assigned to mVm. For now:
                // - It is unlikely to have this situation.
                // - But let's log this situation anyway and restart the vm, so we can make sure the
                //   payload is ready before starting to use it.
                //
                // TODO: implement a better way to wait for payload ready for a running VM to avoid
                //   unnecessary restart.
                Log.w(
                        TAG,
                        "Got a running vm instance from VirtualMachineManager. The vm instance may"
                                + " have been created and started by another service. Restart it"
                                + " here anyway");
            }
        }

        Log.i(TAG, "close and restart the vm, force restart flag = " + forceRestart);
        // Close the vm.
        // Note: close() will be no-op if the status is not VirtualMachine.STATUS_RUNNING.
        mVm.close();

        mPayloadReadyFuture = new CompletableFuture<>();
        IsolateStorageServiceLogger logger = new IsolateStorageServiceLogger(serviceConfig);
        VmCallback vmCallback = new VmCallback(mPayloadReadyFuture, logger);
        mVm.setCallback(mExecutorService, vmCallback);
        mVm.setConfig(config);
        mVm.run();
    }

    /**
     * Tries to create the VM.
     *
     * <p>If the VM already exists, return it. If the VM doesn't exist or is deleted, create a new
     * VM and return it. Return {@code null} if failed to get or create the VM.
     */
    private @Nullable VirtualMachine maybeCreateVm(Context context, VirtualMachineConfig config) {
        VirtualMachineManager vmm = context.getSystemService(VirtualMachineManager.class);
        if (vmm == null) {
            Log.e(TAG, "Unable to get VirtualMachineManager");
            return null;
        }
        try {
            VirtualMachine vm = vmm.getOrCreate(VM_NAME, config);
            VirtualMachineConfig actualConfig = vm.getConfig();
            if (!config.isCompatibleWith(actualConfig)) {
                Log.w(
                        TAG,
                        "expected config is not compatible with the running config. Recreating"
                                + " VM");
                deleteVmByName(vmm, VM_NAME);
                return vmm.getOrCreate(VM_NAME, config);
            }
            return vm;
        } catch (VirtualMachineException e) {
            // TODO(b/437160991): remove once VirtualMachineManager.getOrCreate is properly
            //  handling creation failures.
            if (e.getMessage().contains("Failed to read VM config from file")
                    || e.getMessage().contains("Persisted VM config is invalid")) {
                Log.wtf(TAG, "Deleting the vm to recover from vm config failures", e);
                deleteVmByName(vmm, VM_NAME);
            } else {
                Log.wtf(TAG, "Failed to get or create virtual machine " + VM_NAME, e);
            }
            return null;
        } catch (IllegalArgumentException
                | IllegalStateException
                | UnsupportedOperationException e) {
            Log.e(TAG, "Failed to create virtual machine config " + VM_NAME, e);
            return null;
        }
    }

    private static @NonNull VirtualMachineConfig getVmConfig(
            Context context, ServiceConfig serviceConfig) {
        final int vmDebugLevel =
                IS_DEBUG_BUILD
                        ? VirtualMachineConfig.DEBUG_LEVEL_FULL
                        : VirtualMachineConfig.DEBUG_LEVEL_NONE;
        // Detect if cuttlefish. pKVM is not currently supported on CF, so launch in non-protected
        // mode
        final boolean protectedAppSearchVmEnabled =
                !SystemProperties.getBoolean(
                        SYSTEM_PROPERTY_ENABLE_NONPROTECTED_APPSEARCH_VM, /* def= */ false);
        Log.i(
                TAG,
                "Creating VM config with: MODEL="
                        + Build.MODEL
                        + ", protected VM="
                        + protectedAppSearchVmEnabled);
        return new VirtualMachineConfig.Builder(context)
                .setPayloadBinaryName(PAYLOAD_BINARY_NAME)
                .setProtectedVm(protectedAppSearchVmEnabled)
                .setDebugLevel(vmDebugLevel)
                // Set the maximum size of the VM encrypted storage. Storage is
                // allocated on an as needed basis.
                .setEncryptedStorageBytes(DEFAULT_ENCRYPTED_STORAGE_BYTES)
                .setMemoryBytes(serviceConfig.pVmMemoryBytes)
                .setCpuTopology(VirtualMachineConfig.CPU_TOPOLOGY_ONE_CPU)
                .setShouldUseHugepages(true)
                .build();
    }

    @GuardedBy("mLock")
    private void deleteCurrentVmLocked(@Nullable String vmErrorMessage) {
        Log.i(TAG, "Deleting current VM...");
        if (deleteVmByName(VM_NAME)) {
            if (vmErrorMessage != null) {
                Log.wtf(TAG, vmErrorMessage);
            } else {
                Log.i(TAG, "Successfully deleted the VM.");
            }
            mVm = null;
            mNumConsecutivePayloadChangedErrors = 0;
        } else {
            Log.e(TAG, "Failed to delete current VM");
        }
    }

    private boolean deleteCurrentVmIfExists() throws NullPointerException, VirtualMachineException {
        Context context = createDeviceProtectedStorageContext();
        VirtualMachineManager vmm = context.getSystemService(VirtualMachineManager.class);
        if (vmm == null) {
            Log.e(TAG, "Unable to get VirtualMachineManager");
            throw new NullPointerException("Unable to get VirtualMachineManager");
        }
        if (vmm.get(VM_NAME) == null) {
            Log.i(TAG, "VM " + VM_NAME + " doesn't exist");
            return true;
        }
        return deleteVmByName(vmm, VM_NAME);
    }

    /** Delete old CE VMs. */
    private void deleteOldVms() {
        VirtualMachineManager vmm = getSystemService(VirtualMachineManager.class);
        if (vmm == null) {
            Log.e(TAG, "Unable to get VirtualMachineManager");
            return;
        }
        deleteVmByName(vmm, "isolated_storage_service_vm");
        deleteVmByName(vmm, "isolated_storage_service2_vm");
    }

    /** Deletes DE VM by name. */
    private boolean deleteVmByName(String name) {
        Context context = createDeviceProtectedStorageContext();
        VirtualMachineManager vmm = context.getSystemService(VirtualMachineManager.class);
        if (vmm == null) {
            Log.e(TAG, "Unable to get VirtualMachineManager");
            return false;
        }
        return deleteVmByName(vmm, name);
    }

    /** Deletes VM by name. */
    private boolean deleteVmByName(VirtualMachineManager vmm, String name) {
        try {
            vmm.delete(name);
        } catch (VirtualMachineException e) {
            Log.e(TAG, "Failed to delete VM " + name, e);
            return false;
        }
        return true;
    }

    @GuardedBy("mLock")
    private boolean isVmRunningLocked() {
        return mVm != null && mVm.getStatus() == VirtualMachine.STATUS_RUNNING;
    }

    @GuardedBy("mLock")
    private boolean isPayloadReadyLocked() {
        return mPayloadReadyFuture != null
                && !mPayloadReadyFuture.isCancelled()
                && mPayloadReadyFuture.isDone();
    }

    /** Callbacks for pVM status changes. */
    private class VmCallback implements VirtualMachineCallback {

        private final CompletableFuture<Void> mFuture;
        private final IsolateStorageServiceLogger mLogger;

        VmCallback(@NonNull CompletableFuture<Void> future, IsolateStorageServiceLogger logger) {
            mFuture = Objects.requireNonNull(future);
            mLogger = Objects.requireNonNull(logger);
        }

        @Override
        public void onPayloadStarted(VirtualMachine vm) {
            Log.i(TAG, "Payload started");
            VMPayloadStats stats = new VMPayloadStats.Builder(CALLBACK_TYPE_START).build();
            mLogger.logStats(stats);
        }

        @Override
        public void onPayloadReady(VirtualMachine vm) {
            synchronized (mLock) {
                mNumConsecutivePayloadChangedErrors = 0;

                if (mFuture != mPayloadReadyFuture) {
                    Log.w(
                            TAG,
                            "Another restart has been kicked off before payload ready. Abandon"
                                    + " this callback");
                    return;
                }

                Log.i(TAG, "Payload ready");
                try {
                    mVmIsolatedStorageService =
                            com.android.isolated_storage_service.IIsolatedStorageService.Stub
                                    .asInterface(
                                            vm.connectToVsockServer(
                                                    com.android.isolated_storage_service
                                                            .IIsolatedStorageService.PORT));
                } catch (VirtualMachineException e) {
                    Log.e(TAG, "Failed to connect to " + VM_NAME, e);

                    // Cancel the future to notify the waiting thread to early end the waiting due
                    // to a failure.
                    // The future itself is trivial - it's just a signaling mechanism for the
                    // waiting thread (i.e. get()) to unblock before timeout.
                    mFuture.cancel(/* mayInterruptIfRunning= */ true);

                    // Set the payload ready future object in IsolatedStorageService to null, so
                    // the service can avoid serving any request with an invalid
                    // mVmIsolatedStorageService.
                    mPayloadReadyFuture = null;
                    VMPayloadStats stats = new VMPayloadStats.Builder(CALLBACK_TYPE_ERROR).build();
                    mLogger.logStats(stats);
                    return;
                }
            }

            VMPayloadStats stats = new VMPayloadStats.Builder(CALLBACK_TYPE_READY).build();
            mLogger.logStats(stats);

            mFuture.complete(null);
        }

        @Override
        public void onPayloadFinished(VirtualMachine vm, int exitCode) {
            Log.i(TAG, "Payload finished. Code: " + exitCode);
            VMPayloadStats stats =
                    new VMPayloadStats.Builder(CALLBACK_TYPE_FINISH).setExitCode(exitCode).build();
            mLogger.logStats(stats);
        }

        @Override
        public void onError(VirtualMachine vm, int errorCode, String errorMessage) {

            synchronized (mLock) {
                String vmErrorMessage = "Error " + VM_NAME + " code : " + errorCode + " msg : "
                        + errorMessage;
                Log.e(TAG, vmErrorMessage);
                if (errorCode == VirtualMachineCallback.ERROR_PAYLOAD_CHANGED) {
                    mNumConsecutivePayloadChangedErrors++;
                    if (mNumConsecutivePayloadChangedErrors
                            >= CONSECUTIVE_VM_PAYLOAD_CHANGED_ERROR_THRESHOLD) {
                        vmErrorMessage = vmErrorMessage + ". Previous payload changed error count: "
                                + mNumConsecutivePayloadChangedErrors;
                        deleteCurrentVmLocked(vmErrorMessage);
                    } else {
                        Log.i(
                                TAG,
                                "Encountered "
                                        + mNumConsecutivePayloadChangedErrors
                                        + " payload changed errors. Not deleting current VM.");
                    }
                } else {
                    mNumConsecutivePayloadChangedErrors = 0;
                }
            }

            VMPayloadStats stats =
                    new VMPayloadStats.Builder(CALLBACK_TYPE_ERROR).setErrorCode(errorCode).build();
            mLogger.logStats(stats);

            // The future itself is trivial - it's just a signaling mechanism for the waiting thread
            // (i.e. get()) to unblock before timeout.
            mFuture.cancel(/* mayInterruptIfRunning= */ true);
        }

        @Override
        public void onStopped(VirtualMachine vm, int stopReason) {
            Log.w(TAG, VM_NAME + " stopped, reason : " + stopReason);
            VMPayloadStats stats =
                    new VMPayloadStats.Builder(CALLBACK_TYPE_STOP)
                            .setStopReason(stopReason)
                            .build();
            mLogger.logStats(stats);

            // The future itself is trivial - it's just a signaling mechanism for the waiting thread
            // (i.e. get()) to unblock before timeout.
            mFuture.cancel(/* mayInterruptIfRunning= */ true);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mIsolatedStorageServiceStub.asBinder();
    }

    /** Implementation of the {@link IIsolatedStorageService}. */
    private class IsolatedStorageServiceStub extends IIsolatedStorageService.Stub {

        // We check here instead of onBind as IBinder can be passed around.
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            checkCallerPermission();
            return super.onTransact(code, data, reply, flags);
        }

        // We only allow packages with BIND_APPSEARCH_ISOLATED_STORAGE_SERVICE permission to do the
        // binder call to this service.
        // Furthermore, we only want system_server to bind to this service.
        private void checkCallerPermission() {
            int checkPermission =
                    checkCallingPermission(
                            "android.permission.BIND_APP_SEARCH_ISOLATED_STORAGE_SERVICE");
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Permission check failed with code " + checkPermission);
            }

            // check to only allow system_server access.
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException(
                        "Only system server is allowed to bind to this service.");
            }
        }

        @Override
        public VmStartResult startVm(
                ServiceConfig config, long timeoutSeconds, boolean forceRestart) {
            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();

            VmStartResult result = new VmStartResult();
            result.pStatusCode = VM_START_STATUS_UNKNOWN;
            try {
                result.pStatusCode = startVmImpl(config, timeoutSeconds, forceRestart);
            } catch (Exception e) {
                Log.e(TAG, "Unable to start VM", e);
                result.pStatusCode = VM_START_STATUS_ERROR;
            } finally {
                result.pTotalLatencyMillis =
                        SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis;
            }
            return result;
        }

        private @VmStartStatus int startVmImpl(
                ServiceConfig config, long timeoutSeconds, boolean forceRestart) {
            CompletableFuture<Void> localPayloadReadyFuture = null;
            synchronized (mLock) {
                if (isVmRunningLocked() && isPayloadReadyLocked() && !forceRestart) {
                    // TODO(b/422197198): add VM_START_STATUS_ALREADY_RUNNING constant and atom enum
                    //   for this case.
                    return VM_START_STATUS_SUCCESS;
                }

                try {
                    restartVmLocked(config, forceRestart);
                } catch (Exception e) {
                    Log.e(TAG, "Unable to start VM", e);

                    // Reset payload ready future object.
                    mPayloadReadyFuture = null;
                    throw new IllegalStateException(e);
                }
                localPayloadReadyFuture = mPayloadReadyFuture;
            }

            if (localPayloadReadyFuture == null) {
                String msg =
                        "Got a null payload ready future object to wait. This should not happen";
                Log.e(TAG, msg);
                throw new NullPointerException(msg);
            }

            try {
                localPayloadReadyFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException | CancellationException e) {
                Log.w(TAG, "Unable to wait for payload ready", e);
                return VM_START_STATUS_ERROR;
            } catch (TimeoutException e) {
                Log.w(TAG, "Unable to wait for payload ready", e);
                return VM_START_STATUS_TIMEOUT;
            } catch (Exception e) {
                Log.e(TAG, "Unable to start VM", e);
                throw new IllegalStateException(e);
            }
            return VM_START_STATUS_SUCCESS;
        }

        @Override
        public boolean deleteVm() {
            try {
                synchronized (mLock) {
                    // setting this to null to prevent the deleted vm from being used
                    mVm = null;
                    return deleteCurrentVmIfExists();
                }
            } catch (Exception e) {
                Log.e(TAG, "Unable to delete VM", e);
                return false;
            }
        }

        @Override
        public @Nullable ParcelFileDescriptor getVmConnection() {
            synchronized (mLock) {
                if (!isVmRunningLocked() || !isPayloadReadyLocked()) {
                    throw new IllegalStateException(
                            "pVM is not running or payload is not ready/available");
                }
                try {
                    return mVm.connectVsock(
                            com.android.isolated_storage_service.IIsolatedStorageService.PORT);
                } catch (Exception e) {
                    Log.e(TAG, "Unable to connect vsock", e);
                    return null;
                }
            }
        }

        @Override
        public int getVmStatus() {
            synchronized (mLock) {
                if (mVm == null) {
                    throw new IllegalStateException("pVM is not available");
                }
                return mVm.getStatus();
            }
        }

        @Override
        public void cleanUpOldVms() {
            deleteOldVms();
        }
    }
}
