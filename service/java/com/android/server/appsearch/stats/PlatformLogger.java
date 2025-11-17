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
package com.android.server.appsearch.stats;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.annotation.CanIgnoreReturnValue;
import android.app.appsearch.stats.BaseStats;
import android.app.appsearch.stats.SchemaMigrationStats;
import android.app.appsearch.util.LogUtil;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.AppSearchMetricsProto.AppSearchVmStartAttempts;
import com.android.server.appsearch.InternalAppSearchLogger;
import com.android.server.appsearch.ServiceAppSearchConfig;
import com.android.server.appsearch.appsindexer.AppOpenEventStats;
import com.android.server.appsearch.appsindexer.AppsUpdateStats;
import com.android.server.appsearch.external.localstorage.stats.CallStats;
import com.android.server.appsearch.external.localstorage.stats.ClickStats;
import com.android.server.appsearch.external.localstorage.stats.InitializeStats;
import com.android.server.appsearch.external.localstorage.stats.OptimizeStats;
import com.android.server.appsearch.external.localstorage.stats.PersistToDiskStats;
import com.android.server.appsearch.external.localstorage.stats.PutDocumentStats;
import com.android.server.appsearch.external.localstorage.stats.QueryStats;
import com.android.server.appsearch.external.localstorage.stats.RemoveStats;
import com.android.server.appsearch.external.localstorage.stats.SearchIntentStats;
import com.android.server.appsearch.external.localstorage.stats.SearchSessionStats;
import com.android.server.appsearch.external.localstorage.stats.SearchStats;
import com.android.server.appsearch.external.localstorage.stats.SetSchemaStats;
import com.android.server.appsearch.util.ApiCallRecord;
import com.android.server.appsearch.util.PackageUtil;
import com.android.server.appsearch.util.StatsUtil;

import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Logger Implementation for pushed atoms.
 *
 * <p>This class is thread-safe.
 *
 * @hide
 */
public class PlatformLogger implements InternalAppSearchLogger {
    private static final String TAG = "AppSearchPlatformLogger";

    // Context of the user we're logging for.
    private final Context mUserContext;

    // Manager holding the configuration flags
    private final ServiceAppSearchConfig mConfig;

    private final Object mLock = new Object();

    /**
     * SparseArray to track how many stats we skipped due to {@link
     * ServiceAppSearchConfig#getCachedMinTimeIntervalBetweenSamplesMillis()}.
     *
     * <p>We can have correct extrapolated number by adding those counts back when we log the same
     * type of stats next time. E.g. the true count of an event could be estimated as:
     * SUM(sampling_interval * (num_skipped_sample + 1)) as est_count
     *
     * <p>The key to the SparseArray is {@link BaseStats.CallType}
     */
    @GuardedBy("mLock")
    private final SparseIntArray mSkippedSampleCountLocked = new SparseIntArray();

    /**
     * Map to cache the packageUid for each package.
     *
     * <p>It maps packageName to packageUid.
     *
     * <p>The entry will be removed whenever the app gets uninstalled
     */
    @GuardedBy("mLock")
    private final Map<String, Integer> mPackageUidCacheLocked = new ArrayMap<>();

    /** Elapsed time for last stats logged (to statsd) from boot in millis */
    @GuardedBy("mLock")
    private long mLastPushTimeMillisLocked = 0L;

    /**
     * Timestamp of the last `logStats(@NonNull CallStats stats)` call, used for calculating
     * 'timeSincePreviousRequestMillis'. Updated even if the log is sampled.
     */
    @GuardedBy("mLock")
    private long mLastCallStatsTimestampMillisLocked = 0L;

    /**
     * Record the last n API calls used by dumpsys to print debugging information about the sequence
     * of the API calls, where n is specified by {@link
     * ServiceAppSearchConfig#getCachedApiCallStatsLimit()}.
     */
    @GuardedBy("mLock")
    private ArrayDeque<ApiCallRecord> mLastNCalls = new ArrayDeque<>();

    /** Helper class to hold platform specific stats for statsd. */
    static final class ExtraStats {
        // UID for the calling package of the stats.
        final int mPackageUid;
        // sampling interval for the call type of the stats.
        final int mSamplingInterval;
        // number of samplings skipped before the current one for the same call type.
        final int mSkippedSampleCount;

        ExtraStats(int packageUid, int samplingInterval, int skippedSampleCount) {
            mPackageUid = packageUid;
            mSamplingInterval = samplingInterval;
            mSkippedSampleCount = skippedSampleCount;
        }
    }

    /** Constructor */
    public PlatformLogger(@NonNull Context userContext, @NonNull ServiceAppSearchConfig config) {
        mUserContext = Objects.requireNonNull(userContext);
        mConfig = Objects.requireNonNull(config);
    }

    /** Logs {@link CallStats}. */
    @Override
    public void logStats(@NonNull CallStats stats) {
        Objects.requireNonNull(stats);
        long currentCallReceivedTimestamp = stats.getCallReceivedTimestampMillis();
        long calculatedTimeSincePreviousRequestMillis = -1L;

        synchronized (mLock) {
            LogUtil.piiTrace(TAG, "CallStats=", stats.getTotalLatencyMillis(), stats);
            if (mLastCallStatsTimestampMillisLocked > 0) {
                calculatedTimeSincePreviousRequestMillis =
                        Math.max(
                                currentCallReceivedTimestamp - mLastCallStatsTimestampMillisLocked,
                                -1L);
            }
            boolean enableStatsQueue =
                    getCachedApiCallStatsLimitForFeatures(stats.getEnabledFeatures()) > 0;
            if (!enableStatsQueue) {
                mLastNCalls.clear();
            }
            if (shouldLogForTypeLocked(stats.getCallType(), stats.getEnabledFeatures())) {
                logStatsImplLocked(stats, calculatedTimeSincePreviousRequestMillis);
            }
            if (enableStatsQueue) {
                // avoid add this call stast itself in the n last queue.
                addStatsToQueueLocked(new ApiCallRecord(stats));
            }
            mLastCallStatsTimestampMillisLocked = currentCallReceivedTimestamp;
        }
    }

    /** Logs {@link PutDocumentStats}. */
    @Override
    public void logStats(@NonNull PutDocumentStats stats) {
        Objects.requireNonNull(stats);
        synchronized (mLock) {
            LogUtil.piiTrace(TAG, "PutDocumentStats=", stats.getTotalLatencyMillis(), stats);
            if (shouldLogForTypeLocked(
                    BaseStats.CALL_TYPE_PUT_DOCUMENT, stats.getEnabledFeatures())) {
                logStatsImplLocked(stats);
            }
        }
    }

    @Override
    public void logStats(@NonNull InitializeStats stats) {
        Objects.requireNonNull(stats);
        synchronized (mLock) {
            LogUtil.piiTrace(TAG, "InitializeStats=", stats.getTotalLatencyMillis(), stats);
            if (shouldLogForTypeLocked(
                    BaseStats.CALL_TYPE_INITIALIZE, stats.getEnabledFeatures())) {
                logStatsImplLocked(stats);
            }
        }
    }

