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
import android.annotation.Nullable;
import android.app.appsearch.AppSearchEnvironment;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.util.LogUtil;
import android.content.Context;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.appsearch.indexer.IndexerForceUpdateConfig;

import java.io.File;
import java.util.Map;
import java.util.Objects;

/**
 * Manages the lifecycle of {@link AppsIndexerUserInstance} instances.
 *
 * @hide
 */
public final class AppsIndexerUserInstanceManager {
    private static final String TAG = "AppsIndexerUserInstMgr";
    private static final String APPS_DIR = "apps";

    private static volatile AppsIndexerUserInstanceManager sAppsIndexerUserInstanceManager;

    // Map of AppsIndexerUserInstances indexed by the UserHandle
    @GuardedBy("mInstancesLocked")
    private final Map<UserHandle, AppsIndexerUserInstance> mInstancesLocked = new ArrayMap<>();

    private AppsIndexerUserInstanceManager() {}

    /**
     * Gets an instance of {@link AppsIndexerUserInstanceManager} to be used.
     *
     * <p>If no instance has been initialized yet, a new one will be created. Otherwise, the
     * existing instance will be returned.
     */
    public static AppsIndexerUserInstanceManager getInstance() {
        if (sAppsIndexerUserInstanceManager == null) {
            synchronized (AppsIndexerUserInstanceManager.class) {
                if (sAppsIndexerUserInstanceManager == null) {
                    sAppsIndexerUserInstanceManager = new AppsIndexerUserInstanceManager();
                }
            }
        }
        return sAppsIndexerUserInstanceManager;
    }

    /**
     * Handles the user-unlock event for AppsIndexer.
     *
     * <p>Gets an instance of {@link AppsIndexerUserInstance} for the given user, or creates one if
     * none exists. It then invokes the first-run AppsIndexer update.
     *
     * @param context Context of the user calling AppsIndexer
     * @param userHandle The multi-user handle of the device user calling AppsIndexer
     * @param config Flag manager for AppsIndexer
     */
    public void handleUserUnlock(
            @NonNull Context context,
            @NonNull UserHandle userHandle,
            @NonNull AppsIndexerConfig config,
            @NonNull IndexerForceUpdateConfig forceUpdateConfig)
            throws AppSearchException {
        Objects.requireNonNull(context);
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(config);
        Objects.requireNonNull(forceUpdateConfig);

        synchronized (mInstancesLocked) {
            AppsIndexerUserInstance instance = mInstancesLocked.get(userHandle);
            if (instance == null) {
                AppSearchEnvironment appSearchEnvironment =
                        AppSearchEnvironmentFactory.getEnvironmentInstance();
                Context userContext = appSearchEnvironment.createContextAsUser(context, userHandle);
                File appSearchDir = appSearchEnvironment.getAppSearchDir(userContext, userHandle);
                File appsDir = new File(appSearchDir, APPS_DIR);
                instance =
                        AppsIndexerUserInstance.createInstance(
                                userContext, appsDir, config, forceUpdateConfig);
                instance.startAsync();
                if (LogUtil.DEBUG) {
                    Log.d(TAG, "Created Apps Indexer instance for user " + userHandle);
                }
                mInstancesLocked.put(userHandle, instance);
            }
            instance.updateAsync(/* firstRun= */ true, /* isForceUpdateTriggered= */ false);
        }
    }

    /**
     * Triggers an AppsIndexer update for a given user.
     *
     * @param userHandle The multi-user handle of the device user calling AppsIndexer
     * @param firstRun boolean indicating if this is a first run and that settings should be checked
     *     for the last update timestamp.
     * @param isForceUpdateTriggered indicates if a force update is triggered.
     */
    public void handleUpdateForUser(
            @NonNull UserHandle userHandle, boolean firstRun, boolean isForceUpdateTriggered) {
        Objects.requireNonNull(userHandle);
        synchronized (mInstancesLocked) {
            AppsIndexerUserInstance instance = mInstancesLocked.get(userHandle);
            if (instance != null) {
                instance.updateAsync(firstRun, isForceUpdateTriggered);
            }
        }
    }

    /**
     * Gets an instance of {@link AppsIndexerUserInstance} for the given user, or returns null if
     * none exists.
     *
     * @param userHandle The multi-user handle of the device user calling AppsIndexer
     * @return An initialized {@link AppsIndexerUserInstance} for this user
     */
    @Nullable
    public AppsIndexerUserInstance getUserInstanceOrNull(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        synchronized (mInstancesLocked) {
            return mInstancesLocked.get(userHandle);
        }
    }

    /**
     * Removes an instance of {@link AppsIndexerUserInstance} for the given user.
     *
     * @param userHandle The multi-user handle of the device user calling AppsIndexer
     */
    public void removeInstance(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        synchronized (mInstancesLocked) {
            AppsIndexerUserInstance instance = mInstancesLocked.get(userHandle);
            if (instance != null) {
                mInstancesLocked.remove(userHandle);
                try {
                    instance.shutdown();
                } catch (InterruptedException e) {
                    Log.w(TAG, "Failed to shutdown apps indexer for " + userHandle, e);
                }
            }
        }
    }
}
