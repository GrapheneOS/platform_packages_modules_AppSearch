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

import static com.android.server.appsearch.indexer.IndexerMaintenanceConfig.APPS_INDEXER;

import android.annotation.NonNull;
import android.annotation.WorkerThread;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.indexer.IndexerMaintenanceService;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Apps Indexer for a single user.
 *
 * <p>It reads the updated/newly-inserted/deleted apps from PackageManager, and syncs the changes
 * into AppSearch.
 *
 * <p>This class is thread safe.
 *
 * @hide
 */
public final class AppsIndexerUserInstance {

    private static final String TAG = "AppSearchAppsIndexerUserInst";

    private final File mDataDir;
    // While AppsIndexerSettings is not thread safe, it is only accessed through a single-threaded
    // executor service. It will be read and updated before the next scheduled task accesses it.
    private final AppsIndexerSettings mSettings;

    // Used for handling the app change notification so we won't schedule too many updates. At any
    // time, only two threads can run an update. But since we use a single-threaded executor, it
    // means that at most one thread can be running, and another thread can be waiting to run. This
    // will happen in the case that an update is requested while another is running.
    private final Semaphore mRunningOrScheduledSemaphore = new Semaphore(2);

    private AppsIndexerImpl mAppsIndexerImpl;

    /**
     * Single threaded executor to make sure there is only one active sync for this {@link
     * AppsIndexerUserInstance}. Background tasks should be scheduled using {@link
     * #executeOnSingleThreadedExecutor(Runnable)} which ensures that they are not executed if the
     * executor is shutdown during {@link #shutdown()}.
     *
     * <p>Note that this executor is used as both work and callback executors which is fine because
     * AppSearch should be able to handle exceptions thrown by them.
     */
    private final ExecutorService mSingleThreadedExecutor;

    private final Context mContext;
    private final AppsIndexerConfig mAppsIndexerConfig;

    /**
     * Constructs and initializes a {@link AppsIndexerUserInstance}.
     *
     * <p>Heavy operations such as connecting to AppSearch are performed asynchronously.
     *
     * @param appsDir data directory for AppsIndexer.
     */
    @NonNull
    public static AppsIndexerUserInstance createInstance(
            @NonNull Context userContext,
            @NonNull File appsDir,
            @NonNull AppsIndexerConfig appsIndexerConfig)
            throws AppSearchException {
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(appsDir);
        Objects.requireNonNull(appsIndexerConfig);

        ExecutorService singleThreadedExecutor =
                AppSearchEnvironmentFactory.getEnvironmentInstance().createSingleThreadExecutor();
        return createInstance(userContext, appsDir, appsIndexerConfig, singleThreadedExecutor);
    }

    @VisibleForTesting
    @NonNull
    static AppsIndexerUserInstance createInstance(
            @NonNull Context context,
            @NonNull File appsDir,
            @NonNull AppsIndexerConfig appsIndexerConfig,
            @NonNull ExecutorService executorService)
            throws AppSearchException {
        Objects.requireNonNull(context);
        Objects.requireNonNull(appsDir);
        Objects.requireNonNull(appsIndexerConfig);
        Objects.requireNonNull(executorService);

        AppsIndexerUserInstance indexer =
                new AppsIndexerUserInstance(appsDir, executorService, context, appsIndexerConfig);
        indexer.loadSettingsAsync();
        indexer.mAppsIndexerImpl = new AppsIndexerImpl(context);

        return indexer;
    }

    /**
     * Constructs a {@link AppsIndexerUserInstance}.
     *
     * @param dataDir data directory for storing apps indexer state.
     * @param singleThreadedExecutor an {@link ExecutorService} with at most one thread to ensure
     *     the thread safety of this class.
     * @param context Context object passed from {@link AppsIndexerManagerService}
     */
    private AppsIndexerUserInstance(
            @NonNull File dataDir,
            @NonNull ExecutorService singleThreadedExecutor,
            @NonNull Context context,
            @NonNull AppsIndexerConfig appsIndexerConfig) {
        mDataDir = Objects.requireNonNull(dataDir);
        mSettings = new AppsIndexerSettings(mDataDir);
        mSingleThreadedExecutor = Objects.requireNonNull(singleThreadedExecutor);
        mContext = Objects.requireNonNull(context);
        mAppsIndexerConfig = Objects.requireNonNull(appsIndexerConfig);
    }

    /** Shuts down the AppsIndexerUserInstance */
    public void shutdown() throws InterruptedException {
        mAppsIndexerImpl.close();
        IndexerMaintenanceService.cancelUpdateJobIfScheduled(
                mContext, mContext.getUser(), APPS_INDEXER);
        synchronized (mSingleThreadedExecutor) {
            mSingleThreadedExecutor.shutdown();
        }
        boolean unused = mSingleThreadedExecutor.awaitTermination(30L, TimeUnit.SECONDS);
    }

