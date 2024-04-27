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
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResults;
import android.app.appsearch.exceptions.AppSearchException;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

public class SyncSearchResultsImpl extends SyncAppSearchBase implements SyncSearchResults {
    private final SearchResults mSearchResults;

    public SyncSearchResultsImpl(SearchResults searchResults, @NonNull Executor executor) {
        super(executor);
        mSearchResults = Objects.requireNonNull(searchResults);
    }

    @NonNull
    @Override
    public List<SearchResult> getNextPage() throws AppSearchException {
        return executeAppSearchResultOperation(
                resultHandler -> mSearchResults.getNextPage(mExecutor, resultHandler));
    }
}
