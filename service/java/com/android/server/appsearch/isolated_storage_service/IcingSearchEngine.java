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
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.google.android.icing.IcingSearchEngineInterface;
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
import com.google.android.icing.proto.PutResultProto;
import com.google.android.icing.proto.ReportUsageResultProto;
import com.google.android.icing.proto.ResetResultProto;
import com.google.android.icing.proto.ResultSpecProto;
import com.google.android.icing.proto.SchemaProto;
import com.google.android.icing.proto.ScoringSpecProto;
import com.google.android.icing.proto.SearchResultProto;
import com.google.android.icing.proto.SearchSpecProto;
import com.google.android.icing.proto.SetSchemaResultProto;
import com.google.android.icing.proto.StatusProto;
import com.google.android.icing.proto.StorageInfoResultProto;
import com.google.android.icing.proto.SuggestionResponse;
import com.google.android.icing.proto.SuggestionSpecProto;
import com.google.android.icing.proto.UsageReport;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Icing engine backed by the isolated storage service. */
public final class IcingSearchEngine implements IcingSearchEngineInterface {
    private static final String TAG = "IcingSearchEngine";
    private static final long LATCH_TIMEOUT_SECONDS = 60;

    private final IIcingSearchEngine mEngine;
    private final IcingSearchEngineOptions mOptions;

    /** Enforces singleton class pattern. */
    public IcingSearchEngine(
            @NonNull IIcingSearchEngine engine, @NonNull IcingSearchEngineOptions options) {
        Log.d(TAG, "constructing");
        mEngine = Objects.requireNonNull(engine);
        mOptions = Objects.requireNonNull(options);
    }

    @Override
    public void close() {
        Log.d(TAG, "closing");
    }

