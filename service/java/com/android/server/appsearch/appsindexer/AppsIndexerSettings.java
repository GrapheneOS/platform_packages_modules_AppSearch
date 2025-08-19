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
import android.annotation.Nullable;
import android.os.Build;

import com.android.server.appsearch.indexer.IndexerSettings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

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

    private static final String LAST_PARTITIONS_FINGERPRINT_SORTED_BY_PARTITION_NAME =
            "last_partitions_fingerprint_sorted_by_partition_name";

    private static final String LOG_LINES_KEY = "log_lines";
    private static final int MAX_LOG_LINES = 15;
    private static final int MAX_LOG_LENGTH = 10_000;

    private static final String PREVIOUS_LOCALE_CODE = "previous_locale_code";

    private final Deque<String> mLogLines;

    public AppsIndexerSettings(@NonNull File baseDir) {
        super(baseDir);
        mLogLines = new ArrayDeque<>(MAX_LOG_LINES);
        String[] storedLogLines = mBundle.getStringArray(LOG_LINES_KEY);
        if (storedLogLines != null) {
            for (String line : storedLogLines) {
                mLogLines.offerLast(line);
            }
        }
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

    /**
     * Returns the stored fingerprint strings for partitions sorted by {@link
     * Build.Partition#getName()} returned by {@link Build#getFingerprintedPartitions()} from the
     * last indexer run.
     */
    @Nullable
    public String[] getLastPartitionFingerprintsSortedByPartitionName() {
        return mBundle.getStringArray(LAST_PARTITIONS_FINGERPRINT_SORTED_BY_PARTITION_NAME);
    }

    /**
     * Stores the fingerprints of all partitions as a string array sorted by {@link
     * Build.Partition#getName()}.
     */
    public void setLastPartitionFingerprintsSortedByPartitionName(
            @NonNull List<Build.Partition> fingerprintedPartitions) {
        String[] fingerprints = new String[fingerprintedPartitions.size()];
        for (int i = 0; i < fingerprintedPartitions.size(); ++i) {
            fingerprints[i] = fingerprintedPartitions.get(i).getFingerprint();
        }
        mBundle.putStringArray(LAST_PARTITIONS_FINGERPRINT_SORTED_BY_PARTITION_NAME, fingerprints);
    }

    /** Get the locale code of the previous apps indexer run. */
    public String getPreviousLocaleCode() {
        return mBundle.getString(PREVIOUS_LOCALE_CODE);
    }

    /** Sets the locale code of the most recent apps indexer run. */
    public void setPreviousLocaleCode(@NonNull String localeCode) {
        mBundle.putString(PREVIOUS_LOCALE_CODE, Objects.requireNonNull(localeCode));
    }

    /** Appends a log message to the settings log. */
    public void appendLog(@NonNull String log) {
        if (log.length() > MAX_LOG_LENGTH) {
            log = log.substring(0, MAX_LOG_LENGTH);
        }
        // There are no locks protecting access to this field since it is only accessed through a
        // single-threaded executor. Even if there is a race condition, it is acceptable as this is
        // for debugging purposes.
        mLogLines.offerLast(log);
        if (mLogLines.size() > MAX_LOG_LINES) {
            mLogLines.pollFirst();
        }
    }

    /** Returns the current log messages. */
    @NonNull
    public Collection<String> getLogLines() {
        return Collections.unmodifiableCollection(mLogLines);
    }

    /** Resets all settings to default values except {@link #getPreviousIndexerVersionCode()}. */
    @Override
    public void reset() {
        super.reset();
        setLastAppUpdateTimestampMillis(0);
        setPreviousIndexerVersionCode(APP_INDEXER_VERSION_UNKNOWN);
        mBundle.remove(PREVIOUS_LOCALE_CODE);
        mLogLines.clear();
        mBundle.remove(LOG_LINES_KEY);
    }

    @Override
    public void persist() throws IOException {
        mBundle.putStringArray(LOG_LINES_KEY, mLogLines.toArray(new String[0]));
        super.persist();
    }
}