    @Override
    public void logStats(@NonNull AppsUpdateStats appsUpdateStats) {
        Objects.requireNonNull(appsUpdateStats);
        synchronized (mLock) {
            LogUtil.piiTrace(TAG, "AppsUpdateStats=", appsUpdateStats.getTotalLatencyMillis(),
                    appsUpdateStats);
            if (shouldLogForTypeLocked(
                    BaseStats.INTERNAL_CALL_TYPE_APPS_INDEXER,
                    BaseStats.NO_FEATURES_ENABLED_BITMASK)) {
                logStatsImplLocked(appsUpdateStats);
            }
        }
    }

    @Override
    public void logStats(@NonNull AppOpenEventStats appOpenEventStats) {
        Objects.requireNonNull(appOpenEventStats);
        synchronized (mLock) {
            LogUtil.piiTrace(
                    TAG,
                    "AppOpenEventStats=",
                    appOpenEventStats.getTotalLatencyMillis(),
                    appOpenEventStats);
            if (shouldLogForTypeLocked(
                    BaseStats.INTERNAL_CALL_TYPE_APP_OPEN_EVENT_INDEXER,
                    BaseStats.NO_FEATURES_ENABLED_BITMASK)) {
                logStatsImplLocked(appOpenEventStats);
            }
        }
    }

    @Override
    public void logStats(@NonNull QueryStats stats) {
        Objects.requireNonNull(stats);
        synchronized (mLock) {
            LogUtil.piiTrace(TAG, "QueryStats=", stats.getTotalLatencyMillis(), stats);
            if (shouldLogForTypeLocked(BaseStats.CALL_TYPE_SEARCH, stats.getEnabledFeatures())) {
                logStatsImplLocked(stats);
            }
        }
    }

    @Override
    public void logStats(@NonNull RemoveStats stats) {
        Objects.requireNonNull(stats);
        synchronized (mLock) {
            LogUtil.piiTrace(TAG, "RemoveStats=", stats.getTotalLatencyMillis(), stats);
            if (shouldLogForTypeLocked(
                    BaseStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_ID, stats.getEnabledFeatures())) {
                logStatsImplLocked(stats);
            }
        }
    }

    @Override
    public void logStats(@NonNull OptimizeStats stats) {
        Objects.requireNonNull(stats);
        synchronized (mLock) {
            LogUtil.piiTrace(TAG, "OptimizeStats=", stats.getTotalLatencyMillis(), stats);
            if (getCachedApiCallStatsLimitForFeatures(stats.getEnabledFeatures()) > 0) {
                // Unlike most other API calls, Optimize does not produce a CallStats, so we
                // record OptimizeStats in the queue.
                addStatsToQueueLocked(new ApiCallRecord(stats));
            } else {
                mLastNCalls.clear();
            }
            if (shouldLogForTypeLocked(BaseStats.CALL_TYPE_OPTIMIZE, stats.getEnabledFeatures())) {
                logStatsImplLocked(stats);
            }
        }
    }

    @Override
    public void logStats(@NonNull SetSchemaStats stats) {
        Objects.requireNonNull(stats);
        synchronized (mLock) {
            LogUtil.piiTrace(TAG, "SetSchemaStats=", stats.getTotalLatencyMillis(), stats);
            if (shouldLogForTypeLocked(
                    BaseStats.CALL_TYPE_SET_SCHEMA, stats.getEnabledFeatures())) {
                logStatsImplLocked(stats);
            }
        }
    }

    @Override
    public void logStats(@NonNull SchemaMigrationStats stats) {
        Objects.requireNonNull(stats);
        synchronized (mLock) {
            LogUtil.piiTrace(TAG, "SchemaMigrationStats=", stats.getTotalLatencyMillis(), stats);
            if (shouldLogForTypeLocked(
                    BaseStats.CALL_TYPE_SCHEMA_MIGRATION, stats.getEnabledFeatures())) {
                logStatsImplLocked(stats);
            }
        }
    }

    @Override
    public void logStats(@NonNull List<SearchSessionStats> searchSessionsStats) {
        Objects.requireNonNull(searchSessionsStats);
        if (searchSessionsStats.isEmpty()) {
            return;
        }

        synchronized (mLock) {
            // TODO(b/173532925): apply sampling if necessary
            logStatsImplLocked(searchSessionsStats);
        }
    }

    @Override
    public void logStats(@NonNull VmInitializationStats stats) {
        Objects.requireNonNull(stats);
        synchronized (mLock) {
            LogUtil.piiTrace(TAG, "VmInitializationStats=", stats.getVmInitType(), stats);
            // ignore close exception
            AppSearchStatsLog.write(
                    AppSearchStatsLog.APP_SEARCH_VM_INITIALIZATION_STATS_REPORTED,
                    stats.getVmInitType(),
                    getSerializedVmStartAttempts(stats.getVmStartAttemptsStats()));
        }
    }

    @Override
    public void logStats(@NonNull PersistToDiskStats stats) {
        Objects.requireNonNull(stats);
        synchronized (mLock) {
            LogUtil.piiTrace(TAG, "PersistToDiskStats=", stats.getTotalLatencyMillis(), stats);
            if (shouldLogForTypeLocked(BaseStats.CALL_TYPE_FLUSH, stats.getEnabledFeatures())) {
                logStatsImplLocked(stats);
            }
        }
    }

    @Override
    public void removeCacheForPackage(@NonNull String packageName) {
        removeCachedUidForPackage(packageName);
    }

    /**
     * Removes cached UID for package.
     *
     * @return removed UID for the package, or {@code INVALID_UID} if package was not previously
     *     cached.
     */
    @CanIgnoreReturnValue
    @VisibleForTesting
    int removeCachedUidForPackage(@NonNull String packageName) {
        // TODO(b/173532925) This needs to be called when we get PACKAGE_REMOVED intent
        Objects.requireNonNull(packageName);
        synchronized (mLock) {
            Integer uid = mPackageUidCacheLocked.remove(packageName);
            return uid != null ? uid : Process.INVALID_UID;
        }
    }

    /** Return a copy of the recorded {@link ApiCallRecord}. */
    @Override
    @NonNull
    public List<ApiCallRecord> getLastCalledApis() {
        synchronized (mLock) {
            trimExcessStatsQueueLocked();
            return new ArrayList<>(mLastNCalls);
        }
    }

