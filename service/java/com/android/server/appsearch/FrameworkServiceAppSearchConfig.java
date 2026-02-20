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
// @exportToGMSCore:skipFile()
package com.android.server.appsearch;

import static com.android.server.appsearch.isolated_storage_service.IsolatedStorageServiceManager.DEFAULT_MAX_PAGE_BYTES_LIMIT_FOR_ISOLATED_STORAGE;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.OnPropertiesChangedListener;

import com.android.appsearch.flags.Flags;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.appsindexer.AppOpenEventIndexerConfig;
import com.android.server.appsearch.appsindexer.FrameworkAppOpenEventIndexerConfig;
import com.android.server.appsearch.external.localstorage.IcingOptionsConfig;
import com.android.server.appsearch.isolated_storage_service.IsolatedStorageServiceManager;

import com.google.android.icing.proto.PersistType;

import org.jspecify.annotations.NonNull;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Implementation of {@link ServiceAppSearchConfig} using {@link DeviceConfig}.
 *
 * <p>Though the latest flag values can always be retrieved by calling {@link
 * DeviceConfig#getProperty}, we want to cache some of those values. For example, the sampling
 * intervals for logging, they are needed for each api call and it would be a little expensive to
 * call {@link DeviceConfig#getProperty} every time.
 *
 * <p>Listener is registered to DeviceConfig keep the cached value up to date.
 *
 * <p>This class is thread-safe.
 *
 * @hide
 */
public final class FrameworkServiceAppSearchConfig implements ServiceAppSearchConfig {
    private static volatile FrameworkServiceAppSearchConfig sConfig;

    /*
     * Keys for ALL the flags stored in DeviceConfig.
     */
    public static final String KEY_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS =
            "min_time_interval_between_samples_millis";
    public static final String KEY_SAMPLING_INTERVAL_DEFAULT = "sampling_interval_default";
    public static final String KEY_SAMPLING_INTERVAL_FOR_BATCH_CALL_STATS =
            "sampling_interval_for_batch_call_stats";
    public static final String KEY_SAMPLING_INTERVAL_FOR_PUT_DOCUMENT_STATS =
            "sampling_interval_for_put_document_stats";
    public static final String KEY_SAMPLING_INTERVAL_FOR_INITIALIZE_STATS =
            "sampling_interval_for_initialize_stats";
    public static final String KEY_SAMPLING_INTERVAL_FOR_SEARCH_STATS =
            "sampling_interval_for_search_stats";
    public static final String KEY_SAMPLING_INTERVAL_FOR_GLOBAL_SEARCH_STATS =
            "sampling_interval_for_global_search_stats";
    public static final String KEY_SAMPLING_INTERVAL_FOR_OPTIMIZE_STATS =
            "sampling_interval_for_optimize_stats";
    public static final String KEY_LIMIT_CONFIG_MAX_DOCUMENT_SIZE_BYTES =
            "limit_config_max_document_size_bytes";
    public static final String KEY_LIMIT_CONFIG_MAX_BYTE_LIMIT_BATCH_PUT =
            "limit_config_max_byte_limit_batch_put";
    public static final String KEY_LIMIT_CONFIG_PER_PACKAGE_DOCUMENT_COUNT_LIMIT =
            "limit_config_per_package_document_count_limit";
    public static final String KEY_LIMIT_CONFIG_DOCUMENT_COUNT_LIMIT_START_THRESHOLD =
            "limit_config_document_count_limit_start_threshold";
    public static final String KEY_LIMIT_CONFIG_MAX_SUGGESTION_COUNT =
            "limit_config_max_suggestion_count";
    public static final String KEY_BYTES_OPTIMIZE_THRESHOLD = "bytes_optimize_threshold";
    public static final String KEY_TIME_OPTIMIZE_THRESHOLD_MILLIS = "time_optimize_threshold";
    public static final String KEY_DOC_COUNT_OPTIMIZE_THRESHOLD = "doc_count_optimize_threshold";
    public static final String KEY_MIN_TIME_OPTIMIZE_THRESHOLD_MILLIS =
            "min_time_optimize_threshold";
    public static final String KEY_API_CALL_STATS_LIMIT = "api_call_stats_limit";
    public static final String KEY_DENYLIST = "denylist";
    public static final String KEY_RATE_LIMIT_ENABLED = "rate_limit_enabled";
    public static final String KEY_RATE_LIMIT_TASK_QUEUE_TOTAL_CAPACITY =
            "rate_limit_task_queue_total_capacity";
    public static final String KEY_RATE_LIMIT_TASK_QUEUE_PER_PACKAGE_CAPACITY_PERCENTAGE =
            "rate_limit_task_queue_per_package_capacity_percentage";
    public static final String KEY_RATE_LIMIT_API_COSTS = "rate_limit_api_costs";

    public static final String KEY_ICING_MAX_TOKEN_LENGTH = "icing_max_token_length";
    public static final String KEY_ICING_INDEX_MERGE_SIZE = "icing_index_merge_size";
    public static final String KEY_ICING_DOCUMENT_STORE_NAMESPACE_ID_FINGERPRINT =
            "icing_document_store_namespace_id_fingerprint";
    public static final String KEY_ICING_OPTIMIZE_REBUILD_INDEX_THRESHOLD =
            "icing_optimize_rebuild_index_threshold";
    public static final String KEY_ICING_COMPRESSION_LEVEL = "icing_compression_level";
    public static final String KEY_ICING_USE_READ_ONLY_SEARCH = "icing_use_read_only_search";
    public static final String KEY_ICING_USE_PRE_MAPPING_WITH_FILE_BACKED_VECTOR =
            "icing_use_pre_mapping_with_file_backed_vector";
    public static final String KEY_ICING_USE_PERSISTENT_HASHMAP = "icing_use_persistent_hashmap";
    public static final String KEY_ICING_MAX_PAGE_BYTES_LIMIT = "icing_max_page_bytes_limit";
    public static final String KEY_ICING_INTEGER_INDEX_BUCKET_SPLIT_THRESHOLD =
            "icing_integer_index_bucket_split_threshold";
    public static final String KEY_ICING_LITE_INDEX_SORT_AT_INDEXING =
            "icing_lite_index_sort_at_indexing";
    public static final String KEY_ICING_LITE_INDEX_SORT_SIZE = "icing_lite_index_sort_size";
    public static final String KEY_SHOULD_RETRIEVE_PARENT_INFO = "should_retrieve_parent_info";
    public static final String KEY_USE_NEW_QUALIFIED_ID_JOIN_INDEX =
            "use_new_qualified_id_join_index";
    public static final String KEY_BUILD_PROPERTY_EXISTENCE_METADATA_HITS =
            "build_property_existence_metadata_hits";
    public static final String KEY_APP_FUNCTION_CALL_TIMEOUT_MILLIS =
            "app_function_call_timeout_millis";
    public static final String KEY_FULLY_PERSIST_JOB_INTERVAL = "fully_persist_job_interval";
    public static final String KEY_PERSIST_DELAY = "persist_delay";
    public static final String KEY_MAX_OPEN_BLOB_COUNT = "max_open_blob_count";
    public static final String KEY_ORPHAN_BLOB_TIME_TO_LIVE_MS = "orphan_blob_time_to_live_ms";
    public static final String KEY_ISOLATED_STORAGE_MEMORY_BYTES = "isolated_storage_memory_bytes";
    public static final String KEY_LIGHTWEIGHT_PERSIST_TYPE = "lightweight_persist_type";
    public static final String KEY_ISOLATED_STORAGE_DISABLED = "isolated_storage_disabled";
    public static final String KEY_ISOLATED_STORAGE_MIGRATION_DISABLED =
            "isolated_storage_migration_disabled";
    public static final String KEY_ISOLATED_STORAGE_DELETE_CE_VMS =
            "isolated_storage_delete_ce_vms";

    public static final String KEY_ISOLATED_STORAGE_ENABLE_UNFREEZING_MIGRATION =
            "isolated_storage_enable_unfreezing_migration";
    public static final String KEY_ISOLATED_STORAGE_ENABLE_REVERSE_MIGRATION =
            "isolated_storage_enable_reverse_migration";

    public static final String KEY_COMPRESSION_THRESHOLD_BYTES = "compression_threshold_bytes";
    public static final String KEY_COMPRESSION_MEM_LEVEL = "compression_mem_level";
    public static final String KEY_CHECK_OPTIMIZE_DELAY_MILLIS = "check_optimize_delay_millis";
    public static final String KEY_MAX_BYTES_OPTIMIZE_THRESHOLD = "max_bytes_optimize_threshold";
    public static final String KEY_MAX_DOC_COUNT_OPTIMIZE_THRESHOLD =
            "max_doc_count_optimize_threshold";
    public static final String KEY_EMBEDDING_INDEX_NUM_SHARDS = "embedding_index_num_shards";

    /**
     * This config does not need to be cached in FrameworkServiceAppSearchConfig as it is only
     * accessed statically. AppSearch retrieves this directly from DeviceConfig when needed.
     */
    public static final String KEY_USE_FIXED_EXECUTOR_SERVICE = "use_fixed_executor_service";

    // Array contains all the corresponding keys for the cached values.
    private static final String[] KEYS_TO_ALL_CACHED_VALUES = {
        KEY_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS,
        KEY_SAMPLING_INTERVAL_DEFAULT,
        KEY_SAMPLING_INTERVAL_FOR_BATCH_CALL_STATS,
        KEY_SAMPLING_INTERVAL_FOR_PUT_DOCUMENT_STATS,
        KEY_SAMPLING_INTERVAL_FOR_INITIALIZE_STATS,
        KEY_SAMPLING_INTERVAL_FOR_SEARCH_STATS,
        KEY_SAMPLING_INTERVAL_FOR_GLOBAL_SEARCH_STATS,
        KEY_SAMPLING_INTERVAL_FOR_OPTIMIZE_STATS,
        KEY_LIMIT_CONFIG_MAX_DOCUMENT_SIZE_BYTES,
        KEY_LIMIT_CONFIG_MAX_BYTE_LIMIT_BATCH_PUT,
        KEY_LIMIT_CONFIG_PER_PACKAGE_DOCUMENT_COUNT_LIMIT,
        KEY_LIMIT_CONFIG_DOCUMENT_COUNT_LIMIT_START_THRESHOLD,
        KEY_LIMIT_CONFIG_MAX_SUGGESTION_COUNT,
        KEY_BYTES_OPTIMIZE_THRESHOLD,
        KEY_TIME_OPTIMIZE_THRESHOLD_MILLIS,
        KEY_DOC_COUNT_OPTIMIZE_THRESHOLD,
        KEY_MIN_TIME_OPTIMIZE_THRESHOLD_MILLIS,
        KEY_API_CALL_STATS_LIMIT,
        KEY_DENYLIST,
        KEY_RATE_LIMIT_ENABLED,
        KEY_RATE_LIMIT_TASK_QUEUE_TOTAL_CAPACITY,
        KEY_RATE_LIMIT_TASK_QUEUE_PER_PACKAGE_CAPACITY_PERCENTAGE,
        KEY_RATE_LIMIT_API_COSTS,
        KEY_ICING_MAX_TOKEN_LENGTH,
        KEY_ICING_INDEX_MERGE_SIZE,
        KEY_ICING_DOCUMENT_STORE_NAMESPACE_ID_FINGERPRINT,
        KEY_ICING_OPTIMIZE_REBUILD_INDEX_THRESHOLD,
        KEY_ICING_COMPRESSION_LEVEL,
        KEY_ICING_USE_READ_ONLY_SEARCH,
        KEY_ICING_USE_PRE_MAPPING_WITH_FILE_BACKED_VECTOR,
        KEY_ICING_USE_PERSISTENT_HASHMAP,
        KEY_ICING_MAX_PAGE_BYTES_LIMIT,
        KEY_ICING_INTEGER_INDEX_BUCKET_SPLIT_THRESHOLD,
        KEY_ICING_LITE_INDEX_SORT_AT_INDEXING,
        KEY_ICING_LITE_INDEX_SORT_SIZE,
        KEY_SHOULD_RETRIEVE_PARENT_INFO,
        KEY_USE_NEW_QUALIFIED_ID_JOIN_INDEX,
        KEY_BUILD_PROPERTY_EXISTENCE_METADATA_HITS,
        KEY_APP_FUNCTION_CALL_TIMEOUT_MILLIS,
        KEY_FULLY_PERSIST_JOB_INTERVAL,
        KEY_PERSIST_DELAY,
        KEY_MAX_OPEN_BLOB_COUNT,
        KEY_ORPHAN_BLOB_TIME_TO_LIVE_MS,
        KEY_LIGHTWEIGHT_PERSIST_TYPE,
        KEY_LIGHTWEIGHT_PERSIST_TYPE,
        KEY_COMPRESSION_THRESHOLD_BYTES,
        KEY_USE_FIXED_EXECUTOR_SERVICE,
        KEY_COMPRESSION_MEM_LEVEL,
        KEY_CHECK_OPTIMIZE_DELAY_MILLIS,
        KEY_MAX_BYTES_OPTIMIZE_THRESHOLD,
        KEY_MAX_DOC_COUNT_OPTIMIZE_THRESHOLD,
        KEY_EMBEDDING_INDEX_NUM_SHARDS,
    };

    // Lock needed for all the operations in this class.
    private final Object mLock = new Object();

    /**
     * Bundle to hold all the cached flag values corresponding to {@link
     * FrameworkServiceAppSearchConfig#KEYS_TO_ALL_CACHED_VALUES}.
     */
    @GuardedBy("mLock")
    private final Bundle mBundleLocked = new Bundle();

    @GuardedBy("mLock")
    private Denylist mDenylistLocked = Denylist.EMPTY_INSTANCE;

    @GuardedBy("mLock")
    private AppSearchRateLimitConfig mRateLimitConfigLocked =
            AppSearchRateLimitConfig.create(
                    DEFAULT_RATE_LIMIT_TASK_QUEUE_TOTAL_CAPACITY,
                    DEFAULT_RATE_LIMIT_TASK_QUEUE_PER_PACKAGE_CAPACITY_PERCENTAGE,
                    DEFAULT_RATE_LIMIT_API_COSTS_STRING);

    @GuardedBy("mLock")
    private boolean mIsClosedLocked = false;

    /** Listener to update cached flag values from DeviceConfig. */
    private final OnPropertiesChangedListener mOnDeviceConfigChangedListener =
            properties -> {
                if (!properties.getNamespace().equals(DeviceConfig.NAMESPACE_APPSEARCH)) {
                    return;
                }

                updateCachedValues(properties);
            };

    private final Context mContext;

    private FrameworkServiceAppSearchConfig(@NonNull Context context) {
        mContext = context;
    }

    private final AppOpenEventIndexerConfig mAppOpenEventIndexerConfig =
            new FrameworkAppOpenEventIndexerConfig();

    /**
     * Creates an instance of {@link FrameworkServiceAppSearchConfig}.
     *
     * @param executor used to fetch and cache the flag values from DeviceConfig during creation or
     *     config change.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @NonNull
    public static FrameworkServiceAppSearchConfig create(
            @NonNull Executor executor, @NonNull Context context) {
        Objects.requireNonNull(executor);
        FrameworkServiceAppSearchConfig configManager =
                new FrameworkServiceAppSearchConfig(context);
        configManager.initialize(executor);
        return configManager;
    }

    /**
     * Gets an instance of {@link FrameworkServiceAppSearchConfig} to be used.
     *
     * <p>If no instance has been initialized yet, a new one will be created. Otherwise, the
     * existing instance will be returned.
     */
    @NonNull
    public static FrameworkServiceAppSearchConfig getInstance(
            @NonNull Executor executor, @NonNull Context context) {
        Objects.requireNonNull(executor);
        if (sConfig == null) {
            synchronized (FrameworkServiceAppSearchConfig.class) {
                if (sConfig == null) {
                    sConfig = create(executor, context);
                }
            }
        }
        return sConfig;
    }

    /**
     * Returns whether or not to use a fixed executor service for AppSearch. This config is only
     * queried statically and is therefore retrieved directly from DeviceConfig.
     */
    public static boolean getUseFixedExecutorService() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_USE_FIXED_EXECUTOR_SERVICE,
                DEFAULT_USE_FIXED_EXECUTOR_SERVICE);
    }

    /**
     * Initializes the {@link FrameworkServiceAppSearchConfig}
     *
     * <p>It fetches the custom properties from DeviceConfig if available.
     *
     * @param executor listener would be run on to handle P/H flag change.
     */
    private void initialize(@NonNull Executor executor) {
        executor.execute(
                () -> {
                    // Attach the callback to get updates on those properties.
                    DeviceConfig.addOnPropertiesChangedListener(
                            DeviceConfig.NAMESPACE_APPSEARCH,
                            executor,
                            mOnDeviceConfigChangedListener);

                    DeviceConfig.Properties properties =
                            DeviceConfig.getProperties(
                                    DeviceConfig.NAMESPACE_APPSEARCH, KEYS_TO_ALL_CACHED_VALUES);
                    updateCachedValues(properties);
                });
    }

    // TODO(b/173532925) check this will be called. If we have a singleton instance for this
    //  class, probably we don't need it.
    @Override
    public void close() {
        synchronized (mLock) {
            if (mIsClosedLocked) {
                return;
            }

            DeviceConfig.removeOnPropertiesChangedListener(mOnDeviceConfigChangedListener);
            mIsClosedLocked = true;
        }
    }

    @Override
    public long getCachedMinTimeIntervalBetweenSamplesMillis() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getLong(
                    KEY_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS,
                    DEFAULT_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS);
        }
    }

    @Override
    public int getCachedSamplingIntervalDefault() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(KEY_SAMPLING_INTERVAL_DEFAULT, defaultSamplingInterval());
        }
    }

    @Override
    public int getCachedSamplingIntervalForBatchCallStats() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_SAMPLING_INTERVAL_FOR_BATCH_CALL_STATS, getCachedSamplingIntervalDefault());
        }
    }

    @Override
    public int getCachedSamplingIntervalForPutDocumentStats() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_SAMPLING_INTERVAL_FOR_PUT_DOCUMENT_STATS,
                    getCachedSamplingIntervalDefault());
        }
    }

    @Override
    public int getCachedSamplingIntervalForInitializeStats() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_SAMPLING_INTERVAL_FOR_INITIALIZE_STATS, getCachedSamplingIntervalDefault());
        }
    }

    @Override
    public int getCachedSamplingIntervalForSearchStats() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_SAMPLING_INTERVAL_FOR_SEARCH_STATS, getCachedSamplingIntervalDefault());
        }
    }

    @Override
    public int getCachedSamplingIntervalForGlobalSearchStats() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_SAMPLING_INTERVAL_FOR_GLOBAL_SEARCH_STATS,
                    getCachedSamplingIntervalDefault());
        }
    }

    @Override
    public int getCachedSamplingIntervalForOptimizeStats() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_SAMPLING_INTERVAL_FOR_OPTIMIZE_STATS, getCachedSamplingIntervalDefault());
        }
    }

    @Override
    public int getMaxDocumentSizeBytes() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_LIMIT_CONFIG_MAX_DOCUMENT_SIZE_BYTES,
                    DEFAULT_LIMIT_CONFIG_MAX_DOCUMENT_SIZE_BYTES);
        }
    }

    @Override
    public int getMaxByteLimitForBatchPut() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_LIMIT_CONFIG_MAX_BYTE_LIMIT_BATCH_PUT,
                    DEFAULT_MAX_BYTE_LIMIT_FOR_BATCH_PUT);
        }
    }

    @Override
    public int getPerPackageDocumentCountLimit() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_LIMIT_CONFIG_PER_PACKAGE_DOCUMENT_COUNT_LIMIT,
                    DEFAULT_LIMIT_CONFIG_PER_PACKAGE_DOCUMENT_COUNT_LIMIT);
        }
    }

    @Override
    public int getDocumentCountLimitStartThreshold() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_LIMIT_CONFIG_DOCUMENT_COUNT_LIMIT_START_THRESHOLD,
                    DEFAULT_LIMIT_CONFIG_DOCUMENT_COUNT_LIMIT_START_THRESHOLD);
        }
    }

    @Override
    public int getMaxSuggestionCount() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_LIMIT_CONFIG_MAX_SUGGESTION_COUNT,
                    DEFAULT_LIMIT_CONFIG_MAX_SUGGESTION_COUNT);
        }
    }

    @Override
    public int getMaxOpenBlobCount() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(KEY_MAX_OPEN_BLOB_COUNT, DEFAULT_MAX_OPEN_BLOB_COUNT);
        }
    }

    @Override
    public int getCachedBytesOptimizeThreshold() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_BYTES_OPTIMIZE_THRESHOLD, DEFAULT_BYTES_OPTIMIZE_THRESHOLD);
        }
    }

    @Override
    public int getCachedTimeOptimizeThresholdMs() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_TIME_OPTIMIZE_THRESHOLD_MILLIS, DEFAULT_TIME_OPTIMIZE_THRESHOLD_MILLIS);
        }
    }

    @Override
    public int getCachedDocCountOptimizeThreshold() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_DOC_COUNT_OPTIMIZE_THRESHOLD, DEFAULT_DOC_COUNT_OPTIMIZE_THRESHOLD);
        }
    }

    @Override
    public int getCachedMinTimeOptimizeThresholdMs() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_MIN_TIME_OPTIMIZE_THRESHOLD_MILLIS,
                    defaultMinTimeOptimizeThresholdMillis());
        }
    }

    @Override
    public int getCachedApiCallStatsLimit() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(KEY_API_CALL_STATS_LIMIT, defaultApiCallStatsLimit());
        }
    }

    @Override
    public Denylist getCachedDenylist() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mDenylistLocked;
        }
    }

    @Override
    public int getMaxTokenLength() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_ICING_MAX_TOKEN_LENGTH, IcingOptionsConfig.DEFAULT_MAX_TOKEN_LENGTH);
        }
    }

    @Override
    public int getIndexMergeSize() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_ICING_INDEX_MERGE_SIZE, IcingOptionsConfig.DEFAULT_INDEX_MERGE_SIZE);
        }
    }

    @Override
    public boolean getDocumentStoreNamespaceIdFingerprint() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getBoolean(
                    KEY_ICING_DOCUMENT_STORE_NAMESPACE_ID_FINGERPRINT,
                    IcingOptionsConfig.DEFAULT_DOCUMENT_STORE_NAMESPACE_ID_FINGERPRINT);
        }
    }

    @Override
    public float getOptimizeRebuildIndexThreshold() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getFloat(
                    KEY_ICING_OPTIMIZE_REBUILD_INDEX_THRESHOLD,
                    IcingOptionsConfig.DEFAULT_OPTIMIZE_REBUILD_INDEX_THRESHOLD);
        }
    }

    @Override
    public int getCompressionLevel() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_ICING_COMPRESSION_LEVEL, IcingOptionsConfig.DEFAULT_COMPRESSION_LEVEL);
        }
    }

    @Override
    public boolean getAllowCircularSchemaDefinitions() {
        // TODO(b/282108040) add flag(default on) to cover this feature in case a bug is discovered.
        synchronized (mLock) {
            throwIfClosedLocked();
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
        }
    }

    @Override
    public boolean getUseReadOnlySearch() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getBoolean(
                    KEY_ICING_USE_READ_ONLY_SEARCH, DEFAULT_ICING_CONFIG_USE_READ_ONLY_SEARCH);
        }
    }

    @Override
    public boolean getUsePreMappingWithFileBackedVector() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getBoolean(
                    KEY_ICING_USE_PRE_MAPPING_WITH_FILE_BACKED_VECTOR,
                    IcingOptionsConfig.DEFAULT_USE_PREMAPPING_WITH_FILE_BACKED_VECTOR);
        }
    }

    @Override
    public boolean getUsePersistentHashMap() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getBoolean(
                    KEY_ICING_USE_PERSISTENT_HASHMAP,
                    IcingOptionsConfig.DEFAULT_USE_PERSISTENT_HASH_MAP);
        }
    }

    @Override
    public int getMaxPageBytesLimit() {
        synchronized (mLock) {
            throwIfClosedLocked();
            // TODO: b/389105038 - remove the temporary workaround for binder transaction
            //  limit.
            if (Flags.enableMaxPageBytesLimitInHost()) {
                // Reuse the same page size limit (512 KiB) that we used for isolated storage.
                return mBundleLocked.getInt(
                        KEY_ICING_MAX_PAGE_BYTES_LIMIT,
                        DEFAULT_MAX_PAGE_BYTES_LIMIT_FOR_ISOLATED_STORAGE);
            }
            return mBundleLocked.getInt(
                    KEY_ICING_MAX_PAGE_BYTES_LIMIT,
                    IcingOptionsConfig.DEFAULT_MAX_PAGE_BYTES_LIMIT);
        }
    }

    @Override
    public int getMaxPageBytesLimitForVm() {
        synchronized (mLock) {
            throwIfClosedLocked();
            // TODO: b/389105038 - remove the temporary workaround for binder transaction
            //  limit.
            return mBundleLocked.getInt(
                    KEY_ICING_MAX_PAGE_BYTES_LIMIT,
                    DEFAULT_MAX_PAGE_BYTES_LIMIT_FOR_ISOLATED_STORAGE);
        }
    }

    @Override
    public boolean getCachedRateLimitEnabled() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getBoolean(KEY_RATE_LIMIT_ENABLED, DEFAULT_RATE_LIMIT_ENABLED);
        }
    }

    @Override
    public AppSearchRateLimitConfig getCachedRateLimitConfig() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mRateLimitConfigLocked;
        }
    }

    @Override
    public long getAppFunctionCallTimeoutMillis() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getLong(
                    KEY_APP_FUNCTION_CALL_TIMEOUT_MILLIS, DEFAULT_APP_FUNCTION_CALL_TIMEOUT_MILLIS);
        }
    }

    @Override
    public long getCachedFullyPersistJobIntervalMillis() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getLong(
                    KEY_FULLY_PERSIST_JOB_INTERVAL, DEFAULT_FULLY_PERSIST_JOB_INTERVAL);
        }
    }

    @Override
    public long getCachedPersistDelayMillis() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getLong(KEY_PERSIST_DELAY, defaultPersistDelayMillis());
        }
    }

    @Override
    public long getCachedCheckOptimizeDelayMillis() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getLong(
                    KEY_CHECK_OPTIMIZE_DELAY_MILLIS, defaultCheckOptimizeDelayMillis());
        }
    }

    @Override
    public long getCachedMaxBytesOptimizeThreshold() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getLong(
                    KEY_MAX_BYTES_OPTIMIZE_THRESHOLD, DEFAULT_MAX_BYTES_OPTIMIZE_THRESHOLD);
        }
    }

    @Override
    public long getCachedMaxDocCountOptimizeThreshold() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getLong(
                    KEY_MAX_DOC_COUNT_OPTIMIZE_THRESHOLD, DEFAULT_MAX_DOC_COUNT_OPTIMIZE_THRESHOLD);
        }
    }

    @Override
    public int getIntegerIndexBucketSplitThreshold() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_ICING_INTEGER_INDEX_BUCKET_SPLIT_THRESHOLD,
                    DEFAULT_INTEGER_INDEX_BUCKET_SPLIT_THRESHOLD);
        }
    }

    @Override
    public boolean getLiteIndexSortAtIndexing() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getBoolean(
                    KEY_ICING_LITE_INDEX_SORT_AT_INDEXING, DEFAULT_LITE_INDEX_SORT_AT_INDEXING);
        }
    }

    @Override
    public int getLiteIndexSortSize() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_ICING_LITE_INDEX_SORT_SIZE, DEFAULT_LITE_INDEX_SORT_SIZE);
        }
    }

    @Override
    public boolean getUseNewQualifiedIdJoinIndex() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getBoolean(
                    KEY_USE_NEW_QUALIFIED_ID_JOIN_INDEX, DEFAULT_USE_NEW_QUALIFIED_ID_JOIN_INDEX);
        }
    }

    @Override
    public boolean getBuildPropertyExistenceMetadataHits() {
        // This option is always true in Framework due to trunk stable frozen flags.
        return true;
    }

    @Override
    public long getOrphanBlobTimeToLiveMs() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getLong(
                    KEY_ORPHAN_BLOB_TIME_TO_LIVE_MS, DEFAULT_ORPHAN_BLOB_TIME_TO_LIVE_MS);
        }
    }

    // The absolute path for the ICU data file is not available in Framework.
    // This method is functionally no-op and returns an empty string.
    @Override
    public @NonNull String getIcuDataFileAbsolutePath() {
        return DEFAULT_ICU_DATA_FILE_ABSOLUTE_PATH;
    }

    @Override
    public long getIsolatedStorageMemoryBytes() {
        return DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_ISOLATED_STORAGE_MEMORY_BYTES,
                IsolatedStorageServiceManager.DEFAULT_MEMORY_BYTES);
    }

    @Override
    public boolean getIsolatedStorageDisabled() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_ISOLATED_STORAGE_DISABLED,
                IsolatedStorageServiceManager.DEFAULT_ISOLATED_STORAGE_DISABLED);
    }

    @Override
    public boolean disableIsolatedStorageMigration() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_ISOLATED_STORAGE_MIGRATION_DISABLED,
                IsolatedStorageServiceManager.DEFAULT_ISOLATED_STORAGE_MIGRATION_DISABLED);
    }

    @Override
    public boolean getIsolatedStorageDeleteCeVms() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_ISOLATED_STORAGE_DELETE_CE_VMS,
                IsolatedStorageServiceManager.DEFAULT_ISOLATED_STORAGE_DELETE_CE_VMS);
    }

    @Override
    public boolean getIsolatedStorageEnableUnfreezingMigration() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_ISOLATED_STORAGE_ENABLE_UNFREEZING_MIGRATION,
                IsolatedStorageServiceManager.DEFAULT_ISOLATED_STORAGE_ENABLE_UNFREEZING_MIGRATION);
    }

    @Override
    public boolean getIsolatedStorageEnableReverseMigration() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_ISOLATED_STORAGE_ENABLE_REVERSE_MIGRATION,
                IsolatedStorageServiceManager.DEFAULT_ISOLATED_STORAGE_ENABLE_REVERSE_MIGRATION);
    }

    @Override
    public boolean shouldStoreParentInfoAsSyntheticProperty() {
        // This option is always true in Framework.
        return true;
    }

    @Override
    public boolean shouldRetrieveParentInfo() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getBoolean(
                    KEY_SHOULD_RETRIEVE_PARENT_INFO, DEFAULT_SHOULD_RETRIEVE_PARENT_INFO);
        }
    }

    @Override
    public PersistType.@NonNull Code getLightweightPersistType() {
        synchronized (mLock) {
            throwIfClosedLocked();
            int val =
                    mBundleLocked.getInt(
                            KEY_LIGHTWEIGHT_PERSIST_TYPE,
                            defaultLightweightPersistType().getNumber());
            return PersistType.Code.forNumber(val);
        }
    }

    @Override
    public int getCompressionThresholdBytes() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_COMPRESSION_THRESHOLD_BYTES, DEFAULT_COMPRESSION_THRESHOLD_BYTES);
        }
    }

    @Override
    public int getCompressionMemLevel() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(KEY_COMPRESSION_MEM_LEVEL, defaultCompressionMemLevel());
        }
    }

    @GuardedBy("mLock")
    private void throwIfClosedLocked() {
        if (mIsClosedLocked) {
            throw new IllegalStateException("Trying to use a closed AppSearchConfig instance.");
        }
    }

    @Override
    public int getAppOpenEventIndexerLoggingSamplingRate() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mAppOpenEventIndexerConfig.getLoggingSamplingRate();
        }
    }

    @Override
    public int getCachedApiCallStatsLimitForVm() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_API_CALL_STATS_LIMIT, DEFAULT_ENABLED_API_CALL_STATS_LIMIT);
        }
    }

    @Override
    public int getEmbeddingIndexNumShards() {
        synchronized (mLock) {
            throwIfClosedLocked();
            return mBundleLocked.getInt(
                    KEY_EMBEDDING_INDEX_NUM_SHARDS, DEFAULT_EMBEDDING_INDEX_NUM_SHARDS);
        }
    }

    private void updateCachedValues(DeviceConfig.@NonNull Properties properties) {
        for (String key : properties.getKeyset()) {
            updateCachedValue(key, properties);
        }
        updateDerivedClasses();
    }

    private void updateCachedValue(
            @NonNull String key, DeviceConfig.@NonNull Properties properties) {
        if (properties.getString(key, /* defaultValue= */ null) == null) {
            // Key is missing or value is just null. That is not expected if the key is
            // defined in the configuration.
            //
            // We choose NOT to put the default value in the bundle.
            // Instead, we let the getters handle what default value should be returned.
            //
            // Also we keep the old value in the bundle. So getters can still
            // return last valid value.
            return;
        }

        switch (key) {
            case KEY_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS:
                synchronized (mLock) {
                    mBundleLocked.putLong(
                            key,
                            properties.getLong(
                                    key, DEFAULT_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS));
                }
                break;
            case KEY_SAMPLING_INTERVAL_DEFAULT:
            case KEY_SAMPLING_INTERVAL_FOR_BATCH_CALL_STATS:
            case KEY_SAMPLING_INTERVAL_FOR_PUT_DOCUMENT_STATS:
            case KEY_SAMPLING_INTERVAL_FOR_INITIALIZE_STATS:
            case KEY_SAMPLING_INTERVAL_FOR_SEARCH_STATS:
            case KEY_SAMPLING_INTERVAL_FOR_GLOBAL_SEARCH_STATS:
            case KEY_SAMPLING_INTERVAL_FOR_OPTIMIZE_STATS:
                synchronized (mLock) {
                    mBundleLocked.putInt(key, properties.getInt(key, defaultSamplingInterval()));
                }
                break;
            case KEY_LIMIT_CONFIG_MAX_DOCUMENT_SIZE_BYTES:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key,
                            properties.getInt(key, DEFAULT_LIMIT_CONFIG_MAX_DOCUMENT_SIZE_BYTES));
                }
                break;
            case KEY_LIMIT_CONFIG_MAX_BYTE_LIMIT_BATCH_PUT:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key, properties.getInt(key, DEFAULT_MAX_BYTE_LIMIT_FOR_BATCH_PUT));
                }
                break;
            case KEY_LIMIT_CONFIG_PER_PACKAGE_DOCUMENT_COUNT_LIMIT:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key,
                            properties.getInt(
                                    key, DEFAULT_LIMIT_CONFIG_PER_PACKAGE_DOCUMENT_COUNT_LIMIT));
                }
                break;
            case KEY_LIMIT_CONFIG_DOCUMENT_COUNT_LIMIT_START_THRESHOLD:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key,
                            properties.getInt(
                                    key,
                                    DEFAULT_LIMIT_CONFIG_DOCUMENT_COUNT_LIMIT_START_THRESHOLD));
                }
                break;
            case KEY_LIMIT_CONFIG_MAX_SUGGESTION_COUNT:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key, properties.getInt(key, DEFAULT_LIMIT_CONFIG_MAX_SUGGESTION_COUNT));
                }
                break;
            case KEY_BYTES_OPTIMIZE_THRESHOLD:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key, properties.getInt(key, DEFAULT_BYTES_OPTIMIZE_THRESHOLD));
                }
                break;
            case KEY_TIME_OPTIMIZE_THRESHOLD_MILLIS:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key, properties.getInt(key, DEFAULT_TIME_OPTIMIZE_THRESHOLD_MILLIS));
                }
                break;
            case KEY_DOC_COUNT_OPTIMIZE_THRESHOLD:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key, properties.getInt(key, DEFAULT_DOC_COUNT_OPTIMIZE_THRESHOLD));
                }
                break;
            case KEY_MIN_TIME_OPTIMIZE_THRESHOLD_MILLIS:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key, properties.getInt(key, defaultMinTimeOptimizeThresholdMillis()));
                }
                break;
            case KEY_API_CALL_STATS_LIMIT:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key, properties.getInt(key, defaultApiCallStatsLimit()));
                }
                break;
            case KEY_DENYLIST:
                String denylistString = properties.getString(key, /* defaultValue= */ "");
                Denylist denylist =
                        denylistString.isEmpty()
                                ? Denylist.EMPTY_INSTANCE
                                : Denylist.create(denylistString);
                synchronized (mLock) {
                    mDenylistLocked = denylist;
                }
                break;
            case KEY_RATE_LIMIT_ENABLED:
                synchronized (mLock) {
                    mBundleLocked.putBoolean(
                            key, properties.getBoolean(key, DEFAULT_RATE_LIMIT_ENABLED));
                }
                break;
            case KEY_RATE_LIMIT_TASK_QUEUE_TOTAL_CAPACITY:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key,
                            properties.getInt(key, DEFAULT_RATE_LIMIT_TASK_QUEUE_TOTAL_CAPACITY));
                }
                break;
            case KEY_RATE_LIMIT_TASK_QUEUE_PER_PACKAGE_CAPACITY_PERCENTAGE:
                synchronized (mLock) {
                    mBundleLocked.putFloat(
                            key,
                            properties.getFloat(
                                    key,
                                    DEFAULT_RATE_LIMIT_TASK_QUEUE_PER_PACKAGE_CAPACITY_PERCENTAGE));
                }
                break;
            case KEY_RATE_LIMIT_API_COSTS:
                synchronized (mLock) {
                    mBundleLocked.putString(
                            key, properties.getString(key, DEFAULT_RATE_LIMIT_API_COSTS_STRING));
                }
                break;
            case KEY_ICING_MAX_TOKEN_LENGTH:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key,
                            properties.getInt(key, IcingOptionsConfig.DEFAULT_MAX_TOKEN_LENGTH));
                }
                break;
            case KEY_ICING_INDEX_MERGE_SIZE:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key,
                            properties.getInt(key, IcingOptionsConfig.DEFAULT_INDEX_MERGE_SIZE));
                }
                break;
            case KEY_ICING_DOCUMENT_STORE_NAMESPACE_ID_FINGERPRINT:
                synchronized (mLock) {
                    mBundleLocked.putBoolean(
                            key,
                            properties.getBoolean(
                                    key,
                                    IcingOptionsConfig
                                            .DEFAULT_DOCUMENT_STORE_NAMESPACE_ID_FINGERPRINT));
                }
                break;
            case KEY_ICING_OPTIMIZE_REBUILD_INDEX_THRESHOLD:
                synchronized (mLock) {
                    mBundleLocked.putFloat(
                            key,
                            properties.getFloat(
                                    key,
                                    IcingOptionsConfig.DEFAULT_OPTIMIZE_REBUILD_INDEX_THRESHOLD));
                }
                break;
            case KEY_ICING_COMPRESSION_LEVEL:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key,
                            properties.getInt(key, IcingOptionsConfig.DEFAULT_COMPRESSION_LEVEL));
                }
                break;
            case KEY_ICING_USE_READ_ONLY_SEARCH:
                synchronized (mLock) {
                    mBundleLocked.putBoolean(
                            key,
                            properties.getBoolean(key, DEFAULT_ICING_CONFIG_USE_READ_ONLY_SEARCH));
                }
                break;
            case KEY_ICING_USE_PRE_MAPPING_WITH_FILE_BACKED_VECTOR:
                synchronized (mLock) {
                    mBundleLocked.putBoolean(
                            key,
                            properties.getBoolean(
                                    key,
                                    IcingOptionsConfig
                                            .DEFAULT_USE_PREMAPPING_WITH_FILE_BACKED_VECTOR));
                }
                break;
            case KEY_ICING_USE_PERSISTENT_HASHMAP:
                synchronized (mLock) {
                    mBundleLocked.putBoolean(
                            key,
                            properties.getBoolean(
                                    key, IcingOptionsConfig.DEFAULT_USE_PERSISTENT_HASH_MAP));
                }
                break;
            case KEY_ICING_MAX_PAGE_BYTES_LIMIT:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key,
                            properties.getInt(
                                    key, IcingOptionsConfig.DEFAULT_MAX_PAGE_BYTES_LIMIT));
                }
                break;
            case KEY_ICING_INTEGER_INDEX_BUCKET_SPLIT_THRESHOLD:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key,
                            properties.getInt(
                                    key,
                                    IcingOptionsConfig
                                            .DEFAULT_INTEGER_INDEX_BUCKET_SPLIT_THRESHOLD));
                }
                break;
            case KEY_ICING_LITE_INDEX_SORT_AT_INDEXING:
                synchronized (mLock) {
                    mBundleLocked.putBoolean(
                            key,
                            properties.getBoolean(
                                    key, IcingOptionsConfig.DEFAULT_LITE_INDEX_SORT_AT_INDEXING));
                }
                break;
            case KEY_ICING_LITE_INDEX_SORT_SIZE:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key,
                            properties.getInt(
                                    key, IcingOptionsConfig.DEFAULT_LITE_INDEX_SORT_SIZE));
                }
                break;
            case KEY_SHOULD_RETRIEVE_PARENT_INFO:
                synchronized (mLock) {
                    mBundleLocked.putBoolean(
                            key, properties.getBoolean(key, DEFAULT_SHOULD_RETRIEVE_PARENT_INFO));
                }
                break;
            case KEY_USE_NEW_QUALIFIED_ID_JOIN_INDEX:
                synchronized (mLock) {
                    mBundleLocked.putBoolean(
                            key,
                            properties.getBoolean(key, DEFAULT_USE_NEW_QUALIFIED_ID_JOIN_INDEX));
                }
                break;
            case KEY_APP_FUNCTION_CALL_TIMEOUT_MILLIS:
                synchronized (mLock) {
                    mBundleLocked.putLong(
                            key, properties.getLong(key, DEFAULT_APP_FUNCTION_CALL_TIMEOUT_MILLIS));
                }
                break;
            case KEY_FULLY_PERSIST_JOB_INTERVAL:
                synchronized (mLock) {
                    mBundleLocked.putLong(
                            key, properties.getLong(key, DEFAULT_FULLY_PERSIST_JOB_INTERVAL));
                }
                break;
            case KEY_PERSIST_DELAY:
                synchronized (mLock) {
                    mBundleLocked.putLong(
                            key, properties.getLong(key, defaultPersistDelayMillis()));
                }
                break;
            case KEY_CHECK_OPTIMIZE_DELAY_MILLIS:
                synchronized (mLock) {
                    mBundleLocked.putLong(
                            key, properties.getLong(key, defaultCheckOptimizeDelayMillis()));
                }
                break;
            case KEY_MAX_BYTES_OPTIMIZE_THRESHOLD:
                synchronized (mLock) {
                    mBundleLocked.putLong(
                            key,
                            properties.getLong(key, DEFAULT_MAX_BYTES_OPTIMIZE_THRESHOLD));
                }
                break;
            case KEY_MAX_DOC_COUNT_OPTIMIZE_THRESHOLD:
                synchronized (mLock) {
                    mBundleLocked.putLong(
                            key,
                            properties.getLong(key, DEFAULT_MAX_DOC_COUNT_OPTIMIZE_THRESHOLD));
                }
                break;
            case KEY_ORPHAN_BLOB_TIME_TO_LIVE_MS:
                synchronized (mLock) {
                    mBundleLocked.putLong(
                            key, properties.getLong(key, DEFAULT_ORPHAN_BLOB_TIME_TO_LIVE_MS));
                }
                break;
            case KEY_MAX_OPEN_BLOB_COUNT:
                synchronized (mLock) {
                    mBundleLocked.putInt(key, properties.getInt(key, DEFAULT_MAX_OPEN_BLOB_COUNT));
                }
                break;
            case KEY_LIGHTWEIGHT_PERSIST_TYPE:
                synchronized (mLock) {
                    int val = properties.getInt(key, defaultLightweightPersistType().getNumber());
                    PersistType.Code code = PersistType.Code.forNumber(val);
                    // Confirm that the provided value is actually valid. Otherwise, ignore.
                    if (code != null && code != PersistType.Code.UNKNOWN) {
                        mBundleLocked.putInt(key, val);
                    }
                }
                break;
            case KEY_COMPRESSION_THRESHOLD_BYTES:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key, properties.getInt(key, DEFAULT_COMPRESSION_THRESHOLD_BYTES));
                }
                break;
            case KEY_COMPRESSION_MEM_LEVEL:
                synchronized (mLock) {
                    mBundleLocked.putInt(key, properties.getInt(key, defaultCompressionMemLevel()));
                }
                break;
            case KEY_EMBEDDING_INDEX_NUM_SHARDS:
                synchronized (mLock) {
                    mBundleLocked.putInt(
                            key, properties.getInt(key, DEFAULT_EMBEDDING_INDEX_NUM_SHARDS));
                }
                break;
            case KEY_BUILD_PROPERTY_EXISTENCE_METADATA_HITS:
            // TODO(b/309826655) Set this value properly in main branch
            // fall throw to default since we never turn this feature on in udc-mainline-prod
            default:
                break;
        }
    }

    private void updateDerivedClasses() {
        if (getCachedRateLimitEnabled()) {
            synchronized (mLock) {
                int taskQueueTotalCapacity =
                        mBundleLocked.getInt(
                                KEY_RATE_LIMIT_TASK_QUEUE_TOTAL_CAPACITY,
                                DEFAULT_RATE_LIMIT_TASK_QUEUE_TOTAL_CAPACITY);
                float taskQueuePerPackagePercentage =
                        mBundleLocked.getFloat(
                                KEY_RATE_LIMIT_TASK_QUEUE_PER_PACKAGE_CAPACITY_PERCENTAGE,
                                DEFAULT_RATE_LIMIT_TASK_QUEUE_PER_PACKAGE_CAPACITY_PERCENTAGE);
                String apiCostsString =
                        mBundleLocked.getString(
                                KEY_RATE_LIMIT_API_COSTS, DEFAULT_RATE_LIMIT_API_COSTS_STRING);
                mRateLimitConfigLocked =
                        mRateLimitConfigLocked.rebuildIfNecessary(
                                taskQueueTotalCapacity,
                                taskQueuePerPackagePercentage,
                                apiCostsString);
            }
        }
    }
}
