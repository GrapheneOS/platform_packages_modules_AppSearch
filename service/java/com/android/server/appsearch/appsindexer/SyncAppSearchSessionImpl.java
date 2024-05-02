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

import android.annotation.NonNull;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.SetSchemaResponse;
import android.app.appsearch.exceptions.AppSearchException;

import java.util.Objects;
import java.util.concurrent.Executor;

/** SyncAppSearchSessionImpl methods are a super set of SyncGlobalSearchSessionImpl methods. */
public class SyncAppSearchSessionImpl extends SyncAppSearchBase implements SyncAppSearchSession {
    private final AppSearchSession mSession;

    public SyncAppSearchSessionImpl(
            @NonNull AppSearchManager appSearchManager,
            @NonNull AppSearchManager.SearchContext searchContext,
            @NonNull Executor executor)
            throws AppSearchException {
        super(executor);
        Objects.requireNonNull(appSearchManager);
        Objects.requireNonNull(searchContext);
        Objects.requireNonNull(executor);
        mSession =
                executeAppSearchResultOperation(
                        resultHandler ->
                                appSearchManager.createSearchSession(
                                        searchContext, executor, resultHandler));
    }

    // Not actually asynchronous but added for convenience
    @Override
    @NonNull
    public SyncSearchResults search(@NonNull String query, @NonNull SearchSpec searchSpec) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(searchSpec);
        return new SyncSearchResultsImpl(mSession.search(query, searchSpec), mExecutor);
    }

    @Override
    @NonNull
    public SetSchemaResponse setSchema(@NonNull SetSchemaRequest setSchemaRequest)
            throws AppSearchException {
        Objects.requireNonNull(setSchemaRequest);
        return executeAppSearchResultOperation(
                resultHandler ->
                        mSession.setSchema(setSchemaRequest, mExecutor, mExecutor, resultHandler));
    }

    // Put involves an AppSearchBatchResult, so it can't be simplified through
    // executeAppSearchResultOperation. Instead we use executeAppSearchBatchResultOperation.
    @Override
    @NonNull
    public AppSearchBatchResult<String, Void> put(@NonNull PutDocumentsRequest request)
            throws AppSearchException {
        Objects.requireNonNull(request);
        return executeAppSearchBatchResultOperation(
                resultHandler -> mSession.put(request, mExecutor, resultHandler));
    }

    // Also not asynchronous but it's necessary to be able to close the session
    @Override
    public void close() {
        mSession.close();
    }
}
