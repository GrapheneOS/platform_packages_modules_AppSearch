/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.appsearch;

import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import android.os.Build;

import com.android.appsearch.flags.Flags;
import com.android.server.appsearch.external.localstorage.AppSearchConfig;
import com.android.server.appsearch.isolated_storage_service.IsolatedStorageServiceManager;

import com.google.android.icing.proto.PersistType;

import org.jspecify.annotations.NonNull;

import java.util.concurrent.TimeUnit;

/**
 * An interface which exposes config flags to AppSearch.
 *
 * <p>This interface provides an abstraction for the AppSearch's flag mechanism and implements
 * caching to avoid expensive lookups. This interface is only used by environments which have a
 * running AppSearch service like Framework and GMSCore. JetPack uses {@link AppSearchConfig}
 * directly instead.
 *
 * <p>Implementations of this interface must be thread-safe.
 *
 * @hide
 */
public interface ServiceAppSearchConfig extends AppSearchConfig, AutoCloseable {
    /**
     * Default min time interval between samples in millis if there is no value set for {@link
     * #getCachedMinTimeIntervalBetweenSamplesMillis()} in the flag system.
     */
    long DEFAULT_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS = 50;

    /**
     * Default sampling interval if there is no value set for {@link
     * #getCachedSamplingIntervalDefault()} in the flag system.
     */
    int DEFAULT_SAMPLING_INTERVAL = 10;

    /**
     * Sampling interval to use for trunkfood users. This will sample 25% of logs rather than the
     * production default of 10%.
     */
    int TRUNKFOODER_SAMPLING_INTERVAL = 4;

    long DEFAULT_MAX_BYTES_OPTIMIZE_THRESHOLD = 512 * 1024 * 1024; // 512 MiB
    long DEFAULT_MAX_DOC_COUNT_OPTIMIZE_THRESHOLD = 500_000; // 500k docs
    int DEFAULT_LIMIT_CONFIG_MAX_DOCUMENT_SIZE_BYTES = 512 * 1024; // 512KiB
    int DEFAULT_LIMIT_CONFIG_PER_PACKAGE_DOCUMENT_COUNT_LIMIT = 80_000;
    int DEFAULT_LIMIT_CONFIG_DOCUMENT_COUNT_LIMIT_START_THRESHOLD = 2_000_000;
    int DEFAULT_LIMIT_CONFIG_MAX_SUGGESTION_COUNT = 20_000;
    int DEFAULT_BYTES_OPTIMIZE_THRESHOLD = 10 * 1024 * 1024; // 10 MiB
    int DEFAULT_TIME_OPTIMIZE_THRESHOLD_MILLIS = (int) TimeUnit.DAYS.toMillis(7);
    int DEFAULT_DOC_COUNT_OPTIMIZE_THRESHOLD = 10_000;
    int DEFAULT_MIN_TIME_OPTIMIZE_THRESHOLD_MILLIS = 0;
    int DEFAULT_FOUR_HOUR_MIN_TIME_OPTIMIZE_THRESHOLD_MILLIS = (int) TimeUnit.HOURS.toMillis(4);
    // Cached API Call Stats is disabled by default
    int DEFAULT_DISABLED_API_CALL_STATS_LIMIT = 0;
    int DEFAULT_ENABLED_API_CALL_STATS_LIMIT = 50;
    boolean DEFAULT_RATE_LIMIT_ENABLED = false;

    /** This defines the task queue's total capacity for rate limiting. */
    int DEFAULT_RATE_LIMIT_TASK_QUEUE_TOTAL_CAPACITY = Integer.MAX_VALUE;

    /**
     * This defines the per-package capacity for rate limiting as a percentage of the total
     * capacity.
     */
    float DEFAULT_RATE_LIMIT_TASK_QUEUE_PER_PACKAGE_CAPACITY_PERCENTAGE = 1;

