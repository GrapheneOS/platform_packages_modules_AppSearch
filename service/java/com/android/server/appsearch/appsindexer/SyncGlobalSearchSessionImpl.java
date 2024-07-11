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
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.GlobalSearchSession;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.exceptions.AppSearchException;

import com.android.internal.annotations.GuardedBy;

import java.util.Objects;
import java.util.concurrent.Executor;

public class SyncGlobalSearchSessionImpl extends SyncAppSearchBase
        implements SyncGlobalSearchSession {

    @GuardedBy("mSessionLock")
    private volatile GlobalSearchSession mGlobalSession;

    private final AppSearchManager mAppSearchManager;

    public SyncGlobalSearchSessionImpl(
            @NonNull AppSearchManager appSearchManager, @NonNull Executor executor) {
        super(Objects.requireNonNull(executor));
        mAppSearchManager = Objects.requireNonNull(appSearchManager);
    }

    /**
     * Sets up the {@link GlobalSearchSession}.
     *
     * @throws AppSearchException if unable to initialize the {@link GlobalSearchSession}.
     */
    @WorkerThread
    private void ensureSessionInitializedLocked() throws AppSearchException {
        synchronized (mSessionLock) {
            if (mGlobalSession != null) {
                return;
            }
            // It is best to initialize search sessions in a different thread from the thread that
            // calls onUserUnlock, which calls the constructor.
            mGlobalSession =
                    executeAppSearchResultOperation(
                            resultHandler ->
                                    mAppSearchManager.createGlobalSearchSession(
                                            mExecutor, resultHandler));
        }
    }

    /**
     * Searches with a query and {@link SearchSpec}. Initializes the {@link GlobalSearchSession} if
     * it hasn't been initialized already.
     */
    @Override
    @NonNull
    @WorkerThread
    public SyncSearchResults search(@NonNull String query, @NonNull SearchSpec searchSpec)
            throws AppSearchException {
        Objects.requireNonNull(query);
        Objects.requireNonNull(searchSpec);
        ensureSessionInitializedLocked();
        return new SyncSearchResultsImpl(mGlobalSession.search(query, searchSpec), mExecutor);
    }

    // Not an asynchronous call but it's necessary to be able to close the session
    @Override
    public void close() {
        synchronized (mSessionLock) {
            if (mGlobalSession != null) {
                mGlobalSession.close();
            }
        }
    }
}
