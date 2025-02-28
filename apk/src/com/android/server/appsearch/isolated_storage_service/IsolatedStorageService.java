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

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.system.OsConstants;
import android.system.virtualmachine.VirtualMachine;
import android.system.virtualmachine.VirtualMachineCallback;
import android.system.virtualmachine.VirtualMachineConfig;
import android.system.virtualmachine.VirtualMachineException;
import android.system.virtualmachine.VirtualMachineManager;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
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

    private final ExecutorService mExecutorService = Executors.newFixedThreadPool(1);
    private final IsolatedStorageServiceStub mIsolatedStorageServiceStub =
            new IsolatedStorageServiceStub();

    private volatile com.android.isolated_storage_service.IIsolatedStorageService
            mIsolatedStorageService;

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
        VirtualMachine vm = maybeCreateVm(config);
        if (vm == null) {
            Log.e(TAG, "Unable to create/get VirtualMachine");
            future.cancel(/* mayInterruptIfRunning= */ true);
            return future;
        }
        VmCallback vmCallback = new VmCallback(future);
        vm.setCallback(mExecutorService, vmCallback);
        try {
            vm.run();
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
        VirtualMachineConfig config =
                new VirtualMachineConfig.Builder(this)
                        .setPayloadBinaryName(PAYLOAD_BINARY_NAME)
                        .setProtectedVm(true)
                        .setDebugLevel(VirtualMachineConfig.DEBUG_LEVEL_FULL)
                        .setEncryptedStorageBytes(serviceConfig.pVmEncryptedStorageBytes)
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
    }

    /** Callbacks for pVM status changes. */
    private class VmCallback implements VirtualMachineCallback {

        private final CompletableFuture<Void> mFuture;

        VmCallback(@NonNull CompletableFuture<Void> future) {
            mFuture = Objects.requireNonNull(future);
        }

        @Override
        public void onPayloadStarted(VirtualMachine vm) {
            Log.i(TAG, "Payload started");
        }

        @Override
        public void onPayloadReady(VirtualMachine vm) {
            Log.i(TAG, "Payload ready");
            try {
                mIsolatedStorageService =
                        com.android.isolated_storage_service.IIsolatedStorageService.Stub
                                .asInterface(
                                        vm.connectToVsockServer(
                                                com.android.isolated_storage_service
                                                        .IIsolatedStorageService.PORT));
                mFuture.complete(null);
            } catch (VirtualMachineException e) {
                Log.e(TAG, "Failed to connect to " + VM_NAME, e);
                mFuture.completeExceptionally(e);
            }
        }

        @Override
        public void onPayloadFinished(VirtualMachine vm, int exitCode) {
            Log.i(TAG, "Payload finished. Code: " + exitCode);
        }

        @Override
        public void onError(VirtualMachine vm, int errorCode, String errorMessage) {
            Log.e(TAG, "Error " + VM_NAME + " code : " + errorCode + " msg : " + errorMessage);
        }

        @Override
        public void onStopped(VirtualMachine vm, int stopReason) {
            Log.w(TAG, VM_NAME + " stopped, reason : " + stopReason);
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
        private final AtomicReference<ServiceConfig> mConfig = new AtomicReference<>();

        @GuardedBy("mEnginesLocked")
        private final Map<Integer, IIcingSearchEngine> mEnginesLocked = new ArrayMap<>();

        @Override
        public void setup(ServiceConfig config) throws RemoteException {
            synchronized (mConfig) {
                if (mConfig.get() != null) {
                    Log.w(TAG, "Service already set up");
                    return;
                }
                mConfig.set(config);
                try {
                    startVm(config).get(PAYLOAD_READY_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Log.e(TAG, "Unable to wait for payload ready", e);
                    throw new RemoteException(e.getMessage());
                }
            }
        }

        @Override
        public IIcingSearchEngine getIcingSearchEngine(int userId) throws RemoteException {
            synchronized (mConfig) {
                if (mConfig.get() == null) {
                    throw new RemoteException("Service not set up yet");
                }
                if (mIsolatedStorageService == null) {
                    throw new RemoteException("pVM payload is not ready/available");
                }
                IIcingSearchEngine engine;
                synchronized (mEnginesLocked) {
                    engine = mEnginesLocked.get(userId);
                    if (engine == null) {
                        engine =
                                new IcingSearchEngineStub(
                                        mIsolatedStorageService.getOrCreateIcingConnection(userId),
                                        mConfig.get().icingDataUnionSizeThresholdBytes);
                        mEnginesLocked.put(userId, engine);
                    }
                }
                return engine;
            }
        }
    }

    /**
     * Implementation of the {@link IIcingSearchEngine}.
     *
     * <p>It's mostly a wrapper for the underlying pVM interface {@link
     * com.android.isolated_storage_service.IIcingSearchEngine IIcingSearchEngine}. It passes
     * serialized Icing requests/responses between pVM and AppSearch. {@link SharedMemory} instances
     * are used for transferring large data.
     */
    private static class IcingSearchEngineStub extends IIcingSearchEngine.Stub {

        private final com.android.isolated_storage_service.IIcingSearchEngine mVmEngine;
        private final long mIcingDataUnionSizeThresholdBytes;

        IcingSearchEngineStub(
                @NonNull com.android.isolated_storage_service.IIcingSearchEngine vmEngine,
                long icingDataUnionSizeThresholdBytes) {
            mVmEngine = Objects.requireNonNull(vmEngine);
            mIcingDataUnionSizeThresholdBytes = icingDataUnionSizeThresholdBytes;
        }

        /**
         * Creates an {@link IcingDataUnion} instance to pass {@code data}.
         *
         * <p>For data smaller than {@link IcingSearchEngineStub#mIcingDataUnionSizeThresholdBytes},
         * use byte array to pass it. For data larger than that, use {@link SharedMemory} to pass
         * it. Using {@link SharedMemory} is more expensive so we want to avoid when possible.
         */
        private IcingDataUnion createIcingDataUnion(byte[] data) throws RemoteException {
            IcingDataUnion union = new IcingDataUnion();
            if (data.length < mIcingDataUnionSizeThresholdBytes) {
                union.setRawData(data);
            } else {
                union.setSharedMemory(createSharedMemory(data));
            }
            return union;
        }

        private static byte[] readFromIcingDataUnion(IcingDataUnion union) throws RemoteException {
            if (union.getTag() == IcingDataUnion.sharedMemory) {
                return readFromSharedMemory(union.getSharedMemory());
            } else {
                return union.getRawData();
            }
        }

        /**
         * Creates a {@link SharedMemory} instance to pass {@code data}.
         *
         * <p>Use {@link SharedMemory} to overcome the binder transaction limit.
         *
         * <p>Map a {@link ByteBuffer} to the {@link SharedMemory}, and unmap it after finishing
         * writing {@code data} to it. After client closes the {@link SharedMemory}, it gets cleaned
         * up.
         */
        private static SharedMemory createSharedMemory(byte[] data) throws RemoteException {
            try {
                SharedMemory sharedMemory = SharedMemory.create("appsearch-apk-iss", data.length);
                ByteBuffer buffer = sharedMemory.mapReadWrite();
                try {
                    buffer.order(ByteOrder.nativeOrder());
                    buffer.put(data);
                } finally {
                    SharedMemory.unmap(buffer);
                }
                sharedMemory.setProtect(OsConstants.PROT_READ);
                return sharedMemory;
            } catch (Exception e) {
                Log.e(TAG, "Failed to create/write to SharedMemory", e);
                throw new RemoteException(e.getMessage());
            }
        }

        /**
         * Reads {@link data} from a {@link SharedMemory} instance.
         *
         * <p>Use {@link SharedMemory} to overcome the binder transaction limit.
         *
         * <p>Map a {@link ByteBuffer} to the {@link SharedMemory}, and unmap after finishing
         * reading from to it. Close the {@link SharedMemory} instance after reading from it.
         */
        private static byte[] readFromSharedMemory(SharedMemory sharedMemory)
                throws RemoteException {
            byte[] data;
            try (sharedMemory) {
                ByteBuffer buffer = sharedMemory.mapReadOnly();
                try {
                    buffer.order(ByteOrder.nativeOrder());
                    data = new byte[sharedMemory.getSize()];
                    buffer.get(data);
                } finally {
                    SharedMemory.unmap(buffer);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to read from SharedMemory", e);
                throw new RemoteException(e.getMessage());
            }
            return data;
        }

        @Override
        public byte[] initialize(byte[] icingSearchEngineOptionsProto) throws RemoteException {
            return mVmEngine.initialize(icingSearchEngineOptionsProto);
        }

        @Override
        public void close() throws RemoteException {
            mVmEngine.close();
        }

        @Override
        public byte[] setSchema(IcingDataUnion schemaProto, boolean ignoreErrorsAndDeleteDocuments)
                throws RemoteException {
            return mVmEngine.setSchema(
                    readFromIcingDataUnion(schemaProto), ignoreErrorsAndDeleteDocuments);
        }

        @Override
        public byte[] getSchema() throws RemoteException {
            return mVmEngine.getSchema();
        }

        @Override
        public byte[] getSchemaForDatabase(String database) throws RemoteException {
            return mVmEngine.getSchemaForDatabase(database);
        }

        @Override
        public byte[] getSchemaType(String schemaType) throws RemoteException {
            return mVmEngine.getSchemaType(schemaType);
        }

        @Override
        public byte[] put(IcingDataUnion documentProto) throws RemoteException {
            return mVmEngine.put(readFromIcingDataUnion(documentProto));
        }

        @Override
        public IcingDataUnion get(String namespace, String uri, byte[] getResultSpecProto)
                throws RemoteException {
            return createIcingDataUnion(mVmEngine.get(namespace, uri, getResultSpecProto));
        }

        @Override
        public byte[] reportUsage(byte[] usageReportProto) throws RemoteException {
            return mVmEngine.reportUsage(usageReportProto);
        }

        @Override
        public byte[] getAllNamespaces() throws RemoteException {
            return mVmEngine.getAllNamespaces();
        }

        @Override
        public IcingDataUnion search(
                byte[] searchSpecProto, byte[] scoringSpecProto, byte[] resultSpecProto)
                throws RemoteException {
            return createIcingDataUnion(
                    mVmEngine.search(searchSpecProto, scoringSpecProto, resultSpecProto));
        }

        @Override
        public IcingDataUnion getNextPage(long nextPageToken) throws RemoteException {
            return createIcingDataUnion(mVmEngine.getNextPage(nextPageToken));
        }

        @Override
        public void invalidateNextPageToken(long nextPageToken) throws RemoteException {
            mVmEngine.invalidateNextPageToken(nextPageToken);
        }

        @Override
        public byte[] openWriteBlob(byte[] blobHandleProto) throws RemoteException {
            return mVmEngine.openWriteBlob(blobHandleProto);
        }

        @Override
        public byte[] removeBlob(byte[] blobHandleProto) throws RemoteException {
            return mVmEngine.removeBlob(blobHandleProto);
        }

        @Override
        public byte[] openReadBlob(byte[] blobHandleProto) throws RemoteException {
            return mVmEngine.openReadBlob(blobHandleProto);
        }

        @Override
        public byte[] commitBlob(byte[] blobHandleProto) throws RemoteException {
            return mVmEngine.commitBlob(blobHandleProto);
        }

        @Override
        public byte[] deleteByUri(String namespace, String uri) throws RemoteException {
            return mVmEngine.deleteDoc(namespace, uri);
        }

        @Override
        public byte[] searchSuggestions(byte[] suggestionSpecProto) throws RemoteException {
            return mVmEngine.searchSuggestions(suggestionSpecProto);
        }

        @Override
        public byte[] deleteByNamespace(String namespace) throws RemoteException {
            return mVmEngine.deleteByNamespace(namespace);
        }

        @Override
        public byte[] deleteBySchemaType(String schemaType) throws RemoteException {
            return mVmEngine.deleteBySchemaType(schemaType);
        }

        @Override
        public byte[] deleteByQuery(byte[] searchSpecProto, boolean returnDeletedDocumentInfo)
                throws RemoteException {
            return mVmEngine.deleteByQuery(searchSpecProto, returnDeletedDocumentInfo);
        }

        @Override
        public byte[] persistToDisk(
                /*PersistType.Code*/ int persistTypeCode) throws RemoteException {
            return mVmEngine.persistToDisk(persistTypeCode);
        }

        @Override
        public byte[] optimize() throws RemoteException {
            return mVmEngine.optimize();
        }

        @Override
        public byte[] getOptimizeInfo() throws RemoteException {
            return mVmEngine.getOptimizeInfo();
        }

        @Override
        public byte[] getStorageInfo() throws RemoteException {
            return mVmEngine.getStorageInfo();
        }

        @Override
        public byte[] getDebugInfo(
                /*DebugInfoVerbosity.Code*/ int verbosity) throws RemoteException {
            return mVmEngine.getDebugInfo(verbosity);
        }

        @Override
        public byte[] reset() throws RemoteException {
            return mVmEngine.reset();
        }
    }
}
