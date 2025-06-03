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
import com.android.server.appsearch.isolated_storage_service.DataMigrationUtil;
import com.android.server.appsearch.isolated_storage_service.IsolatedStorageServiceManager;
import com.android.server.appsearch.util.ExecutorManager;

import com.google.android.icing.IcingSearchEngineInterface;
import com.google.android.icing.proto.InitializeResultProto;
import com.google.android.icing.proto.ResetResultProto;
import com.google.android.icing.proto.StatusProto;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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

    @GuardedBy("mPerUserMigrationFutureLocked")
    private final Map<UserHandle, ScheduledFuture<?>> mPerUserMigrationFutureLocked =
            new ArrayMap();

    public AppSearchUserInstanceManager() {}

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
     * @param isolatedStorageServiceManager Manager for isolated storage
     * @return An initialized {@link AppSearchUserInstance} for this user
     */
    @NonNull
    public AppSearchUserInstance getOrCreateUserInstance(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @NonNull ServiceAppSearchConfig config,
            @NonNull ExecutorManager executorManager,
            @Nullable IsolatedStorageServiceManager isolatedStorageServiceManager)
            throws AppSearchException {
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(config);

        mInstanceMapLock.lock();
        try {
            AppSearchUserInstance instance = mInstancesLocked.get(userHandle);
            if (instance == null) {
                instance =
                        createUserInstance(
                                userContext,
                                userHandle,
                                config,
                                executorManager,
                                isolatedStorageServiceManager);
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
     * @param isolatedStorageServiceManager Manager for isolated storage.
     */
    public void removeUserData(
            @NonNull UserHandle userHandle,
            @NonNull Context userContext,
            @NonNull ServiceAppSearchConfig config,
            @Nullable IsolatedStorageServiceManager isolatedStorageServiceManager) {
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(config);

        // Remove the icing user instance in IsolatedStorageService
        if (isolatedStorageServiceManager != null) {
            isolatedStorageServiceManager.removeUserInstance(userHandle);
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
    public AppSearchUserInstance getUserInstance(@NonNull UserHandle userHandle)
            throws CancellationException, InterruptedException, ExecutionException {
        Objects.requireNonNull(userHandle);
        mInstanceMapLock.lock();
        try {
            AppSearchUserInstance instance = mInstancesLocked.get(userHandle);
            if (instance == null) {
                // Impossible scenario, user cannot call an uninitialized SearchSession,
                // getInstance should always find the instance for the given user and never
                // try to create an instance for this user again.
                throw new IllegalStateException(
                        "AppSearchUserInstance is not created for "
                         + userHandle
                         + ". Instance may still be starting, have crashed, have never been "
                         + "created, or may have been removed.");
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
            @NonNull ServiceAppSearchConfig config,
            @NonNull ExecutorManager executorManager,
            @Nullable IsolatedStorageServiceManager isolatedStorageServiceManager)
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
        IcingSearchEngineInterface icingInstance = null;
        if (isolatedStorageServiceManager != null
                && IsolatedStorageServiceManager.isUserAllowed(userHandle)) {
            if (LogUtil.INFO) {
                Log.i(
                        TAG,
                        "Creating new AppSearch instance for "
                                + userHandle
                                + " in isolated storage.");
            }
            icingInstance =
                    maybeGetIsolatedIcingSearchEngine(
                            userContext,
                            userHandle,
                            config,
                            executorManager,
                            isolatedStorageServiceManager);
        } else {
            if (LogUtil.INFO) {
                Log.i(
                        TAG,
                        "Creating new AppSearch instance for " + userHandle + " at: " + icingDir);
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
                            icingInstance,
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
            @NonNull ServiceAppSearchConfig config,
            @NonNull ExecutorManager executorManager,
            @NonNull IsolatedStorageServiceManager isolatedStorageServiceManager)
            throws AppSearchException {
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(config);
        Objects.requireNonNull(executorManager);
        Objects.requireNonNull(isolatedStorageServiceManager);

        IcingSearchEngineInterface isolatedIcingInterface =
                isolatedStorageServiceManager.getIcingInstance(userHandle, config);

        // Enforce successful isolated storage creation when configured for use
        if (isolatedIcingInterface == null) {
            Log.e(TAG, "Failed to get isolated storage instance!");
            throw new AppSearchException(
                    RESULT_INTERNAL_ERROR, "Failed to get isolated storage instance!");
        }

        if (!DataMigrationUtil.needDataMigration(userContext, userHandle)) {
            return isolatedIcingInterface;
        }

        if (!config.enableIsolatedStorageMigration()) {
            return isolatedIcingInterface;
        }

        // Schedule migration
        synchronized (mPerUserMigrationFutureLocked) {
            ScheduledFuture<?> migrationFuture = mPerUserMigrationFutureLocked.get(userHandle);
            if (migrationFuture == null) {
                // TODO(b/407815165): Allow configuring resetDestination, forceOverride & delay
                migrationFuture =
                        executorManager.scheduleLambdaForUserNoCallbackAsync(
                                userHandle,
                                () -> {
                                    AppSearchUserInstance instance =
                                            getUserInstanceOrNull(userHandle);
                                    if (instance != null) {
                                        InitializeResultProto initResult =
                                                isolatedIcingInterface.initialize();
                                        if (initResult.getStatus().getCode()
                                                != StatusProto.Code.OK) {
                                            Log.e(
                                                    TAG,
                                                    "Failed to initialize Isolated Storage Icing!"
                                                            + " Status code: "
                                                            + initResult
                                                                    .getStatus()
                                                                    .getCode()
                                                                    .getNumber());
                                        }
                                        StatusProto status =
                                                DataMigrationUtil.copyData(
                                                        // Get the non-isolated icing instance
                                                        instance.getAppSearchImpl(),
                                                        isolatedIcingInterface,
                                                        /* resetDestination= */ false,
                                                        /* forceOverride= */ true);
                                        if (LogUtil.INFO) {
                                            Log.i(TAG, "Data migration status: " + status);
                                        }

                                        if (status.getCode() != StatusProto.Code.OK) {
                                            Log.e(
                                                    TAG,
                                                    "Data migration failed with status code: "
                                                            + status.getCode().getNumber());
                                            return;
                                        }

                                        // Switch to the isolated instance
                                        IcingSearchEngineInterface priorIcingSearchEngine =
                                                instance.getAppSearchImpl()
                                                        .swapIcingSearchEngineLocked(
                                                                isolatedIcingInterface);

                                        // Destroy the current instance.
                                        ResetResultProto resetResult =
                                                priorIcingSearchEngine.clearAndDestroy();
                                        priorIcingSearchEngine.close();
                                        if (LogUtil.INFO) {
                                            Log.i(TAG, "Clear and destroy result: " + resetResult);
                                        }
                                    }
                                },
                                1,
                                TimeUnit.MINUTES);
                mPerUserMigrationFutureLocked.put(userHandle, migrationFuture);
            }
        }
        // If we need a migration, return null so that AppSearch will create the non-isolated
        // version of Icing in AppSearchImpl.create.
        return null;
    }
}
