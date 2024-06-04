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

package com.android.server.appsearch.indexer;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.annotation.CanIgnoreReturnValue;
import android.app.appsearch.util.LogUtil;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalManagerRegistry;
import com.android.server.appsearch.contactsindexer.ContactsIndexerMaintenanceService;
import com.android.server.appsearch.indexer.IndexerMaintenanceConfig.IndexerType;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Dispatches maintenance tasks for various indexers. */
public class IndexerMaintenanceService extends JobService {
    private static final String TAG = "AppSearchIndexerMaintena";
    private static final String EXTRA_USER_ID = "user_id";
    private static final String INDEXER_TYPE = "indexer_type";

    /**
     * A mapping of userHandle-to-CancellationSignal. Since we schedule a separate job for each
     * user, this JobService might be executing simultaneously for the various users, so we need to
     * keep track of the cancellation signal for each user update so we stop the appropriate update
     * when necessary.
     */
    @GuardedBy("mSignals")
    private final Map<UserHandle, CancellationSignal> mSignals = new ArrayMap<>();

    private final Executor mExecutor =
            AppSearchEnvironmentFactory.getEnvironmentInstance()
                    .createExecutorService(
                            /* corePoolSize= */ 1,
                            /* maximumPoolSize= */ 1,
                            /* keepAliveTime= */ 60L,
                            /* unit= */ TimeUnit.SECONDS,
                            /* workQueue= */ new LinkedBlockingQueue<>(),
                            /* priority= */ 0); // priority is unused.

    /**
     * Schedules an update job for the given device-user.
     *
     * @param userHandle Device user handle for whom the update job should be scheduled.
     * @param periodic True to indicate that the job should be repeated.
     * @param indexerType Indicates which {@link IndexerType} to schedule an update for.
     * @param intervalMillis Millisecond interval for which this job should repeat.
     */
    public static void scheduleUpdateJob(
            @NonNull Context context,
            @NonNull UserHandle userHandle,
            @IndexerType int indexerType,
            boolean periodic,
            long intervalMillis) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(userHandle);
        int jobId = getJobIdForUser(userHandle, indexerType);
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        // For devices U and below, we have to schedule using ContactsIndexerMaintenanceService
        // as it has the proper permissions in core/res/AndroidManifest.xml.
        // IndexerMaintenanceService does not have the proper permissions on U. For simplicity, we
        // can also use the same component for scheduling maintenance on U+.
        ComponentName component =
                new ComponentName(context, ContactsIndexerMaintenanceService.class);

        final PersistableBundle extras = new PersistableBundle();
        extras.putInt(EXTRA_USER_ID, userHandle.getIdentifier());
        extras.putInt(INDEXER_TYPE, indexerType);
        JobInfo.Builder jobInfoBuilder =
                new JobInfo.Builder(jobId, component)
                        .setExtras(extras)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresDeviceIdle(true)
                        .setPersisted(true);

