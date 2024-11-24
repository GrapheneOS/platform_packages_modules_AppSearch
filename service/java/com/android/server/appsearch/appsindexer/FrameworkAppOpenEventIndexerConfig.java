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
 * Implementation of {@link AppOpenEventIndexerConfig} using {@link DeviceConfig}.
 *
 * <p>It contains all the keys for flags related to App Open Event Indexer.
 *
 * <p>This class is thread-safe.
 *
 * @hide
 */
public class FrameworkAppOpenEventIndexerConfig implements AppOpenEventIndexerConfig {
    static final String KEY_APP_OPEN_EVENT_INDEXER_ENABLED = "app_open_event_indexer_enabled";
    static final String KEY_APP_OPEN_EVENT_UPDATE_INTERVAL_MILLIS =
            "app_open_event_update_interval_millis";

    @Override
    public boolean isAppOpenEventIndexerEnabled() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_APP_OPEN_EVENT_INDEXER_ENABLED,
                DEFAULT_APP_OPEN_EVENT_INDEXER_ENABLED);
    }

    @Override
    public long getAppOpenEventMaintenanceUpdateIntervalMillis() {
        return DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_APP_OPEN_EVENT_UPDATE_INTERVAL_MILLIS,
                DEFAULT_APP_OPEN_EVENT_INDEXER_UPDATE_INTERVAL_MILLIS);
    }
}