    /**
     * Helper function to convert a list of {@link VmStartAttemptStats} to {@link
     * AppSearchVmStartAttempts} and serialize to a byte array.
     *
     * @param vmStartAttemptsStats a list of {@link VmStartAttemptStats}.
     * @return byteArray of the converted and serialized {@link AppSearchVmStartAttempts}.
     */
    private static byte[] getSerializedVmStartAttempts(
            @NonNull List<VmStartAttemptStats> vmStartAttemptsStats) {
        AppSearchVmStartAttempts.Builder builder = AppSearchVmStartAttempts.newBuilder();
        for (int i = 0; i < vmStartAttemptsStats.size(); ++i) {
            VmStartAttemptStats stats = vmStartAttemptsStats.get(i);
            builder.addStats(
                    AppSearchVmStartAttempts.Stats.newBuilder()
                            .setStatusCode(
                                    AppSearchVmStartAttempts.Stats.Status.Code.forNumber(
                                            stats.getStatusCode()))
                            .setLatencyMillis(stats.getLatencyMillis()));
        }
        return builder.build().toByteArray();
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(
            @NonNull CallStats stats, long timeSincePreviousRequestMillis) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        ExtraStats extraStats = createExtraStatsLocked(
                stats.getPackageName(), stats.getCallType(), stats.getEnabledFeatures());
        String database = stats.getDatabase();
        try {
            // The num_reported_calls field in AppSearchPutDocumentStatsReported is always set to 1.
            // This is so that we can use one single sum value metrics to compute the total
            // estimated call count based on the formula defined in num_skipped_sample's field doc
            // (see atoms.proto). For example, if a device logged 3 call stats atoms for a call
            // type, and the numbers of skipped samples are 5, 0, 7, respectively, and the sampling
            // interval is 10, the total estimated calls are 10*(5+1) + 10*(0+1) + 10*(7+1) = 150.
            // In the sum value metrics reported by the device, we'll see 12 (=5+0+7) as the sum of
            // num_skipped_sample and 3 (=1+1+1) as the sum of num_reported_calls, and the total
            // will be 10*12 + 10*3 = 150 for that device's reported value.
            final int numReportedCalls = 1;

            List<ApiCallRecord> lastNCalls = new ArrayList<>();
            long callReceivedTimestampMillis = stats.getCallReceivedTimestampMillis();
            for (ApiCallRecord apiCallRecord : mLastNCalls) {
                // Only add the previous API calls that finished after the current API call received
                if (apiCallRecord.getTimeMillis() + apiCallRecord.getTotalLatencyMillis()
                        > callReceivedTimestampMillis) {
                    lastNCalls.add(apiCallRecord);
                }
            }

            long[] lastNTimeMillis = new long[lastNCalls.size()];
            int[] lastNCallTypes = new int[lastNCalls.size()];
            int[] lastNUids = new int[lastNCalls.size()];
            int[] lastNDatabases = new int[lastNCalls.size()];
            int[] lastNStatusCodes = new int[lastNCalls.size()];
            int[] lastNTotalLatencyMillis = new int[lastNCalls.size()];
            int[] lastNOnExecutorLatencyMillis = new int[lastNCalls.size()];
            for (int i = 0; i < lastNCalls.size(); i++) {
                ApiCallRecord apiCallRecord = lastNCalls.get(i);
                lastNTimeMillis[i] = apiCallRecord.getTimeMillis();
                lastNCallTypes[i] = apiCallRecord.getCallType();
                lastNUids[i] = getPackageUidAsUserLocked(apiCallRecord.getPackageName());
                lastNDatabases[i] = StatsUtil.calculateHashCodeMd5(apiCallRecord.getDatabaseName());
                lastNStatusCodes[i] = apiCallRecord.getStatusCode();
                lastNTotalLatencyMillis[i] = apiCallRecord.getTotalLatencyMillis();
                lastNOnExecutorLatencyMillis[i] = apiCallRecord.getOnExecutorLatencyMillis();
            }

            int hashCodeForDatabase = StatsUtil.calculateHashCodeMd5(database);
            AppSearchStatsLog.write(
                    AppSearchStatsLog.APP_SEARCH_CALL_STATS_REPORTED,
                    extraStats.mSamplingInterval, // 1
                    extraStats.mSkippedSampleCount, // 2
                    extraStats.mPackageUid, // 3
                    hashCodeForDatabase, // 4
                    stats.getStatusCode(), // 5
                    stats.getTotalLatencyMillis(), // 6
                    stats.getCallType(), // 7
                    stats.getEstimatedBinderLatencyMillis(), // 8
                    stats.getNumOperationsSucceeded(), // 9
                    stats.getNumOperationsFailed(), // 10
                    numReportedCalls, // 11
                    stats.getEnabledFeatures(), // 12
                    timeSincePreviousRequestMillis, // 13
                    stats.getExecutorAcquisitionLatencyMillis(), // 14
                    lastNTimeMillis, // 15
                    lastNCallTypes, // 16
                    lastNUids, // 17
                    lastNDatabases, // 18
                    lastNStatusCodes, // 19
                    lastNTotalLatencyMillis, // 20
                    lastNOnExecutorLatencyMillis, // 21
                    stats.getLastBlockingOperation(), // 22
                    stats.getLastBlockingOperationLatencyMillis(), // 23
                    stats.getJavaLockAcquisitionLatencyMillis(), // 24
                    stats.getGetVmLatencyMillis(), // 25
                    stats.getNumIcingCalls(), // 26
                    stats.getUnblockedAppSearchLatencyMillis()); // 27
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            // TODO(b/184204720) report hashing error to statsd
            //  We need to set a special value(e.g. 0xFFFFFFFF) for the hashing of the database,
            //  so in the dashboard we know there is some error for hashing.
            //
            // Something is wrong while calculating the hash code for database
            // this shouldn't happen since we always use "MD5" and "UTF-8"
            if (database != null) {
                Log.e(TAG, "Error calculating hash code for database " + database, e);
            }
        }
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull SetSchemaStats stats) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        ExtraStats extraStats =
                createExtraStatsLocked(
                        stats.getPackageName(),
                        BaseStats.CALL_TYPE_SET_SCHEMA,
                        stats.getEnabledFeatures());
        String database = stats.getDatabase();
        try {
            int hashCodeForDatabase = StatsUtil.calculateHashCodeMd5(database);
            // ignore close exception
            AppSearchStatsLog.write(
                    AppSearchStatsLog.APP_SEARCH_SET_SCHEMA_STATS_REPORTED,
                    extraStats.mSamplingInterval, // 1
                    extraStats.mSkippedSampleCount, // 2
                    extraStats.mPackageUid, // 3
                    hashCodeForDatabase, // 4
                    stats.getStatusCode(), // 5
                    stats.getTotalLatencyMillis(), // 6
                    stats.getNewTypeCount(), // 7
                    stats.getDeletedTypeCount(), // 8
                    stats.getCompatibleTypeChangeCount(), // 9
                    stats.getIndexIncompatibleTypeChangeCount(), // 10
                    stats.getBackwardsIncompatibleTypeChangeCount(), // 11
                    stats.getVerifyIncomingCallLatencyMillis(), // 12
                    stats.getExecutorAcquisitionLatencyMillis(), // 13
                    stats.getRebuildFromBundleLatencyMillis(), // 14
                    stats.getJavaLockAcquisitionLatencyMillis(), // 15
                    stats.getRewriteSchemaLatencyMillis(), // 16
                    stats.getTotalNativeLatencyMillis(), // 17
                    stats.getVisibilitySettingLatencyMillis(), // 18
                    stats.getDispatchChangeNotificationsLatencyMillis(), // 19
                    stats.getOptimizeLatencyMillis(), // 20
                    stats.isPackageObserved(), // 21
                    stats.getGetOldSchemaLatencyMillis(), // 22
                    stats.getGetObserverLatencyMillis(), // 23
                    stats.getPreparingChangeNotificationLatencyMillis(), // 24
                    stats.getSchemaMigrationCallType(), // 25
                    stats.getEnabledFeatures(), // 26
                    stats.getLastBlockingOperation(), // 27
                    stats.getLastBlockingOperationLatencyMillis(), // 28
                    stats.getGetVmLatencyMillis(), // 29
                    stats.getUnblockedAppSearchLatencyMillis(), // 30
                    stats.getJoinIndexIncompatibleTypeChangeCount(), // 31
                    stats.getScorablePropertyIncompatibleTypeChangeCount(), // 32
                    stats.getDeletedDocumentCount(), // 33
                    stats.isTermIndexRestored(), // 34
                    stats.isIntegerIndexRestored(), // 35
                    stats.isEmbeddingIndexRestored(), // 36
                    stats.isQualifiedIdJoinIndexRestored(), // 37
                    stats.getNativeSchemaStoreSetSchemaLatencyMillis(), // 38
                    stats.getNativeDocumentStoreUpdateSchemaLatencyMillis(), // 39
                    stats.getNativeDocumentStoreOptimizedUpdateSchemaLatencyMillis(), // 40
                    stats.getNativeIndexRestorationLatencyMillis(), // 41
                    stats.getNativeScorablePropertyCacheRegenerationLatencyMillis(), // 42
                    stats.getSkippedIcingInteraction(), //43
                    stats.getNumIcingCalls()); // 44
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            // TODO(b/184204720) report hashing error to statsd
            //  We need to set a special value(e.g. 0xFFFFFFFF) for the hashing of the database,
            //  so in the dashboard we know there is some error for hashing.
            //
            // Something is wrong while calculating the hash code for database
            // this shouldn't happen since we always use "MD5" and "UTF-8"
            if (database != null) {
                Log.e(TAG, "Error calculating hash code for database " + database, e);
            }
        }
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull RemoveStats stats) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        ExtraStats extraStats =
                createExtraStatsLocked(
                        stats.getPackageName(),
                        BaseStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_ID,
                        stats.getEnabledFeatures());
        String database = stats.getDatabase();
        try {
            int hashCodeForDatabase = StatsUtil.calculateHashCodeMd5(database);
            // ignore close exception
            AppSearchStatsLog.write(
                    AppSearchStatsLog.APP_SEARCH_REMOVE_STATS_REPORTED,
                    extraStats.mSamplingInterval, // 1
                    extraStats.mSkippedSampleCount, // 2
                    extraStats.mPackageUid, // 3
                    hashCodeForDatabase, // 4
                    stats.getStatusCode(), // 5
                    stats.getTotalLatencyMillis(), // 6
                    stats.getNativeLatencyMillis(), // 7
                    stats.getDeleteType(), // 8
                    stats.getDeletedDocumentCount(), // 9
                    stats.getEnabledFeatures(), // 10
                    stats.getQueryLength(), // 11
                    stats.getNumTerms(), // 12
                    stats.getNumNamespacesFiltered(), // 13
                    stats.getNumSchemaTypesFiltered(), // 14
                    stats.getParseQueryLatencyMillis(), // 15
                    stats.getDocumentRemovalLatencyMillis(), // 16
                    stats.getLastBlockingOperation(), // 17
                    stats.getLastBlockingOperationLatencyMillis(), // 18
                    stats.getJavaLockAcquisitionLatencyMillis(), // 19
                    stats.getGetVmLatencyMillis(), // 20
                    stats.getUnblockedAppSearchLatencyMillis(), // 21
                    stats.getNumIcingCalls()); // 22
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            // TODO(b/184204720) report hashing error to statsd
            //  We need to set a special value(e.g. 0xFFFFFFFF) for the hashing of the database,
            //  so in the dashboard we know there is some error for hashing.
            //
            // Something is wrong while calculating the hash code for database
            // this shouldn't happen since we always use "MD5" and "UTF-8"
            if (database != null) {
                Log.e(TAG, "Error calculating hash code for database " + database, e);
            }
        }
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull SchemaMigrationStats stats) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        ExtraStats extraStats =
                createExtraStatsLocked(
                        stats.getPackageName(),
                        BaseStats.CALL_TYPE_SCHEMA_MIGRATION,
                        stats.getEnabledFeatures());
        String database = stats.getDatabase();
        try {
            int hashCodeForDatabase = StatsUtil.calculateHashCodeMd5(database);
            // ignore close exception
            AppSearchStatsLog.write(
                    AppSearchStatsLog.APP_SEARCH_SCHEMA_MIGRATION_STATS_REPORTED,
                    extraStats.mSamplingInterval,
                    extraStats.mSkippedSampleCount,
                    extraStats.mPackageUid,
                    hashCodeForDatabase,
                    stats.getStatusCode(),
                    stats.getTotalLatencyMillis(),
                    stats.getGetSchemaLatencyMillis(),
                    stats.getQueryAndTransformLatencyMillis(),
                    stats.getFirstSetSchemaLatencyMillis(),
                    stats.getSecondSetSchemaLatencyMillis(),
                    stats.getSaveDocumentLatencyMillis(),
                    stats.getTotalNeedMigratedDocumentCount(),
                    stats.getTotalSuccessMigratedDocumentCount(),
                    stats.getMigrationFailureCount(),
                    stats.getEnabledFeatures());
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            // TODO(b/184204720) report hashing error to statsd
            //  We need to set a special value(e.g. 0xFFFFFFFF) for the hashing of the database,
            //  so in the dashboard we know there is some error for hashing.
            //
            // Something is wrong while calculating the hash code for database
            // this shouldn't happen since we always use "MD5" and "UTF-8"
            if (database != null) {
                Log.e(TAG, "Error calculating hash code for database " + database, e);
            }
        }
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull PutDocumentStats stats) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        ExtraStats extraStats =
                createExtraStatsLocked(
                        stats.getPackageName(),
                        BaseStats.CALL_TYPE_PUT_DOCUMENT,
                        stats.getEnabledFeatures());
        String database = stats.getDatabase();
        try {
            int hashCodeForDatabase = StatsUtil.calculateHashCodeMd5(database);
            AppSearchStatsLog.write(
                    AppSearchStatsLog.APP_SEARCH_PUT_DOCUMENT_STATS_REPORTED,
                    extraStats.mSamplingInterval, // 1
                    extraStats.mSkippedSampleCount, // 2
                    extraStats.mPackageUid, // 3
                    hashCodeForDatabase, // 4
                    stats.getStatusCode(), // 5
                    stats.getTotalLatencyMillis(), // 6
                    stats.getGenerateDocumentProtoLatencyMillis(), // 7
                    stats.getRewriteDocumentTypesLatencyMillis(), // 8
                    stats.getNativeLatencyMillis(), // 9
                    stats.getNativeDocumentStoreLatencyMillis(), // 10
                    stats.getNativeIndexLatencyMillis(), // 11
                    stats.getNativeIndexMergeLatencyMillis(), // 12
                    stats.getNativeDocumentSizeBytes(), // 13
                    stats.getNativeNumTokensIndexed(), // 14
                    /* nativeExceededMaxNumTokens= */ false /* Deprecated and removed */, // 15
                    stats.getEnabledFeatures(), // 16
                    stats.getMetadataTermIndexLatencyMillis(), // 17
                    stats.getEmbeddingIndexLatencyMillis(), // 18
                    stats.getLastBlockingOperation(), // 19
                    stats.getLastBlockingOperationLatencyMillis(), // 20
                    stats.getJavaLockAcquisitionLatencyMillis(), // 21
                    stats.getGetVmLatencyMillis(), // 22
                    stats.getUnblockedAppSearchLatencyMillis(), // 23
                    stats.getNumIcingCalls());  // 24
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            // TODO(b/184204720) report hashing error to statsd
            //  We need to set a special value(e.g. 0xFFFFFFFF) for the hashing of the database,
            //  so in the dashboard we know there is some error for hashing.
            //
            // Something is wrong while calculating the hash code for database
            // this shouldn't happen since we always use "MD5" and "UTF-8"
            if (database != null) {
                Log.e(TAG, "Error calculating hash code for database " + database, e);
            }
        }
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull QueryStats queryStats) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        ExtraStats extraStats =
                createExtraStatsLocked(
                        queryStats.getPackageName(),
                        BaseStats.CALL_TYPE_SEARCH,
                        queryStats.getEnabledFeatures());
        String database = queryStats.getDatabase();
        try {
            int hashCodeForDatabase = StatsUtil.calculateHashCodeMd5(database);
            int hashCodeForSearchSourceLogTag =
                    StatsUtil.calculateHashCodeMd5(queryStats.getSearchSourceLogTag());
            SearchStats parentStats = queryStats.getParentSearchStats();
            SearchStats childStats = queryStats.getChildSearchStats();
            AppSearchStatsLog.write(
                    AppSearchStatsLog.APP_SEARCH_QUERY_STATS_REPORTED,
                    extraStats.mSamplingInterval, // 1
                    extraStats.mSkippedSampleCount, // 2
                    extraStats.mPackageUid, // 3
                    hashCodeForDatabase, // 4
                    queryStats.getStatusCode(), // 5
                    queryStats.getTotalLatencyMillis(), // 6
                    queryStats.getRewriteSearchSpecLatencyMillis(), // 7
                    queryStats.getRewriteSearchResultLatencyMillis(), // 8
                    queryStats.getVisibilityScope(), // 9
                    queryStats.getNativeLatencyMillis(), // 10
                    parentStats == null ? 0 : parentStats.getNativeTermCount(), // 11
                    parentStats == null ? 0 : parentStats.getNativeQueryLength(), // 12
                    parentStats == null ? 0 : parentStats.getNativeFilteredNamespaceCount(), // 13
                    parentStats == null ? 0 : parentStats.getNativeFilteredSchemaTypeCount(), // 14
                    queryStats.getRequestedPageSize(), // 15
                    queryStats.getCurrentPageReturnedResultCount(), // 16
                    queryStats.isFirstPage(), // 17
                    parentStats == null ? 0 : parentStats.getNativeParseQueryLatencyMillis(), // 18
                    parentStats == null ? 0 : parentStats.getNativeRankingStrategy(), // 19
                    parentStats == null ? 0 : parentStats.getNativeScoredDocumentCount(), // 20
                    parentStats == null ? 0 : parentStats.getNativeScoringLatencyMillis(), // 21
                    queryStats.getRankingLatencyMillis(), // 22
                    queryStats.getDocumentRetrievingLatencyMillis(), // 23
                    queryStats.getResultWithSnippetsCount(), // 24
                    queryStats.getJavaLockAcquisitionLatencyMillis(), // 25
                    queryStats.getAclCheckLatencyMillis(), // 26
                    queryStats.getNativeLockAcquisitionLatencyMillis(), // 27
                    queryStats.getJavaToNativeJniLatencyMillis(), // 28
                    queryStats.getNativeToJavaJniLatencyMillis(), // 29
                    queryStats.getJoinType(), // 30
                    queryStats.getNumJoinedResultsCurrentPage(), // 31
                    queryStats.getJoinLatencyMillis(), // 32
                    hashCodeForSearchSourceLogTag, // 33
                    queryStats.getEnabledFeatures(), // 34
                    parentStats == null ? false : parentStats.isNativeNumericQuery(), // 35
                    parentStats == null ? 0 : parentStats.getNativeNumFetchedHitsLiteIndex(), // 36
                    parentStats == null ? 0 : parentStats.getNativeNumFetchedHitsMainIndex(), // 37
                    parentStats == null ? 0 : parentStats.getNativeNumFetchedHitsIntegerIndex(),
                    // 38
                    parentStats == null
                            ? 0
                            : parentStats.getNativeQueryProcessorLexerExtractTokenLatencyMillis(),
                    // 39
                    parentStats == null
                            ? 0
                            : parentStats.getNativeQueryProcessorParserConsumeQueryLatencyMillis(),
                    // 40
                    parentStats == null
                            ? 0
                            : parentStats.getNativeQueryProcessorQueryVisitorLatencyMillis(), // 41
                    childStats == null ? 0 : childStats.getNativeQueryLength(), // 42
                    childStats == null ? 0 : childStats.getNativeTermCount(), // 43
                    childStats == null ? 0 : childStats.getNativeFilteredNamespaceCount(), // 44
                    childStats == null ? 0 : childStats.getNativeFilteredSchemaTypeCount(), // 45
                    childStats == null ? 0 : childStats.getNativeRankingStrategy(), // 46
                    childStats == null ? 0 : childStats.getNativeScoredDocumentCount(), // 47
                    childStats == null ? 0 : childStats.getNativeParseQueryLatencyMillis(), // 48
                    childStats == null ? 0 : childStats.getNativeScoringLatencyMillis(), // 49
                    childStats == null ? false : childStats.isNativeNumericQuery(), // 50
                    childStats == null ? 0 : childStats.getNativeNumFetchedHitsLiteIndex(), // 51
                    childStats == null ? 0 : childStats.getNativeNumFetchedHitsMainIndex(), // 52
                    childStats == null ? 0 : childStats.getNativeNumFetchedHitsIntegerIndex(), // 53
                    childStats == null
                            ? 0
                            : childStats.getNativeQueryProcessorLexerExtractTokenLatencyMillis(),
                    // 54
                    childStats == null
                            ? 0
                            : childStats.getNativeQueryProcessorParserConsumeQueryLatencyMillis(),
                    // 55
                    childStats == null
                            ? 0
                            : childStats.getNativeQueryProcessorQueryVisitorLatencyMillis(), // 56
                    queryStats.getLiteIndexHitBufferByteSize(), // 57
                    queryStats.getLiteIndexHitBufferUnsortedByteSize(), // 58
                    queryStats.getPageTokenType(), // 59
                    queryStats.getNumResultStatesEvicted(), // 60
                    queryStats.getLastBlockingOperation(), // 61
                    queryStats.getLastBlockingOperationLatencyMillis(), // 62
                    queryStats.getGetVmLatencyMillis(), // 63
                    queryStats.getFirstNativeCallLatencyMillis(), // 64
                    queryStats.getAdditionalPagesReturnedResultCount(), // 65
                    queryStats.getAdditionalPageCount(), // 66
                    queryStats.getAdditionalPageRetrievalLatencyMillis(), // 67
                    queryStats.getUnblockedAppSearchLatencyMillis(), // 68
                    queryStats.getNumIcingCalls()); // 69
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            // TODO(b/184204720) report hashing error to statsd
            //  We need to set a special value(e.g. 0xFFFFFFFF) for the hashing of the database,
            //  so in the dashboard we know there is some error for hashing.
            //
            // Something is wrong while calculating the hash code for database
            // this shouldn't happen since we always use "MD5" and "UTF-8"
            if (database != null) {
                Log.e(TAG, "Error calculating hash code for database " + database, e);
            }
        }
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull InitializeStats stats) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        ExtraStats extraStats =
                createExtraStatsLocked(
                        /* packageName= */ null,
                        BaseStats.CALL_TYPE_INITIALIZE,
                        stats.getEnabledFeatures());
        AppSearchStatsLog.write(
                AppSearchStatsLog.APP_SEARCH_INITIALIZE_STATS_REPORTED,
                extraStats.mSamplingInterval, // 1
                extraStats.mSkippedSampleCount, // 2
                extraStats.mPackageUid, // 3
                stats.getStatusCode(), // 4
                stats.getTotalLatencyMillis(), // 5
                stats.hasDeSync(), // 6
                stats.getPrepareSchemaAndNamespacesLatencyMillis(), // 7
                stats.getPrepareVisibilityStoreLatencyMillis(), // 8
                stats.getNativeLatencyMillis(), // 9
                stats.getNativeDocumentStoreRecoveryCause(), // 10
                stats.getNativeIndexRestorationCause(), // 11
                stats.getNativeSchemaStoreRecoveryCause(), // 12
                stats.getNativeDocumentStoreRecoveryLatencyMillis(), // 13
                stats.getNativeIndexRestorationLatencyMillis(), // 14
                stats.getNativeSchemaStoreRecoveryLatencyMillis(), // 15
                stats.getNativeDocumentStoreDataStatus(), // 16
                stats.getNativeDocumentCount(), // 17
                stats.getNativeSchemaTypeCount(), // 18
                stats.hasReset(), // 19
                stats.getResetStatusCode(), // 20
                stats.getEnabledFeatures(), // 21
                stats.getNativeNumPreviousInitFailures(), // 22
                stats.getNativeIntegerIndexRestorationCause(), // 23
                stats.getNativeQualifiedIdJoinIndexRestorationCause(), // 24
                stats.getNativeEmbeddingIndexRestorationCause(), // 25
                stats.getNativeInitializeIcuDataStatusCode(), // 26
                stats.getNativeNumFailedReindexedDocuments(), // 27
                stats.getJavaLockAcquisitionLatencyMillis(), // 28
                stats.getGetVmLatencyMillis(), // 29
                stats.getNumIcingCalls()); // 30
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull OptimizeStats stats) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        ExtraStats extraStats =
                createExtraStatsLocked(
                        /* packageName= */ null,
                        BaseStats.CALL_TYPE_OPTIMIZE,
                        stats.getEnabledFeatures());
        AppSearchStatsLog.write(
                AppSearchStatsLog.APP_SEARCH_OPTIMIZE_STATS_REPORTED,
                extraStats.mSamplingInterval, // 1
                extraStats.mSkippedSampleCount, // 2
                stats.getStatusCode(), // 3
                stats.getTotalLatencyMillis(), // 4
                stats.getNativeLatencyMillis(), // 5
                stats.getDocumentStoreOptimizeLatencyMillis(), // 6
                stats.getIndexRestorationLatencyMillis(), // 7
                stats.getOriginalDocumentCount(), // 8
                stats.getDeletedDocumentCount(), // 9
                stats.getExpiredDocumentCount(), // 10
                stats.getStorageSizeBeforeBytes(), // 11
                stats.getStorageSizeAfterBytes(), // 12
                stats.getTimeSinceLastOptimizeMillis(), // 13
                stats.getEnabledFeatures(), // 14
                stats.getIndexRestorationMode(), // 15
                stats.getNumOriginalNamespaces(), // 16
                stats.getNumDeletedNamespaces(), // 17
                stats.getLastBlockingOperation(), // 18
                stats.getLastBlockingOperationLatencyMillis(), // 19
                stats.getJavaLockAcquisitionLatencyMillis(), // 20
                stats.getGetVmLatencyMillis(), // 21
                stats.getUnblockedAppSearchLatencyMillis(), // 22
                stats.getNumIcingCalls()); // 23
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull PersistToDiskStats stats) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        ExtraStats extraStats =
                createExtraStatsLocked(
                        /* packageName= */ null,
                        BaseStats.CALL_TYPE_OPTIMIZE,
                        stats.getEnabledFeatures());
        AppSearchStatsLog.write(
                AppSearchStatsLog.APP_SEARCH_PERSIST_TO_DISK_STATS_REPORTED,
                extraStats.mSamplingInterval, // 1
                extraStats.mSkippedSampleCount, // 2
                extraStats.mPackageUid, // 3
                stats.getStatusCode(), // 4
                stats.getEnabledFeatures(), // 5
                stats.getTriggerCallType(), // 6
                stats.getLastBlockingOperation(), // 7
                stats.getLastBlockingOperationLatencyMillis(), // 8
                stats.getJavaLockAcquisitionLatencyMillis(), // 9
                stats.getGetVmLatencyMillis(), // 10
                stats.getTotalLatencyMillis(), // 11
                stats.getPersistType().getNumber(), // 12
                stats.getNativeLatencyMillis(), // 13
                stats.getBlobStorePersistLatencyMillis(), // 14
                stats.getDocumentStoreTotalPersistLatencyMillis(), // 15
                stats.getDocumentStoreComponentsPersistLatencyMillis(), // 16
                stats.getDocumentStoreChecksumUpdateLatencyMillis(), // 17
                stats.getDocumentLogChecksumUpdateLatencyMillis(), // 18
                stats.getDocumentLogDataSyncLatencyMillis(), // 19
                stats.getSchemaStorePersistLatencyMillis(), // 20
                stats.getIndexPersistLatencyMillis(), // 21
                stats.getIntegerIndexPersistLatencyMillis(), // 22
                stats.getQualifiedIdJoinIndexPersistLatencyMillis(), // 23
                stats.getEmbeddingIndexPersistLatencyMillis(), // 24
                stats.getUnblockedAppSearchLatencyMillis(), // 25
                stats.getNumIcingCalls()); // 26
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull AppsUpdateStats appsUpdateStats) {
        int[] updateStatusArr = new int[appsUpdateStats.getUpdateStatusCodes().size()];
        int updateIdx = 0;
        for (int updateStatus : appsUpdateStats.getUpdateStatusCodes()) {
            updateStatusArr[updateIdx] = updateStatus;
            ++updateIdx;
        }
        AppSearchStatsLog.write(
                AppSearchStatsLog.APP_SEARCH_APPS_INDEXER_STATS_REPORTED,
                appsUpdateStats.getUpdateType(),
                updateStatusArr,
                appsUpdateStats.getNumberOfAppsAdded(),
                appsUpdateStats.getNumberOfAppsRemoved(),
                appsUpdateStats.getNumberOfAppsUpdated(),
                appsUpdateStats.getNumberOfAppsUnchanged(),
                appsUpdateStats.getTotalLatencyMillis(),
                appsUpdateStats.getPackageManagerLatencyMillis(),
                appsUpdateStats.getAppSearchGetLatencyMillis(),
                appsUpdateStats.getAppSearchSetSchemaLatencyMillis(),
                appsUpdateStats.getAppSearchPutLatencyMillis(),
                appsUpdateStats.getUpdateStartTimestampMillis(),
                appsUpdateStats.getLastAppUpdateTimestampMillis(),
                appsUpdateStats.getNumberOfFunctionsAdded(),
                appsUpdateStats.getApproximateNumberOfFunctionsRemoved(),
                appsUpdateStats.getNumberOfFunctionsUpdated(),
                appsUpdateStats.getApproximateNumberOfFunctionsUnchanged(),
                appsUpdateStats.getAppSearchRemoveLatencyMillis(),
                appsUpdateStats.isForceUpdateTriggered());
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull AppOpenEventStats appOpenEventStats) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        int[] updateStatusArr = new int[appOpenEventStats.getUpdateStatusCodes().size()];
        int updateIdx = 0;
        for (int updateStatus : appOpenEventStats.getUpdateStatusCodes()) {
            updateStatusArr[updateIdx] = updateStatus;
            ++updateIdx;
        }

        AppSearchStatsLog.write(
                AppSearchStatsLog.APP_SEARCH_APP_OPEN_EVENT_INDEXER_STATS_REPORTED,
                updateStatusArr,
                appOpenEventStats.getNumberOfAppOpenEventsAdded(),
                appOpenEventStats.getTotalLatencyMillis(),
                appOpenEventStats.getUsageStatsManagerReadLatencyMillis(),
                appOpenEventStats.getAppSearchSetSchemaLatencyMillis(),
                appOpenEventStats.getAppSearchPutLatencyMillis(),
                appOpenEventStats.getUpdateStartTimestampMillis(),
                appOpenEventStats.getLastAppUpdateTimestampMillis(),
                appOpenEventStats.getForceUpdateTriggered());
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull List<SearchSessionStats> searchSessionsStats) {
        for (int i = 0; i < searchSessionsStats.size(); ++i) {
            SearchSessionStats searchSessionStats = searchSessionsStats.get(i);
            logStatsImplLocked(searchSessionStats);
        }
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull SearchSessionStats searchSessionStats) {
        List<SearchIntentStats> searchIntentsStats = searchSessionStats.getSearchIntentsStats();
        for (int i = 0; i < searchIntentsStats.size(); ++i) {
            SearchIntentStats searchIntentStats = searchIntentsStats.get(i);
            logStatsImplLocked(searchIntentStats);
        }

        // Additionally log the end session search intent stats.
        SearchIntentStats endSessionSearchIntentStats =
                searchSessionStats.getEndSessionSearchIntentStats();
        if (endSessionSearchIntentStats != null) {
            logStatsImplLocked(endSessionSearchIntentStats);
        }
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull SearchIntentStats searchIntentStats) {
        int packageUid = getPackageUidAsUserLocked(searchIntentStats.getPackageName());
        String database = searchIntentStats.getDatabase();

        // Prepare click related objects for atoms.
        List<ClickStats> clicksStats = searchIntentStats.getClicksStats();
        long[] clicksTimestampMillis = new long[clicksStats.size()];
        long[] clicksTimeStayOnResultMillis = new long[clicksStats.size()];
        int[] clicksResultRankInBlock = new int[clicksStats.size()];
        int[] clicksResultRankGlobal = new int[clicksStats.size()];
        int numClicks = clicksStats.size();
        int numGoodClicks = 0;
        long enabledFeatures = searchIntentStats.getEnabledFeatures();
        for (int i = 0; i < clicksStats.size(); ++i) {
            ClickStats clickStats = clicksStats.get(i);

            clicksTimestampMillis[i] = clickStats.getTimestampMillis();
            clicksTimeStayOnResultMillis[i] = clickStats.getTimeStayOnResultMillis();
            clicksResultRankInBlock[i] = clickStats.getResultRankInBlock();
            clicksResultRankGlobal[i] = clickStats.getResultRankGlobal();
            if (clickStats.isGoodClick()) {
                ++numGoodClicks;
            }
        }

        int hashCodeForDatabase;
        try {
            hashCodeForDatabase = StatsUtil.calculateHashCodeMd5(database);
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            // Something is wrong while calculating the hash code for database. Assign the hash
            // value with 0xFFFFFFFF, and log the error message.
            // This shouldn't happen since we always use "MD5" and "UTF-8".
            hashCodeForDatabase = 0xFFFFFFFF;
            if (database != null) {
                Log.e(TAG, "Error calculating hash code for database " + database, e);
            }
        }

        // Write atoms.
        AppSearchStatsLog.write(
                AppSearchStatsLog.APP_SEARCH_USAGE_SEARCH_INTENT_STATS_REPORTED,
                packageUid,
                hashCodeForDatabase,
                searchIntentStats.getTimestampMillis(),
                searchIntentStats.getNumResultsFetched(),
                searchIntentStats.getQueryCorrectionType(),
                clicksTimestampMillis,
                clicksTimeStayOnResultMillis,
                clicksResultRankInBlock,
                clicksResultRankGlobal,
                enabledFeatures);

        // Only log restricted atoms for QUERY_CORRECTION_TYPE_ABANDONMENT to catch query correction
        // for common synonyms, abbreviation, nicknames and rebranded names, e.g. "Robert" -> "Bob".
        boolean logRestrictedAtom =
                searchIntentStats.getQueryCorrectionType()
                        == SearchIntentStats.QUERY_CORRECTION_TYPE_ABANDONMENT;
        // Restricted atoms are only available on U+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && logRestrictedAtom) {
            String prevQuery = searchIntentStats.getPrevQuery();
            String currQuery = searchIntentStats.getCurrQuery();
            AppSearchStatsLog.write(
                    AppSearchStatsLog.APP_SEARCH_USAGE_SEARCH_INTENT_RAW_QUERY_STATS_REPORTED,
                    searchIntentStats.getPackageName(),
                    hashCodeForDatabase,
                    prevQuery == null ? "" : prevQuery,
                    currQuery == null ? "" : currQuery,
                    searchIntentStats.getNumResultsFetched(),
                    numClicks,
                    numGoodClicks,
                    searchIntentStats.getQueryCorrectionType(),
                    enabledFeatures);
        }
    }

    /**
     * This method will drop the earliest stats in the queue when the number of calls is at the
     * capacity specified by {@link ServiceAppSearchConfig#getCachedApiCallStatsLimit()}.
     */
    @GuardedBy("mLock")
    private void trimExcessStatsQueueLocked() {
        if (mLastNCalls.isEmpty()) {
            return;
        }
        final int n =
                getCachedApiCallStatsLimitForFeatures(mLastNCalls.getLast().getEnabledFeatures());
        if (n <= 0) {
            mLastNCalls.clear();
            return;
        }
        while (mLastNCalls.size() > n) {
            mLastNCalls.removeFirst();
        }
    }

    /**
     * Record {@link ApiCallRecord} to {@link #mLastNCalls} for dumpsys.
     *
     * <p>This method will automatically drop the earliest stats when the number of calls is at the
     * capacity specified by {@link ServiceAppSearchConfig#getCachedApiCallStatsLimit()}.
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    void addStatsToQueueLocked(@NonNull ApiCallRecord stats) {
        mLastNCalls.addLast(stats);
        trimExcessStatsQueueLocked();
    }

    /**
     * Creates {@link ExtraStats} to hold additional information generated for logging.
     *
     * <p>This method is called by most of logStatsImplLocked functions to reduce code duplication.
     */
    // TODO(b/173532925) Once we add CTS test for logging atoms and can inspect the result, we can
    // remove this @VisibleForTesting and directly use PlatformLogger.logStats to test sampling and
    // rate limiting.
    @VisibleForTesting
    @GuardedBy("mLock")
    @NonNull
    ExtraStats createExtraStatsLocked(
            @Nullable String packageName, @BaseStats.CallType int callType, long features) {
        int packageUid = getPackageUidAsUserLocked(packageName);

        // The sampling ratio here might be different from the one used in
        // shouldLogForTypeLocked if there is a config change in the middle.
        // Since it is only one sample, we can just ignore this difference.
        // Or we can retrieve samplingRatio at beginning and pass along
        // as function parameter, but it will make code less cleaner with some duplication.
        int samplingInterval = getSamplingInterval(callType, features);
        int skippedSampleCount =
                mSkippedSampleCountLocked.get(callType, /* valueOfKeyIfNotFound= */ 0);
        mSkippedSampleCountLocked.put(callType, 0);

        return new ExtraStats(packageUid, samplingInterval, skippedSampleCount);
    }

    /**
     * Checks if this stats should be logged.
     *
     * <p>It won't be logged if it is "sampled" out, or it is too close to the previous logged
     * stats.
     */
    @GuardedBy("mLock")
    // TODO(b/173532925) Once we add CTS test for logging atoms and can inspect the result, we can
    // remove this @VisibleForTesting and directly use PlatformLogger.logStats to test sampling and
    // rate limiting.
    @VisibleForTesting
    boolean shouldLogForTypeLocked(@BaseStats.CallType int callType, long features) {
        int samplingInterval = getSamplingInterval(callType, features);
        // Sampling
        if (!StatsUtil.shouldSample(samplingInterval)) {
            return false;
        }

        // Rate limiting
        // Check the timestamp to see if it is too close to last logged sample
        long currentTimeMillis = SystemClock.elapsedRealtime();
        if (mLastPushTimeMillisLocked
                > currentTimeMillis - mConfig.getCachedMinTimeIntervalBetweenSamplesMillis()) {
            int count = mSkippedSampleCountLocked.get(callType, /* valueOfKeyIfNotFound= */ 0);
            ++count;
            mSkippedSampleCountLocked.put(callType, count);
            return false;
        }

        return true;
    }

    /**
     * Finds the UID of the {@code packageName}. Returns {@link Process#INVALID_UID} if unable to
     * find the UID.
     */
    @GuardedBy("mLock")
    private int getPackageUidAsUserLocked(@Nullable String packageName) {
        if (packageName == null) {
            return Process.INVALID_UID;
        }
        Integer packageUid = mPackageUidCacheLocked.get(packageName);
        if (packageUid == null) {
            packageUid = PackageUtil.getPackageUid(mUserContext, packageName);
            if (packageUid != Process.INVALID_UID) {
                mPackageUidCacheLocked.put(packageName, packageUid);
            }
        }
        return packageUid;
    }

    /**
     * Returns sampling ratio for stats type specified form {@link ServiceAppSearchConfig} or the
     * default Trunkfooder value if LAUNCH_VM is enabled.
     **/
    private int getSamplingInterval(@BaseStats.CallType int statsType, long enabledFeatures) {
        int samplingInterval = getSamplingIntervalFromConfig(statsType);
        if (("eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE))
                && BaseStats.areFeaturesOn(enabledFeatures, List.of(BaseStats.LAUNCH_VM))) {
            // Opt any eng or userdebug device with vm-enabled in for higher sampling.
            // TODO(b/) Remove this once the flag is rolled out to trunkfood.
            return Math.min(
                    samplingInterval, ServiceAppSearchConfig.TRUNKFOODER_SAMPLING_INTERVAL);
        }
        return samplingInterval;
    }

    /** Returns sampling ratio for stats type specified form {@link ServiceAppSearchConfig}. */
    private int getSamplingIntervalFromConfig(@BaseStats.CallType int statsType) {
        switch (statsType) {
            case BaseStats.CALL_TYPE_PUT_DOCUMENTS:
            case BaseStats.CALL_TYPE_GET_DOCUMENTS:
            case BaseStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_ID:
            case BaseStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_SEARCH:
                return mConfig.getCachedSamplingIntervalForBatchCallStats();
            case BaseStats.CALL_TYPE_PUT_DOCUMENT:
                return mConfig.getCachedSamplingIntervalForPutDocumentStats();
            case BaseStats.CALL_TYPE_INITIALIZE:
                return mConfig.getCachedSamplingIntervalForInitializeStats();
            case BaseStats.CALL_TYPE_SEARCH:
                return mConfig.getCachedSamplingIntervalForSearchStats();
            case BaseStats.CALL_TYPE_GLOBAL_SEARCH:
                return mConfig.getCachedSamplingIntervalForGlobalSearchStats();
            case BaseStats.CALL_TYPE_OPTIMIZE:
                return mConfig.getCachedSamplingIntervalForOptimizeStats();
            case BaseStats.INTERNAL_CALL_TYPE_APP_OPEN_EVENT_INDEXER:
                return mConfig.getAppOpenEventIndexerLoggingSamplingRate();
            case BaseStats.INTERNAL_CALL_TYPE_ISOLATED_STORAGE_DATA_MIGRATION:
                return mConfig.getIsolatedStorageDataMigrationSamplingRate();
            case BaseStats.CALL_TYPE_UNKNOWN:
            case BaseStats.CALL_TYPE_SET_SCHEMA:
            case BaseStats.CALL_TYPE_GET_DOCUMENT:
            case BaseStats.CALL_TYPE_REMOVE_DOCUMENT_BY_ID:
            case BaseStats.CALL_TYPE_FLUSH:
            case BaseStats.CALL_TYPE_REMOVE_DOCUMENT_BY_SEARCH:
            case BaseStats.CALL_TYPE_GLOBAL_GET_DOCUMENT_BY_ID:
            case BaseStats.CALL_TYPE_GLOBAL_GET_SCHEMA:
            case BaseStats.CALL_TYPE_GET_SCHEMA:
            case BaseStats.CALL_TYPE_GET_NAMESPACES:
            case BaseStats.CALL_TYPE_GET_NEXT_PAGE:
            case BaseStats.CALL_TYPE_INVALIDATE_NEXT_PAGE_TOKEN:
            case BaseStats.CALL_TYPE_WRITE_SEARCH_RESULTS_TO_FILE:
            case BaseStats.CALL_TYPE_PUT_DOCUMENTS_FROM_FILE:
            case BaseStats.CALL_TYPE_SEARCH_SUGGESTION:
            case BaseStats.CALL_TYPE_REPORT_SYSTEM_USAGE:
            case BaseStats.CALL_TYPE_REPORT_USAGE:
            case BaseStats.CALL_TYPE_GET_STORAGE_INFO:
            case BaseStats.CALL_TYPE_REGISTER_OBSERVER_CALLBACK:
            case BaseStats.CALL_TYPE_UNREGISTER_OBSERVER_CALLBACK:
            case BaseStats.CALL_TYPE_GLOBAL_GET_NEXT_PAGE:
            case BaseStats.CALL_TYPE_OPEN_WRITE_BLOB:
            case BaseStats.CALL_TYPE_COMMIT_BLOB:
            case BaseStats.CALL_TYPE_OPEN_READ_BLOB:
            // TODO(b/173532925) Some of them above will have dedicated sampling ratio config
            default:
                return mConfig.getCachedSamplingIntervalDefault();
        }
    }

    //
    // Functions below are used for tests only
    //
    @VisibleForTesting
    @GuardedBy("mLock")
    void setLastPushTimeMillisLocked(long lastPushElapsedTimeMillis) {
        mLastPushTimeMillisLocked = lastPushElapsedTimeMillis;
    }

    private int getCachedApiCallStatsLimitForFeatures(long enabledFeatures) {
        return BaseStats.areFeaturesOn(enabledFeatures, List.of(BaseStats.LAUNCH_VM))
                ? mConfig.getCachedApiCallStatsLimitForVm()
                : mConfig.getCachedApiCallStatsLimit();
    }
}
