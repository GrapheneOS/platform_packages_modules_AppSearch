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
import android.os.RemoteException;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import com.google.android.icing.IcingSearchEngineInterface;
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.Function;

/** Icing engine backed by the isolated storage service. */
public final class IcingSearchEngine implements IcingSearchEngineInterface {
    private static final String TAG = "IcingSearchEngine";

    private final IIcingSearchEngine mEngine;
    private final IcingSearchEngineOptions mOptions;
    private final long mIcingDataUnionSizeThresholdBytes;

    /** Enforces singleton class pattern. */
    public IcingSearchEngine(
            @NonNull IIcingSearchEngine engine,
            @NonNull IcingSearchEngineOptions options,
            long icingDataUnionSizeThresholdBytes) {
        Log.d(TAG, "constructing");
        mEngine = Objects.requireNonNull(engine);
        mOptions = Objects.requireNonNull(options);
        mIcingDataUnionSizeThresholdBytes = icingDataUnionSizeThresholdBytes;
    }

    @Override
    public void close() {
        Log.d(TAG, "closing");
        try {
            mEngine.close();
        } catch (RemoteException e) {
            Log.e(TAG, "failed to call close", e);
        }
    }

    @NonNull
    @Override
    public InitializeResultProto initialize() {
        byte[] resultData;
        try {
            resultData = mEngine.initialize(mOptions.toByteArray());
        } catch (RemoteException e) {
            return InitializeResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProtoFromRawData(
                resultData,
                InitializeResultProto.getDefaultInstance(),
                status -> InitializeResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public SetSchemaResultProto setSchema(@NonNull SchemaProto schema) {
        byte[] resultData;
        try {
            resultData =
                    mEngine.setSchema(
                            createIcingDataUnion(schema),
                            /* ignoreErrorsAndDeleteDocuments= */ false);
        } catch (ErrnoException e) {
            return SetSchemaResultProto.newBuilder()
                    .setStatus(sharedMemoryCreateFailureStatus(e))
                    .build();
        } catch (RemoteException e) {
            return SetSchemaResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProtoFromRawData(
                resultData,
                SetSchemaResultProto.getDefaultInstance(),
                status -> SetSchemaResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public SetSchemaResultProto setSchema(
            @NonNull SchemaProto schema, boolean ignoreErrorsAndDeleteDocuments) {
        byte[] resultData;
        try {
            resultData =
                    mEngine.setSchema(createIcingDataUnion(schema), ignoreErrorsAndDeleteDocuments);
        } catch (ErrnoException e) {
            return SetSchemaResultProto.newBuilder()
                    .setStatus(sharedMemoryCreateFailureStatus(e))
                    .build();
        } catch (RemoteException e) {
            return SetSchemaResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
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
    public SetSchemaResultProto setSchemaWithRequestProto(SetSchemaRequestProto setSchemaRequest) {
        // TODO(b/337913932): have vm version support this api.
        throw new UnsupportedOperationException(
                "setSchemaWithRequestProto is temporarily unsupported.");
    }

    @NonNull
    @Override
    public GetSchemaResultProto getSchema() {
        IcingDataUnion resultUnion;
        try {
            resultUnion = mEngine.getSchema();
        } catch (RemoteException e) {
            return GetSchemaResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProtoFromIcingDataUnion(
                resultUnion,
                GetSchemaResultProto.getDefaultInstance(),
                status -> GetSchemaResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public GetSchemaResultProto getSchemaForDatabase(@NonNull String database) {
        IcingDataUnion resultUnion;
        try {
            resultUnion = mEngine.getSchemaForDatabase(database);
        } catch (RemoteException e) {
            return GetSchemaResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProtoFromIcingDataUnion(
                resultUnion,
                GetSchemaResultProto.getDefaultInstance(),
                status -> GetSchemaResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public GetSchemaTypeResultProto getSchemaType(@NonNull String schemaType) {
        byte[] resultData;
        try {
            resultData = mEngine.getSchemaType(schemaType);
        } catch (RemoteException e) {
            return GetSchemaTypeResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        }

        return getResponseProtoFromRawData(
                resultData,
                GetSchemaTypeResultProto.getDefaultInstance(),
                status -> GetSchemaTypeResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public PutResultProto put(@NonNull DocumentProto document) {
        byte[] resultData;
        try {
            resultData = mEngine.put(createIcingDataUnion(document));
        } catch (ErrnoException e) {
            return PutResultProto.newBuilder()
                    .setStatus(sharedMemoryCreateFailureStatus(e))
                    .build();
        } catch (RemoteException e) {
            return PutResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProtoFromRawData(
                resultData,
                PutResultProto.getDefaultInstance(),
                status -> PutResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public BatchPutResultProto batchPut(@NonNull PutDocumentRequest putDocumentRequest) {
        byte[] resultData;
        try {
            resultData = mEngine.batchPut(createIcingDataUnion(putDocumentRequest));
        } catch (ErrnoException e) {
            return BatchPutResultProto.newBuilder()
                    // TODO(b/401245113) set status when the change is available.
                    // .setStatus(sharedMemoryCreateFailureStatus(e))
                    .build();
        } catch (RemoteException e) {
            return BatchPutResultProto.newBuilder()
                    // TODO(b/401245113) set status when the change is available.
                    // .setStatus(remoteExceptionStatus(e))
                    .build();
        }

        return getResponseProtoFromRawData(
                resultData,
                BatchPutResultProto.getDefaultInstance(),
                status ->
                        BatchPutResultProto.newBuilder()
                                // TODO(b/401245113) set status when the change is available.
                                // .setStatus(status)
                                .build());
    }

    @NonNull
    @Override
    public GetResultProto get(
            @NonNull String namespace,
            @NonNull String uri,
            @NonNull GetResultSpecProto getResultSpec) {
        IcingDataUnion resultUnion;
        try {
            resultUnion = mEngine.get(namespace, uri, getResultSpec.toByteArray());
        } catch (RemoteException e) {
            return GetResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProtoFromIcingDataUnion(
                resultUnion,
                GetResultProto.getDefaultInstance(),
                status -> GetResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public ReportUsageResultProto reportUsage(@NonNull UsageReport usageReport) {
        byte[] resultData;
        try {
            resultData = mEngine.reportUsage(usageReport.toByteArray());
        } catch (RemoteException e) {
            return ReportUsageResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
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
            resultData = mEngine.getAllNamespaces();
        } catch (RemoteException e) {
            return GetAllNamespacesResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
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
        IcingDataUnion resultUnion;
        try {
            resultUnion =
                    mEngine.search(
                            searchSpec.toByteArray(),
                            scoringSpec.toByteArray(),
                            resultSpec.toByteArray());
        } catch (RemoteException e) {
            return SearchResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProtoFromIcingDataUnion(
                resultUnion,
                SearchResultProto.getDefaultInstance(),
                status -> SearchResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public SearchResultProto getNextPage(long nextPageToken) {
        IcingDataUnion resultUnion;
        try {
            resultUnion = mEngine.getNextPage(nextPageToken);
        } catch (RemoteException e) {
            return SearchResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProtoFromIcingDataUnion(
                resultUnion,
                SearchResultProto.getDefaultInstance(),
                status -> SearchResultProto.newBuilder().setStatus(status).build());
    }

    @Override
    public void invalidateNextPageToken(long nextPageToken) {
        try {
            mEngine.invalidateNextPageToken(nextPageToken);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to call invalidateNextPageToken", e);
        }
    }

    @NonNull
    @Override
    public BlobProto openWriteBlob(@NonNull PropertyProto.BlobHandleProto blobHandle) {
        byte[] resultData;
        try {
            resultData = mEngine.openWriteBlob(blobHandle.toByteArray());
        } catch (RemoteException e) {
            return BlobProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
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
            resultData = mEngine.removeBlob(blobHandle.toByteArray());
        } catch (RemoteException e) {
            return BlobProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
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
            resultData = mEngine.openReadBlob(blobHandle.toByteArray());
        } catch (RemoteException e) {
            return BlobProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
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
            resultData = mEngine.commitBlob(blobHandle.toByteArray());
        } catch (RemoteException e) {
            return BlobProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
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
            resultData = mEngine.deleteByUri(namespace, uri);
        } catch (RemoteException e) {
            return DeleteResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
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
            resultData = mEngine.searchSuggestions(suggestionSpec.toByteArray());
        } catch (RemoteException e) {
            return SuggestionResponse.newBuilder().setStatus(remoteExceptionStatus(e)).build();
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
            resultData = mEngine.deleteByNamespace(namespace);
        } catch (RemoteException e) {
            return DeleteByNamespaceResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
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
            resultData = mEngine.deleteBySchemaType(schemaType);
        } catch (RemoteException e) {
            return DeleteBySchemaTypeResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        }

        return getResponseProtoFromRawData(
                resultData,
                DeleteBySchemaTypeResultProto.getDefaultInstance(),
                status -> DeleteBySchemaTypeResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public DeleteByQueryResultProto deleteByQuery(@NonNull SearchSpecProto searchSpec) {
        IcingDataUnion resultUnion;
        try {
            resultUnion =
                    mEngine.deleteByQuery(
                            searchSpec.toByteArray(), /* returnDeletedDocumentInfo= */ false);
        } catch (RemoteException e) {
            return DeleteByQueryResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        }

        return getResponseProtoFromIcingDataUnion(
                resultUnion,
                DeleteByQueryResultProto.getDefaultInstance(),
                status -> DeleteByQueryResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public DeleteByQueryResultProto deleteByQuery(
            @NonNull SearchSpecProto searchSpec, boolean returnDeletedDocumentInfo) {
        IcingDataUnion resultUnion;
        try {
            resultUnion =
                    mEngine.deleteByQuery(searchSpec.toByteArray(), returnDeletedDocumentInfo);
        } catch (RemoteException e) {
            return DeleteByQueryResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        }

        return getResponseProtoFromIcingDataUnion(
                resultUnion,
                DeleteByQueryResultProto.getDefaultInstance(),
                status -> DeleteByQueryResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public PersistToDiskResultProto persistToDisk(@NonNull PersistType.Code persistTypeCode) {
        byte[] resultData;
        try {
            resultData = mEngine.persistToDisk(persistTypeCode.getNumber());
        } catch (RemoteException e) {
            return PersistToDiskResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        }

        return getResponseProtoFromRawData(
                resultData,
                PersistToDiskResultProto.getDefaultInstance(),
                status -> PersistToDiskResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public OptimizeResultProto optimize() {
        IcingDataUnion resultUnion;
        try {
            resultUnion = mEngine.optimize();
        } catch (RemoteException e) {
            return OptimizeResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProtoFromIcingDataUnion(
                resultUnion,
                OptimizeResultProto.getDefaultInstance(),
                status -> OptimizeResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public GetOptimizeInfoResultProto getOptimizeInfo() {
        byte[] resultData;
        try {
            resultData = mEngine.getOptimizeInfo();
        } catch (RemoteException e) {
            return GetOptimizeInfoResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        }

        return getResponseProtoFromRawData(
                resultData,
                GetOptimizeInfoResultProto.getDefaultInstance(),
                status -> GetOptimizeInfoResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public StorageInfoResultProto getStorageInfo() {
        IcingDataUnion resultUnion;
        try {
            resultUnion = mEngine.getStorageInfo();
        } catch (RemoteException e) {
            return StorageInfoResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProtoFromIcingDataUnion(
                resultUnion,
                StorageInfoResultProto.getDefaultInstance(),
                status -> StorageInfoResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public DebugInfoResultProto getDebugInfo(@NonNull DebugInfoVerbosity.Code verbosity) {
        byte[] resultData;
        try {
            resultData = mEngine.getDebugInfo(verbosity.getNumber());
        } catch (RemoteException e) {
            return DebugInfoResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
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
            resultData = mEngine.reset();
        } catch (RemoteException e) {
            return ResetResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProtoFromRawData(
                resultData,
                ResetResultProto.getDefaultInstance(),
                status -> ResetResultProto.newBuilder().setStatus(status).build());
    }

    /**
     * Creates an {@link IcingDataUnion} instance to pass serialized {@code data}.
     *
     * <p>For data smaller than {@link IcingSearchEngine#mIcingDataUnionSizeThresholdBytes}, use
     * byte array to pass it. For data larger than that, use {@link SharedMemory} to pass it. Using
     * {@link SharedMemory} is more expensive so we want to avoid when possible.
     */
    private @NonNull IcingDataUnion createIcingDataUnion(@NonNull MessageLite data)
            throws ErrnoException {
        IcingDataUnion union = new IcingDataUnion();
        if (data.getSerializedSize() < mIcingDataUnionSizeThresholdBytes) {
            union.setRawData(data.toByteArray());
        } else {
            union.setSharedMemory(createSharedMemory(data));
        }
        return union;
    }

    /**
     * Creates a {@link SharedMemory} instance to pass serialized {@code data}.
     *
     * <p>Use {@link SharedMemory} to overcome the binder transaction limit.
     *
     * <p>A {@link ByteBuffer} is mapped to the {@link SharedMemory}, and unmapped after finishing
     * writing {@code data} to it. After client closes the {@link SharedMemory}, it gets cleaned up.
     */
    private static @NonNull SharedMemory createSharedMemory(@NonNull MessageLite data)
            throws ErrnoException {
        SharedMemory sharedMemory =
                SharedMemory.create("appsearch-apk-iss", data.getSerializedSize());
        ByteBuffer buffer = sharedMemory.mapReadWrite();
        try {
            buffer.order(ByteOrder.nativeOrder());
            buffer.put(data.toByteArray());
        } finally {
            SharedMemory.unmap(buffer);
        }
        sharedMemory.setProtect(OsConstants.PROT_READ);
        return sharedMemory;
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

    private static @NonNull <M extends MessageLite> M getResponseProtoFromIcingDataUnion(
            @Nullable IcingDataUnion resultUnion,
            @NonNull M defaultInstance,
            @NonNull Function<StatusProto, M> createResponseWithStatus) {

        M resultProto = defaultInstance;
        if (resultUnion == null) return resultProto;

        try {
            resultProto = readFromIcingDataUnion(resultProto, resultUnion);
        } catch (InvalidProtocolBufferException e) {
            return createResponseWithStatus.apply(protoParseFailureStatus(e));
        } catch (ErrnoException e) {
            return createResponseWithStatus.apply(sharedMemoryReadFailureStatus(e));
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

    private static @NonNull <M extends MessageLite> M readFromIcingDataUnion(
            @NonNull M defaultInstance, @NonNull IcingDataUnion union)
            throws ErrnoException, InvalidProtocolBufferException {
        if (union.getTag() == IcingDataUnion.sharedMemory) {
            return readFromSharedMemory(defaultInstance, union.getSharedMemory());
        } else {
            @SuppressWarnings("unchecked") // valid by protobuf contract
            Parser<M> parser = (Parser<M>) defaultInstance.getParserForType();
            return parser.parseFrom(union.getRawData());
        }
    }

    /**
     * Reads raw bytes from a {@link SharedMemory} instance and p throws ErrnoException,
     * InvalidProtocolBufferExceptionarse it as {@link M}.
     *
     * <p>Use {@link SharedMemory} to overcome the binder transaction limit.
     *
     * <p>Map a {@link ByteBuffer} to the {@link SharedMemory}, and unmap after finishing reading
     * from to it. Close the {@link SharedMemory} instance after reading from it.
     */
    private static @NonNull <M extends MessageLite> M readFromSharedMemory(
            @NonNull M defaultInstance, @NonNull SharedMemory sharedMemory)
            throws ErrnoException, InvalidProtocolBufferException {
        M data;
        try (sharedMemory) {
            ByteBuffer byteBuffer = sharedMemory.mapReadOnly();
            try {
                @SuppressWarnings("unchecked") // valid by protobuf contract
                Parser<M> parser = (Parser<M>) defaultInstance.getParserForType();
                data = parser.parseFrom(byteBuffer);
            } finally {
                SharedMemory.unmap(byteBuffer);
            }
        }
        return data;
    }

    private static @NonNull StatusProto sharedMemoryCreateFailureStatus(@NonNull Exception e) {
        Log.e(TAG, "Failed to create/write to SharedMemory", e);
        return StatusProto.newBuilder()
                .setCode(StatusProto.Code.INTERNAL)
                .setMessage("failed to create/write to SharedMemory: " + e.getMessage())
                .build();
    }

    private static @NonNull StatusProto sharedMemoryReadFailureStatus(@NonNull Exception e) {
        Log.e(TAG, "Failed to read from SharedMemory", e);
        return StatusProto.newBuilder()
                .setCode(StatusProto.Code.INTERNAL)
                .setMessage("failed to read from SharedMemory: " + e.getMessage())
                .build();
    }

    private static @NonNull StatusProto remoteExceptionStatus(@NonNull Exception e) {
        Log.e(TAG, "Failed to call isolated storage service via binder", e);
        return StatusProto.newBuilder()
                .setCode(StatusProto.Code.INTERNAL)
                .setMessage("failed to call isolated storage service via binder: " + e.getMessage())
                .build();
    }

    private static @NonNull StatusProto protoParseFailureStatus(@NonNull Exception e) {
        Log.e(TAG, "Failed to parse proto data", e);
        return StatusProto.newBuilder()
                .setCode(StatusProto.Code.INTERNAL)
                .setMessage("failed to parse proto data: " + e.getMessage())
                .build();
    }
}