    /**
     * This defines API costs used for AppSearch's task queue rate limit.
     *
     * <p>Each entry in the string should follow the format 'api_name:integer_cost', and each entry
     * should be separated by a semi-colon. API names should follow the string definitions in {@link
     * com.android.server.appsearch.external.localstorage.stats.CallStats}.
     *
     * <p>e.g. A valid string: "localPutDocuments:5;localSearch:1;localSetSchema:10"
     */
    String DEFAULT_RATE_LIMIT_API_COSTS_STRING = "";

    boolean DEFAULT_ICING_CONFIG_USE_READ_ONLY_SEARCH = true;
    boolean DEFAULT_USE_FIXED_EXECUTOR_SERVICE = false;
    long DEFAULT_APP_FUNCTION_CALL_TIMEOUT_MILLIS = 30_000;

    /** This flag value is true by default because the flag is intended as a kill-switch. */
    boolean DEFAULT_SHOULD_RETRIEVE_PARENT_INFO = true;

    /** The default interval in millisecond to trigger fully persist job. */
    long DEFAULT_FULLY_PERSIST_JOB_INTERVAL = DAY_IN_MILLIS;

    /** The default delay in millisecond to schedule persistToDisk after putDocuments. */
    long DEFAULT_PERSIST_DELAY = MINUTE_IN_MILLIS;

    /** The time for a 5-min delay in millisecond to schedule persistToDisk after putDocuments. */
    long DEFAULT_FIVE_MINUTE_PERSIST_DELAY = TimeUnit.MINUTES.toMillis(5);

    /**
     * The default number of active fds an app is allowed to open for read and write blob from
     * AppSearch.
     */
    int DEFAULT_MAX_OPEN_BLOB_COUNT = 250;

    /** The default number of max byte size limit for a single batch put request. */
    int DEFAULT_MAX_BYTE_LIMIT_FOR_BATCH_PUT = 512 * 1024; // 512 KiB

    /** Returns cached value for minTimeIntervalBetweenSamplesMillis. */
    long getCachedMinTimeIntervalBetweenSamplesMillis();

    /**
     * Returns cached value for default sampling interval for all the stats NOT listed in the
     * configuration.
     *
     * <p>For example, sampling_interval=10 means that one out of every 10 stats was logged.
     */
    int getCachedSamplingIntervalDefault();

    /**
     * Returns cached value for sampling interval for batch calls.
     *
     * <p>For example, sampling_interval=10 means that one out of every 10 stats was logged.
     */
    int getCachedSamplingIntervalForBatchCallStats();

    /**
     * Returns cached value for sampling interval for putDocument.
     *
     * <p>For example, sampling_interval=10 means that one out of every 10 stats was logged.
     */
    int getCachedSamplingIntervalForPutDocumentStats();

    /**
     * Returns cached value for sampling interval for initialize.
     *
     * <p>For example, sampling_interval=10 means that one out of every 10 stats was logged.
     */
    int getCachedSamplingIntervalForInitializeStats();

    /**
     * Returns cached value for sampling interval for search.
     *
     * <p>For example, sampling_interval=10 means that one out of every 10 stats was logged.
     */
    int getCachedSamplingIntervalForSearchStats();

    /**
     * Returns cached value for sampling interval for globalSearch.
     *
     * <p>For example, sampling_interval=10 means that one out of every 10 stats was logged.
     */
    int getCachedSamplingIntervalForGlobalSearchStats();

    /**
     * Returns cached value for sampling interval for optimize.
     *
     * <p>For example, sampling_interval=10 means that one out of every 10 stats was logged.
     */
    int getCachedSamplingIntervalForOptimizeStats();

    /**
     * Returns the cached optimize byte size threshold.
     *
     * <p>An AppSearch Optimize job will be triggered if the bytes size of garbage resource exceeds
     * this threshold.
     */
    int getCachedBytesOptimizeThreshold();

    /**
     * Returns the cached optimize time interval threshold.
     *
     * <p>An AppSearch Optimize job will be triggered if the time since last optimize job exceeds
     * this threshold.
     */
    int getCachedTimeOptimizeThresholdMs();

    /**
     * Returns the cached optimize document count threshold.
     *
     * <p>An AppSearch Optimize job will be triggered if the number of document of garbage resource
     * exceeds this threshold.
     */
    int getCachedDocCountOptimizeThreshold();

