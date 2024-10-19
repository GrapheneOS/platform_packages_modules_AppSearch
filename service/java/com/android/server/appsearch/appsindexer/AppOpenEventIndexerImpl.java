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
import android.annotation.WorkerThread;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Interacts with UsageStatsManager and AppSearch to index app open events.
 *
 * <p>This class is NOT thread-safe.
 *
 * @hide
 */
public final class AppOpenEventIndexerImpl implements Closeable {
    static final String TAG = "AppSearchAppOpenEventIndexerImpl";

    private final Context mContext;
    private final AppSearchHelper mAppSearchHelper;

    public AppOpenEventIndexerImpl(@NonNull Context context) throws AppSearchException {
        mContext = Objects.requireNonNull(context);
        mAppSearchHelper = new AppSearchHelper(context);
    }

    /**
     * Checks UsageStatsManager and AppSearch to sync the App Open Events Index in AppSearch.
     *
     * @param settings contains update timestamps that help the indexer determine when indexing last
     *     ran
     */
    @WorkerThread
    public void doUpdate(@NonNull AppOpenEventIndexerSettings settings) throws AppSearchException {
        Objects.requireNonNull(settings);

        UsageStatsManager usageStatsManager = mContext.getSystemService(UsageStatsManager.class);

        long currentTimeMillis = System.currentTimeMillis();
        long lastAppOpenIndexerUpdateTimeMillis = settings.getLastUpdateTimestampMillis();
        Map<String, List<Long>> appOpenTimestamps =
                AppsUtil.getAppOpenTimestamps(
                        usageStatsManager, lastAppOpenIndexerUpdateTimeMillis, currentTimeMillis);

        try {
            // This should be a no-op if the schema is already set and unchanged.
            mAppSearchHelper.setSchemaForAppOpenEvents();

            mAppSearchHelper.indexAppOpenEvents(appOpenTimestamps);
            settings.setLastUpdateTimestampMillis(currentTimeMillis);
        } catch (AppSearchException e) {
            // Reset the last update time stamp and app update timestamp so we can try again later.
            settings.reset();
            throw e;
        }
    }

    /** Shuts down the {@link AppsOpenEventIndexerImpl} and its {@link AppSearchHelper}. */
    @Override
    public void close() {
        mAppSearchHelper.close();
    }
}
