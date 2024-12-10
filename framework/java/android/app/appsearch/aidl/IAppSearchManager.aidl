/**
 * Copyright 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.app.appsearch.aidl;

import android.os.Bundle;
import android.os.UserHandle;

import android.app.appsearch.aidl.AppSearchAttributionSource;
import android.app.appsearch.aidl.AppSearchResultParcel;
import android.app.appsearch.aidl.AppSearchResultParcelV2;
import android.app.appsearch.aidl.IAppSearchBatchResultCallback;
import android.app.appsearch.aidl.IAppSearchObserverProxy;
import android.app.appsearch.aidl.IAppSearchResultCallback;
import android.app.appsearch.aidl.CommitBlobAidlRequest;
import android.app.appsearch.aidl.DocumentsParcel;
import android.app.appsearch.aidl.GetDocumentsAidlRequest;
import android.app.appsearch.aidl.GetNamespacesAidlRequest;
import android.app.appsearch.aidl.GetNextPageAidlRequest;
import android.app.appsearch.aidl.GetSchemaAidlRequest;
import android.app.appsearch.aidl.GetStorageInfoAidlRequest;
import android.app.appsearch.aidl.GlobalSearchAidlRequest;
import android.app.appsearch.aidl.InitializeAidlRequest;
import android.app.appsearch.aidl.InvalidateNextPageTokenAidlRequest;
import android.app.appsearch.aidl.OpenBlobForReadAidlRequest;
import android.app.appsearch.aidl.OpenBlobForWriteAidlRequest;
import android.app.appsearch.aidl.PersistToDiskAidlRequest;
import android.app.appsearch.aidl.PutDocumentsAidlRequest;
import android.app.appsearch.aidl.PutDocumentsFromFileAidlRequest;
import android.app.appsearch.aidl.RegisterObserverCallbackAidlRequest;
import android.app.appsearch.aidl.RemoveBlobAidlRequest;
import android.app.appsearch.aidl.RemoveByDocumentIdAidlRequest;
import android.app.appsearch.aidl.RemoveByQueryAidlRequest;
import android.app.appsearch.aidl.ReportUsageAidlRequest;
import android.app.appsearch.aidl.SearchAidlRequest;
import android.app.appsearch.aidl.SearchSuggestionAidlRequest;
import android.app.appsearch.aidl.SetBlobVisibilityAidlRequest;
import android.app.appsearch.aidl.SetSchemaAidlRequest;
import android.app.appsearch.aidl.UnregisterObserverCallbackAidlRequest;
import android.app.appsearch.aidl.WriteSearchResultsToFileAidlRequest;
import android.app.appsearch.stats.SchemaMigrationStats;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.InternalVisibilityConfig;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SearchSuggestionSpec;
import android.content.AttributionSource;
import android.os.ParcelFileDescriptor;

/** {@hide} */
// Always add new functions in the end with the commented transaction id.
interface IAppSearchManager {
    /**
     * Creates and initializes AppSearchImpl for the calling app.
     *
     * @param request {@link InitializeAidlRequest} contains the input parameters for initialize
     *     operation.
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link Void}&gt;.
     */
    void initialize(
        in InitializeAidlRequest request,
        in IAppSearchResultCallback callback) = 0;

    /**
     * Updates the AppSearch schema for this database.
     *
     * @param request {@link SetSchemaAidlRequest} contains the input parameters for set schema
     *     operation.
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link InternalSetSchemaResponse}&gt;.
     */
    void setSchema(
        in SetSchemaAidlRequest request,
        in IAppSearchResultCallback callback) = 1;

    /**
     * Retrieves the AppSearch schema for this database.
     *
     * @param request {@link GetSchemaAidlRequest} contains the input parameters for get schema
     *     operation.
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link Bundle}&gt; where the bundle is a GetSchemaResponse.
     */
    void getSchema(
        in GetSchemaAidlRequest request,
        in IAppSearchResultCallback callback) = 2;

    /**
     * Retrieves the set of all namespaces in the current database with at least one document.
     *
     * @param request {@link GetNamespacesAidlRequest} contains the input parameters for get
     *     namespaces operation.
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link List}&lt;{@link String}&gt;&gt;.
     */
    void getNamespaces(
        in GetNamespacesAidlRequest request,
        in IAppSearchResultCallback callback) = 3;

    /**
     * Inserts documents into the index.
     *
     * @param request {@link PutDocumentsAidlRequest} contains the input parameters for
     *     put documents operation.
     * @param callback
     *     If the call fails to start, {@link IAppSearchBatchResultCallback#onSystemError}
     *     will be called with the cause throwable. Otherwise,
     *     {@link IAppSearchBatchResultCallback#onResult} will be called with an
     *     {@link AppSearchBatchResult}&lt;{@link String}, {@link Void}&gt;
     *     where the keys are document IDs, and the values are {@code null}.
     */
    void putDocuments(
        in PutDocumentsAidlRequest request,
        in IAppSearchBatchResultCallback callback) = 4;