    /** Dumps the internal state of this {@link AppsIndexerUserInstance}. */
    public void dump(@NonNull PrintWriter pw) {
        // Those timestamps are not protected by any lock since in AppsIndexerUserInstance
        // we only have one thread to handle all the updates. It is possible we might run into
        // race condition if there is an update running while those numbers are being printed.
        // This is acceptable though for debug purpose, so still no lock here.
        pw.println("last_update_timestamp_millis: " + mSettings.getLastUpdateTimestampMillis());
        pw.println(
                "last_app_update_timestamp_millis: " + mSettings.getLastAppUpdateTimestampMillis());
    }

    /**
     * Schedule an update. No new update can be scheduled if there are two updates already scheduled
     * or currently being run.
     *
     * @param firstRun boolean indicating if this is a first run and that settings should be checked
     *     for the last update timestamp.
     */
    public void updateAsync(boolean firstRun) {
        // Try to acquire a permit.
        if (!mRunningOrScheduledSemaphore.tryAcquire()) {
            // If there are none available, that means an update is running and we have ALREADY
            // received a change mid-update. The third update request was received during the first
            // update, and will be handled by the scheduled update.
            return;
        }
        // If there is a permit available, that cold mean there is one update running right now
        // with none scheduled. Since we use a single threaded executor, calling execute on it
        // right now will run the requested update after the current update. It could also mean
        // there is no update running right now, so we can just call execute and run the update
        // right now.
        executeOnSingleThreadedExecutor(
                () -> {
                    doUpdate(firstRun);
                    IndexerMaintenanceService.scheduleUpdateJob(
                            mContext,
                            mContext.getUser(),
                            APPS_INDEXER,
                            /* periodic= */ true,
                            /* intervalMillis= */ mAppsIndexerConfig
                                    .getAppsMaintenanceUpdateIntervalMillis());
                });
    }

    /**
     * Does the update. It also releases a permit from {@link #mRunningOrScheduledSemaphore}
     *
     * @param firstRun when set to true, that means this was called from onUserUnlocking. If we
     *     didn't have this check, the apps indexer would run every time the phone got unlocked. It
     *     should only run the first time this happens.
     */
    @VisibleForTesting
    @WorkerThread
    void doUpdate(boolean firstRun) {
        try {
            // Check if there was a prior run
            if (firstRun && mSettings.getLastUpdateTimestampMillis() != 0) {
                return;
            }
            mAppsIndexerImpl.doUpdate(mSettings);
            mSettings.persist();
        } catch (IOException e) {
            Log.w(TAG, "Failed to save settings to disk", e);
        } catch (AppSearchException e) {
            Log.e(TAG, "Failed to sync Apps to AppSearch", e);
        } finally {
            // Finish a update. If there were no permits available, the update that was requested
            // mid-update will run. If there was one permit available, we won't run another update.
            // This happens if no updates were scheduled during the update.
            mRunningOrScheduledSemaphore.release();
        }
    }

    /**
     * Loads the persisted data from disk.
     *
     * <p>It doesn't throw here. If it fails to load file, AppsIndexer would always use the
     * timestamps persisted in the memory.
     */
    private void loadSettingsAsync() {
        executeOnSingleThreadedExecutor(
                () -> {
                    try {
                        // If the directory already exists, this returns false. That is fine as it
                        // might not be the first sync. If this returns true, that is fine as it is
                        // the first run and we want to make a new directory.
                        mDataDir.mkdirs();
                    } catch (SecurityException e) {
                        Log.e(TAG, "Failed to create settings directory on disk.", e);
                        return;
                    }

                    try {
                        mSettings.load();
                    } catch (IOException e) {
                        // Ignore file not found errors (bootstrap case)
                        if (!(e instanceof FileNotFoundException)) {
                            Log.e(TAG, "Failed to load settings from disk", e);
                        }
                    }
                });
    }

    /**
     * Executes the given command on {@link #mSingleThreadedExecutor} if it is still alive.
     *
     * <p>If the {@link #mSingleThreadedExecutor} has been shutdown, this method doesn't execute the
     * given command, and returns silently. Specifically, it does not throw {@link
     * java.util.concurrent.RejectedExecutionException}.
     *
     * @param command the runnable task
     */
    private void executeOnSingleThreadedExecutor(Runnable command) {
        synchronized (mSingleThreadedExecutor) {
            if (mSingleThreadedExecutor.isShutdown()) {
                Log.w(TAG, "Executor is shutdown, not executing task");
                return;
            }
            mSingleThreadedExecutor.execute(
                    () -> {
                        try {
                            command.run();
                        } catch (RuntimeException e) {
                            Slog.wtf(
                                    TAG,
                                    "AppsIndexerUserInstance"
                                            + ".executeOnSingleThreadedExecutor() failed ",
                                    e);
                        }
                    });
        }
    }
}
