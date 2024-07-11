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
import android.annotation.WorkerThread;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.SetSchemaResponse;
import android.app.appsearch.exceptions.AppSearchException;

import com.android.internal.annotations.GuardedBy;

import java.util.Objects;
import java.util.concurrent.Executor;

public class SyncAppSearchSessionImpl extends SyncAppSearchBase implements SyncAppSearchSession {
    @GuardedBy("mSessionLock")
    private volatile AppSearchSession mSession;

    private final AppSearchManager.SearchContext mSearchContext;
    private final AppSearchManager mAppSearchManager;

    public SyncAppSearchSessionImpl(
            @NonNull AppSearchManager appSearchManager,
            @NonNull AppSearchManager.SearchContext searchContext,
            @NonNull Executor executor) {
        super(Objects.requireNonNull(executor));
        mAppSearchManager = Objects.requireNonNull(appSearchManager);
        mSearchContext = Objects.requireNonNull(searchContext);
    }

    /**
     * Initializes the {@link AppSearchSession}. Only one AppSearchSession will be created per
     * {@link SyncAppSearchSessionImpl}.
     *
     * @throws AppSearchException if unable to initialize the {@link AppSearchSession}.
     */
    @WorkerThread
    private void ensureSessionInitializedLocked() throws AppSearchException {
        synchronized (mSessionLock) {
            if (mSession != null) {
                return;
            }
            mSession =
                    executeAppSearchResultOperation(
                            resultHandler ->
                                    mAppSearchManager.createSearchSession(
                                            mSearchContext, mExecutor, resultHandler));
        }
    }

    /**
     * Searches with a query and {@link SearchSpec}. Initializes the {@link AppSearchSession} if it
     * hasn't been initialized already.
     */
    @Override
    @NonNull
    public SyncSearchResults search(@NonNull String query, @NonNull SearchSpec searchSpec)
            throws AppSearchException {
        Objects.requireNonNull(query);
        Objects.requireNonNull(searchSpec);
        ensureSessionInitializedLocked();
        return new SyncSearchResultsImpl(mSession.search(query, searchSpec), mExecutor);
    }

    /**
     * Sets schemas into AppSearch. Initializes the {@link AppSearchSession} if it hasn't been
     * initialized already.
     */
    @Override
    @NonNull
    @WorkerThread
    public SetSchemaResponse setSchema(@NonNull SetSchemaRequest setSchemaRequest)
            throws AppSearchException {
        Objects.requireNonNull(setSchemaRequest);
        ensureSessionInitializedLocked();
        return executeAppSearchResultOperation(
                resultHandler ->
                        mSession.setSchema(setSchemaRequest, mExecutor, mExecutor, resultHandler));
    }

    /**
     * Puts documents into AppSearch. Initializes the {@link AppSearchSession} if it hasn't been
     * initialized already.
     */
    @Override
    @NonNull
    @WorkerThread
    public AppSearchBatchResult<String, Void> put(@NonNull PutDocumentsRequest request)
            throws AppSearchException {
        Objects.requireNonNull(request);
        ensureSessionInitializedLocked();
        // Put involves an AppSearchBatchResult, so it can't be simplified through
        // executeAppSearchResultOperation. Instead we use executeAppSearchBatchResultOperation.
        return executeAppSearchBatchResultOperation(
                resultHandler -> mSession.put(request, mExecutor, resultHandler));
    }

    // Not asynchronous but it's necessary to be able to close the session
    @Override
    public void close() {
        synchronized (mSessionLock) {
            if (mSession != null) {
                mSession.close();
            }
        }
    }
}