    /**
     * Retrieves documents from the index.
     *
     * @param request {@link GetDocumentsAidlRequest} that contains the input parameters for the
     *     get documents operation.
     * @param callback
     *     If the call fails to start, {@link IAppSearchBatchResultCallback#onSystemError}
     *     will be called with the cause throwable. Otherwise,
     *     {@link IAppSearchBatchResultCallback#onResult} will be called with an
     *     {@link AppSearchBatchResult}&lt;{@link String}, {@link Bundle}&gt;
     *     where the keys are document IDs, and the values are Document bundles.
     */
    void getDocuments(
        in GetDocumentsAidlRequest request,
        in IAppSearchBatchResultCallback callback) = 5;

    /**
     * Searches a document based on a given specifications.
     *
     * @param request {@link QueryAidlRequest} that contains the input parameters for the search
     *     operation
     * @param callback {@link AppSearchResult}&lt;{@link SearchResultPage}&gt; of performing this
     *     operation.
     */
    void search(
        in SearchAidlRequest request,
        in IAppSearchResultCallback callback) = 6;

    /**
     * Executes a global search, i.e. over all permitted databases, against the AppSearch index and
     * returns results.
     *
     * @param request {@link GlobalSearchAidlRequest} that contains the input parameters for the
     *     global search operation.
     * @param callback {@link AppSearchResult}&lt;{@link SearchResultPage}&gt; of performing this
     *     operation.
     */
    void globalSearch(
        in GlobalSearchAidlRequest request,
        in IAppSearchResultCallback callback) = 7;

    /**
     * Fetches the next page of results of a previously executed search. Results can be empty if
     * next-page token is invalid or all pages have been returned.
     *
     * @param request {@link GetNextPageAidlRequest} that contains the input parameters for the
     *     get next page operation.
     * @param callback {@link AppSearchResult}&lt;{@link SearchResultPage}&gt; of performing this
     *                  operation.
     */
    void getNextPage(
        in GetNextPageAidlRequest request,
        in IAppSearchResultCallback callback) = 8;

    /**
     * Invalidates the next-page token so that no more results of the related search can be
     * returned.
     *
     * @param request {@link InvalidateNextPageTokenAidlRequest} that contains the input parameters
     *     for the invalidate next-page token operation.
     */
    void invalidateNextPageToken(in InvalidateNextPageTokenAidlRequest request) = 9;

    /**
     * Searches a document based on a given specifications.
     *
     * <p>Documents will be save to the given ParcelFileDescriptor
     *
     * @param request {@link WriteSearchResultsToFileAidlRequest} that contains the input parameters
     *     to write search results to a file.
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@code Void}&gt;.
     */
    void writeSearchResultsToFile(
        in WriteSearchResultsToFileAidlRequest request,
        in IAppSearchResultCallback callback) = 10;

    /**
     * Inserts documents from the given file into the index.
     *
     * <p>This method does not dispatch change notifications for the individual documents being
     * inserted, so it is only appropriate to use for batch upload situations where a broader change
     * notification will indicate what has changed, like schema migration.
     *
     * @param request {@link PutDocumentsFromFileAidlRequest} that contains the input parameters to
     *     put documents from a file.
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link List}&lt;{@link Bundle}&gt;&gt;, where the value are
     *     MigrationFailure bundles.
     */
    void putDocumentsFromFile(
        in PutDocumentsFromFileAidlRequest request,
        in IAppSearchResultCallback callback) = 11;

    /**
     * Retrieves suggested Strings that could be used as {@code queryExpression} in search API.
     *
     * @param request {@link SearchSuggestionAidlRequest} contains the input parameters to retrieve
     *     suggested Strings for search.
     * @param callback {@link AppSearchResult}&lt;List&lt;{@link SearchSuggestionResult}&gt; of
     *   performing this operation.
     */
    void searchSuggestion(
            in SearchSuggestionAidlRequest request,
            in IAppSearchResultCallback callback) = 12;

