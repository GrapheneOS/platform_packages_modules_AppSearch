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
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * App Open Event Indexer for a single user.
 *
 * <p>It reads the updated opened apps from UsageStatsManager and syncs the changes into AppSearch
 *
 * <p>This class is thread safe.
 *
 * @hide
 */
public final class AppOpenEventIndexerUserInstance {

    // Arbitrarily selected time to prevent indexer from running too frequently. It is scheduled to
    // run once daily.
    private static final long MIN_TIME_BETWEEN_UPDATES_MILLIS = 60 * 60 * 1000; // 1 hour

    private static final String TAG = "AppSearchAppOpenEventIn";
    private final AppOpenEventIndexerImpl mAppOpenEventIndexerImpl;
    private final AppOpenEventIndexerSettings mAppOpenEventIndexerSettings;

    // While IndexerSettings is not thread safe, it is only accessed through a single-threaded
    // executor service. It will be read and updated before the next scheduled task accesses it.
    private final File mDataDir;
    private final Context mContext;

    /**
     * Single threaded executor to make sure there is only one active sync per {@link
     * BaseIndexerUserInstance}. Background tasks should be scheduled using {@link
     * #executeOnSingleThreadedExecutor(Runnable)} which ensures that they are not executed if the
     * executor is shutdown during {@link #shutdown()}.
     *
     * <p>Note that this executor is used as both work and callback executors which is fine because
     * AppSearch should be able to handle exceptions thrown by them.
     */
    private final ExecutorService mSingleThreadedExecutor;

    /**
     * Constructs and initializes a {@link AppOpenEventIndexerUserInstance}.
     *
     * <p>Heavy operations such as connecting to AppSearch are performed asynchronously.
     *
     * @param appOpenEventIndexerDir directory for AppOpenEventIndexer.
     */
    @NonNull
    public static AppOpenEventIndexerUserInstance createInstance(
            @NonNull Context userContext, @NonNull File appOpenEventIndexerDir)
            throws AppSearchException {
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(appOpenEventIndexerDir);

        ExecutorService singleThreadedExecutor =
                AppSearchEnvironmentFactory.getEnvironmentInstance().createSingleThreadExecutor();
        return createInstance(userContext, appOpenEventIndexerDir, singleThreadedExecutor);
    }

    @VisibleForTesting
    @NonNull
    static AppOpenEventIndexerUserInstance createInstance(
            @NonNull Context context,
            @NonNull File appOpenEventIndexerDir,
            @NonNull ExecutorService executorService)
            throws AppSearchException {
        Objects.requireNonNull(context);
        Objects.requireNonNull(appOpenEventIndexerDir);
        Objects.requireNonNull(executorService);
        AppOpenEventIndexerImpl appOpenEventIndexerImpl = new AppOpenEventIndexerImpl(context);

        AppOpenEventIndexerUserInstance indexer =
                new AppOpenEventIndexerUserInstance(
                        appOpenEventIndexerDir,
                        executorService,
                        context,
                        appOpenEventIndexerImpl,
                        new AppOpenEventIndexerSettings(appOpenEventIndexerDir));
        indexer.loadSettingsAsync();

        return indexer;
    }

    /**
     * Constructs a {@link AppOpenEventIndexerUserInstance}.
     *
     * @param dataDir data directory for storing app open event indexer state.
     * @param singleThreadedExecutor an {@link ExecutorService} with at most one thread to ensure
     *     the thread safety of this class.
     */
    private AppOpenEventIndexerUserInstance(
            @NonNull File dataDir,
            @NonNull ExecutorService singleThreadedExecutor,
            @NonNull Context context,
            @NonNull AppOpenEventIndexerImpl appOpenEventIndexerImpl,
            @NonNull AppOpenEventIndexerSettings appOpenEventIndexerSettings) {
        mDataDir = Objects.requireNonNull(dataDir);
        mSingleThreadedExecutor = Objects.requireNonNull(singleThreadedExecutor);
        mContext = Objects.requireNonNull(context);
        mAppOpenEventIndexerImpl = Objects.requireNonNull(appOpenEventIndexerImpl);
        mAppOpenEventIndexerSettings = Objects.requireNonNull(appOpenEventIndexerSettings);
    }

