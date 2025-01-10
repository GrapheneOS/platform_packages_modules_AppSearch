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

import static com.android.server.appsearch.appsindexer.AppIndexerVersions.APP_INDEXER_VERSION_UNKNOWN;

import android.annotation.NonNull;

import com.android.server.appsearch.indexer.IndexerSettings;

import java.io.File;

/**
 * Apps indexer settings backed by a PersistableBundle.
 *
 * <p>Holds settings such as:
 *
 * <ul>
 *   <li>the timestamp of the last full update
 *   <li>the timestamp of the last apps update
 * </ul>
 */
public class AppsIndexerSettings extends IndexerSettings {
    static final String SETTINGS_FILE_NAME = "apps_indexer_settings.pb";
    static final String LAST_APP_UPDATE_TIMESTAMP_KEY = "last_app_update_timestamp_millis";

    static final String PREVIOUS_APP_INDEXER_VERSION_CODE = "previous_app_indexer_version_code";

    public AppsIndexerSettings(@NonNull File baseDir) {
        super(baseDir);
    }

    @Override
    protected String getSettingsFileName() {
        return SETTINGS_FILE_NAME;
    }

    /** Returns the timestamp of when the last app was updated in milliseconds. */
    public long getLastAppUpdateTimestampMillis() {
        return mBundle.getLong(LAST_APP_UPDATE_TIMESTAMP_KEY);
    }

    /** Sets the timestamp of when the last app was updated in milliseconds. */
    public void setLastAppUpdateTimestampMillis(long timestampMillis) {
        mBundle.putLong(LAST_APP_UPDATE_TIMESTAMP_KEY, timestampMillis);
    }

    /** Returns the version code of AppSearch module that previously indexed the apps. */
    @AppIndexerVersions.AppIndexerVersion
    public int getPreviousIndexerVersionCode() {
        return mBundle.getInt(PREVIOUS_APP_INDEXER_VERSION_CODE, APP_INDEXER_VERSION_UNKNOWN);
    }

    /** Sets the version code of App Indexer that previously indexed the apps. */
    public void setPreviousIndexerVersionCode(
            @AppIndexerVersions.AppIndexerVersion int versionCode) {
        mBundle.putInt(PREVIOUS_APP_INDEXER_VERSION_CODE, versionCode);
    }

    /** Resets all settings to default values except {@link #getPreviousIndexerVersionCode()}. */
    @Override
    public void reset() {
        super.reset();
        setLastAppUpdateTimestampMillis(0);
        setPreviousIndexerVersionCode(APP_INDEXER_VERSION_UNKNOWN);
    }
}
