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

import com.android.server.appsearch.indexer.IndexerSettings;

import java.io.File;

/**
 * Abstract class for settings backed by a PersistableBundle.
 *
 * <p>Holds settings such as:
 *
 * <ul>
 *   <li>getting and setting the timestamp of the last update, stored in {@link
 *       #getLastUpdateTimestampMillis()}
 * </ul>
 *
 * <p>This class is NOT thread safe (similar to {@link PersistableBundle} which it wraps).
 */
public class AppOpenEventIndexerSettings extends IndexerSettings {
    static final String SETTINGS_FILE_NAME = "app_open_event_indexer_settings.pb";

    public AppOpenEventIndexerSettings(@NonNull File baseDir) {
        super(baseDir);
    }

    @Override
    protected String getSettingsFileName() {
        return SETTINGS_FILE_NAME;
    }
}
