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
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.stats.InitializeStats;
import com.android.server.appsearch.external.localstorage.stats.CallStats;
import com.android.server.appsearch.external.localstorage.visibilitystore.VisibilityChecker;
import com.android.server.appsearch.isolated_storage_service.DataMigrationUtil;
import com.android.server.appsearch.isolated_storage_service.IcingSearchEngine;
import com.android.server.appsearch.isolated_storage_service.IsolatedStorageServiceManager;
import com.android.server.appsearch.util.ExecutorManager;

import com.google.android.icing.IcingSearchEngineInterface;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
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

    private final ExecutorService mAppSearchUserInstanceExecutorService =
            Flags.enableUserInstanceFutures()
                    ? ExecutorManager.createDefaultExecutorService()
                    : null;

    private final ReentrantLock mInstanceMapLock = new ReentrantLock();

    private final ReentrantLock mFutureInstanceMapLock = new ReentrantLock();

    @GuardedBy("mInstanceMapLock")
    private final Map<UserHandle, AppSearchUserInstance> mInstancesLocked = new ArrayMap<>();

    @GuardedBy("mFutureInstanceMapLock")
    private final Map<UserHandle, Future<AppSearchUserInstance>> mFutureInstancesLocked =
            new ArrayMap<>();

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
        if (Flags.enableUserInstanceFutures()) {
            return getOrCreateUserInstanceFuture(
                    userContext,
                    userHandle,
                    config,
                    executorManager,
                    isolatedStorageServiceManager);
        } else {
            return internalGetOrCreateUserInstance(
                    userContext,
                    userHandle,
                    config,
                    executorManager,
                    isolatedStorageServiceManager);
        }
    }

    /**
     * Closes and removes an {@link AppSearchUserInstance} for the given user.
     *
     * <p>All mutations applied to the underlying {@link AppSearchImpl} will be persisted to disk.
     *
     * @param userHandle The multi-user user handle of the user that needs to be removed.
     * @param removeUserData Whether to remove the user data.
     */
    public void closeAndRemoveUserInstance(@NonNull UserHandle userHandle, boolean removeUserData) {
        Objects.requireNonNull(userHandle);
        if (Flags.enableUserInstanceFutures()) {
            closeAndRemoveUserInstanceFuture(userHandle, removeUserData);
        } else {
            internalCloseAndRemoveUserInstance(userHandle, removeUserData);
        }

        synchronized (mStorageInfoLocked) {
            mStorageInfoLocked.remove(userHandle);
        }
    }

    /**
     * Cancels {@link AppSearchUserInstance} creation for the given user.
     *
     * <p>This will NOT remove the future from the map of futures instance. Use {@link
     * AppSearchUserInstanceManager#closeAndRemoveUserInstanceFuture(UserHandle, boolean)} to remove
     * futures instances.
     *
     * @param userHandle The user whose instance creation should be cancelled.
     */
    public void cancelUserCreation(UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        Future<AppSearchUserInstance> instanceFuture;
        mFutureInstanceMapLock.lock();
        try {
            instanceFuture = mFutureInstancesLocked.get(userHandle);
        } finally {
            mFutureInstanceMapLock.unlock();
        }
        if (instanceFuture == null) {
            Log.w(
                    TAG,
                    "Unable to cancel user instance creation for: "
                            + userHandle
                            + ". Instance does not exist.");
            return;
        }
        if (!instanceFuture.cancel(/* mayInterruptIfRunning= */ true)) {
            if (instanceFuture.isDone()) {
                if (LogUtil.INFO) {
                    Log.i(
                            TAG,
                            "Unable to cancel user instance creation for: "
                                    + userHandle
                                    + ". Future creation has already been stopped.");
                }
            } else {
                Log.e(TAG, "Unable to cancel user instance creation for: " + userHandle);
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
     * @throws CancellationException if {@link AppSearchUserInstance} creation was cancelled.
     * @throws InterruptedException if the thread creating {@link AppSearchUserInstance} was
     *     interrupted
     * @throws ExecutionException if {@link AppSearchUserInstance} creation encountered an exception
     */
    @NonNull
    public AppSearchUserInstance getUserInstance(@NonNull UserHandle userHandle)
            throws CancellationException, InterruptedException, ExecutionException {
        Objects.requireNonNull(userHandle);
        if (Flags.enableUserInstanceFutures()) {
            return getUserInstanceFuture(userHandle);
        } else {
            return internalGetUserInstance(userHandle);
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
        AppSearchUserInstance instance;
        if (Flags.enableUserInstanceFutures()) {
            instance = getUserInstanceFutureOrNull(userHandle);

        } else {
            instance = internalGetUserInstanceOrNull(userHandle);
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
        if (Flags.enableUserInstanceFutures()) {
            return getAllFutureUserHandles();
        } else {
            return internalGetAllUserHandles();
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
        boolean isVMEnabledForUser =
                isolatedStorageServiceManager != null
                        && IsolatedStorageServiceManager.isUserAllowed(userHandle);
        if (isVMEnabledForUser) {
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
            // When isolated storage is enabled, the migration file is always created even if the
            // migration doesn't need to run, so it can be used to check whether isolated storage
            // was used before. We need to clean up isolated storage if it was used before but is
            // turned off now.
            if (isolatedStorageServiceManager == null
                    && IsolatedStorageServiceManager.isUserAllowed(userHandle)
                    && IsolatedStorageServiceManager.deviceSupportsVmsAndNewApis(userContext)
                    && DataMigrationUtil.migrationStatusFileExists(userHandle, appSearchDir)) {
                IsolatedStorageServiceManager.cleanUp(
                        userContext,
                        unused ->
                                DataMigrationUtil.deleteMigrationStatus(userHandle, appSearchDir));
            } else {
                DataMigrationUtil.deleteMigrationStatus(userHandle, appSearchDir);
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
        AppSearchImpl appSearchImpl = null;
        try {
            appSearchImpl =
                    AppSearchImpl.create(
                            icingDir,
                            config,
                            initStatsBuilder,
                            /* callStatsBuilder= */ null,
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
            Log.wtf(TAG, "Failed to create AppSearch instance: ", e);
            if (Flags.enableCloseAppsearchOnCreationFailure() && appSearchImpl != null) {
                // If we've created the instance, but encountered some issue.
                // Close this instance so that we clean up it's resources.
                appSearchImpl.close();
            }
            throw e;
        } catch (Exception e) {
            Log.wtf(TAG, "Failed to create AppSearch instance: ", e);
            if (Flags.enableCloseAppsearchOnCreationFailure() && appSearchImpl != null) {
                // If we've created the instance, but encountered some issue.
                // Close this instance so that we clean up it's resources.
                appSearchImpl.close();
            }
            throw e;
        } finally {
            initStatsBuilder
                    .setTotalLatencyMillis(
                            (int) (SystemClock.elapsedRealtime() - totalLatencyStartMillis))
                    .setStatusCode(statusCode);
            logger.logStats(initStatsBuilder.build());
        }
    }

    private Future<AppSearchUserInstance> createUserInstanceFuture(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @NonNull ServiceAppSearchConfig config,
            @NonNull ExecutorManager executorManager,
            @Nullable IsolatedStorageServiceManager isolatedStorageServiceManager) {
        return mAppSearchUserInstanceExecutorService.submit(
                () ->
                        createUserInstance(
                                userContext,
                                userHandle,
                                config,
                                executorManager,
                                isolatedStorageServiceManager));
    }

    private AppSearchUserInstance getOrCreateUserInstanceFuture(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @NonNull ServiceAppSearchConfig config,
            @NonNull ExecutorManager executorManager,
            @Nullable IsolatedStorageServiceManager isolatedStorageServiceManager)
            throws AppSearchException {
        Future<AppSearchUserInstance> instanceFuture;
        mFutureInstanceMapLock.lock();
        try {
            instanceFuture = mFutureInstancesLocked.get(userHandle);
            if (instanceFuture == null) {
                instanceFuture =
                        createUserInstanceFuture(
                                userContext,
                                userHandle,
                                config,
                                executorManager,
                                isolatedStorageServiceManager);
                mFutureInstancesLocked.put(userHandle, instanceFuture);
            }
        } finally {
            mFutureInstanceMapLock.unlock();
        }
        try {
            return instanceFuture.get();
        } catch (ExecutionException | InterruptedException e) {
            throw new AppSearchException(
                    AppSearchResult.RESULT_ABORTED,
                    "User Instance creation for: " + userHandle + " failed to complete.");
        }
    }

    private AppSearchUserInstance internalGetOrCreateUserInstance(
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

    private void closeAndRemoveUserInstanceFuture(UserHandle userHandle, boolean removeUserData) {
        Future<AppSearchUserInstance> instanceFuture;
        mFutureInstanceMapLock.lock();
        try {
            instanceFuture = mFutureInstancesLocked.remove(userHandle);
        } finally {
            mFutureInstanceMapLock.unlock();
        }
        if (instanceFuture != null) {
            if (instanceFuture.isDone()) {
                try {
                    AppSearchImpl appSearchImpl = instanceFuture.get().getAppSearchImpl();
                    if (removeUserData) {
                        appSearchImpl.clearAndDestroy();
                    } else {
                        appSearchImpl.close();
                    }
                } catch (InterruptedException | ExecutionException | CancellationException e) {
                    Log.w(
                            TAG,
                            "No AppSearchImpl to close for "
                                    + userHandle
                                    + ". User instance failed to be created.",
                            e);
                }
            }
        }
    }

    private void internalCloseAndRemoveUserInstance(UserHandle userHandle, boolean removeUserData) {
        AppSearchUserInstance instance;
        mInstanceMapLock.lock();
        try {
            instance = mInstancesLocked.remove(userHandle);
        } finally {
            mInstanceMapLock.unlock();
        }
        if (instance != null) {
            if (removeUserData) {
                instance.getAppSearchImpl().clearAndDestroy();
            } else {
                instance.getAppSearchImpl().close();
            }
        }
    }

    private AppSearchUserInstance getUserInstanceFuture(UserHandle userHandle)
            throws ExecutionException, InterruptedException, CancellationException {
        Future<AppSearchUserInstance> instanceFuture;
        mFutureInstanceMapLock.lock();
        try {
            instanceFuture = mFutureInstancesLocked.get(userHandle);
            if (instanceFuture == null) {
                throw new IllegalStateException(
                        "AppSearchUserInstanceFuture has never been created for: " + userHandle);
            }
        } finally {
            mFutureInstanceMapLock.unlock();
        }
        try {
            return instanceFuture.get();
        } catch (ExecutionException e) {
            throw new ExecutionException(
                    "An exception occurred during AppSearchUserInstance creation for userHandle: "
                            + userHandle,
                    e.getCause());
        } catch (InterruptedException e) {
            throw new InterruptedException(
                    "An interrupt occurred during AppSearchUserInstance creation for userHandle: "
                            + userHandle
                            + " with cause: "
                            + e.getMessage());
        } catch (CancellationException e) {
            throw new CancellationException(
                    "AppSearchUserInstance creation for: "
                            + userHandle
                            + " was cancelled with cause: "
                            + e.getMessage());
        }
    }

    private AppSearchUserInstance internalGetUserInstance(UserHandle userHandle) {
        mInstanceMapLock.lock();
        try {
            AppSearchUserInstance instance = mInstancesLocked.get(userHandle);
            if (instance == null) {
                // Impossible scenario, user cannot call an uninitialized SearchSession,
                // getInstance should always find the instance for the given user and never try to
                // create an instance for this user again.
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

    private AppSearchUserInstance getUserInstanceFutureOrNull(UserHandle userHandle) {
        Future<AppSearchUserInstance> instanceFuture = null;
        if (mFutureInstanceMapLock.tryLock()) {
            try {
                instanceFuture = mFutureInstancesLocked.get(userHandle);
            } finally {
                mFutureInstanceMapLock.unlock();
            }
        }
        if (instanceFuture != null && instanceFuture.isDone()) {
            try {
                return instanceFuture.get();
            } catch (ExecutionException | InterruptedException | CancellationException e) {
                Log.w(
                        TAG,
                        "User instance creation encountered an issue or was cancelled."
                                + " Returning null.");
            }
        }
        return null;
    }

    private AppSearchUserInstance internalGetUserInstanceOrNull(UserHandle userHandle) {
        // mInstanceMapLock being held is unlikely unless we're creating a UserInstance. If we're
        // in the midst of UserInstance creation, we should avoid blocking and simply return null.
        if (mInstanceMapLock.tryLock()) {
            try {
                return mInstancesLocked.get(userHandle);
            } finally {
                mInstanceMapLock.unlock();
            }
        }
        return null;
    }

    private List<UserHandle> getAllFutureUserHandles() {
        mFutureInstanceMapLock.lock();
        try {
            return new ArrayList<>(mFutureInstancesLocked.keySet());
        } finally {
            mFutureInstanceMapLock.unlock();
        }
    }

    private List<UserHandle> internalGetAllUserHandles() {
        mInstanceMapLock.lock();
        try {
            return new ArrayList<>(mInstancesLocked.keySet());
        } finally {
            mInstanceMapLock.unlock();
        }
    }

    /**
     * Gets the isolated icing engine for the user.
     *
     * @return IcingSearchEngineInterface or null if isolated storage can't be used right away due
     *     to, e.g. pending data migration.
     */
    @Nullable
    private IcingSearchEngineInterface maybeGetIsolatedIcingSearchEngine(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @NonNull ServiceAppSearchConfig config,
            @NonNull ExecutorManager executorManager,
            @NonNull IsolatedStorageServiceManager isolatedStorageServiceManager) {
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(config);
        Objects.requireNonNull(executorManager);
        Objects.requireNonNull(isolatedStorageServiceManager);

        IcingSearchEngineInterface isolatedIcingInterface =
                new IcingSearchEngine(
                        isolatedStorageServiceManager,
                        userHandle,
                        config.toIcingSearchEngineOptions(
                                /* baseDir= */ "appsearch", /* isVMEnabled= */ true));

        // Initialize the isolated storage service if not already
        try {
            isolatedStorageServiceManager.initialize();
        } catch (AppSearchException e) {
            Log.e(TAG, "Failed to initialize IsolatedStorageService", e);
        }

        final File appSearchDir =
                AppSearchEnvironmentFactory.getEnvironmentInstance()
                        .getAppSearchDir(userContext, userHandle);
        // Check whether this is the first time VM is booted after the feature is enabled.
        boolean vmFirstRun = true;
        if (DataMigrationUtil.migrationStatusFileExists(userHandle, appSearchDir)) {
            vmFirstRun = false;
        } else {
            // We always create the migration file if vm is created. It will be empty until data
            // migration actually runs(migration might not needed so it will remain empty). With
            // this file created, it can help us to remove the vm if
            // it gets disabled. During startup, we can check if this file exists, and call vm
            // removal if it does.
            DataMigrationUtil.writeMigrationStatus(appSearchDir, /* migrationStats= */ null);
        }

        if (!DataMigrationUtil.needDataMigration(userContext, userHandle)) {
            // Data migration is not needed. But we still want to log an entry to
            // indicate that data migration is correctly skipped.
            AppSearchUserInstance instance = getUserInstanceOrNull(userHandle);
            if (vmFirstRun && instance != null && instance.getLogger() != null) {
                // Skipped is effectively doing migration with 0 data.
                CallStats.Builder callStatsBuilder =
                        new CallStats.Builder()
                                .setStatusCode(AppSearchResult.RESULT_OK)
                                // We re-purpose this to be the counter for previous runs.
                                .setTotalLatencyMillis(0)
                                .setEstimatedBinderLatencyMillis(0)
                                .setCallType(
                                        CallStats
                                                .INTERNAL_CALL_TYPE_ISOLATED_STORAGE_DATA_MIGRATION)
                                .setLaunchVMEnabled(true)
                                .setNumOperationsSucceeded(0)
                                .setNumOperationsFailed(0);
                instance.getLogger().logStats(callStatsBuilder.build());
            }
            Log.i(TAG, "Data migration is not needed.");
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
                                    // Run the data migration. This is run on our worker executor.
                                    // Right now it only has one thread, so it will block
                                    // other API calls.
                                    // We need to let the migration happen in AppSearchImpl to
                                    // grab ReadWrite lock there once multi-threading is enabled.
                                    executorManager.executeLambdaForUserNoCallbackAsync(
                                            userHandle,
                                            /* isReadOnly= */ false,
                                            () -> {
                                                AppSearchUserInstance instance =
                                                        getUserInstanceOrNull(userHandle);
                                                if (instance != null) {
                                                    // TODO(b/407815165) we should move this outside
                                                    //  so it will include executor waiting time.
                                                    //  And we can add execution_latency for
                                                    //  migration later.
                                                    DataMigrationUtil.runDataMigrationForUser(
                                                            userContext,
                                                            userHandle,
                                                            instance.getAppSearchImpl(),
                                                            isolatedIcingInterface,
                                                            instance.getLogger());
                                                }
                                            });
                                },
                                1,
                                TimeUnit.MINUTES);
                mPerUserMigrationFutureLocked.put(userHandle, migrationFuture);
                Log.i(TAG, "Data migration for " + userHandle + " scheduled.");
            }
        }

        // If we need a migration, return null so that AppSearch will create the non-isolated
        // version of Icing in AppSearchImpl.create.
        return null;
    }

    @VisibleForTesting
    void lockInstanceMap() {
        if (Flags.enableUserInstanceFutures()) {
            mFutureInstanceMapLock.lock();
        } else {
            mInstanceMapLock.lock();
        }
    }

    @VisibleForTesting
    void unlockInstanceMap() {
        if (Flags.enableUserInstanceFutures()) {
            mFutureInstanceMapLock.unlock();
        } else {
            mInstanceMapLock.unlock();
        }
    }
}