    @NonNull
    @Override
    public InitializeResultProto initialize() {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.initialize(
                    createPfd(mOptions),
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (IOException e) {
            return InitializeResultProto.newBuilder().setStatus(pfdCreateFailureStatus(e)).build();
        } catch (RemoteException e) {
            return InitializeResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                InitializeResultProto.getDefaultInstance(),
                status -> InitializeResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public SetSchemaResultProto setSchema(@NonNull SchemaProto schema) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.setSchema(
                    createPfd(schema),
                    /* ignoreErrorsAndDeleteDocuments= */ false,
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (IOException e) {
            return SetSchemaResultProto.newBuilder().setStatus(pfdCreateFailureStatus(e)).build();
        } catch (RemoteException e) {
            return SetSchemaResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                SetSchemaResultProto.getDefaultInstance(),
                status -> SetSchemaResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public SetSchemaResultProto setSchema(
            @NonNull SchemaProto schema, boolean ignoreErrorsAndDeleteDocuments) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.setSchema(
                    createPfd(schema),
                    ignoreErrorsAndDeleteDocuments,
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (IOException e) {
            return SetSchemaResultProto.newBuilder().setStatus(pfdCreateFailureStatus(e)).build();
        } catch (RemoteException e) {
            return SetSchemaResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                SetSchemaResultProto.getDefaultInstance(),
                status -> SetSchemaResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public GetSchemaResultProto getSchema() {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.getSchema(
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            return GetSchemaResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                GetSchemaResultProto.getDefaultInstance(),
                status -> GetSchemaResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public GetSchemaResultProto getSchemaForDatabase(@NonNull String database) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.getSchemaForDatabase(
                    database,
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            return GetSchemaResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                GetSchemaResultProto.getDefaultInstance(),
                status -> GetSchemaResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public GetSchemaTypeResultProto getSchemaType(@NonNull String schemaType) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.getSchemaType(
                    schemaType,
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            return GetSchemaTypeResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                GetSchemaTypeResultProto.getDefaultInstance(),
                status -> GetSchemaTypeResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public PutResultProto put(@NonNull DocumentProto document) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.put(
                    createPfd(document),
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (IOException e) {
            return PutResultProto.newBuilder().setStatus(pfdCreateFailureStatus(e)).build();
        } catch (RemoteException e) {
            return PutResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                PutResultProto.getDefaultInstance(),
                status -> PutResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public GetResultProto get(
            @NonNull String namespace,
            @NonNull String uri,
            @NonNull GetResultSpecProto getResultSpec) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.get(
                    namespace,
                    uri,
                    createPfd(getResultSpec),
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (IOException e) {
            return GetResultProto.newBuilder().setStatus(pfdCreateFailureStatus(e)).build();
        } catch (RemoteException e) {
            return GetResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                GetResultProto.getDefaultInstance(),
                status -> GetResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public ReportUsageResultProto reportUsage(@NonNull UsageReport usageReport) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.reportUsage(
                    createPfd(usageReport),
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (IOException e) {
            return ReportUsageResultProto.newBuilder().setStatus(pfdCreateFailureStatus(e)).build();
        } catch (RemoteException e) {
            return ReportUsageResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                ReportUsageResultProto.getDefaultInstance(),
                status -> ReportUsageResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public GetAllNamespacesResultProto getAllNamespaces() {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.getAllNamespaces(
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            return GetAllNamespacesResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                GetAllNamespacesResultProto.getDefaultInstance(),
                status -> GetAllNamespacesResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public SearchResultProto search(
            @NonNull SearchSpecProto searchSpec,
            @NonNull ScoringSpecProto scoringSpec,
            @NonNull ResultSpecProto resultSpec) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.search(
                    createPfd(searchSpec),
                    createPfd(scoringSpec),
                    createPfd(resultSpec),
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (IOException e) {
            return SearchResultProto.newBuilder().setStatus(pfdCreateFailureStatus(e)).build();
        } catch (RemoteException e) {
            return SearchResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                SearchResultProto.getDefaultInstance(),
                status -> SearchResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public SearchResultProto getNextPage(long nextPageToken) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.getNextPage(
                    nextPageToken,
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            return SearchResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
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
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.openWriteBlob(
                    createPfd(blobHandle),
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (IOException e) {
            return BlobProto.newBuilder().setStatus(pfdCreateFailureStatus(e)).build();
        } catch (RemoteException e) {
            return BlobProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                BlobProto.getDefaultInstance(),
                status -> BlobProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public BlobProto removeBlob(@NonNull PropertyProto.BlobHandleProto blobHandle) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.removeBlob(
                    createPfd(blobHandle),
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (IOException e) {
            return BlobProto.newBuilder().setStatus(pfdCreateFailureStatus(e)).build();
        } catch (RemoteException e) {
            return BlobProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                BlobProto.getDefaultInstance(),
                status -> BlobProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public BlobProto openReadBlob(@NonNull PropertyProto.BlobHandleProto blobHandle) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.openReadBlob(
                    createPfd(blobHandle),
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (IOException e) {
            return BlobProto.newBuilder().setStatus(pfdCreateFailureStatus(e)).build();
        } catch (RemoteException e) {
            return BlobProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                BlobProto.getDefaultInstance(),
                status -> BlobProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public BlobProto commitBlob(@NonNull PropertyProto.BlobHandleProto blobHandle) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.commitBlob(
                    createPfd(blobHandle),
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (IOException e) {
            return BlobProto.newBuilder().setStatus(pfdCreateFailureStatus(e)).build();
        } catch (RemoteException e) {
            return BlobProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                BlobProto.getDefaultInstance(),
                status -> BlobProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public DeleteResultProto delete(@NonNull String namespace, @NonNull String uri) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.deleteByUri(
                    namespace,
                    uri,
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            return DeleteResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                DeleteResultProto.getDefaultInstance(),
                status -> DeleteResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public SuggestionResponse searchSuggestions(@NonNull SuggestionSpecProto suggestionSpec) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.searchSuggestions(
                    createPfd(suggestionSpec),
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (IOException e) {
            return SuggestionResponse.newBuilder().setStatus(pfdCreateFailureStatus(e)).build();
        } catch (RemoteException e) {
            return SuggestionResponse.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                SuggestionResponse.getDefaultInstance(),
                status -> SuggestionResponse.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public DeleteByNamespaceResultProto deleteByNamespace(@NonNull String namespace) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.deleteByNamespace(
                    namespace,
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            return DeleteByNamespaceResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                DeleteByNamespaceResultProto.getDefaultInstance(),
                status -> DeleteByNamespaceResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public DeleteBySchemaTypeResultProto deleteBySchemaType(@NonNull String schemaType) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.deleteBySchemaType(
                    schemaType,
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            return DeleteBySchemaTypeResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                DeleteBySchemaTypeResultProto.getDefaultInstance(),
                status -> DeleteBySchemaTypeResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public DeleteByQueryResultProto deleteByQuery(@NonNull SearchSpecProto searchSpec) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.deleteByQuery(
                    createPfd(searchSpec),
                    /* returnDeletedDocumentInfo= */ false,
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (IOException e) {
            return DeleteByQueryResultProto.newBuilder()
                    .setStatus(pfdCreateFailureStatus(e))
                    .build();
        } catch (RemoteException e) {
            return DeleteByQueryResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                DeleteByQueryResultProto.getDefaultInstance(),
                status -> DeleteByQueryResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public DeleteByQueryResultProto deleteByQuery(
            @NonNull SearchSpecProto searchSpec, boolean returnDeletedDocumentInfo) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.deleteByQuery(
                    createPfd(searchSpec),
                    returnDeletedDocumentInfo,
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (IOException e) {
            return DeleteByQueryResultProto.newBuilder()
                    .setStatus(pfdCreateFailureStatus(e))
                    .build();
        } catch (RemoteException e) {
            return DeleteByQueryResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                DeleteByQueryResultProto.getDefaultInstance(),
                status -> DeleteByQueryResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public PersistToDiskResultProto persistToDisk(@NonNull PersistType.Code persistTypeCode) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.persistToDisk(
                    persistTypeCode.getNumber(),
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            return PersistToDiskResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                PersistToDiskResultProto.getDefaultInstance(),
                status -> PersistToDiskResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public OptimizeResultProto optimize() {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.optimize(
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            return OptimizeResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                OptimizeResultProto.getDefaultInstance(),
                status -> OptimizeResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public GetOptimizeInfoResultProto getOptimizeInfo() {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.getOptimizeInfo(
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            return GetOptimizeInfoResultProto.newBuilder()
                    .setStatus(remoteExceptionStatus(e))
                    .build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                GetOptimizeInfoResultProto.getDefaultInstance(),
                status -> GetOptimizeInfoResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public StorageInfoResultProto getStorageInfo() {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.getStorageInfo(
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            return StorageInfoResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                StorageInfoResultProto.getDefaultInstance(),
                status -> StorageInfoResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public DebugInfoResultProto getDebugInfo(@NonNull DebugInfoVerbosity.Code verbosity) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.getDebugInfo(
                    verbosity.getNumber(),
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            return DebugInfoResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                DebugInfoResultProto.getDefaultInstance(),
                status -> DebugInfoResultProto.newBuilder().setStatus(status).build());
    }

    @NonNull
    @Override
    public ResetResultProto reset() {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> resultPfd = new AtomicReference<>();
        try {
            mEngine.reset(
                    new IIcingSearchResultCallback.Stub() {
                        @Override
                        public void onResult(IcingSearchResult result) {
                            resultPfd.set(result.data);
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            return ResetResultProto.newBuilder().setStatus(remoteExceptionStatus(e)).build();
        }

        return getResponseProto(
                latch,
                resultPfd,
                ResetResultProto.getDefaultInstance(),
                status -> ResetResultProto.newBuilder().setStatus(status).build());
    }

    private static @NonNull ParcelFileDescriptor createPfd(@NonNull MessageLite data)
            throws IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createSocketPair();
        try (ParcelFileDescriptor.AutoCloseOutputStream outputStream =
                new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
            data.writeTo(outputStream);
        }
        return pipe[0];
    }

    private static @NonNull <M extends MessageLite> M getResponseProto(
            @NonNull CountDownLatch latch,
            @NonNull AtomicReference<ParcelFileDescriptor> resultPfd,
            @NonNull M defaultInstance,
            @NonNull Function<StatusProto, M> createResponseWithStatus) {
        boolean latchCountReachedZero;
        try {
            latchCountReachedZero = latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return createResponseWithStatus.apply(latchWaitFailureStatus(e));
        }

        if (!latchCountReachedZero) {
            // timeout happened
            return createResponseWithStatus.apply(latchWaitTimedOutStatus());
        }

        M resultProto = defaultInstance;
        if (resultPfd.get() == null) return resultProto;

        try {
            resultProto = readFromPfd(resultProto, resultPfd.get());
        } catch (InvalidProtocolBufferException e) {
            return createResponseWithStatus.apply(protoParseFailureStatus(e));
        } catch (IOException e) {
            return createResponseWithStatus.apply(pfdReadFailureStatus(e));
        }

        return resultProto;
    }

    private static @NonNull <M extends MessageLite> M readFromPfd(
            @NonNull M defaultInstance, @NonNull ParcelFileDescriptor pfd) throws IOException {
        M data;
        try (ParcelFileDescriptor.AutoCloseInputStream inputStream =
                new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            @SuppressWarnings("unchecked") // valid by protobuf contract
            Parser<M> parser = (Parser<M>) defaultInstance.getParserForType();
            data = parser.parseFrom(inputStream);
        }
        return data;
    }

    private static @NonNull StatusProto pfdCreateFailureStatus(@NonNull Exception e) {
        return StatusProto.newBuilder()
                .setCode(StatusProto.Code.INTERNAL)
                .setMessage("failed to create/write to pfd: " + e.getMessage())
                .build();
    }

    private static @NonNull StatusProto pfdReadFailureStatus(@NonNull Exception e) {
        return StatusProto.newBuilder()
                .setCode(StatusProto.Code.INTERNAL)
                .setMessage("failed to read from pfd: " + e.getMessage())
                .build();
    }

    private static @NonNull StatusProto remoteExceptionStatus(@NonNull Exception e) {
        return StatusProto.newBuilder()
                .setCode(StatusProto.Code.INTERNAL)
                .setMessage("failed to call isolated storage service via binder: " + e.getMessage())
                .build();
    }

    private static @NonNull StatusProto latchWaitFailureStatus(@NonNull Exception e) {
        return StatusProto.newBuilder()
                .setCode(StatusProto.Code.INTERNAL)
                .setMessage(
                        "failed to wait for latch due to InterruptedException: " + e.getMessage())
                .build();
    }

    private static @NonNull StatusProto latchWaitTimedOutStatus() {
        return StatusProto.newBuilder()
                .setCode(StatusProto.Code.INTERNAL)
                .setMessage(
                        "timed out after waiting for latch for "
                                + LATCH_TIMEOUT_SECONDS
                                + " seconds")
                .build();
    }

    private static @NonNull StatusProto protoParseFailureStatus(@NonNull Exception e) {
        return StatusProto.newBuilder()
                .setCode(StatusProto.Code.INTERNAL)
                .setMessage("failed to parse proto data: " + e.getMessage())
                .build();
    }
}
