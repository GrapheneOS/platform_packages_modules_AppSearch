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
import android.annotation.Nullable;
import android.os.Build;

import com.android.server.appsearch.indexer.BaseSettings;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Holds settings and persistent state for AppsIndexer.
 *
 * <p>This class is NOT thread safe.
 */
public class AppsIndexerSettings extends BaseSettings {

    private static final int MAX_LOG_LINES = 15;
    private static final int MAX_LOG_LENGTH = 10_000;

    // A rolling log of the most recent indexing operations for debugging.
    private final Deque<String> mLogLines = new ArrayDeque<>();

    private long mLastAppUpdateTimestampMillis;
    private int mPreviousIndexerVersionCode;
    private String[] mLastPartitionFingerprints;
    private String mPreviousLocaleCode;

    public AppsIndexerSettings() {
        // Set explicit default values for all fields.
        reset();
    }

    /** Returns the timestamp of when the last app was updated in milliseconds. */
    public long getLastAppUpdateTimestampMillis() {
        return mLastAppUpdateTimestampMillis;
    }

    /** Sets the timestamp of when the last app was updated in milliseconds. */
    public void setLastAppUpdateTimestampMillis(long timestampMillis) {
        mLastAppUpdateTimestampMillis = timestampMillis;
    }

    /** Returns the version code of AppSearch module that previously indexed the apps. */
    @AppIndexerVersions.AppIndexerVersion
    public int getPreviousIndexerVersionCode() {
        return mPreviousIndexerVersionCode;
    }

    /** Sets the version code of App Indexer that previously indexed the apps. */
    public void setPreviousIndexerVersionCode(
            @AppIndexerVersions.AppIndexerVersion int versionCode) {
        mPreviousIndexerVersionCode = versionCode;
    }

    /**
     * Returns the stored fingerprint strings for partitions sorted by {@link
     * Build.Partition#getName()} returned by {@link Build#getFingerprintedPartitions()} from the
     * last indexer run.
     */
    @Nullable
    public String[] getLastPartitionFingerprints() {
        return mLastPartitionFingerprints;
    }

    /**
     * Sets the stored fingerprint partitions sorted by {@link Build.Partition#getName()} returned
     * by {@link Build#getFingerprintedPartitions()} from the last indexer run.
     *
     * @param fingerprintedPartitions The list of partitions to extract fingerprints from. The
     *     caller is responsible for ensuring this list is sorted.
     */
    public void setLastPartitionFingerprintsSortedByPartitionName(
            @NonNull List<Build.Partition> fingerprintedPartitions) {
        String[] fingerprints = new String[fingerprintedPartitions.size()];
        for (int i = 0; i < fingerprintedPartitions.size(); ++i) {
            fingerprints[i] = fingerprintedPartitions.get(i).getFingerprint();
        }
        setLastPartitionFingerprints(fingerprints);
    }

    /**
     * Sets the stored fingerprint strings for partitions sorted by {@link
     * Build.Partition#getName()} returned by {@link Build#getFingerprintedPartitions()} from the
     * last indexer run.
     */
    public void setLastPartitionFingerprints(@Nullable String[] fingerprints) {
        mLastPartitionFingerprints = fingerprints;
    }

    /** Returns the locale code of the previous apps indexer run. */
    @NonNull
    public String getPreviousLocaleCode() {
        return mPreviousLocaleCode;
    }

    /** Sets the locale code of the most recent apps indexer run. */
    public void setPreviousLocaleCode(@NonNull String localeCode) {
        mPreviousLocaleCode = localeCode;
    }

    /** Returns the current log messages. */
    @NonNull
    public Collection<String> getLogLines() {
        return Collections.unmodifiableCollection(mLogLines);
    }

    /** Sets the current log messages. */
    public void setLogLines(@NonNull Deque<String> logLines) {
        mLogLines.clear();
        mLogLines.addAll(logLines);
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

    /** Resets all settings to default values. */
    @Override
    public void reset() {
        super.reset();
        mLastAppUpdateTimestampMillis = 0L;
        mPreviousIndexerVersionCode = AppIndexerVersions.APP_INDEXER_VERSION_UNKNOWN;
        mLastPartitionFingerprints = null;
        mPreviousLocaleCode = "";
        mLogLines.clear();
    }
}
