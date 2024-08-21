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

import android.provider.DeviceConfig;

/**
 * Implementation of {@link AppsIndexerConfig} using {@link DeviceConfig}.
 *
 * <p>It contains all the keys for flags related to Apps Indexer.
 *
 * <p>This class is thread-safe.
 *
 * @hide
 */
public class FrameworkAppsIndexerConfig implements AppsIndexerConfig {
    static final String KEY_APPS_INDEXER_ENABLED = "apps_indexer_enabled";
    static final String KEY_APPS_UPDATE_INTERVAL_MILLIS = "apps_update_interval_millis";
    static final String KEY_MAX_APP_FUNCTIONS_PER_PACKAGE = "max_app_functions_per_package";

    @Override
    public boolean isAppsIndexerEnabled() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_APPS_INDEXER_ENABLED,
                DEFAULT_APPS_INDEXER_ENABLED);
    }

    @Override
    public long getAppsMaintenanceUpdateIntervalMillis() {
        return DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_APPS_UPDATE_INTERVAL_MILLIS,
                DEFAULT_APPS_UPDATE_INTERVAL_MILLIS);
    }

    @Override
    public int getMaxAppFunctionsPerPackage() {
        return DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_MAX_APP_FUNCTIONS_PER_PACKAGE,
                DEFAULT_MAX_APP_FUNCTIONS_PER_PACKAGE);
    }
}

