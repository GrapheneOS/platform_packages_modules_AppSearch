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

import java.util.concurrent.TimeUnit;

/**
 * An interface which exposes config flags to Apps Indexer.
 *
 * <p>Implementations of this interface must be thread-safe.
 *
 * @hide
 */
public interface AppsIndexerConfig {
    boolean DEFAULT_APPS_INDEXER_ENABLED = false;
    long DEFAULT_APPS_UPDATE_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(30); // 30 days.

    /** Returns whether Apps Indexer is enabled. */
    boolean isAppsIndexerEnabled();

    /**
     * Returns the minimum internal in millis for two consecutive full update. This is only checked
     * once after each boot.
     */
    long getAppsMaintenanceUpdateIntervalMillis();
}

