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

import android.app.appsearch.AppSearchSession;

import java.util.concurrent.TimeUnit;

/**
 * An interface which exposes config flags to Apps Indexer.
 *
 * <p>Implementations of this interface must be thread-safe.
 *
 * @hide
 */
public interface AppsIndexerConfig {
    boolean DEFAULT_APPS_INDEXER_ENABLED = true;

    long DEFAULT_APPS_UPDATE_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(30); // 30 days.

    /** The default maximum number of app functions per package that the app indexer will index. */
    int DEFAULT_MAX_APP_FUNCTIONS_PER_PACKAGE = 250;

    /**
     * The default maximum number of app function schemas per package that the app indexer will
     * index.
     */
    int DEFAULT_MAX_ALLOWED_APP_FUNCTION_SCHEMAS_PER_PACKAGE = 20;

    /**
     * The default max allowed size of an app function document.
     *
     * <p>More conservative than one enforced by {@link AppSearchSession#put} to prevent app
     * developers from indexing additional properties in app function documents using this indexer.
     */
    int DEFAULT_MAX_ALLOWED_APP_FUNCTION_DOC_SIZE_IN_BYTES = 4 * 1024; // 4KiB

    /**
     * The default minimum time required to wait before attempting a firstRun sync after a previous
     * firstRun sync.
     */
    long DEFAULT_MIN_TIME_BETWEEN_FIRST_SYNCS_MILLIS = TimeUnit.HOURS.toMillis(4);

    /** Returns whether Apps Indexer is enabled. */
    boolean isAppsIndexerEnabled();

    /* Returns the minimum internal in millis for two consecutive scheduled updates. */
    long getAppsMaintenanceUpdateIntervalMillis();

    /** Returns the max number of app functions the app indexer will index per package. */
    int getMaxAppFunctionsPerPackage();

    /** Returns the max number of app function schemas the app indexer will index per package. */
    int getMaxAllowedAppFunctionSchemasPerPackage();

    /**
     * Returns the minimum time required to wait before attempting a firstRun sync after a previous
     * firstRun sync in milliseconds.
     */
    long getMinTimeBetweenFirstSyncsMillis();
}