    /** Shuts down the AppOpenEventIndexerUserInstance */
    public void shutdown() throws InterruptedException {
        mAppOpenEventIndexerImpl.close();
        synchronized (mSingleThreadedExecutor) {
            mSingleThreadedExecutor.shutdown();
        }
        boolean unused = mSingleThreadedExecutor.awaitTermination(30L, TimeUnit.SECONDS);
    }

    /** Dumps the internal state of this {@link AppOpenEventIndexerUserInstance}. */
    public void dump(@NonNull PrintWriter pw) {
        // Those timestamps are not protected by any lock since in AppOpenEventIndexerUserInstance
        // we only have one thread to handle all the updates. It is possible we might run into
        // race condition if there is an update running while those numbers are being printed.
        // This is acceptable though for debug purpose, so still no lock here.
        pw.println(
                "last_update_timestamp_millis: "
                        + mAppOpenEventIndexerSettings.getLastUpdateTimestampMillis());
    }

    /** Schedule an update on single threaded executor. */
    public void updateAsync() {
        executeOnSingleThreadedExecutor(() -> doUpdate());
    }

    /**
     * Schedule an update on a single-threaded executor.
     *
     * @param callback A callback to be invoked after the update is complete.
     */
    @VisibleForTesting
    void updateAsync(@NonNull Runnable callback) {
        executeOnSingleThreadedExecutor(
                () -> {
                    try {
                        doUpdate();
                    } finally {
                        callback.run();
                    }
                });
    }

    /** Loads the persisted data from disk asynchronously. */
    protected void loadSettingsAsync() {
        executeOnSingleThreadedExecutor(
                () -> {
                    try {
                        // If the directory already exists, this returns false. That is fine as it
                        // might not be the first sync. If this returns true, that is fine as it is
                        // the first run and we want to make a new directory.
                        // TODO(b/357835538): Consider moving this to
                        // IndexerSettings#ensureFileCreated.  Would need to migrate the same logic
                        // in App and Contact indexers. No real blockers aside from launching it
                        // behind a flag.
                        mDataDir.mkdirs();
                    } catch (SecurityException e) {
                        Log.e(TAG, "Failed to create settings directory on disk.", e);
                        return;
                    }

                    try {
                        mAppOpenEventIndexerSettings.load();
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
    protected void executeOnSingleThreadedExecutor(Runnable command) {
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
                            Slog.wtf(TAG, "executeOnSingleThreadedExecutor failed", e);
                        }
                    });
        }
    }

    /**
     * Does the update by verifying if it's been run before, syncing the app open events to
     * AppSearch and finally persisting the settings. If the update has been run within the last
     * {@code MIN_TIME_BETWEEN_UPDATES_MILLIS} it will be a no-op.
     */
    @VisibleForTesting
    void doUpdate() {
        try {
            long lastUpdateMillis = mAppOpenEventIndexerSettings.getLastUpdateTimestampMillis();
            long currentTimeMillis = System.currentTimeMillis();

            if (currentTimeMillis - lastUpdateMillis < MIN_TIME_BETWEEN_UPDATES_MILLIS) {
                Log.w(TAG, "Skipping update because last update was too recent");
                return;
            }
            mAppOpenEventIndexerImpl.doUpdate(mAppOpenEventIndexerSettings);
            mAppOpenEventIndexerSettings.persist();
        } catch (IOException e) {
            Log.w(TAG, "Failed to save settings to disk", e);
        } catch (AppSearchException e) {
            Log.e(TAG, "Failed to sync app open events to AppSearch", e);
        }
    }
}
