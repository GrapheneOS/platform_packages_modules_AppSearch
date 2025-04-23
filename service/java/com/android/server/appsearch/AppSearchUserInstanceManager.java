/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.app.appsearch.AppSearchResult.RESULT_INTERNAL_ERROR;
import static android.app.appsearch.AppSearchResult.RESULT_OK;
import static android.app.appsearch.AppSearchResult.throwableToFailedResult;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.util.LogUtil;
import android.content.Context;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;

import com.android.appsearch.flags.Flags;
import com.android.internal.annotations.GuardedBy;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.stats.InitializeStats;
import com.android.server.appsearch.external.localstorage.visibilitystore.VisibilityChecker;
import com.android.server.appsearch.isolated_storage_service.IsolatedStorageServiceManager;

import com.google.android.icing.IcingSearchEngineInterface;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages the lifecycle of AppSearch classes that should only be initialized once per device-user
 * and make up the core of the AppSearch system.
 *
 * @hide
 */
public final class AppSearchUserInstanceManager {
    private static final String TAG = "AppSearchUserInstanceMa";

    private static volatile AppSearchUserInstanceManager sAppSearchUserInstanceManager;

    private final ReentrantLock mInstanceMapLock = new ReentrantLock();

    @GuardedBy("mInstanceMapLock")
    private final Map<UserHandle, AppSearchUserInstance> mInstancesLocked = new ArrayMap<>();

    @GuardedBy("mStorageInfoLocked")
    private final Map<UserHandle, UserStorageInfo> mStorageInfoLocked = new ArrayMap<>();

    @GuardedBy("mIsolatedStorageServiceManagerLocked")
    private final AtomicReference<IsolatedStorageServiceManager>
            mIsolatedStorageServiceManagerLocked = new AtomicReference<>();

    private AppSearchUserInstanceManager() {}

    /**
     * Gets an instance of AppSearchUserInstanceManager to be used.
     *
     * <p>If no instance has been initialized yet, a new one will be created. Otherwise, the
     * existing instance will be returned.
     */
    @NonNull
    public static AppSearchUserInstanceManager getInstance() {
        if (sAppSearchUserInstanceManager == null) {
            synchronized (AppSearchUserInstanceManager.class) {
                if (sAppSearchUserInstanceManager == null) {
                    sAppSearchUserInstanceManager = new AppSearchUserInstanceManager();
                }
            }
        }
        return sAppSearchUserInstanceManager;
    }

    /**
     * Gets an instance of AppSearchUserInstance for the given user, or creates one if none exists.
     *
     * <p>If no AppSearchUserInstance exists for the unlocked user, Icing will be initialized and
     * one will be created.
     *
     * @param userContext Context of the user calling AppSearch
     * @param userHandle The multi-user handle of the device user calling AppSearch
     * @param config Flag manager for AppSearch
     * @return An initialized {@link AppSearchUserInstance} for this user
     */
    @NonNull
    public AppSearchUserInstance getOrCreateUserInstance(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @NonNull ServiceAppSearchConfig config)
            throws AppSearchException {
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(config);

        mInstanceMapLock.lock();
        try {
            AppSearchUserInstance instance = mInstancesLocked.get(userHandle);
            if (instance == null) {
                instance = createUserInstance(userContext, userHandle, config);
                mInstancesLocked.put(userHandle, instance);
            }
            return instance;
        } finally {
            mInstanceMapLock.unlock();
        }
    }

    /**
     * Closes and removes an {@link AppSearchUserInstance} for the given user.
     *
     * <p>All mutations applied to the underlying {@link AppSearchImpl} will be persisted to disk.
     *
     * @param userHandle The multi-user user handle of the user that needs to be removed.
     */
    public void closeAndRemoveUserInstance(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        AppSearchUserInstance instance;
        mInstanceMapLock.lock();
        try {
            instance = mInstancesLocked.remove(userHandle);
        } finally {
            mInstanceMapLock.unlock();
        }
        if (instance != null) {
            instance.getAppSearchImpl().close();
        }
        synchronized (mStorageInfoLocked) {
            mStorageInfoLocked.remove(userHandle);
        }
    }