    /**
     * Returns the cached minimum optimize time interval threshold.
     *
     * <p>An AppSearch Optimize job will only be triggered if the time since last optimize job
     * exceeds this threshold.
     */
    int getCachedMinTimeOptimizeThresholdMs();

    /** Returns the delay in millisecond to schedule checkForOptimize. */
    long getCachedCheckOptimizeDelayMillis();

    /**
     * Returns the cached max optimize byte size threshold.
     *
     * <p>An AppSearch Optimize job will be FORCED if the estimated number of reclaimable bytes from
     * deleted and expired documents exceeds this threshold.
     */
    long getCachedMaxBytesOptimizeThreshold();

    /**
     * Returns the cached max optimize document count threshold.
     *
     * <p>An AppSearch Optimize job will be FORCED if the number of expired/deleted documents
     * exceeds this threshold.
     */
    long getCachedMaxDocCountOptimizeThreshold();

    /**
     * Returns the maximum number of last API calls' statistics that can be included in the tracking
     * queue.
     */
    int getCachedApiCallStatsLimit();

    /**
     * Returns the maximum number of last API calls' statistics that can be included in the tracking
     * queue when VM is enabled.
     */
    default int getCachedApiCallStatsLimitForVm() {
        return DEFAULT_ENABLED_API_CALL_STATS_LIMIT;
    }

    /** Returns the cached denylist. */
    Denylist getCachedDenylist();

    /** Returns whether to enable AppSearch rate limiting. */
    boolean getCachedRateLimitEnabled();

    /** Returns the cached {@link AppSearchRateLimitConfig}. */
    AppSearchRateLimitConfig getCachedRateLimitConfig();

    /** Returns the sampling rate for App Open Event Indexer stats logging. */
    default int getAppOpenEventIndexerLoggingSamplingRate() {
        return DEFAULT_SAMPLING_INTERVAL;
    }

    /** Returns the sampling rate for isolated storage data migration stats logging. */
    default int getIsolatedStorageDataMigrationSamplingRate() {
        // Always log the data migration stats, as it only happens once per reboot.
        return 1;
    }

    /**
     * Returns the maximum allowed duration for an app function call in milliseconds.
     *
     * @see android.app.appsearch.functions.AppFunctionManager#executeAppFunction
     */
    long getAppFunctionCallTimeoutMillis();

    /**
     * Returns the time interval to schedule a full persist to disk back ground job in milliseconds.
     */
    long getCachedFullyPersistJobIntervalMillis();

    /** Returns the delay in millisecond to schedule persistToDisk after putDocuments. */
    long getCachedPersistDelayMillis();

    /** Returns the memory size in bytes for isolated storage. */
    default long getIsolatedStorageMemoryBytes() {
        return IsolatedStorageServiceManager.DEFAULT_MEMORY_BYTES;
    }

    /** Returns whether or not AppSearch should use Isolated Storage */
    default boolean getIsolatedStorageDisabled() {
        return IsolatedStorageServiceManager.DEFAULT_ISOLATED_STORAGE_DISABLED;
    }

    /** Returns whether or not AppSearch should migrate data to isolated storage. */
    default boolean disableIsolatedStorageMigration() {
        return IsolatedStorageServiceManager.DEFAULT_ISOLATED_STORAGE_MIGRATION_DISABLED;
    }

    /** Returns whether or not we need to clean up old CE VMs */
    default boolean getIsolatedStorageDeleteCeVms() {
        return IsolatedStorageServiceManager.DEFAULT_ISOLATED_STORAGE_DELETE_CE_VMS;
    }

    /**
     * Default min time interval between consecutive optimize calls in millis if there is no value
     * set for {@link #getCachedMinTimeOptimizeThresholdMs()} in the flag system.
     */
    default int defaultMinTimeOptimizeThresholdMillis() {
        return Flags.enableFourHourMinTimeOptimizeThreshold()
                ? DEFAULT_FOUR_HOUR_MIN_TIME_OPTIMIZE_THRESHOLD_MILLIS
                : DEFAULT_MIN_TIME_OPTIMIZE_THRESHOLD_MILLIS;
    }

