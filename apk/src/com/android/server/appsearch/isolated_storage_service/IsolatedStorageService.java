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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    private final CountDownLatch mIsolatedStorageServiceLatch = new CountDownLatch(1);
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

    private void startVm(VmConfig vmConfig) {
        VirtualMachine vm = maybeCreateVm(vmConfig);
        if (vm == null) {
            Log.e(TAG, "Unable to create/get VirtualMachine");
            return;
        }
        Callback callback = new Callback();
        vm.setCallback(mExecutorService, callback);
        try {
            vm.run();
        } catch (VirtualMachineException e) {
            Log.e(TAG, "Failed to run " + VM_NAME, e);
        }
    }

    /**
     * Tries to create the VM.
     *
     * <p>If the VM already exists, return it. If the VM doesn't exist or is deleted, create a new
     * VM and return it. Return {@code null} if failed to get or create the VM.
     */
    private @Nullable VirtualMachine maybeCreateVm(VmConfig vmConfig) {
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
        return createVm(vmm, vmConfig);
    }

    private @Nullable VirtualMachine createVm(VirtualMachineManager vmm, VmConfig vmConfig) {
        VirtualMachineConfig config =
                new VirtualMachineConfig.Builder(this)
                        .setPayloadBinaryName(PAYLOAD_BINARY_NAME)
                        .setProtectedVm(true)
                        .setDebugLevel(VirtualMachineConfig.DEBUG_LEVEL_FULL)
                        .setEncryptedStorageBytes(vmConfig.encryptedStorageBytes)
                        .setMemoryBytes(vmConfig.memoryBytes)
                        .setCpuTopology(VirtualMachineConfig.CPU_TOPOLOGY_MATCH_HOST)
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
    private class Callback implements VirtualMachineCallback {

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
                mIsolatedStorageServiceLatch.countDown();
            } catch (VirtualMachineException e) {
                Log.e(TAG, "Failed to connect to " + VM_NAME, e);
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

        @GuardedBy("mEnginesLocked")
        private final Map<Integer, IIcingSearchEngine> mEnginesLocked = new ArrayMap<>();

        @Override
        public void startAndWaitForVm(VmConfig vmConfig, IVmPayloadReadyCallback callback)
                throws RemoteException {
            startVm(vmConfig);
            try {
                if (!mIsolatedStorageServiceLatch.await(
                        PAYLOAD_READY_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    // TODO: b/384768541 - add telemetry logging for timeout scenarios
                    Log.e(
                            TAG,
                            "Timed out after waiting for payload ready for "
                                    + PAYLOAD_READY_WAIT_TIMEOUT_SECONDS
                                    + " seconds");
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Unable to wait for payload ready");
            }
            callback.onReady();
        }

        @Override
        public void getIcingSearchEngine(int userId, IIcingSearchEngineCallback callback)
                throws RemoteException {
            if (mIsolatedStorageService == null) {
                throw new RemoteException("pVM payload is not ready/available");
            }
            IIcingSearchEngine engine;
            synchronized (mEnginesLocked) {
                engine = mEnginesLocked.get(userId);
                if (engine == null) {
                    engine =
                            new IcingSearchEngineStub(
                                    mIsolatedStorageService.getOrCreateIcingConnection(userId));
                    mEnginesLocked.put(userId, engine);
                }
            }
            callback.onResult(engine);
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

        IcingSearchEngineStub(
                @NonNull com.android.isolated_storage_service.IIcingSearchEngine vmEngine) {
            mVmEngine = Objects.requireNonNull(vmEngine);
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
        public void initialize(
                byte[] icingSearchEngineOptionsProto, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data =
                    IcingSearchResult.Data.rawData(
                            mVmEngine.initialize(icingSearchEngineOptionsProto));
            callback.onResult(result);
        }

        @Override
        public void close() throws RemoteException {
            mVmEngine.close();
        }

        @Override
        public void setSchema(
                SharedMemory schemaProto,
                boolean ignoreErrorsAndDeleteDocuments,
                IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data =
                    IcingSearchResult.Data.rawData(
                            mVmEngine.setSchema(
                                    readFromSharedMemory(schemaProto),
                                    ignoreErrorsAndDeleteDocuments));
            callback.onResult(result);
        }

        @Override
        public void getSchema(IIcingSearchResultCallback callback) throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.getSchema());
            callback.onResult(result);
        }

        @Override
        public void getSchemaForDatabase(String database, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.getSchemaForDatabase(database));
            callback.onResult(result);
        }

        @Override
        public void getSchemaType(String schemaType, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.getSchemaType(schemaType));
            callback.onResult(result);
        }

        @Override
        public void put(SharedMemory documentProto, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data =
                    IcingSearchResult.Data.rawData(
                            mVmEngine.put(readFromSharedMemory(documentProto)));
            callback.onResult(result);
        }

        @Override
        public void get(
                String namespace,
                String uri,
                byte[] getResultSpecProto,
                IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data =
                    IcingSearchResult.Data.sharedMemory(
                            createSharedMemory(mVmEngine.get(namespace, uri, getResultSpecProto)));
            callback.onResult(result);
        }

        @Override
        public void reportUsage(byte[] usageReportProto, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.reportUsage(usageReportProto));
            callback.onResult(result);
        }

        @Override
        public void getAllNamespaces(IIcingSearchResultCallback callback) throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.getAllNamespaces());
            callback.onResult(result);
        }

        @Override
        public void search(
                byte[] searchSpecProto,
                byte[] scoringSpecProto,
                byte[] resultSpecProto,
                IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data =
                    IcingSearchResult.Data.sharedMemory(
                            createSharedMemory(
                                    mVmEngine.search(
                                            searchSpecProto, scoringSpecProto, resultSpecProto)));
            callback.onResult(result);
        }

        @Override
        public void getNextPage(long nextPageToken, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data =
                    IcingSearchResult.Data.sharedMemory(
                            createSharedMemory(mVmEngine.getNextPage(nextPageToken)));
            callback.onResult(result);
        }

        @Override
        public void invalidateNextPageToken(long nextPageToken) throws RemoteException {
            mVmEngine.invalidateNextPageToken(nextPageToken);
        }

        @Override
        public void openWriteBlob(byte[] blobHandleProto, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.openWriteBlob(blobHandleProto));
            callback.onResult(result);
        }

        @Override
        public void removeBlob(byte[] blobHandleProto, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.removeBlob(blobHandleProto));
            callback.onResult(result);
        }

        @Override
        public void openReadBlob(byte[] blobHandleProto, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.openReadBlob(blobHandleProto));
            callback.onResult(result);
        }

        @Override
        public void commitBlob(byte[] blobHandleProto, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.commitBlob(blobHandleProto));
            callback.onResult(result);
        }

        @Override
        public void deleteByUri(String namespace, String uri, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.deleteDoc(namespace, uri));
            callback.onResult(result);
        }

        @Override
        public void searchSuggestions(
                byte[] suggestionSpecProto, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data =
                    IcingSearchResult.Data.rawData(
                            mVmEngine.searchSuggestions(suggestionSpecProto));
            callback.onResult(result);
        }

        @Override
        public void deleteByNamespace(String namespace, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.deleteByNamespace(namespace));
            callback.onResult(result);
        }

        @Override
        public void deleteBySchemaType(String schemaType, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.deleteBySchemaType(schemaType));
            callback.onResult(result);
        }

        @Override
        public void deleteByQuery(
                byte[] searchSpecProto,
                boolean returnDeletedDocumentInfo,
                IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data =
                    IcingSearchResult.Data.rawData(
                            mVmEngine.deleteByQuery(searchSpecProto, returnDeletedDocumentInfo));
            callback.onResult(result);
        }

        @Override
        public void persistToDisk(
                /*PersistType.Code*/ int persistTypeCode, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.persistToDisk(persistTypeCode));
            callback.onResult(result);
        }

        @Override
        public void optimize(IIcingSearchResultCallback callback) throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.optimize());
            callback.onResult(result);
        }

        @Override
        public void getOptimizeInfo(IIcingSearchResultCallback callback) throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.getOptimizeInfo());
            callback.onResult(result);
        }

        @Override
        public void getStorageInfo(IIcingSearchResultCallback callback) throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.getStorageInfo());
            callback.onResult(result);
        }

        @Override
        public void getDebugInfo(
                /*DebugInfoVerbosity.Code*/ int verbosity, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.getDebugInfo(verbosity));
            callback.onResult(result);
        }

        @Override
        public void reset(IIcingSearchResultCallback callback) throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = IcingSearchResult.Data.rawData(mVmEngine.reset());
            callback.onResult(result);
        }
    }
}