        if (periodic) {
            // Specify a flex value of 1/2 the interval so that the job is scheduled to run
            // in the [interval/2, interval) time window, assuming the other conditions are
            // met. This avoids the scenario where the next update job is started within
            // a short duration of the previous run.
            jobInfoBuilder.setPeriodic(intervalMillis, /* flexMillis= */ intervalMillis / 2);
        }
        JobInfo jobInfo = jobInfoBuilder.build();
        JobInfo pendingJobInfo = jobScheduler.getPendingJob(jobId);
        // Don't reschedule a pending job if the parameters haven't changed.
        if (jobInfo.equals(pendingJobInfo)) {
            return;
        }
        jobScheduler.schedule(jobInfo);
        if (LogUtil.DEBUG) {
            Log.v(TAG, "Scheduled update job " + jobId + " for user " + userHandle);
        }
    }

    /**
     * Cancel update job for the given user.
     *
     * @param userHandle The user handle for whom the update job needs to be cancelled.
     */
    private static void cancelUpdateJob(
            @NonNull Context context,
            @NonNull UserHandle userHandle,
            @IndexerType int indexerType) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(userHandle);
        int jobId = getJobIdForUser(userHandle, indexerType);
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        jobScheduler.cancel(jobId);
        if (LogUtil.DEBUG) {
            Log.v(TAG, "Canceled update job " + jobId + " for user " + userHandle);
        }
    }

    /**
     * Check if a update job is scheduled for the given user.
     *
     * @param userHandle The user handle for whom the check for scheduled job needs to be performed
     * @return true if a scheduled job exists
     */
    public static boolean isUpdateJobScheduled(
            @NonNull Context context,
            @NonNull UserHandle userHandle,
            @IndexerType int indexerType) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(userHandle);
        int jobId = getJobIdForUser(userHandle, indexerType);
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        return jobScheduler.getPendingJob(jobId) != null;
    }

    /**
     * Cancel any scheduled update job for the given user. Checks if a update job for the given user
     * exists before trying to cancel it.
     *
     * @param user The user for whom the update job needs to be cancelled.
     */
    public static void cancelUpdateJobIfScheduled(
            @NonNull Context context, @NonNull UserHandle user, @IndexerType int indexerType) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(user);
        try {
            if (isUpdateJobScheduled(context, user, indexerType)) {
                cancelUpdateJob(context, user, indexerType);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to cancel pending update job ", e);
        }
    }

    /**
     * Generate job ids in the range (MIN_INDEXER_JOB_ID, MAX_INDEXER_JOB_ID) to avoid conflicts
     * with other jobs scheduled by the system service. The range corresponds to 21475 job ids,
     * which is the maximum number of user ids in the system.
     *
     * @see com.android.server.pm.UserManagerService#MAX_USER_ID
     */
    private static int getJobIdForUser(
            @NonNull UserHandle userHandle, @IndexerType int indexerType) {
        Objects.requireNonNull(userHandle);
        int baseJobId = IndexerMaintenanceConfig.getConfigForIndexer(indexerType).getMinJobId();
        return baseJobId + userHandle.getIdentifier();
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            int userId = params.getExtras().getInt(EXTRA_USER_ID, /* defaultValue= */ -1);
            if (userId == -1) {
                return false;
            }

            @IndexerType
            int indexerType = params.getExtras().getInt(INDEXER_TYPE, /* defaultValue= */ -1);
            if (indexerType == -1) {
                return false;
            }

            if (LogUtil.DEBUG) {
                Log.v(TAG, "Update job started for user " + userId);
            }

            UserHandle userHandle = UserHandle.getUserHandleForUid(userId);
            final CancellationSignal oldSignal;
            synchronized (mSignals) {
                oldSignal = mSignals.get(userHandle);
            }
            if (oldSignal != null) {
                // This could happen if we attempt to schedule a new job for the user while there's
                // one already running.
                Log.w(TAG, "Old update job still running for user " + userHandle);
                oldSignal.cancel();
            }
            final CancellationSignal signal = new CancellationSignal();
            synchronized (mSignals) {
                mSignals.put(userHandle, signal);
            }
            mExecutor.execute(() -> doUpdateForUser(this, params, userHandle, signal));
            return true;
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "IndexerMaintenanceService.onStartJob() failed ", e);
            return false;
        }
    }

    /**
     * Triggers update from a background job for the given device-user using {@link
     * ContactsIndexerManagerService.LocalService} manager.
     *
     * @param params Parameters from the job that triggered the update.
     * @param userHandle Device user handle for whom the update job should be triggered.
     * @param signal Used to indicate if the update task should be cancelled.
     * @return A boolean representing whether the update operation completed or encountered an
     *     issue. This return value is only used for testing purposes.
     */
    @VisibleForTesting
    @CanIgnoreReturnValue
    public boolean doUpdateForUser(
            @NonNull Context context,
            @Nullable JobParameters params,
            @NonNull UserHandle userHandle,
            @NonNull CancellationSignal signal) {
        try {
            Objects.requireNonNull(context);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(signal);

            @IndexerType int indexerType = params.getExtras().getInt(INDEXER_TYPE, -1);
            if (indexerType == -1) {
                return false;
            }
            Class<? extends IndexerLocalService> indexerLocalService =
                    IndexerMaintenanceConfig.getConfigForIndexer(indexerType).getLocalService();
            IndexerLocalService service = LocalManagerRegistry.getManager(indexerLocalService);
            if (service == null) {
                Log.e(
                        TAG,
                        "Background job failed to trigger Update because "
                                + "Indexer.LocalService is not available.");
                // If a background update job exists while an indexer is disabled, cancel the
                // job after its first run. This will prevent any periodic jobs from being
                // unnecessarily triggered repeatedly. If the service is null, it means the indexer
                // is disabled. So the local service is not registered during the startup.
                cancelUpdateJob(context, userHandle, indexerType);
                return false;
            }
            service.doUpdateForUser(userHandle, signal);
        } catch (RuntimeException e) {
            Log.e(TAG, "Background job failed to trigger Update because ", e);
            return false;
        } finally {
            jobFinished(params, signal.isCanceled());
            synchronized (mSignals) {
                if (signal == mSignals.get(userHandle)) {
                    mSignals.remove(userHandle);
                }
            }
        }
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        try {
            final int userId = params.getExtras().getInt(EXTRA_USER_ID, /* defaultValue */ -1);
            if (userId == -1) {
                return false;
            }
            UserHandle userHandle = UserHandle.getUserHandleForUid(userId);
            // This will only run on S+ builds, so no need to do a version check.
            if (LogUtil.DEBUG) {
                Log.d(
                        TAG,
                        "Stopping update job for user "
                                + userId
                                + " because "
                                + params.getStopReason());
            }
            synchronized (mSignals) {
                final CancellationSignal signal = mSignals.get(userHandle);
                if (signal != null) {
                    signal.cancel();
                    mSignals.remove(userHandle);
                    // We had to stop the job early. Request reschedule.
                    return true;
                }
            }
            Log.e(TAG, "JobScheduler stopped an update that wasn't happening...");
            return false;
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "IndexerMaintenanceService.onStopJob() failed ", e);
            return false;
        }
    }
}
