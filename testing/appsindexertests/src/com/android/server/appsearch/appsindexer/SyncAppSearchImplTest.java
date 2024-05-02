/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.appsearch.appsindexer;

import static android.app.appsearch.SearchSpec.TERM_MATCH_PREFIX;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.SetSchemaResponse;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Tests for {@link SyncAppSearchSessionImpl}. */
public class SyncAppSearchImplTest {
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final AppSearchManager mAppSearch = mContext.getSystemService(AppSearchManager.class);
    private final Executor mExecutor = mContext.getMainExecutor();

    @Before
    public void setUp() throws Exception {
        Objects.requireNonNull(mAppSearch);
        clean();
    }

    @After
    public void tearDown() throws Exception {
       clean();
    }

    private void clean() throws Exception {
        // Remove all documents from any instances that may have been created in the tests.
        AppSearchManager.SearchContext searchContext =
                new AppSearchManager.SearchContext.Builder("testDb").build();
        CompletableFuture<AppSearchResult<AppSearchSession>> future = new CompletableFuture<>();
        mAppSearch.createSearchSession(searchContext, mExecutor, future::complete);
        AppSearchSession searchSession = future.get().getResultValue();
        CompletableFuture<AppSearchResult<SetSchemaResponse>> schemaFuture =
                new CompletableFuture<>();
        searchSession.setSchema(
                new SetSchemaRequest.Builder().setForceOverride(true).build(), mExecutor, mExecutor,
                schemaFuture::complete);
        schemaFuture.get().getResultValue();
    }

    @Test
    public void testSynchronousMethods() throws Exception {
        AppSearchManager.SearchContext searchContext =
                new AppSearchManager.SearchContext.Builder("testDb").build();

        SyncAppSearchSession syncWrapper =
                new SyncAppSearchSessionImpl(mAppSearch, searchContext, mExecutor);

        // Set the schema.
        syncWrapper.setSchema(new SetSchemaRequest.Builder()
                .addSchemas(new AppSearchSchema.Builder("schema1").build())
                .setForceOverride(true).build());

        // Create a document and insert 3 package1 documents
        GenericDocument document1 = new GenericDocument.Builder<>("namespace", "id1",
                "schema1").build();
        GenericDocument document2 = new GenericDocument.Builder<>("namespace", "id2",
                "schema1").build();
        GenericDocument document3 = new GenericDocument.Builder<>("namespace", "id3",
                "schema1").build();

        PutDocumentsRequest request = new PutDocumentsRequest.Builder()
                .addGenericDocuments(document1, document2, document3).build();
        // Test put operation with no futures
        AppSearchBatchResult<String, Void> result = syncWrapper.put(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSuccesses()).hasSize(3);

        SyncGlobalSearchSession globalSession =
                new SyncGlobalSearchSessionImpl(mAppSearch, mExecutor);
        // Search globally for only 2 result per page
        SearchSpec searchSpec = new SearchSpec.Builder()
                .setTermMatch(TERM_MATCH_PREFIX)
                .addFilterPackageNames(mContext.getPackageName())
                .setResultCountPerPage(2)
                .build();
        SyncSearchResults searchResults = globalSession.search("", searchSpec);

        // Get the first page, it contains 2 results.
        List<GenericDocument> outDocs = new ArrayList<>();
        List<SearchResult> results = searchResults.getNextPage();
        assertThat(results).hasSize(2);
        outDocs.add(results.get(0).getGenericDocument());
        outDocs.add(results.get(1).getGenericDocument());

        // Get the second page, it contains only 1 result.
        results = searchResults.getNextPage();
        assertThat(results).hasSize(1);
        outDocs.add(results.get(0).getGenericDocument());

        assertThat(outDocs).containsExactly(document1, document2, document3);

        // We get all documents, and it shouldn't fail if we keep calling getNextPage().
        results = searchResults.getNextPage();
        assertThat(results).isEmpty();

        // Check that we can keep using the global session
        searchSpec =
                new SearchSpec.Builder()
                        .setTermMatch(TERM_MATCH_PREFIX)
                        .addFilterPackageNames(mContext.getPackageName())
                        .setResultCountPerPage(3)
                        .build();
        searchResults = globalSession.search("", searchSpec);
        results = searchResults.getNextPage();

        outDocs.clear();
        outDocs.add(results.get(0).getGenericDocument());
        outDocs.add(results.get(1).getGenericDocument());
        outDocs.add(results.get(2).getGenericDocument());
        assertThat(outDocs).containsExactly(document1, document2, document3);
    }

    @Test
    public void testClosedCallbackExecutor() {
        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
        callbackExecutor.shutdown();
        AppSearchManager.SearchContext searchContext =
                new AppSearchManager.SearchContext.Builder("testDb").build();
        assertThrows(RejectedExecutionException.class, () ->
                new SyncAppSearchSessionImpl(mAppSearch, searchContext, callbackExecutor));
    }
}