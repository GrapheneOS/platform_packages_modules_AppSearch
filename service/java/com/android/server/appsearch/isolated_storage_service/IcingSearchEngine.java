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
import android.os.DeadObjectException;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.isolated_storage_service.IIcingSearchEngine;
import com.android.server.appsearch.util.MemInfoReader;

import com.google.android.icing.IcingSearchEngineInterface;
import com.google.android.icing.proto.BatchGetResultProto;
import com.google.android.icing.proto.BatchPutResultProto;
import com.google.android.icing.proto.BlobProto;
import com.google.android.icing.proto.DebugInfoResultProto;
import com.google.android.icing.proto.DebugInfoVerbosity;
import com.google.android.icing.proto.DeleteByNamespaceResultProto;
import com.google.android.icing.proto.DeleteByQueryResultProto;
import com.google.android.icing.proto.DeleteBySchemaTypeResultProto;
import com.google.android.icing.proto.DeleteResultProto;
import com.google.android.icing.proto.DocumentProto;
import com.google.android.icing.proto.GetAllNamespacesResultProto;
import com.google.android.icing.proto.GetNextPageRequestProto;
import com.google.android.icing.proto.GetOptimizeInfoResultProto;
import com.google.android.icing.proto.GetResultProto;
import com.google.android.icing.proto.GetResultSpecProto;
import com.google.android.icing.proto.GetSchemaResultProto;
import com.google.android.icing.proto.GetSchemaTypeResultProto;
import com.google.android.icing.proto.IcingSearchEngineOptions;
import com.google.android.icing.proto.InitializeResultProto;
import com.google.android.icing.proto.OptimizeResultProto;
import com.google.android.icing.proto.PersistToDiskResultProto;
import com.google.android.icing.proto.PersistType;
import com.google.android.icing.proto.PropertyProto;
import com.google.android.icing.proto.PutDocumentRequest;
import com.google.android.icing.proto.PutResultProto;
import com.google.android.icing.proto.ReportUsageResultProto;
import com.google.android.icing.proto.ResetResultProto;
import com.google.android.icing.proto.ResultSpecProto;
import com.google.android.icing.proto.SchemaProto;
import com.google.android.icing.proto.ScoringSpecProto;
import com.google.android.icing.proto.SearchResultProto;
import com.google.android.icing.proto.SearchSpecProto;
import com.google.android.icing.proto.SetSchemaRequestProto;
import com.google.android.icing.proto.SetSchemaResultProto;
import com.google.android.icing.proto.StatusProto;
import com.google.android.icing.proto.StorageInfoResultProto;
import com.google.android.icing.proto.SuggestionResponse;
import com.google.android.icing.proto.SuggestionSpecProto;
import com.google.android.icing.proto.UsageReport;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Icing engine backed by the isolated storage service. */
public final class IcingSearchEngine implements IcingSearchEngineInterface {
    private static final String TAG = "IcingSearchEngine";
    private static final int GET_VM_ISOLATED_STORAGE_SERVICE_TIMEOUT_SECONDS = 5;
    private static final int GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS = 5;

    private final IcingSearchEngineOptions mOptions;
    private final IsolatedStorageServiceManager mManager;
    private final UserHandle mUserHandle;

    /** Enforces singleton class pattern. */
    public IcingSearchEngine(
            @NonNull IsolatedStorageServiceManager manager,
            @NonNull UserHandle userHandle,
            @NonNull IcingSearchEngineOptions options) {
        Log.d(TAG, "constructing");
        mManager = Objects.requireNonNull(manager);
        mUserHandle = Objects.requireNonNull(userHandle);
        mOptions = Objects.requireNonNull(options);
        mManager.setIcingSearchEngineOptions(userHandle, options);
    }

    @Override
    public void close() {
        Log.d(TAG, "closing");
        IIcingSearchEngine vmIcingInstance = mManager.getVmIcingInstanceOrNull(mUserHandle);
        if (vmIcingInstance == null) {
            Log.w(TAG, "vm Icing instance for " + mUserHandle + "doesn't exist");
            return;
        }
        try {
            mManager.signalActivityStarts();
            vmIcingInstance.close();
            mManager.removeUserInstance(mUserHandle);
        } catch (Exception e) {
            Log.e(TAG, "failed to call close", e);
        } finally {
            mManager.signalActivityEnds();
        }
    }

