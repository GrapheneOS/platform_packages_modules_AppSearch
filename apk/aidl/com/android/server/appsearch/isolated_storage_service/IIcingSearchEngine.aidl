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

import android.os.ParcelFileDescriptor;
import com.android.server.appsearch.isolated_storage_service.IIcingSearchResultCallback;

/**
 * The AIDL interface for the Icing search engine within the isolated storage service.
 * These APIs generally map to the available APIs in the Icing search engine library at external/icing/icing/icing-search-engine.h.
 */
oneway interface IIcingSearchEngine {
  void initialize(in ParcelFileDescriptor icingSearchEngineOptionsProto, in IIcingSearchResultCallback callback);
  void close();
  void setSchema(in ParcelFileDescriptor schemaProto, in boolean ignoreErrorsAndDeleteDocuments, in IIcingSearchResultCallback callback);
  void getSchema(in IIcingSearchResultCallback callback);
  void getSchemaForDatabase(in String database, in IIcingSearchResultCallback callback);
  void getSchemaType(in String schemaType, in IIcingSearchResultCallback callback);
  void put(in ParcelFileDescriptor documentProto, in IIcingSearchResultCallback callback);
  void get(in String name_space, String uri, in ParcelFileDescriptor getResultSpecProto, in IIcingSearchResultCallback callback);
  void reportUsage(in ParcelFileDescriptor usageReportProto, in IIcingSearchResultCallback callback);
  void getAllNamespaces(in IIcingSearchResultCallback callback);
  void search(in ParcelFileDescriptor searchSpecProto, in ParcelFileDescriptor scoringSpecProto, in ParcelFileDescriptor resultSpecProto, in IIcingSearchResultCallback callback);
  void getNextPage(long nextPageToken, in IIcingSearchResultCallback callback);
  void invalidateNextPageToken(long nextPageToken);
  void openWriteBlob(in ParcelFileDescriptor blobHandleProto, in IIcingSearchResultCallback callback);
  void removeBlob(in ParcelFileDescriptor blobHandleProto, in IIcingSearchResultCallback callback);
  void openReadBlob(in ParcelFileDescriptor blobHandleProto, in IIcingSearchResultCallback callback);
  void commitBlob(in ParcelFileDescriptor blobHandleProto, in IIcingSearchResultCallback callback);
  void deleteByUri(in String name_space, in String uri, in IIcingSearchResultCallback callback);
  void searchSuggestions(in ParcelFileDescriptor suggestionSpecProto, in IIcingSearchResultCallback callback);
  void deleteByNamespace(in String name_space, in IIcingSearchResultCallback callback);
  void deleteBySchemaType(in String schemaType, in IIcingSearchResultCallback callback);
  void deleteByQuery(in ParcelFileDescriptor searchSpecProto, boolean returnDeletedDocumentInfo, in IIcingSearchResultCallback callback);
  void persistToDisk(/*PersistType.Code*/ int persistTypeCode, in IIcingSearchResultCallback callback);
  void optimize(in IIcingSearchResultCallback callback);
  void getOptimizeInfo(in IIcingSearchResultCallback callback);
  void getStorageInfo(in IIcingSearchResultCallback callback);
  void getDebugInfo(/*DebugInfoVerbosity.Code*/ int verbosity, in IIcingSearchResultCallback callback);
  void reset(in IIcingSearchResultCallback callback);
}
