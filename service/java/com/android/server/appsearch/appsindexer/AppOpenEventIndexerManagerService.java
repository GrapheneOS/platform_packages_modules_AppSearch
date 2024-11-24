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

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchEnvironment;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.util.LogUtil;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalManagerRegistry;
import com.android.server.SystemService;
import com.android.server.appsearch.indexer.IndexerLocalService;

import java.io.File;
import java.io.PrintWriter;
import java.util.Map;
import java.util.Objects;

/**
 * Manages the per device-user AppOpenEventIndexer instance to index apps into AppSearch.
 *
 * <p>Unlike apps indexer and contacts indexer, this does not hook into package update, phone
 * unlock/stop, etc. It only runs when the maintenance job runs.
 *
 * <p>This class is thread-safe.
 *
 * @hide
 */
public final class AppOpenEventIndexerManagerService extends SystemService {
    private static final String TAG = "AppSearchAppOpenEventIn";
    private final Context mContext;
    @VisibleForTesting final LocalService mLocalService;
    @VisibleForTesting @Nullable final Runnable mCallback;

    private final AppOpenEventIndexerConfig mAppOpenEventIndexerConfig;

    // Map of AppOpenEventIndexerUserInstances indexed by the UserHandle
    @GuardedBy("mAppOpenEventIndexersLocked")
    private final Map<UserHandle, AppOpenEventIndexerUserInstance> mAppOpenEventIndexersLocked =
            new ArrayMap<>();

    /** Constructs a {@link AppOpenEventIndexerManagerService}. */
    public AppOpenEventIndexerManagerService(
            @NonNull Context context,
            @NonNull AppOpenEventIndexerConfig appOpenEventIndexerConfig) {
        this(context, appOpenEventIndexerConfig, /* callback= */ null);
    }

    /**
     * Constructs a {@link AppOpenEventIndexerManagerService} for testing with a callback for
     * synchronization.
     */
    @VisibleForTesting
    public AppOpenEventIndexerManagerService(
            @NonNull Context context,
            @NonNull AppOpenEventIndexerConfig appOpenEventIndexerConfig,
            @Nullable Runnable callback) {
        super(context);
        mContext = Objects.requireNonNull(context);
        mAppOpenEventIndexerConfig = Objects.requireNonNull(appOpenEventIndexerConfig);
        mCallback = callback;
        mLocalService = new LocalService();
    }

    @Override
    public void onStart() {
        LocalManagerRegistry.addManager(LocalService.class, mLocalService);
    }

    /** Handles user stopping by shutting down the instance for the user. */
    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        try {
            Objects.requireNonNull(user);
            UserHandle userHandle = user.getUserHandle();
            synchronized (mAppOpenEventIndexersLocked) {
                AppOpenEventIndexerUserInstance instance =
                        mAppOpenEventIndexersLocked.get(userHandle);
                if (instance != null) {
                    mAppOpenEventIndexersLocked.remove(userHandle);
                    try {
                        instance.shutdown();
                    } catch (InterruptedException e) {
                        Log.w(
                                TAG,
                                "Failed to shutdown app open event indexer for " + userHandle,
                                e);
                    }
                }
            }
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "AppOpenEventIndexerManagerService.onUserStopping() failed ", e);
        }
    }

    /** Dumps AppOpenEventIndexer internal state for the user. */
    @BinderThread
    public void dumpAppOpenEventIndexerForUser(
            @NonNull UserHandle userHandle, @NonNull PrintWriter pw) {
        try {
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(pw);
            synchronized (mAppOpenEventIndexersLocked) {
                AppOpenEventIndexerUserInstance instance =
                        mAppOpenEventIndexersLocked.get(userHandle);
                if (instance != null) {
                    instance.dump(pw);
                } else {
                    pw.println("AppOpenEventIndexerUserInstance is not created for " + userHandle);
                }
            }
        } catch (RuntimeException e) {
            Slog.wtf(
                    TAG,
                    "AppOpenEventIndexerManagerService.dumpAppOpenEventIndexerForUser() failed ",
                    e);
        }
    }

    /** Schedules the periodic update job for all users we have an instance for. */
    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        synchronized (mAppOpenEventIndexersLocked) {
            try {
                AppOpenEventIndexerUserInstance instance =
                        getOrCreateUserInstance(user.getUserHandle());
                if (instance != null) {
                    instance.schedulePeriodicUpdate();
                }
            } catch (RuntimeException e) {
                Slog.wtf(TAG, "AppOpenEventIndexerManagerService.onUserUnlocking() failed", e);
            }
        }
    }

    /** Retrieves or creates the {@link AppOpenEventIndexerUserInstance} for the specified user. */
    private AppOpenEventIndexerUserInstance getOrCreateUserInstance(
            @NonNull UserHandle userHandle) {
        synchronized (mAppOpenEventIndexersLocked) {
            Objects.requireNonNull(userHandle);
            AppOpenEventIndexerUserInstance instance = mAppOpenEventIndexersLocked.get(userHandle);

            if (instance == null) {
                if (LogUtil.INFO) {
                    Log.i(TAG, "Creating AppOpenEventIndexerUserInstance for " + userHandle);
                }
                try {
                    AppSearchEnvironment appSearchEnvironment =
                            AppSearchEnvironmentFactory.getEnvironmentInstance();
                    Context userContext =
                            appSearchEnvironment.createContextAsUser(mContext, userHandle);
                    File appSearchDir =
                            appSearchEnvironment.getAppSearchDir(userContext, userHandle);
                    File appOpenEventDir = new File(appSearchDir, "app-open-events");
                    instance =
                            AppOpenEventIndexerUserInstance.createInstance(
                                    userContext, appOpenEventDir, mAppOpenEventIndexerConfig);
                    mAppOpenEventIndexersLocked.put(userHandle, instance);
                } catch (AppSearchException e) {
                    Log.e(
                            TAG,
                            "Error while creating AppOpenEventIndexerUserInstance for "
                                    + userHandle,
                            e);
                }
            }
            return instance;
        }
    }

    class LocalService implements IndexerLocalService {
        /** Runs an update for a user. */
        @Override
        public void doUpdateForUser(
                @NonNull UserHandle userHandle, @Nullable CancellationSignal unused) {

            Objects.requireNonNull(userHandle);
            try {
                synchronized (mAppOpenEventIndexersLocked) {
                    AppOpenEventIndexerUserInstance instance = getOrCreateUserInstance(userHandle);
                    if (instance != null) {
                        if (mCallback != null) {
                            instance.updateAsync(mCallback);
                        } else {
                            instance.updateAsync();
                        }
                    }
                }
            } catch (RuntimeException e) {
                Slog.wtf(TAG, "AppOpenEventIndexerManagerService.doUpdateForUser() failed ", e);
            }
        }
    }
}
