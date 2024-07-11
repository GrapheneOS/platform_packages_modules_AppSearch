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
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.SetSchemaResponse;
import android.app.appsearch.exceptions.AppSearchException;

import java.io.Closeable;

/**
 * A synchronous wrapper around {@link AppSearchSession}. This allows us to perform operations in
 * AppSearch without needing to handle async calls.
 *
 * <p>Note that calling the methods in this class will park the calling thread.
 *
 * @see AppSearchSession
 */
public interface SyncAppSearchSession extends Closeable {
    /**
     * Synchronously sets an {@link AppSearchSchema}.
     *
     * @see AppSearchSession#setSchema
     */
    @NonNull
    @WorkerThread
    SetSchemaResponse setSchema(@NonNull SetSchemaRequest setSchemaRequest)
            throws AppSearchException;

    /**
     * Synchronously inserts documents into AppSearch.
     *
     * @see AppSearchSession#put
     */
    @NonNull
    @WorkerThread
    AppSearchBatchResult<String, Void> put(@NonNull PutDocumentsRequest request)
            throws AppSearchException;

    /**
     * Returns a synchronous version of {@link SearchResults}.
     *
     * <p>While the underlying method is not asynchronous, this method allows for convenience while
     * synchronously searching AppSearch.
     *
     * @see AppSearchSession#search
     */
    @NonNull
    @WorkerThread
    SyncSearchResults search(@NonNull String query, @NonNull SearchSpec searchSpec)
            throws AppSearchException;

    /**
     * Closes the session.
     *
     * @see AppSearchSession#close
     */
    @Override
    void close();

    // TODO(b/275592563): Bring in additional methods such as getByDocumentId as needed
}
