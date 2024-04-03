/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.appsearch;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
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
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalManagerRegistry;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class AppSearchMaintenanceService extends JobService {
    private static final String TAG = "AppSearchMaintenanceSer";

    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String EXTRA_USER_ID = "user_id";
    /**
     * Generate job ids in the range (MIN_APPSEARCH_MAINTENANCE_JOB_ID,
     * MIN_APPSEARCH_MAINTENANCE_JOB_ID + MAX_USER_ID) to avoid conflicts with other jobs scheduled
     * by the system service. The range corresponds to 21475 job ids, which is the maximum number of
     * user ids in the system.
     *
     * @see com.android.server.pm.UserManagerService#MAX_USER_ID
     */
    public static final int MIN_APPSEARCH_MAINTENANCE_JOB_ID = 461234957; // 0x1B7DE30D

    /**
     * A mapping of userId-to-CancellationSignal. Since we schedule a separate job for each user,
     * this JobService might be executing simultaneously for the various users, so we need to keep
     * track of the cancellation signal for each user update so we stop the appropriate update
     * when necessary.
     */
    @GuardedBy("mSignalsLocked")
    private final SparseArray<CancellationSignal> mSignalsLocked = new SparseArray<>();

    /**
     * Schedule the daily fully persist job for the given user.
     *
     * <p>The job will persists all pending mutation operation to disk.
     */
    static void scheduleFullyPersistJob(@NonNull Context context, @UserIdInt int userId,
            long intervalMillis) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);

        final PersistableBundle extras = new PersistableBundle();
        extras.putInt(EXTRA_USER_ID, userId);
        JobInfo jobInfo = new JobInfo.Builder(
                MIN_APPSEARCH_MAINTENANCE_JOB_ID + userId, // must be unique across uid
                new ComponentName(context, AppSearchMaintenanceService.class))
                .setPeriodic(intervalMillis) // run once a day, at most
                .setExtras(extras)
                .setPersisted(true) // persist across reboots
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .build();
        jobScheduler.schedule(jobInfo);
        if (LogUtil.DEBUG) {
            Log.v(TAG, "Scheduling the daily AppSearch full persist job");
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            int userId = params.getExtras().getInt(EXTRA_USER_ID, /*defaultValue=*/ -1);
            if (userId == -1) {
                return false;
            }

            final CancellationSignal signal;
            synchronized (mSignalsLocked) {
                CancellationSignal oldSignal = mSignalsLocked.get(userId);
                if (oldSignal != null) {
                    // This could happen if we attempt to schedule a new job for the user while
                    // there's
                    // one already running.
                    Log.w(TAG, "Old maintenance job still running for user " + userId);
                    oldSignal.cancel();
                }
                signal = new CancellationSignal();
                mSignalsLocked.put(userId, signal);
            }
            EXECUTOR.execute(() -> doFullyPersistJobForUser(this, params, userId, signal));
            return true;
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "AppSearchMaintenanceService.onStartJob() failed ", e);
            return false;
        }
    }

    /** Triggers full persist job for the given user directly. */
    @VisibleForTesting
    @CanIgnoreReturnValue
    protected boolean doFullyPersistJobForUser(Context context, JobParameters params, int userId,
            CancellationSignal signal) {
        try {
            AppSearchManagerService.LocalService service = LocalManagerRegistry
                    .getManager(AppSearchManagerService.LocalService.class);
            if (service == null) {
                Log.e(TAG, "Background job failed to trigger Full persist because "
                        + "AppSearchManagerService.LocalService is not available.");
                // Cancel unnecessary background full persist job if AppSearch local service is not
                // registered
                cancelFullyPersistJobIfScheduled(context, userId);
                return false;
            }
            service.doFullyPersistForUser(userId);
        } catch (Throwable t) {
            Log.e(TAG, "Run Daily optimize job failed.", t);
            jobFinished(params, /*wantsReschedule=*/ true);
            return false;
        } finally {
            jobFinished(params, /*wantsReschedule=*/ false);
            synchronized (mSignalsLocked) {
                if (signal == mSignalsLocked.get(userId)) {
                    mSignalsLocked.remove(userId);
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
            if (LogUtil.DEBUG) {
                Log.d(TAG, "AppSearch maintenance job is stopped; id=" + params.getJobId()
                        + ", reason=" + params.getStopReason());
            }
            synchronized (mSignalsLocked) {
                final CancellationSignal signal = mSignalsLocked.get(userId);
                if (signal != null) {
                    signal.cancel();
                    mSignalsLocked.remove(userId);
                    // We had to stop the job early. Request reschedule.
                    return true;
                }
            }
            Log.e(TAG, "JobScheduler stopped an update that wasn't happening...");
            return false;
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "AppSearchMaintenanceService.onStopJob() failed ", e);
        }
        return false;
    }

    /**
     * Cancel full persist job for the given user.
     *
     * @param userId The user id for whom the full persist job needs to be cancelled.
     */
    public static void cancelFullyPersistJobIfScheduled(@NonNull Context context,
            @UserIdInt int userId) {
        Objects.requireNonNull(context);
        int jobId = MIN_APPSEARCH_MAINTENANCE_JOB_ID + userId;
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        if (jobScheduler.getPendingJob(jobId) != null) {
            jobScheduler.cancel(jobId);
            if (LogUtil.DEBUG) {
                Log.v(TAG, "Canceled job " + jobId + " for user " + userId);
            }
        }
    }
}
