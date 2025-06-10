/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.provider.DeviceConfig;

import com.android.server.appsearch.indexer.IndexerForceUpdateConfig;

/**
 * Implementation of {@link IndexerForceUpdateConfig} using {@link DeviceConfig} for AppsIndexer.
 *
 * <p>It contains all the keys for flags related to Apps Indexer Force Update Configuration.
 *
 * <p>This class is thread-safe.
 *
 * @hide
 */
public class FrameworkAppsIndexerForceUpdateConfig implements IndexerForceUpdateConfig {
    static final boolean DEFAULT_APPS_INDEXER_FORCE_UPDATE_ENABLED = false;
    static final int DEFAULT_APPS_INDEXER_FORCE_UPDATE_EMERGENCY_COUNTER = 0;
    static final String KEY_APPS_INDEXER_FORCE_UPDATE_ENABLED = "apps_indexer_force_update_enabled";
    static final String KEY_APPS_INDEXER_FORCE_UPDATE_EMERGENCY_COUNTER =
            "apps_indexer_force_update_emergency_counter";

    @Override
    public boolean isIndexerForceUpdateEnabled() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_APPS_INDEXER_FORCE_UPDATE_ENABLED,
                DEFAULT_APPS_INDEXER_FORCE_UPDATE_ENABLED);
    }

    @Override
    public int getIndexerForceUpdateEmergencyCounter() {
        return DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_APPS_INDEXER_FORCE_UPDATE_EMERGENCY_COUNTER,
                DEFAULT_APPS_INDEXER_FORCE_UPDATE_EMERGENCY_COUNTER);
    }
}