    @NonNull
    @Override
    public InitializeResultProto initialize() {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .initialize(mOptions.toByteArray());
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return InitializeResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return InitializeResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                InitializeResultProto.getDefaultInstance(),
                status -> InitializeResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public SetSchemaResultProto setSchema(@NonNull SchemaProto schema) {
        return setSchema(schema, /* ignoreErrorsAndDeleteDocuments= */ false);
    }

    @NonNull
    @Override
    public SetSchemaResultProto setSchema(
            @NonNull SchemaProto schema, boolean ignoreErrorsAndDeleteDocuments) {
        byte[] input = schema.toByteArray();

        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .setSchema(input, ignoreErrorsAndDeleteDocuments);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return SetSchemaResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (OutOfMemoryError e) {
            Log.w(
                    TAG,
                    "Got out of memory in set schema. Request length: "
                            + input.length
                            + ", number of schema types in request: "
                            + schema.getTypesCount());
            logFreeMemoryInfo(e);

            return SetSchemaResultProto.newBuilder().setStatus(oomExceptionStatus(e)).build();
        } catch (RemoteException e) {
            return SetSchemaResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                SetSchemaResultProto.getDefaultInstance(),
                status -> SetSchemaResultProto.newBuilder().setStatus(status).build());
    }

    /**
     * Sets the schema for the icing instance.
     *
     * @param setSchemaRequest the request proto for setting the schema.
     */
    @NonNull
    @Override
    public SetSchemaResultProto setSchemaWithRequestProto(SetSchemaRequestProto setSchemaRequest) {
        byte[] inputRequest = setSchemaRequest.toByteArray();

        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .setSchemaWithRequestProto(inputRequest);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return SetSchemaResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (OutOfMemoryError e) {
            // TODO: if we are still seeing issue, print VM MemAvailable as well
            Log.w(
                    TAG,
                    "Got out of memory in set schema. Request length: "
                            + inputRequest.length
                            + ", number of schema types in request: "
                            + setSchemaRequest.getSchema().getTypesCount());
            return SetSchemaResultProto.newBuilder().setStatus(oomExceptionStatus(e)).build();
        } catch (RemoteException e) {
            return SetSchemaResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                SetSchemaResultProto.getDefaultInstance(),
                status -> SetSchemaResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public GetSchemaResultProto getSchema() {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .getSchema();
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return GetSchemaResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return GetSchemaResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                GetSchemaResultProto.getDefaultInstance(),
                status -> GetSchemaResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public GetSchemaResultProto getSchemaForDatabase(@NonNull String database) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .getSchemaForDatabase(database);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return GetSchemaResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return GetSchemaResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                GetSchemaResultProto.getDefaultInstance(),
                status -> GetSchemaResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public GetSchemaTypeResultProto getSchemaType(@NonNull String schemaType) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .getSchemaType(schemaType);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return GetSchemaTypeResultProto.newBuilder()
                    .setStatus(futureGetFailureStatus(e))
                    .build();
        } catch (RemoteException e) {
            return GetSchemaTypeResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                GetSchemaTypeResultProto.getDefaultInstance(),
                status -> GetSchemaTypeResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public PutResultProto put(@NonNull DocumentProto document) {
        byte[] input = document.toByteArray();

        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .put(input);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return PutResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "Got out of memory in put. Request length: " + input.length);
            logFreeMemoryInfo(e);

            return PutResultProto.newBuilder().setStatus(oomExceptionStatus(e)).build();
        } catch (RemoteException e) {
            return PutResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                PutResultProto.getDefaultInstance(),
                status -> PutResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public BatchPutResultProto batchPut(@NonNull PutDocumentRequest putDocumentRequest) {
        byte[] input = putDocumentRequest.toByteArray();

        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .batchPut(input);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return BatchPutResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (OutOfMemoryError e) {
            Log.w(
                    TAG,
                    "Got out of memory in batch put. Request length: "
                            + input.length
                            + ", number of documents in request: "
                            + putDocumentRequest.getDocumentsCount());
            logFreeMemoryInfo(e);

            return BatchPutResultProto.newBuilder().setStatus(oomExceptionStatus(e)).build();
        } catch (RemoteException e) {
            return BatchPutResultProto.newBuilder()
                    // TODO(b/401245113) set status when the change is available.
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                BatchPutResultProto.getDefaultInstance(),
                status ->
                        BatchPutResultProto.newBuilder()
                                // TODO(b/401245113) set status when the change is available.
                                .setStatus(status)
                                .build());
    }

    @NonNull
    @Override
    public GetResultProto get(
            @NonNull String namespace,
            @NonNull String uri,
            @NonNull GetResultSpecProto getResultSpec) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .get(namespace, uri, getResultSpec.toByteArray());
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return GetResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return GetResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                GetResultProto.getDefaultInstance(),
                status -> GetResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public BatchGetResultProto batchGet(@NonNull GetResultSpecProto getResultSpec) {
        BatchGetResultProto responseForTheFirstCall = null;
        // Used to rewrite the response from the 1st call if limit is reached.
        BatchGetResultProto.Builder responseBuilderIfLimitReached = null;

        try {
            mManager.signalActivityStarts();
            // TODO(b/401245769) this could be set directly in the caller so we don't need to do
            //  this extra toBuilder here.
            // The transaction limit for the VM is 600KB. We just use the same limit for query here.
            GetResultSpecProto.Builder requestBuilder = getResultSpec.toBuilder()
                    .setNumTotalDocumentBytesToReturn(
                            IsolatedStorageServiceManager
                                    .DEFAULT_MAX_PAGE_BYTES_LIMIT_FOR_ISOLATED_STORAGE);
            int numIdsRequested = getResultSpec.getIdsList().size();
            // tracks the previous curIndex value
            int prevIndex = 0;
            // tracks the doc position in the response being rewritten. It is being used to rewrite
            // the result
            // if we reach the limit.
            int curIndex = 0;

            // TODO(b/401245769) We can refactor this do-while with some helper functions to make
            //  the logic easier to follow.

            // We do one batchGet first, and if we see from some id we reach the limit, we will
            // call batchGet again and rewrite the response to retrieve all the docs.
            do {
                getResultSpec = requestBuilder.build();
                // requestBuilder is reused for all the batchGet so we need to clear ids here.
                requestBuilder.clearIds();

                byte[] resultData =
                        mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                                .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                .batchGet(getResultSpec.toByteArray());
                BatchGetResultProto response = getResponseProtoFromRawData(
                        resultData,
                        BatchGetResultProto.getDefaultInstance(),
                        status ->
                                BatchGetResultProto.newBuilder()
                                        .setStatus(status)
                                        .build());
                List<GetResultProto> getResultProtoList = response.getGetResultProtosList();

                if (responseForTheFirstCall == null) {
                    responseForTheFirstCall = response;
                }

                // This flag is set when we see the first ABORTED to indicate for the current
                // batchGet, we can't get all the documents out due to reaching the limit
                // Right now in Icing, if we hit the limit, we will fail the remaining ids.
                // So we don't need to check the status code for the rest of the results if
                // this flag is true.
                boolean limitReached = false;
                prevIndex = curIndex; // saves the previous position.
                // TODO(b/401245769) We can set a different status code to indicate we reach
                //  the limit and should retry to avoid entering this loop unnecessarily.
                for (int i = 0; i < getResultProtoList.size(); ++i) {
                    GetResultProto getResultProto = getResultProtoList.get(i);
                    if (!limitReached
                            && getResultProto.getStatus().getCode() != StatusProto.Code.ABORTED) {
                        if (responseBuilderIfLimitReached != null) {
                            // This is not the 1st call so we need to rewrite the result for
                            // curIndex.
                            responseBuilderIfLimitReached.setGetResultProtos(curIndex,
                                    getResultProto);
                        }
                        // Advance the index in the final result as we won't retry for
                        // the current id.
                        ++curIndex;
                    } else {
                        //
                        // We hit the limit and need to retry.
                        //
                        if (responseBuilderIfLimitReached == null) {
                            // This is the first call. Create response builder from the 1st call.
                            responseBuilderIfLimitReached = responseForTheFirstCall.toBuilder();
                        }
                        limitReached = true;
                        requestBuilder.addIds(getResultProto.getUri());
                    }
                }

                // We exit if
                // 1) we have gotten all the docs we need out(curIndex == requestIdsSize).
                // OR
                // 2) curIndex is not changed(prevIndex == curIndex).
                // It means somehow we get stuck. It could be the asked doc size is always bigger
                // than the limit set. In this case, we should just exit.
            } while (curIndex < numIdsRequested && prevIndex < curIndex);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return BatchGetResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return BatchGetResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        } finally {
            mManager.signalActivityEnds();
        }

        if (responseBuilderIfLimitReached == null) {
            // We didn't reach the limit.
            return responseForTheFirstCall;
        }
        return responseBuilderIfLimitReached.build();
    }

    @NonNull
    @Override
    public ReportUsageResultProto reportUsage(@NonNull UsageReport usageReport) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .reportUsage(usageReport.toByteArray());
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return ReportUsageResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return ReportUsageResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                ReportUsageResultProto.getDefaultInstance(),
                status -> ReportUsageResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public GetAllNamespacesResultProto getAllNamespaces() {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .getAllNamespaces();
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return GetAllNamespacesResultProto.newBuilder()
                    .setStatus(futureGetFailureStatus(e))
                    .build();
        } catch (RemoteException e) {
            return GetAllNamespacesResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                GetAllNamespacesResultProto.getDefaultInstance(),
                status -> GetAllNamespacesResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public SearchResultProto search(
            @NonNull SearchSpecProto searchSpec,
            @NonNull ScoringSpecProto scoringSpec,
            @NonNull ResultSpecProto resultSpec) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .search(
                                    searchSpec.toByteArray(),
                                    scoringSpec.toByteArray(),
                                    resultSpec.toByteArray());
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return SearchResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return SearchResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                SearchResultProto.getDefaultInstance(),
                status -> SearchResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public SearchResultProto getNextPage(long nextPageToken) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .getNextPage(nextPageToken);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return SearchResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return SearchResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                SearchResultProto.getDefaultInstance(),
                status -> SearchResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public SearchResultProto getNextPage(@NonNull GetNextPageRequestProto getNextPageRequest) {
        byte[] inputRequest = getNextPageRequest.toByteArray();

        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .getNextPageWithRequestProto(inputRequest);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return SearchResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return SearchResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                SearchResultProto.getDefaultInstance(),
                status -> SearchResultProto.newBuilder().setStatus(status).build());
    }

    @Override
    public void invalidateNextPageToken(long nextPageToken) {
        try {
            mManager.signalActivityStarts();
            mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                    .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .invalidateNextPageToken(nextPageToken);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            Log.e(TAG, "failed to get VM icing instance", e);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to call invalidateNextPageToken", e);
        } finally {
            mManager.signalActivityEnds();
        }
    }

    @NonNull
    @Override
    public BlobProto openWriteBlob(@NonNull PropertyProto.BlobHandleProto blobHandle) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .openWriteBlob(blobHandle.toByteArray());
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return BlobProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return BlobProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                BlobProto.getDefaultInstance(),
                status -> BlobProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public BlobProto removeBlob(@NonNull PropertyProto.BlobHandleProto blobHandle) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .removeBlob(blobHandle.toByteArray());
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return BlobProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return BlobProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                BlobProto.getDefaultInstance(),
                status -> BlobProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public BlobProto openReadBlob(@NonNull PropertyProto.BlobHandleProto blobHandle) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .openReadBlob(blobHandle.toByteArray());
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return BlobProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return BlobProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                BlobProto.getDefaultInstance(),
                status -> BlobProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public BlobProto commitBlob(@NonNull PropertyProto.BlobHandleProto blobHandle) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .commitBlob(blobHandle.toByteArray());
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return BlobProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return BlobProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                BlobProto.getDefaultInstance(),
                status -> BlobProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public DeleteResultProto delete(@NonNull String namespace, @NonNull String uri) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .deleteDoc(namespace, uri);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return DeleteResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return DeleteResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                DeleteResultProto.getDefaultInstance(),
                status -> DeleteResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public SuggestionResponse searchSuggestions(@NonNull SuggestionSpecProto suggestionSpec) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .searchSuggestions(suggestionSpec.toByteArray());
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return SuggestionResponse.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return SuggestionResponse.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                SuggestionResponse.getDefaultInstance(),
                status -> SuggestionResponse.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public DeleteByNamespaceResultProto deleteByNamespace(@NonNull String namespace) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .deleteByNamespace(namespace);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return DeleteByNamespaceResultProto.newBuilder()
                    .setStatus(futureGetFailureStatus(e))
                    .build();
        } catch (RemoteException e) {
            return DeleteByNamespaceResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                DeleteByNamespaceResultProto.getDefaultInstance(),
                status -> DeleteByNamespaceResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public DeleteBySchemaTypeResultProto deleteBySchemaType(@NonNull String schemaType) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .deleteBySchemaType(schemaType);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return DeleteBySchemaTypeResultProto.newBuilder()
                    .setStatus(futureGetFailureStatus(e))
                    .build();
        } catch (RemoteException e) {
            return DeleteBySchemaTypeResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                DeleteBySchemaTypeResultProto.getDefaultInstance(),
                status -> DeleteBySchemaTypeResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public DeleteByQueryResultProto deleteByQuery(@NonNull SearchSpecProto searchSpec) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .deleteByQuery(
                                    searchSpec.toByteArray(),
                                    /* returnDeletedDocumentInfo= */ false);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return DeleteByQueryResultProto.newBuilder()
                    .setStatus(futureGetFailureStatus(e))
                    .build();
        } catch (RemoteException e) {
            return DeleteByQueryResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                DeleteByQueryResultProto.getDefaultInstance(),
                status -> DeleteByQueryResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public DeleteByQueryResultProto deleteByQuery(
            @NonNull SearchSpecProto searchSpec, boolean returnDeletedDocumentInfo) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .deleteByQuery(searchSpec.toByteArray(), returnDeletedDocumentInfo);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return DeleteByQueryResultProto.newBuilder()
                    .setStatus(futureGetFailureStatus(e))
                    .build();
        } catch (RemoteException e) {
            return DeleteByQueryResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                DeleteByQueryResultProto.getDefaultInstance(),
                status -> DeleteByQueryResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public PersistToDiskResultProto persistToDisk(@NonNull PersistType.Code persistTypeCode) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .persistToDisk(persistTypeCode.getNumber());
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return PersistToDiskResultProto.newBuilder()
                    .setStatus(futureGetFailureStatus(e))
                    .build();
        } catch (RemoteException e) {
            return PersistToDiskResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                PersistToDiskResultProto.getDefaultInstance(),
                status -> PersistToDiskResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public OptimizeResultProto optimize() {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .optimize();
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return OptimizeResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return OptimizeResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                OptimizeResultProto.getDefaultInstance(),
                status -> OptimizeResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public GetOptimizeInfoResultProto getOptimizeInfo() {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .getOptimizeInfo();
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return GetOptimizeInfoResultProto.newBuilder()
                    .setStatus(futureGetFailureStatus(e))
                    .build();
        } catch (RemoteException e) {
            return GetOptimizeInfoResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                GetOptimizeInfoResultProto.getDefaultInstance(),
                status -> GetOptimizeInfoResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public StorageInfoResultProto getStorageInfo() {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .getStorageInfo();
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return StorageInfoResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return StorageInfoResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                StorageInfoResultProto.getDefaultInstance(),
                status -> StorageInfoResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public DebugInfoResultProto getDebugInfo(@NonNull DebugInfoVerbosity.Code verbosity) {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .getDebugInfo(verbosity.getNumber());
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return DebugInfoResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return DebugInfoResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                DebugInfoResultProto.getDefaultInstance(),
                status -> DebugInfoResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public ResetResultProto reset() {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .reset();
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return ResetResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return ResetResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        return getResponseProtoFromRawData(
                resultData,
                ResetResultProto.getDefaultInstance(),
                status -> ResetResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public ResetResultProto clearAndDestroy() {
        byte[] resultData;
        try {
            mManager.signalActivityStarts();
            resultData =
                    mManager.getOrCreateVmIcingInstanceAsync(mUserHandle)
                            .get(GET_VM_ICING_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .clearAndDestroy();
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return ResetResultProto.newBuilder().setStatus(futureGetFailureStatus(e)).build();
        } catch (RemoteException e) {
            return ResetResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        } finally {
            mManager.signalActivityEnds();
        }

        mManager.removeUserInstance(mUserHandle);

        return getResponseProtoFromRawData(
                resultData,
                ResetResultProto.getDefaultInstance(),
                status -> ResetResultProto.newBuilder().setStatus(status).build());
    }

    private static @NonNull <M extends MessageLite> M getResponseProtoFromRawData(
            @Nullable byte[] result,
            @NonNull M defaultInstance,
            @NonNull Function<StatusProto, M> createResponseWithStatus) {
        M resultProto = defaultInstance;
        if (result == null) return resultProto;

        try {
            resultProto = parseData(resultProto, result);
        } catch (InvalidProtocolBufferException e) {
            return createResponseWithStatus.apply(protoParseFailureStatus(e));
        }

        return resultProto;
    }

    private static @NonNull <M extends MessageLite> M parseData(
            @NonNull M defaultInstance, @NonNull byte[] data)
            throws InvalidProtocolBufferException {
        @SuppressWarnings("unchecked") // valid by protobuf contract
        Parser<M> parser = (Parser<M>) defaultInstance.getParserForType();
        return parser.parseFrom(data);
    }

    private @NonNull StatusProto futureGetFailureStatus(@NonNull Exception e) {
        String message;
        if (mManager.isReconnecting()) {
            Log.w(TAG, "Reconnecting to isolated storage service", e);
            message = "reconnecting to isolated storage service, please wait and retry";
        } else {
            Log.w(TAG, "Failed to get future result", e);
            message = "vm isolated storage service/icing is not available: " + e.getMessage();
        }
        return StatusProto.newBuilder()
                .setCode(StatusProto.Code.UNAVAILABLE)
                .setMessage(message)
                .build();
    }

    private @NonNull StatusProto remoteExceptionStatus(@NonNull RemoteException e) {
        String message;
        if (mManager.isReconnecting() && e instanceof DeadObjectException) {
            Log.w(TAG, "Reconnecting to isolated storage service", e);
            message = "reconnecting to isolated storage service, please wait and retry";
        } else {
            Log.w(TAG, "Failed to call isolated storage service via binder", e);
            message = "failed to call isolated storage service via binder: " + e.getMessage();
        }
        return StatusProto.newBuilder()
                .setCode(StatusProto.Code.UNAVAILABLE)
                .setMessage(message)
                .build();
    }

    private static @NonNull StatusProto oomExceptionStatus(@NonNull OutOfMemoryError e) {
        Log.w(TAG, "Encountered OOM in midst of binder transaction", e);
        // TODO(b/404210068): Add a different error code to distinguish these failures.
        return StatusProto.newBuilder()
                .setCode(StatusProto.Code.UNKNOWN)
                .setMessage("Ran out of memory when allocating request: " + e.getMessage())
                .build();
    }

    private static @NonNull StatusProto protoParseFailureStatus(@NonNull Exception e) {
        Log.w(TAG, "Failed to parse proto data", e);
        return StatusProto.newBuilder()
                .setCode(StatusProto.Code.INTERNAL)
                .setMessage("failed to parse proto data: " + e.getMessage())
                .build();
    }

    /**
     * Helper function to get and print the amount of free RAM in KB. {@code -1} will be printed if
     * failing to get the number.
     */
    private void logFreeMemoryInfo(@Nullable OutOfMemoryError oomError) {
        long deviceFreeMemoryKb = -1;
        try {
            MemInfoReader memInfo = new MemInfoReader();
            deviceFreeMemoryKb = memInfo.getFreeSizeKb();
        } catch (Error e) {
            Log.w(TAG, "Unable to get device free memory size from /proc/meminfo due to error", e);
        } catch (Exception e) {
            Log.w(TAG, "Unable to get device free memory size from /proc/meminfo", e);
        }

        long jvmFreeMemoryKb = -1;
        try {
            jvmFreeMemoryKb = Runtime.getRuntime().freeMemory() / 1024;
        } catch (Error e) {
            Log.w(TAG, "Unable to get jvm free memory size due to error", e);
        }

        long vmIsolatedStorageFreeMemoryKb = -1;
        try {
            vmIsolatedStorageFreeMemoryKb =
                    mManager.getVmIsolatedStorageServiceAsync()
                            .get(GET_VM_ISOLATED_STORAGE_SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .getAvailableMemory();
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            Log.wtf(TAG, "Unable to get vm isolated storage service", e);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Unable to get vm isolated storage free memory size", e);
        } catch (OutOfMemoryError e) {
            Log.wtf(TAG, "Unable to get vm isolated storage free memory size due to OOM error", e);
        } catch (Error e) {
            Log.wtf(TAG, "Unable to get vm isolated storage free memory size due to error", e);
        }

        Log.wtf(
                TAG,
                "Android device free memory (from /proc/meminfo): "
                        + deviceFreeMemoryKb
                        + " KB, Android JVM free memory (from Runtime): "
                        + jvmFreeMemoryKb
                        + " KB, VM isolated storage free memory: "
                        + vmIsolatedStorageFreeMemoryKb
                        + " KB.", oomError);
    }
}