    /**
     * Default {@code PersistType.Code} that should be used to persist common mutations such as PUTs
     * or DELETEs.
     */
    default PersistType.@NonNull Code defaultLightweightPersistType() {
        return Flags.enableRecoveryProofPersistence()
                ? PersistType.Code.RECOVERY_PROOF
                : PersistType.Code.LITE;
    }

    /** Default mem level used for document compression. */
    default int defaultCompressionMemLevel() {
        return Flags.enableCompressionMemLevelOne() ? 1 : DEFAULT_COMPRESSION_MEM_LEVEL;
    }

    /** Default persist to disk delay time. */
    default long defaultPersistDelayMillis() {
        return Flags.enableFiveMinPersistToDiskDelay()
                ? DEFAULT_FIVE_MINUTE_PERSIST_DELAY
                : DEFAULT_PERSIST_DELAY;
    }

    /** Default check optimize delay time. */
    default long defaultCheckOptimizeDelayMillis() {
        // 5min delay
        return TimeUnit.MINUTES.toMillis(5);
    }

    /** Default number of API call stats appsearch is tracking. */
    default int defaultApiCallStatsLimit() {
        return Flags.enableApiCallStatsTracking()
                ? DEFAULT_ENABLED_API_CALL_STATS_LIMIT
                : DEFAULT_DISABLED_API_CALL_STATS_LIMIT;
    }

    /** Default number of API call stats appsearch is tracking. */
    default int defaultSamplingInterval() {
        return Flags.enableHigherSamplingForTrunkfooders()
                ? TRUNKFOODER_SAMPLING_INTERVAL
                : DEFAULT_SAMPLING_INTERVAL;
    }

    /** Returns whether to enable repeated fields for join API. */
    default boolean enableRepeatedFieldJoins() {
        // Currently, this feature is rollback incompatible to older AppSearch versions. Therefore,
        // restrict this feature on C+ only.
        // - AppSearch propagates this boolean value (with C+ SDK_INT check) to Icing in
        //   IcingOptionsConfig.
        // - Icing rejects a schema with repeated joinable fields if the feature is not enabled.
        //   This ensures rollback compatibility.
        //
        // TODO(b/457496944): change Icing backup schema to cover repeated joinable fields and
        //   modify SDK_INT check here if we decide to support this feature on T.
        return Flags.enableRepeatedFieldJoins()
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN;
    }

    /** Returns whether or not we need to clean up old CE VMs */
    default boolean getIsolatedStorageEnableUnfreezingMigration() {
        return IsolatedStorageServiceManager.DEFAULT_ISOLATED_STORAGE_ENABLE_UNFREEZING_MIGRATION;
    }

    /**
     * Returns whether enabling Icing background task scheduler or not.
     *
     * <p>Should always be false on AppSearch system service.
     */
    default boolean enableIcingBackgroundTaskScheduler() {
        return false;
    }

    /**
     * Returns the time threshold for an expired document to be purged.
     *
     * <ul>
     *   <li>Since we schedule a task to purge expired documents according to the next expiration
     *       time of the documents, it is possible that some documents expire within a small time
     *       window and the task executes too frequently.
     *   <li>Therefore, we use this flag to purge more documents that also expire in a short period
     *       of time after the current time.
     * </ul>
     *
     * <p>For example, if the value is 1000 ms and the current time is 10000 ms:
     *
     * <ul>
     *   <li>All documents that are expired before 10000 ms will be purged, since they are already
     *       expired.
     *   <li>Additionally, we will also purge documents that expire in the next 1000 ms, i.e.
     *       (10000, 11000] ms.
     * </ul>
     */
    @Override
    default long getExpiredDocumentPurgingThresholdMillis() {
        return DEFAULT_EXPIRED_DOCUMENT_PURGING_THRESHOLD_MILLIS;
    }

    /**
     * Closes this {@link AppSearchConfig}.
     *
     * <p>This close() operation does not throw an exception.
     */
    @Override
    void close();
}
