/*
 * Copyright 2025 The Android Open Source Project
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
package com.android.server.appsearch.stats;

import static com.android.server.appsearch.stats.VMPayloadStats.PAYLOAD_CALLBACK_TYPE_SIZE;

import android.os.SystemClock;

import androidx.annotation.GuardedBy;
import androidx.annotation.VisibleForTesting;

import com.android.server.appsearch.isolated_storage_service.ServiceConfig;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Logger Implementation for pushed atoms.
 *
 * <p>This class is thread-safe.
 *
 * @hide
 */
public class IsolateStorageServiceLogger {

    private static final Random sRng = new Random();

    /** Elapsed time for last stats logged from boot in millis */
    @GuardedBy("mLock")
    private long mLastPushTimeMillisLocked = 0;

    /**
     * The List to track how many stats we skipped due to {@link
     * ServiceConfig#pCachedSamplingInterval()}.
     *
     * <p>We can have correct extrapolated number by adding those counts back when we log the same
     * type of stats next time. E.g. the true count of an event could be estimated as:
     * SUM(sampling_interval * (num_skipped_sample + 1)) as est_count
     *
     * <p>The key to the SparseArray is {@link VMPayloadStats.PayloadCallbackType}
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    final List<Integer> mSkippedSampleCountLocked = new ArrayList(PAYLOAD_CALLBACK_TYPE_SIZE);

    // Manager holding the configuration flags
    private final ServiceConfig mConfig;

    private final Object mLock = new Object();

    /** Creates the {@link IsolateStorageServiceLogger} instance. */
    public IsolateStorageServiceLogger(@NonNull ServiceConfig config) {
        Objects.requireNonNull(config);
        mConfig = config;
        // preload the mSkippedSampleCountLocked.
        for (int i = 0; i < PAYLOAD_CALLBACK_TYPE_SIZE; i++) {
            mSkippedSampleCountLocked.add(0);
        }
    }

    /** Push the given {@link VMPayloadStats} to services. */
    public void logStats(@NonNull VMPayloadStats stats) {
        Objects.requireNonNull(stats);
        synchronized (mLock) {
            if (shouldLogForTypeLocked(stats.getCallbackType())) {
                mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
                int skippedSampleCount = mSkippedSampleCountLocked.get(stats.getCallbackType());
                mSkippedSampleCountLocked.set(stats.getCallbackType(), 0);
                // ignore close exception
                AppSearchStatsLog.write(
                        AppSearchStatsLog.APP_SEARCH_VM_PAYLOAD_STATS_REPORTED,
                        mConfig.pCachedSamplingInterval,
                        skippedSampleCount,
                        stats.getCallbackType(),
                        stats.getErrorCode(),
                        stats.getExitCode(),
                        stats.getStopReason());
            }
        }
    }

    /**
     * Checks if this stats should be logged.
     *
     * <p>It won't be logged if it is "sampled" out, or it is too close to the previous logged
     * stats.
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    boolean shouldLogForTypeLocked(@VMPayloadStats.PayloadCallbackType int callbackType) {
        int samplingInterval = mConfig.pCachedSamplingInterval;
        // Sampling
        if (!shouldSample(samplingInterval)) {
            return false;
        }

        // Rate limiting
        // Check the timestamp to see if it is too close to last logged sample
        long currentTimeMillis = SystemClock.elapsedRealtime();
        if (mLastPushTimeMillisLocked
                > currentTimeMillis - mConfig.pCachedMinTimeIntervalBetweenSamplesMillis) {
            int count = mSkippedSampleCountLocked.get(callbackType);
            ++count;
            mSkippedSampleCountLocked.set(callbackType, count);
            return false;
        }

        return true;
    }

    /**
     * Checks if the stats should be sampled for logging based on the provided sampling interval.
     *
     * <p>The probability of sampling is 1/samplingInterval. For example:
     *
     * <ul>
     *   <li>If the samplingInterval is 1, all stats will be sampled (100% sampling).
     *   <li>If the samplingInterval is 10, 1 in 10 stats will be sampled (10% sampling).
     * </ul>
     *
     * @param samplingInterval the interval used to calculate the sampling probability.
     * @return true if the stats should be sampled, false otherwise.
     */
    private static boolean shouldSample(int samplingInterval) {
        if (samplingInterval <= 0) {
            return false;
        }

        return sRng.nextInt(samplingInterval) == 0;
    }
}
