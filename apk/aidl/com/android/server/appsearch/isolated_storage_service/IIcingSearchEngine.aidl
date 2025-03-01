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

import com.android.server.appsearch.isolated_storage_service.IcingDataUnion;

/**
 * The AIDL interface for the Icing search engine within the isolated storage service.
 * These APIs generally map to the available APIs in the Icing search engine library at external/icing/icing/icing-search-engine.h.
 */
interface IIcingSearchEngine {
  @nullable
  /*InitializeResultProto*/ byte[] initialize(in byte[] icingSearchEngineOptionsProto);
  void close();
  @nullable
  /*SetSchemaResultProto*/ byte[]  setSchema(in IcingDataUnion schemaProto, in boolean ignoreErrorsAndDeleteDocuments);
  @nullable
  /*GetSchemaResultProto*/ IcingDataUnion getSchema();
  @nullable
  /*GetSchemaResultProto*/ IcingDataUnion getSchemaForDatabase(in String database);
  @nullable
  /*GetSchemaTypeResultProto*/ byte[] getSchemaType(in String schemaType);
  @nullable
  /*PutResultProto*/ byte[] put(in IcingDataUnion documentProto);
  @nullable
  /*GetResultProto*/ IcingDataUnion get(in String name_space, String uri, in byte[] getResultSpecProto);
  @nullable
  /*ReportUsageResultProto*/ byte[] reportUsage(in byte[] usageReportProto);
  @nullable
  /*GetAllNamespacesResultProto*/ byte[] getAllNamespaces();
  @nullable
  /*SearchResultProto*/ IcingDataUnion search(in byte[] searchSpecProto, in byte[] scoringSpecProto, in byte[] resultSpecProto);
  @nullable
  /*SearchResultProto*/ IcingDataUnion getNextPage(long nextPageToken);
  void invalidateNextPageToken(long nextPageToken);
  @nullable
  /*BlobProto*/ byte[] openWriteBlob(in byte[] blobHandleProto);
  @nullable
  /*BlobProto*/ byte[] removeBlob(in byte[] blobHandleProto);
  @nullable
  /*BlobProto*/ byte[] openReadBlob(in byte[] blobHandleProto);
  @nullable
  /*BlobProto*/ byte[] commitBlob(in byte[] blobHandleProto);
  @nullable
  /*DeleteResultProto*/ byte[] deleteByUri(in String name_space, in String uri);
  @nullable
  /*SuggestionResponse*/ byte[] searchSuggestions(in byte[] suggestionSpecProto);
  @nullable
  /*DeleteByNamespaceResultProto*/ byte[] deleteByNamespace(in String name_space);
  @nullable
  /*DeleteBySchemaTypeResultProto*/ byte[] deleteBySchemaType(in String schemaType);
  @nullable
  /*DeleteByQueryResultProto*/ IcingDataUnion deleteByQuery(in byte[] searchSpecProto, boolean returnDeletedDocumentInfo);
  @nullable
  /*PersistToDiskResultProto*/ byte[] persistToDisk(/*PersistType.Code*/ int persistTypeCode);
  @nullable
  /*OptimizeResultProto*/ IcingDataUnion optimize();
  @nullable
  /*GetOptimizeInfoResultProto*/ byte[] getOptimizeInfo();
  @nullable
  /*StorageInfoResultProto*/ IcingDataUnion getStorageInfo();
  @nullable
  /*DebugInfoResultProto*/ byte[] getDebugInfo(/*DebugInfoVerbosity.Code*/ int verbosity);
  @nullable
  /*ResetResultProto*/ byte[] reset();
}
