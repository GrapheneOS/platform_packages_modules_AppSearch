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
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.GlobalSearchSession;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.exceptions.AppSearchException;

import java.util.Objects;
import java.util.concurrent.Executor;

public class SyncGlobalSearchSessionImpl extends SyncAppSearchBase
        implements SyncGlobalSearchSession {

    private final GlobalSearchSession mGlobalSession;

    public SyncGlobalSearchSessionImpl(
            @NonNull AppSearchManager appSearchManager, @NonNull Executor executor)
            throws AppSearchException {
        super(executor);
        Objects.requireNonNull(appSearchManager);
        Objects.requireNonNull(executor);

        mGlobalSession =
                executeAppSearchResultOperation(
                        resultHandler ->
                                appSearchManager.createGlobalSearchSession(
                                        executor, resultHandler));
    }

    // Not actually asynchronous but added for convenience
    @Override
    @NonNull
    public SyncSearchResults search(@NonNull String query, @NonNull SearchSpec searchSpec) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(searchSpec);
        return new SyncSearchResultsImpl(mGlobalSession.search(query, searchSpec), mExecutor);
    }

    // Also not asynchronous but it's necessary to be able to close the session
    @Override
    public void close() {
        mGlobalSession.close();
    }
}
