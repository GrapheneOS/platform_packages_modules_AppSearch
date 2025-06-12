/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import android.os.SystemClock;
import com.android.appsearch.flags.Flags;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppOpenEvent;

import java.io.Closeable;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Interacts with UsageStatsManager and AppSearch to index app open events.
 *
 * <p>This class is NOT thread-safe.
 *
 * @hide
 */
public final class AppOpenEventIndexerImpl implements Closeable {
    static final String TAG = "AppSearchAppOpenEventIndexerImpl";
    private static final long TWO_WEEKS_IN_MILLIS = TimeUnit.DAYS.toMillis(14);

    // Pagination intervals should be much longer than this, otherwise we'll end up querying
    // UsageStatsManager in a tight loop.  Adding as a safeguard we should not be reliant on.
    private static final long MIN_PAGINATION_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);

    private final Context mContext;
    private final AppSearchHelper mAppSearchHelper;
    private final AppOpenEventIndexerConfig mAppOpenEventIndexerConfig;

    public AppOpenEventIndexerImpl(
            @NonNull Context context, @NonNull AppOpenEventIndexerConfig appOpenEventIndexerConfig)
            throws AppSearchException {
        mContext = Objects.requireNonNull(context);
        mAppSearchHelper = new AppSearchHelper(context);
        mAppOpenEventIndexerConfig = Objects.requireNonNull(appOpenEventIndexerConfig);
    }

    /**
     * Checks UsageStatsManager and AppSearch to sync the App Open Events Index in AppSearch.
     *
     * @param settings contains update timestamps that help the indexer determine when indexing last
     *     ran
     */
    @WorkerThread
    public void doUpdate(@NonNull AppOpenEventIndexerSettings settings, @NonNull AppOpenEventStats.Builder appOpenEventStatsBuilder) throws AppSearchException {
        Objects.requireNonNull(settings);
        Objects.requireNonNull(appOpenEventStatsBuilder);

        UsageStatsManager usageStatsManager = mContext.getSystemService(UsageStatsManager.class);

        long currentTimeMillis = System.currentTimeMillis();
        appOpenEventStatsBuilder.setUpdateStartTimestampMillis(currentTimeMillis);
        long queryStartTimeMillis =
                Math.max(
                        settings.getLastUpdateTimestampMillis(),
                        currentTimeMillis - TWO_WEEKS_IN_MILLIS);

        appOpenEventStatsBuilder.setLastAppUpdateTimestampMillis(
                settings.getLastUpdateTimestampMillis());


        try {
            // This should be a no-op if the schema is already set and unchanged.
            long startTimeMillis = SystemClock.elapsedRealtime();
            mAppSearchHelper.setSchemaForAppOpenEvents();
            appOpenEventStatsBuilder.setAppSearchSetSchemaLatencyMillis(
                    SystemClock.elapsedRealtime() - startTimeMillis);


            long paginationInterval = mAppOpenEventIndexerConfig.getPaginationIntervalMs();

            // This is already gated by Flags.appOpenEventIndexerEnabled() &&
            // appOpenEventIndexerConfig.isAppOpenEventIndexerEnabled() in AppSearchModule
            if (isPaginatedReadEnabled() && paginationInterval >= MIN_PAGINATION_INTERVAL_MS) {
                long summedPutLatencyMillis = 0;
                long summedReadLatencyMillis = 0;
                int numberOfAppOpenEventsAdded = 0;
                long currentChunkStartTimeMillis = queryStartTimeMillis;

                while (currentChunkStartTimeMillis < currentTimeMillis) {
                    long currentChunkEndTimeMillis =
                            Math.min(
                                    currentChunkStartTimeMillis + paginationInterval,
                                    currentTimeMillis);

                    long readStartTimeMillis = SystemClock.elapsedRealtime();
                    List<AppOpenEvent> appOpenEventsInChunk =
                            AppsUtil.getAppOpenEvents(
                                    usageStatsManager,
                                    currentChunkStartTimeMillis,
                                    currentChunkEndTimeMillis);
                    summedReadLatencyMillis += SystemClock.elapsedRealtime() - readStartTimeMillis;
                    numberOfAppOpenEventsAdded += appOpenEventsInChunk.size();

                    if (appOpenEventsInChunk != null && !appOpenEventsInChunk.isEmpty()) {
                        long indexStartTimeMillis = SystemClock.elapsedRealtime();
                        mAppSearchHelper.indexAppOpenEvents(appOpenEventsInChunk, appOpenEventStatsBuilder);
                        summedPutLatencyMillis += SystemClock.elapsedRealtime() - indexStartTimeMillis;
                    }
                    currentChunkStartTimeMillis = currentChunkEndTimeMillis;
                }
                appOpenEventStatsBuilder.setAppSearchPutLatencyMillis(summedPutLatencyMillis);
                appOpenEventStatsBuilder.setNumberOfAppOpenEventsAdded(numberOfAppOpenEventsAdded);
                appOpenEventStatsBuilder.setUsageStatsManagerReadLatencyMillis(summedReadLatencyMillis);

            } else {
                // Fallback to original non-paginated behavior if pagination is not enabled or if
                // interval is invalid.
                long readStartTimeMillis = SystemClock.elapsedRealtime();
                List<AppOpenEvent> appOpenEvents =
                        AppsUtil.getAppOpenEvents(
                                usageStatsManager, queryStartTimeMillis, currentTimeMillis);
                appOpenEventStatsBuilder.setUsageStatsManagerReadLatencyMillis(
                        SystemClock.elapsedRealtime() - readStartTimeMillis);

                if (appOpenEvents != null && !appOpenEvents.isEmpty()) {
                    long indexStartTimeMillis = SystemClock.elapsedRealtime();
                    mAppSearchHelper.indexAppOpenEvents(appOpenEvents, appOpenEventStatsBuilder);
                    appOpenEventStatsBuilder.setAppSearchPutLatencyMillis(
                            SystemClock.elapsedRealtime() - indexStartTimeMillis);
                    appOpenEventStatsBuilder.setNumberOfAppOpenEventsAdded(appOpenEvents.size());
                }
            }

            appOpenEventStatsBuilder.setTotalLatencyMillis(SystemClock.elapsedRealtime() - startTimeMillis);
            settings.setLastUpdateTimestampMillis(currentTimeMillis);
        } catch (AppSearchException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AppSearchException(AppSearchResult.RESULT_INTERNAL_ERROR, e.getMessage(), e);
        }
    }

    /** Shuts down the {@link AppOpenEventIndexerImpl} and its {@link AppSearchHelper}. */
    @Override
    public void close() {
        mAppSearchHelper.close();
    }

    /** Checks if paginated read is enabled based on both the feature flag and the config. */
    private boolean isPaginatedReadEnabled() {
        return Flags.appOpenEventIndexerPaginatedReadEnabled()
                && mAppOpenEventIndexerConfig.isPaginatedReadEnabled();
    }
}