    /**
     * Reports usage of a particular document by namespace and id.
     *
     * <p>A usage report represents an event in which a user interacted with or viewed a document.
     *
     * <p>For each call to {@link #reportUsage}, AppSearch updates usage count and usage recency
     * metrics for that particular document. These metrics are used for ordering {@link #query}
     * results by the {@link SearchSpec#RANKING_STRATEGY_USAGE_COUNT} and
     * {@link SearchSpec#RANKING_STRATEGY_USAGE_LAST_USED_TIMESTAMP} ranking strategies.
     *
     * <p>Reporting usage of a document is optional.
     *
     * @param request {@link ReportUsageAidlRequest} contains the input parameters for report
     *     usage operation.
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link Void}&gt;.
     */
    void reportUsage(
        in ReportUsageAidlRequest request,
        in IAppSearchResultCallback callback) = 13;

    /**
     * Removes documents by ID.
     *
     * @param request {@link RemoveByDocumentIdAidlRequest} contains the input parameters for remove
     *     by id operation.
     * @param callback
     *     If the call fails to start, {@link IAppSearchBatchResultCallback#onSystemError}
     *     will be called with the cause throwable. Otherwise,
     *     {@link IAppSearchBatchResultCallback#onResult} will be called with an
     *     {@link AppSearchBatchResult}&lt;{@link String}, {@link Void}&gt;
     *     where the keys are document IDs. If a document doesn't exist, it will be reported as a
     *     failure where the {@code throwable} is {@code null}.
     */
    void removeByDocumentId(
        in RemoveByDocumentIdAidlRequest request,
        in IAppSearchBatchResultCallback callback) = 14;

    /**
     * Removes documents by given query.
     *
     * @param request {@link RemoveByQueryAidlRequest} contains the input parameters for remove by
     *     query operation.
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link Void}&gt;.
     */
    void removeByQuery(
        in RemoveByQueryAidlRequest request,
        in IAppSearchResultCallback callback) = 15;

    /**
     * Gets the storage info.
     *
     * @param request {@link GetStorageInfoAidlRequest} contains the input parameters to get
     *     storage info operation.
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link Bundle}&gt;, where the value is a
     *     {@link StorageInfo}.
     */
    void getStorageInfo(
        in GetStorageInfoAidlRequest request,
        in IAppSearchResultCallback callback) = 16;

    /**
     * Persists all update/delete requests to the disk.
     *
     * @param request {@link PersistToDiskAidlRequest} contains the input parameters for set schema
     *     operation.
     */
    void persistToDisk(in PersistToDiskAidlRequest request) = 17;

    /**
     * Adds an observer to monitor changes within the databases owned by {@code observedPackage} if
     * they match the given ObserverSpec.
     *
     * @param request {@link RegisterObserverCallbackAidlRequest} contains the input parameters
     *     for register observer operation.
     * @param observerProxy Callback to trigger when a schema or document changes
     * @return the success or failure of this operation
     */
    AppSearchResultParcel registerObserverCallback(
        in RegisterObserverCallbackAidlRequest request,
        in IAppSearchObserverProxy observerProxy) = 18;

    /**
     * Removes previously registered {@link ObserverCallback} instances from the system.
     *
     * @param request {@link UnregisterObserverCallbackAidlRequest} contains the input parameters
     *     for unregister observer operation.
     * @param observerProxy Observer callback to remove
     * @return the success or failure of this operation
     */
    AppSearchResultParcel unregisterObserverCallback(
        in UnregisterObserverCallbackAidlRequest request,
        in IAppSearchObserverProxy observerProxy) = 19;

    // reserved function id = 20.

    /**
     * Opens a batch of AppSearch Blobs for writing.
     *
     * @param request the request to open blob for writing
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     OpenBlobForWriteResponse
     */
    void openBlobForWrite(
       in OpenBlobForWriteAidlRequest request,
       in IAppSearchResultCallback callback) = 21;

    /**
     * Commits the blobs to make it retrievable and immutable.
     *
     * @param request the request to commit blobs
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     CommitBlobResponse
     */
    void commitBlob(
       in CommitBlobAidlRequest request,
       in IAppSearchResultCallback callback) = 22;

    /**
     * Opens a batch of AppSearch Blobs for reading.
     *
     * @param request the request to open blob for reading
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *    OpenBlobForReadResponse
     */
   void openBlobForRead(
       in OpenBlobForReadAidlRequest request,
       in IAppSearchResultCallback callback) = 23;

   /**
     * Removes a batch of blobs from AppSearch
     *
     * @param request the request to remove blobs
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     RemoveBlobResponse
     */
   void removeBlob(
       in RemoveBlobAidlRequest request,
       in IAppSearchResultCallback callback) = 24;

   /**
     * Set blob visibility for a specific database to AppSearch
     *
     * @param request the request to set blob visibility settings.
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link Void}&gt;.
     */
   void setBlobVisibility(
       in SetBlobVisibilityAidlRequest request,
       in IAppSearchResultCallback callback) = 25;

    // next function transaction ID = 26;
}
