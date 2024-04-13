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
import android.app.appsearch.GlobalSearchSession;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.exceptions.AppSearchException;

/**
 * A synchronous wrapper around {@link GlobalSearchSession}. This allows us to call globalSearch
 * synchronously.
 *
 * @see GlobalSearchSession
 */
public interface SyncGlobalSearchSession {
    /**
     * Returns a synchronous version of {@link SearchResults}.
     *
     * <p> While the underlying method is not asynchronous, this method allows for convenience while
     * synchronously searching globally.
     *
     * @see GlobalSearchSession#search
     */
    @NonNull
    SyncSearchResults search(@NonNull String query, @NonNull SearchSpec searchSpec)
            throws AppSearchException;
}
