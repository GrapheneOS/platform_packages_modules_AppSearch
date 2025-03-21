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
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.system.virtualmachine.VirtualMachine;
import android.system.virtualmachine.VirtualMachineCallback;
import android.system.virtualmachine.VirtualMachineConfig;
import android.system.virtualmachine.VirtualMachineException;
import android.system.virtualmachine.VirtualMachineManager;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.server.appsearch.stats.IsolateStorageServiceLogger;
import com.android.server.appsearch.stats.VMPayloadStats;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service that provides isolated storage.
 *
 * <p>This services manages the pVM that hosts the isolated storage, and implements the {@link
 * IIsolatedStorageService} interface.
 */
public class IsolatedStorageService extends Service {

    private static final String TAG = "IsolatedStorageService";

    private static final String VM_NAME = "isolated_storage_service_vm";
    private static final String PAYLOAD_BINARY_NAME = "libisolated_storage_service.so";

    private static final String SYSTEM_PROPERTY_ENABLE_DEBUG_BUILD = "ro.debuggable";
    private static final boolean IS_DEBUG_BUILD =
            SystemProperties.getInt(SYSTEM_PROPERTY_ENABLE_DEBUG_BUILD, /* def= */ 0) == 1;

    /* Constant large storage size used for the VM's encrypted storage. The VM grows storage
     * as needed and choosing a reasonably large storage size avoids costly storage resizing
     * in the VM.
     */
    private static final long DEFAULT_ENCRYPTED_STORAGE_BYTES = 2_000_000_000L; // 2GB

    private final ExecutorService mExecutorService = Executors.newFixedThreadPool(1);
    private final IsolatedStorageServiceStub mIsolatedStorageServiceStub =
            new IsolatedStorageServiceStub();

    private VirtualMachine mVm;

    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand. flags = " + flags + ", startId = " + startId);
        return START_STICKY;
    }

    private CompletableFuture<Void> startVm(ServiceConfig config) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        mVm = maybeCreateVm(config);
        if (mVm == null) {
            Log.e(TAG, "Unable to create/get VirtualMachine");
            future.cancel(/* mayInterruptIfRunning= */ true);
            return future;
        }
        IsolateStorageServiceLogger logger = new IsolateStorageServiceLogger(config);
        VmCallback vmCallback = new VmCallback(future, logger);
        mVm.setCallback(mExecutorService, vmCallback);
        try {
            mVm.run();
        } catch (VirtualMachineException e) {
            Log.e(TAG, "Failed to run " + VM_NAME, e);
        }
        return future;
    }

    /**
     * Tries to create the VM.
     *
     * <p>If the VM already exists, return it. If the VM doesn't exist or is deleted, create a new
     * VM and return it. Return {@code null} if failed to get or create the VM.
     */
    private @Nullable VirtualMachine maybeCreateVm(ServiceConfig config) {
        VirtualMachineManager vmm = getSystemService(VirtualMachineManager.class);
        if (vmm == null) {
            Log.e(TAG, "Unable to get VirtualMachineManager");
            return null;
        }
        VirtualMachine vm = null;
        try {
            vm = vmm.get(VM_NAME);
        } catch (VirtualMachineException e) {
            Log.e(TAG, "VirtualMachineManager#get failed", e);
        }
        if (vm != null && vm.getStatus() != VirtualMachine.STATUS_DELETED) {
            return vm;
        }
        if (vm == null) {
            Log.i(TAG, "Virtual machine " + VM_NAME + " does not exist. Creating one");
        } else {
            Log.i(TAG, "Virtual machine " + VM_NAME + " is deleted. Creating one");
        }
        return createVm(vmm, config);
    }

    private @Nullable VirtualMachine createVm(
            VirtualMachineManager vmm, ServiceConfig serviceConfig) {
        final int vmDebugLevel =
                IS_DEBUG_BUILD
                        ? VirtualMachineConfig.DEBUG_LEVEL_FULL
                        : VirtualMachineConfig.DEBUG_LEVEL_NONE;
        try {
            VirtualMachineConfig config =
                    new VirtualMachineConfig.Builder(this)
                            .setPayloadBinaryName(PAYLOAD_BINARY_NAME)
                            .setProtectedVm(true)
                            .setDebugLevel(vmDebugLevel)
                            // Set the maximum size of the VM encrypted storage. Storage is
                            // allocated on an as needed basis.
                            .setEncryptedStorageBytes(DEFAULT_ENCRYPTED_STORAGE_BYTES)
                            .setMemoryBytes(serviceConfig.pVmMemoryBytes)
                            .setCpuTopology(VirtualMachineConfig.CPU_TOPOLOGY_ONE_CPU)
                            .setShouldUseHugepages(true)
                            .build();
            try {
                return vmm.create(VM_NAME, config);
            } catch (VirtualMachineException e) {
                Log.e(TAG, "Failed to create virtual machine " + VM_NAME, e);
                return null;
            }
        } catch (IllegalArgumentException
                | IllegalStateException
                | UnsupportedOperationException e) {
            Log.e(TAG, "Failed to create virtual machine config " + VM_NAME, e);
            return null;
        }
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
            Log.i(TAG, "Payload ready");
            mFuture.complete(null);
            VMPayloadStats stats = new VMPayloadStats.Builder(CALLBACK_TYPE_READY).build();
            mLogger.logStats(stats);
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
            Log.e(TAG, "Error " + VM_NAME + " code : " + errorCode + " msg : " + errorMessage);
            VMPayloadStats stats =
                    new VMPayloadStats.Builder(CALLBACK_TYPE_ERROR).setErrorCode(errorCode).build();
            mLogger.logStats(stats);
        }

        @Override
        public void onStopped(VirtualMachine vm, int stopReason) {
            Log.w(TAG, VM_NAME + " stopped, reason : " + stopReason);
            VMPayloadStats stats =
                    new VMPayloadStats.Builder(CALLBACK_TYPE_STOP)
                            .setStopReason(stopReason)
                            .build();
            mLogger.logStats(stats);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: b/384768541 - ensure only AppSearch can bind to this service.
        return mIsolatedStorageServiceStub.asBinder();
    }

    /** Implementation of the {@link IIsolatedStorageService}. */
    private class IsolatedStorageServiceStub extends IIsolatedStorageService.Stub {
        private static final int PAYLOAD_READY_WAIT_TIMEOUT_SECONDS = 15;

        @GuardedBy("mConfigLocked")
        private final AtomicReference<ServiceConfig> mConfigLocked = new AtomicReference<>();

        @Override
        public void setup(ServiceConfig config) throws RemoteException {
            Objects.requireNonNull(config);
            synchronized (mConfigLocked) {
                if (mConfigLocked.get() != null) {
                    Log.w(TAG, "Service already set up");
                    return;
                }
                mConfigLocked.set(config);
                try {
                    startVm(config).get(PAYLOAD_READY_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Log.e(TAG, "Unable to wait for payload ready", e);
                    throw new RemoteException(e.getMessage());
                }
            }
        }

        @Override
        public ParcelFileDescriptor getVmConnection() throws RemoteException {
            synchronized (mConfigLocked) {
                if (mConfigLocked.get() == null) {
                    throw new RemoteException("Service not set up yet");
                }
                if (mVm == null) {
                    throw new RemoteException("pVM payload is not ready/available");
                }
                try {
                    return mVm.connectVsock(
                            com.android.isolated_storage_service.IIsolatedStorageService.PORT);
                } catch (Exception e) {
                    Log.e(TAG, "Unable to connect vsock", e);
                    throw new RemoteException(e.getMessage());
                }
            }
        }
    }
}