    /**
     * Removes the user data for the given {@link AppSearchUserInstance} user. This is handled
     * automatically by the system unless isolated storage is used.
     *
     * @param userHandle The multi-user user handle of the user that needs to be removed.
     * @param userContext Context for the user instance being removed.
     */
    public void removeUserData(
            @NonNull UserHandle userHandle,
            @NonNull Context userContext,
            @NonNull ServiceAppSearchConfig config) {
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(config);

        // Remove the icing user instance in IsolatedStorageService
        if (IsolatedStorageServiceManager.useIsolatedStorage(userContext, config)) {
            synchronized (mIsolatedStorageServiceManagerLocked) {
                mIsolatedStorageServiceManagerLocked.get().removeUserInstance(userHandle);
            }
        }
    }

    /**
     * Gets an {@link AppSearchUserInstance} for the given user.
     *
     * <p>This method should only be called by an initialized SearchSession, which has already
     * called {@link #getOrCreateUserInstance} before.
     *
     * @param userHandle The multi-user handle of the device user calling AppSearch
     * @return An initialized {@link AppSearchUserInstance} for this user
     * @throws IllegalStateException if {@link AppSearchUserInstance} haven't created for the given
     *     user.
     */
    @NonNull
    public AppSearchUserInstance getUserInstance(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        mInstanceMapLock.lock();
        try {
            AppSearchUserInstance instance = mInstancesLocked.get(userHandle);
            if (instance == null) {
                // Impossible scenario, user cannot call an uninitialized SearchSession,
                // getInstance should always find the instance for the given user and never try to
                // create an instance for this user again.
                throw new IllegalStateException(
                        "AppSearchUserInstance has never been created for: " + userHandle);
            }
            return instance;
        } finally {
            mInstanceMapLock.unlock();
        }
    }

    /**
     * Returns the initialized {@link AppSearchUserInstance} for the given user, or {@code null} if
     * no such instance exists.
     *
     * This method will NOT block on creation of {@link AppSearchUserInstance} and will instead
     * return null;
     *
     * @param userHandle The multi-user handle of the device user calling AppSearch
     */
    @Nullable
    public AppSearchUserInstance getUserInstanceOrNull(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        AppSearchUserInstance instance = null;
        // mInstanceMapLock being held is unlikely unless we're creating a UserInstance. If we're
        // in the midst of UserInstance creation, we should avoid blocking and simply return null.
        if (mInstanceMapLock.tryLock()) {
            try {
                instance = mInstancesLocked.get(userHandle);
            } finally {
                mInstanceMapLock.unlock();
            }
        }
        return instance;
    }

    /**
     * Gets an {@link UserStorageInfo} for the given user.
     *
     * @param userContext Context for the user.
     * @param userHandle The multi-user handle of the device user
     * @return An initialized {@link UserStorageInfo} for this user
     */
    @NonNull
    public UserStorageInfo getOrCreateUserStorageInfoInstance(
            @NonNull Context userContext, @NonNull UserHandle userHandle) {
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(userHandle);
        synchronized (mStorageInfoLocked) {
            UserStorageInfo userStorageInfo = mStorageInfoLocked.get(userHandle);
            if (userStorageInfo == null) {
                File appSearchDir =
                        AppSearchEnvironmentFactory.getEnvironmentInstance()
                                .getAppSearchDir(userContext, userHandle);
                userStorageInfo = new UserStorageInfo(appSearchDir);
                mStorageInfoLocked.put(userHandle, userStorageInfo);
            }
            return userStorageInfo;
        }
    }

    /**
     * Gets an {@link UserStorageInfo} for the given user.
     *
     * @param userContext Context for the user.
     * @param userHandle The multi-user handle of the device user
     * @return An initialized {@link UserStorageInfo} for this user, or {@code null} if not found.
     */
    @Nullable
    public UserStorageInfo getOrUserStorageInfoInstanceOrNull(
            @NonNull Context userContext, @NonNull UserHandle userHandle) {
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(userHandle);
        synchronized (mStorageInfoLocked) {
            return mStorageInfoLocked.get(userHandle);
        }
    }

    /**
     * Returns the list of all {@link UserHandle}s.
     *
     * <p>It can return an empty list if there is no {@link AppSearchUserInstance} created yet.
     */
    @NonNull
    public List<UserHandle> getAllUserHandles() {
        mInstanceMapLock.lock();
        try {
            return new ArrayList<>(mInstancesLocked.keySet());
        } finally {
            mInstanceMapLock.unlock();
        }
    }

