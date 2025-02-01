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
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.virtualmachine.VirtualMachine;
import android.system.virtualmachine.VirtualMachineCallback;
import android.system.virtualmachine.VirtualMachineConfig;
import android.system.virtualmachine.VirtualMachineException;
import android.system.virtualmachine.VirtualMachineManager;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.Map;
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
     * serialized Icing requests/responses between pVM and AppSearch. {@link ParcelFileDescriptor}
     * instances are used for communication with AppSearch to overcome the binder transaction limit.
     */
    private static class IcingSearchEngineStub extends IIcingSearchEngine.Stub {

        private final com.android.isolated_storage_service.IIcingSearchEngine mVmEngine;

        IcingSearchEngineStub(com.android.isolated_storage_service.IIcingSearchEngine vmEngine) {
            mVmEngine = vmEngine;
        }

        /**
         * Creates a {@link ParcelFileDescriptor} socket pair to pass {@code data}, and returns the
         * {@link ParcelFileDescriptor} instance that clients can read from.
         *
         * <p>Use {@link ParcelFileDescriptor} to overcome the binder transaction limit.
         *
         * <p>The {@link ParcelFileDescriptor} instances used here are backed by a socket pair. This
         * method writes {@code data} to one end of the socket pair and closes it. The {@link
         * ParcelFileDescriptor} instance backed by the other end of the socket pair is returned to
         * client, and client can read {@code data} from it. After client also closes the other end
         * of the socket pair, data gets cleaned up.
         */
        private static ParcelFileDescriptor createPfd(byte[] data) throws RemoteException {
            ParcelFileDescriptor[] pipe;
            try {
                pipe = ParcelFileDescriptor.createSocketPair();
                try (ParcelFileDescriptor.AutoCloseOutputStream outputStream =
                        new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    outputStream.write(data);
                }
            } catch (Exception e) {
                throw new RemoteException(e.getMessage());
            }
            return pipe[0];
        }

        /**
         * Reads {@link data} from a {@link ParcelFileDescriptor} instance.
         *
         * <p>Use {@link ParcelFileDescriptor} to overcome the binder transaction limit.
         */
        private static byte[] readFromPfd(ParcelFileDescriptor pfd) throws RemoteException {
            ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
            try (ParcelFileDescriptor.AutoCloseInputStream inputStream =
                    new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                inputStream.transferTo(dataStream);
            } catch (Exception e) {
                throw new RemoteException(e.getMessage());
            }
            return dataStream.toByteArray();
        }

        @Override
        public void initialize(
                ParcelFileDescriptor icingSearchEngineOptionsProto,
                IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data =
                    createPfd(mVmEngine.initialize(readFromPfd(icingSearchEngineOptionsProto)));
            callback.onResult(result);
        }

        @Override
        public void close() throws RemoteException {
            mVmEngine.close();
        }

        @Override
        public void setSchema(
                ParcelFileDescriptor schemaProto,
                boolean ignoreErrorsAndDeleteDocuments,
                IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data =
                    createPfd(
                            mVmEngine.setSchema(
                                    readFromPfd(schemaProto), ignoreErrorsAndDeleteDocuments));
            callback.onResult(result);
        }

        @Override
        public void getSchema(IIcingSearchResultCallback callback) throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.getSchema());
            callback.onResult(result);
        }

        @Override
        public void getSchemaForDatabase(String database, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.getSchemaForDatabase(database));
            callback.onResult(result);
        }

        @Override
        public void getSchemaType(String schemaType, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.getSchemaType(schemaType));
            callback.onResult(result);
        }

        @Override
        public void put(ParcelFileDescriptor documentProto, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.put(readFromPfd(documentProto)));
            callback.onResult(result);
        }

        @Override
        public void get(
                String namespace,
                String uri,
                ParcelFileDescriptor getResultSpecProto,
                IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.get(namespace, uri, readFromPfd(getResultSpecProto)));
            callback.onResult(result);
        }

        @Override
        public void reportUsage(
                ParcelFileDescriptor usageReportProto, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.reportUsage(readFromPfd(usageReportProto)));
            callback.onResult(result);
        }

        @Override
        public void getAllNamespaces(IIcingSearchResultCallback callback) throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.getAllNamespaces());
            callback.onResult(result);
        }

        @Override
        public void search(
                ParcelFileDescriptor searchSpecProto,
                ParcelFileDescriptor scoringSpecProto,
                ParcelFileDescriptor resultSpecProto,
                IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data =
                    createPfd(
                            mVmEngine.search(
                                    readFromPfd(searchSpecProto),
                                    readFromPfd(scoringSpecProto),
                                    readFromPfd(resultSpecProto)));
            callback.onResult(result);
        }

        @Override
        public void getNextPage(long nextPageToken, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.getNextPage(nextPageToken));
            callback.onResult(result);
        }

        @Override
        public void invalidateNextPageToken(long nextPageToken) throws RemoteException {
            mVmEngine.invalidateNextPageToken(nextPageToken);
        }

        @Override
        public void openWriteBlob(
                ParcelFileDescriptor blobHandleProto, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.openWriteBlob(readFromPfd(blobHandleProto)));
            callback.onResult(result);
        }

        @Override
        public void removeBlob(
                ParcelFileDescriptor blobHandleProto, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.removeBlob(readFromPfd(blobHandleProto)));
            callback.onResult(result);
        }

        @Override
        public void openReadBlob(
                ParcelFileDescriptor blobHandleProto, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.openReadBlob(readFromPfd(blobHandleProto)));
            callback.onResult(result);
        }

        @Override
        public void commitBlob(
                ParcelFileDescriptor blobHandleProto, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.commitBlob(readFromPfd(blobHandleProto)));
            callback.onResult(result);
        }

        @Override
        public void deleteByUri(String namespace, String uri, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.deleteDoc(namespace, uri));
            callback.onResult(result);
        }

        @Override
        public void searchSuggestions(
                ParcelFileDescriptor suggestionSpecProto, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.searchSuggestions(readFromPfd(suggestionSpecProto)));
            callback.onResult(result);
        }

        @Override
        public void deleteByNamespace(String namespace, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.deleteByNamespace(namespace));
            callback.onResult(result);
        }

        @Override
        public void deleteBySchemaType(String schemaType, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.deleteBySchemaType(schemaType));
            callback.onResult(result);
        }

        @Override
        public void deleteByQuery(
                ParcelFileDescriptor searchSpecProto,
                boolean returnDeletedDocumentInfo,
                IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data =
                    createPfd(
                            mVmEngine.deleteByQuery(
                                    readFromPfd(searchSpecProto), returnDeletedDocumentInfo));
            callback.onResult(result);
        }

        @Override
        public void persistToDisk(
                /*PersistType.Code*/ int persistTypeCode, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.persistToDisk(persistTypeCode));
            callback.onResult(result);
        }

        @Override
        public void optimize(IIcingSearchResultCallback callback) throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.optimize());
            callback.onResult(result);
        }

        @Override
        public void getOptimizeInfo(IIcingSearchResultCallback callback) throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.getOptimizeInfo());
            callback.onResult(result);
        }

        @Override
        public void getStorageInfo(IIcingSearchResultCallback callback) throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.getStorageInfo());
            callback.onResult(result);
        }

        @Override
        public void getDebugInfo(
                /*DebugInfoVerbosity.Code*/ int verbosity, IIcingSearchResultCallback callback)
                throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.getDebugInfo(verbosity));
            callback.onResult(result);
        }

        @Override
        public void reset(IIcingSearchResultCallback callback) throws RemoteException {
            IcingSearchResult result = new IcingSearchResult();
            result.data = createPfd(mVmEngine.reset());
            callback.onResult(result);
        }
    }
}
