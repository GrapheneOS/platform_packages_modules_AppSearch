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
import android.os.PersistableBundle;

import com.android.server.appsearch.indexer.IndexerSettings;

import java.io.File;
import java.util.Objects;

/**
 * Apps indexer settings backed by a PersistableBundle.
 *
 * <p>Holds settings such as:
 *
 * <ul>
 *   <li>the last time a full update was performed
 *   <li>the time of the last apps update
 *   <li>the time of the last apps deletion
 * </ul>
 *
 * <p>This class is NOT thread safe (similar to {@link PersistableBundle} which it wraps).
 *
 * @hide
 */
public class AppsIndexerSettings extends IndexerSettings {
    static final String SETTINGS_FILE_NAME = "apps_indexer_settings.pb";
    static final String LAST_UPDATE_TIMESTAMP_KEY = "last_update_timestamp_millis";
    static final String LAST_APP_UPDATE_TIMESTAMP_KEY = "last_app_update_timestamp_millis";

    public AppsIndexerSettings(@NonNull File baseDir) {
        super(Objects.requireNonNull(baseDir));
    }

    @Override
    protected String getSettingsFileName() {
        return SETTINGS_FILE_NAME;
    }

    /** Returns the timestamp of when the last full update occurred in milliseconds. */
    public long getLastUpdateTimestampMillis() {
        return mBundle.getLong(LAST_UPDATE_TIMESTAMP_KEY);
    }

    /** Sets the timestamp of when the last full update occurred in milliseconds. */
    public void setLastUpdateTimestampMillis(long timestampMillis) {
        mBundle.putLong(LAST_UPDATE_TIMESTAMP_KEY, timestampMillis);
    }

    /** Returns the timestamp of when the last app was updated in milliseconds. */
    public long getLastAppUpdateTimestampMillis() {
        return mBundle.getLong(LAST_APP_UPDATE_TIMESTAMP_KEY);
    }

    /** Sets the timestamp of when the last apps was updated in milliseconds. */
    public void setLastAppUpdateTimestampMillis(long timestampMillis) {
        mBundle.putLong(LAST_APP_UPDATE_TIMESTAMP_KEY, timestampMillis);
    }

    /** Resets all the settings to default values. */
    @Override
    public void reset() {
        setLastUpdateTimestampMillis(0);
        setLastAppUpdateTimestampMillis(0);
    }
}