    @NonNull
    private AppSearchUserInstance createUserInstance(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @NonNull ServiceAppSearchConfig config)
            throws AppSearchException {
        long totalLatencyStartMillis = SystemClock.elapsedRealtime();
        InitializeStats.Builder initStatsBuilder = new InitializeStats.Builder();

        // Initialize the classes that make up AppSearchUserInstance
        InternalAppSearchLogger logger =
                AppSearchComponentFactory.createLoggerInstance(userContext, config);

        File appSearchDir =
                AppSearchEnvironmentFactory.getEnvironmentInstance()
                        .getAppSearchDir(userContext, userHandle);
        File icingDir = new File(appSearchDir, "icing");
        if (LogUtil.INFO) {
            if (IsolatedStorageServiceManager.useIsolatedStorage(userContext, config)) {
                Log.i(TAG, "Creating new AppSearch instance in isolated storage.");
            } else {
                Log.i(TAG, "Creating new AppSearch instance at: " + icingDir);
            }
        }
        VisibilityChecker visibilityCheckerImpl =
                AppSearchComponentFactory.createVisibilityCheckerInstance(userContext);
        FrameworkRevocableFileDescriptorStore frameworkRevocableFileDescriptorStore = null;
        if (Flags.enableBlobStore()) {
            frameworkRevocableFileDescriptorStore =
                    new FrameworkRevocableFileDescriptorStore(userContext, config);
        }
        @AppSearchResult.ResultCode int statusCode = RESULT_OK;
        try {
            AppSearchImpl appSearchImpl =
                    AppSearchImpl.create(
                            icingDir,
                            config,
                            initStatsBuilder,
                            visibilityCheckerImpl,
                            frameworkRevocableFileDescriptorStore,
                            maybeGetIsolatedIcingSearchEngine(userContext, userHandle, config),
                            new ServiceOptimizeStrategy(config));

            // Update storage info file
            UserStorageInfo userStorageInfo =
                    getOrCreateUserStorageInfoInstance(userContext, userHandle);
            userStorageInfo.updateStorageInfoFile(appSearchImpl);

            return new AppSearchUserInstance(logger, appSearchImpl, visibilityCheckerImpl);
        } catch (AppSearchException e) {
            AppSearchResult<Void> failedResult = throwableToFailedResult(e);
            statusCode = failedResult.getResultCode();
            throw e;
        } finally {
            initStatsBuilder
                    .setTotalLatencyMillis(
                            (int) (SystemClock.elapsedRealtime() - totalLatencyStartMillis))
                    .setStatusCode(statusCode);
            logger.logStats(initStatsBuilder.build());
        }
    }

    /**
     * Gets the isolated icing engine for the user if isolated storage is enabled.
     *
     * @return IcingSearchEngineInterface or null if isolated storage is not enabled.
     * @throws AppSearchException if isolated storage is enabled, but the isolated storage service
     *     is unavailable or fails.
     */
    @Nullable
    private IcingSearchEngineInterface maybeGetIsolatedIcingSearchEngine(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @NonNull ServiceAppSearchConfig config)
            throws AppSearchException {
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(config);

        if (!IsolatedStorageServiceManager.useIsolatedStorage(userContext, config)) {
            Log.i(TAG, "Isolated storage is not enabled.");
            return null;
        }

        IcingSearchEngineInterface icingInstance;
        synchronized (mIsolatedStorageServiceManagerLocked) {
            if (mIsolatedStorageServiceManagerLocked.get() == null) {
                mIsolatedStorageServiceManagerLocked.set(
                        new IsolatedStorageServiceManager(userContext, config));
            }
            icingInstance =
                    mIsolatedStorageServiceManagerLocked.get().getIcingInstance(userHandle, config);
        }

        // Enforce successful isolated storage creation when configured for use
        if (icingInstance == null) {
            Log.e(TAG, "Failed to get isolated storage instance!");
            throw new AppSearchException(
                    RESULT_INTERNAL_ERROR, "Failed to get isolated storage instance!");
        }

        return icingInstance;
    }

    // TODO(b/407815165) Right now just check if the icing/version on host exists
    //  We can persist a file to save the migration status, so dir deletion could happen later.
    //
    // TODO(b/407815165) Right now we are checking for USE_ISOLATED_STORAGE flag, later this can be
    // replaced by checking DATA_MIGRATION_TO_ISOLATED_STORAGE_ENABLED flag as well.
    protected boolean needDataMigration(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @NonNull ServiceAppSearchConfig config) {
        if (!IsolatedStorageServiceManager.useIsolatedStorage(userContext, config)) {
            return false;
        }

        File appSearchDir =
                AppSearchEnvironmentFactory.getEnvironmentInstance()
                        .getAppSearchDir(userContext, userHandle);
        File icingDir = new File(appSearchDir, "icing/version");

        return icingDir.exists();
    }
}
