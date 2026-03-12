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

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.util.ExceptionUtil;
import android.app.appsearch.util.LogUtil;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.appsearch.flags.Flags;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.AppSearchLogger;
import com.android.server.appsearch.external.localstorage.stats.CallStats;
import com.android.server.appsearch.external.localstorage.stats.InitializeStats;
import com.android.server.appsearch.external.localstorage.visibilitystore.VisibilityChecker;
import com.android.server.appsearch.isolated_storage_service.DataMigrationUtil;
import com.android.server.appsearch.isolated_storage_service.IcingSearchEngine;
import com.android.server.appsearch.isolated_storage_service.IsolatedStorageServiceManager;
import com.android.server.appsearch.util.ExecutorManager;

import com.google.android.icing.IcingSearchEngineInterface;
import com.google.android.icing.proto.IcingSearchEngineOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
            ExecutorManager.createDefaultExecutorService();

    private final ReentrantLock mFutureInstanceMapLock = new ReentrantLock();

    @GuardedBy("mFutureInstanceMapLock")
    private final Map<UserHandle, Future<AppSearchUserInstance>> mFutureInstancesLocked =
            new ArrayMap<>();

    @GuardedBy("mStorageInfoLocked")
    private final Map<UserHandle, UserStorageInfo> mStorageInfoLocked = new ArrayMap<>();

    @GuardedBy("mPerUserMigrationFutureLocked")
    private final Map<UserHandle, ScheduledFuture<?>> mPerUserMigrationFutureLocked =
            new ArrayMap();

    /**
     * A dedicated handler for processing background tasks, such as account updates and other
     * I/O-intensive operations.
     *
     * <p>Since AppSearch is a mainline module, it cannot access the shared system {@code
     * com.android.internal.os.BackgroundThread}. Therefore, a private instance is required to
     * ensure these operations do not block the system server's main thread.
     */
    private final @Nullable Handler mBackgroundHandler;

    private static final String BACKGROUND_HANDLER_THREAD_NAME = "AppSearchBackgroundThread";

    /**
     * The default delay for resetting handle expired documents alarm (via {@link
     * HandleExpiredDocumentsAlarmListener}) during {@link AppSearchUserInstance} creation.
     *
     * <p>{@link AppSearchUserInstance} should schedule the 1st handle expired documents alarm after
     * creation and initialization in order to kick off the background task cycle, and the task
     * itself will keep rescheduling the next alarm for the next expiration time.
     */
    private static final long HANDLE_EXPIRED_DOCUMENTS_ALARM_RESET_AT_CREATION_DELAY_MILLIS =
            60 * 1000; // 1 minute

    public AppSearchUserInstanceManager() {
        if (Flags.enableSchemasWipeoutAccountPropertyPaths()) {
            HandlerThread handlerThread = new HandlerThread(BACKGROUND_HANDLER_THREAD_NAME);
            handlerThread.start();
            mBackgroundHandler = new Handler(handlerThread.getLooper());
        } else {
            mBackgroundHandler = null;
        }
    }

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
            @Nullable IsolatedStorageServiceManager isolatedStorageServiceManager,
            boolean enableIsolatedStorageReverseMigration,
            boolean isIsolatedStorageAvailable)
            throws AppSearchException {
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(config);
        return getOrCreateUserInstanceFuture(
                userContext,
                userHandle,
                config,
                executorManager,
                isolatedStorageServiceManager,
                enableIsolatedStorageReverseMigration,
                isIsolatedStorageAvailable);
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
        closeAndRemoveUserInstanceFuture(userHandle, removeUserData);

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
        mFutureInstanceMapLock.lock();
        try {
            return new ArrayList<>(mFutureInstancesLocked.keySet());
        } finally {
            mFutureInstanceMapLock.unlock();
        }
    }

    @NonNull
    private AppSearchUserInstance createUserInstance(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @NonNull ServiceAppSearchConfig config,
            @NonNull ExecutorManager executorManager,
            @Nullable IsolatedStorageServiceManager isolatedStorageServiceManager,
            boolean enableIsolatedStorageReverseDataMigration,
            boolean isIsolatedStorageAvailable)
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
        // When the aegis flag is disabled but data migration file exists,
        // isIsolatedStorageAvailable
        // returns false but isolatedStorageServiceManager will be non-null as we initialized it in
        // onUserUnlock for reverse migration.
        boolean isVMEnabledForUser =
                (enableIsolatedStorageReverseDataMigration
                                ? isIsolatedStorageAvailable
                                : isolatedStorageServiceManager != null)
                        && IsolatedStorageServiceManager.isUserAllowed(userHandle);
        boolean wasVmEnabledBefore =
                IsolatedStorageServiceManager.isUserAllowed(userHandle)
                        && DataMigrationUtil.migrationStatusFileExists(userHandle, appSearchDir);

        if (isVMEnabledForUser
                || (enableIsolatedStorageReverseDataMigration && wasVmEnabledBefore)) {
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
                            /* skipDataMigration= */ !isVMEnabledForUser,
                            isolatedStorageServiceManager,
                            logger,
                            enableIsolatedStorageReverseDataMigration);
        } else if (!enableIsolatedStorageReverseDataMigration) {
            // If reverse data migration is enabled we will not clean isolated storage.
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

        // Reverse Migration needed.
        if (enableIsolatedStorageReverseDataMigration
                && !isVMEnabledForUser
                && wasVmEnabledBefore) {
            Log.i(TAG, "Schedule reverse migration.");
            // icingInstance for VM has been created.
            if (icingInstance != null) {
                // We should only try to run reverse migration once when we first detect the VM is
                // disabled.
                scheduleReverseDataMigration(userContext, userHandle, config, executorManager);
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
                            // TODO(b/430289015): Right now AppSearchImpl infers isVMEnabledForUser
                            // from whether this is null or not.
                            // We should refactor to use the same value other places are using.
                            // Caveats are for the very 1st time, we will treat the system as vm
                            // disabled until migration finishes. We might want to refactor that
                            // part as well.
                            icingInstance,
                            // This isVMEnabledForUser might not be accurate when data migration is
                            // scheduled. Before it finishes, the system would behave like VM is
                            // disabled, but this boolean is true.
                            // But, since the optimization strategy itself can apply on non-vm
                            // cases, and we are still targeting right devices, we can just
                            // make it simple by just enabling it directly using this
                            // isVMEnabledForUser boolean.
                            new ServiceOptimizeStrategy(config, isVMEnabledForUser));

            // Update storage info file
            UserStorageInfo userStorageInfo =
                    getOrCreateUserStorageInfoInstance(userContext, userHandle);
            userStorageInfo.updateStorageInfoFile(appSearchImpl);

            // Create the AccountManager and OnAccountUpdateListener for the user to monitoring
            // account updates.
            AccountManager accountManager = null;
            OnAccountsUpdateListener listener = null;
            if (Flags.enableSchemasWipeoutAccountPropertyPaths()) {
                accountManager =
                        Objects.requireNonNull(userContext.getSystemService(AccountManager.class));
                listener =
                        createOnAccountsUpdateListenerForInstance(
                                userContext, appSearchImpl, userHandle);
            }

            // Create alarm handler thread and HandleExpiredDocuments alarm listener.
            HandlerThread alarmHandlerThread = null;
            HandleExpiredDocumentsAlarmListener handleExpiredDocumentsAlarmListener = null;
            if (Flags.enableDeletePropagationRw()) {
                alarmHandlerThread =
                        new HandlerThread(
                                "AppSearchUserInstance_alarmHandlerThread_user"
                                        + userHandle.getIdentifier());
                alarmHandlerThread.start();

                AlarmManager alarmManager =
                        (AlarmManager)
                                Objects.requireNonNull(
                                        userContext.getSystemService(Context.ALARM_SERVICE));
                handleExpiredDocumentsAlarmListener =
                        new HandleExpiredDocumentsAlarmListener(
                                alarmManager,
                                new Handler(alarmHandlerThread.getLooper()),
                                userHandle,
                                appSearchImpl);
            }

            AppSearchUserInstance userInstance =
                    new AppSearchUserInstance(
                            logger,
                            appSearchImpl,
                            visibilityCheckerImpl,
                            accountManager,
                            listener,
                            alarmHandlerThread,
                            handleExpiredDocumentsAlarmListener);
            if (Flags.enableDeletePropagationRw()
                    && userInstance.getHandleExpiredDocumentsAlarmListener() != null) {
                long triggerAtMillis =
                        System.currentTimeMillis()
                                + HANDLE_EXPIRED_DOCUMENTS_ALARM_RESET_AT_CREATION_DELAY_MILLIS;
                userInstance.getHandleExpiredDocumentsAlarmListener().maybeReset(triggerAtMillis);
            }
            return userInstance;
        } catch (AppSearchException e) {
            AppSearchResult<Void> failedResult = throwableToFailedResult(e);
            statusCode = failedResult.getResultCode();
            LogUtil.criticalError(TAG, "Failed to create AppSearch instance", e);
            if (Flags.enableCloseAppsearchOnCreationFailure() && appSearchImpl != null) {
                // If we've created the instance, but encountered some issue.
                // Close this instance so that we clean up it's resources.
                appSearchImpl.close();
            }
            throw e;
        } catch (Exception e) {
            LogUtil.criticalError(TAG, "Failed to create AppSearch instance", e);
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
            @Nullable IsolatedStorageServiceManager isolatedStorageServiceManager,
            boolean enableIsolatedStorageReverseMigration,
            boolean isIsolatedStorageAvailable) {
        return mAppSearchUserInstanceExecutorService.submit(
                () ->
                        createUserInstance(
                                userContext,
                                userHandle,
                                config,
                                executorManager,
                                isolatedStorageServiceManager,
                                enableIsolatedStorageReverseMigration,
                                isIsolatedStorageAvailable));
    }

    private AppSearchUserInstance getOrCreateUserInstanceFuture(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @NonNull ServiceAppSearchConfig config,
            @NonNull ExecutorManager executorManager,
            @Nullable IsolatedStorageServiceManager isolatedStorageServiceManager,
            boolean enableIsolatedStorageReverseMigration,
            boolean isIsolatedStorageAvailable)
            throws AppSearchException {
        Future<AppSearchUserInstance> instanceFuture;
        mFutureInstanceMapLock.lock();
        try {
            instanceFuture = getValidUserInstanceFutureLocked(userHandle);
            if (instanceFuture == null) {
                instanceFuture =
                        createUserInstanceFuture(
                                userContext,
                                userHandle,
                                config,
                                executorManager,
                                isolatedStorageServiceManager,
                                enableIsolatedStorageReverseMigration,
                                isIsolatedStorageAvailable);
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
                    "User Instance creation for: " + userHandle + " failed to complete.", e);
        } catch (CancellationException e) {
            throw new AppSearchException(
                    AppSearchResult.RESULT_ABORTED,
                    "User Instance creation for: " + userHandle + " was cancelled.",
                    e);
        }
    }

    /**
     * Creates and registers an {@link OnAccountsUpdateListener} to monitor and handle account
     * changes.
     *
     * @param context The context used to retrieve the {@link AccountManager} system service.
     * @param appsearchImpl The {@link AppSearchImpl} containing the state to be updated.
     * @param user The {@link SystemService.TargetUser} representing the user associated with these
     *     accounts.
     */
    private OnAccountsUpdateListener createOnAccountsUpdateListenerForInstance(
            @NonNull Context context,
            @NonNull AppSearchImpl appsearchImpl,
            @NonNull UserHandle user) {
        AccountManager accountManager =
                Objects.requireNonNull(context.getSystemService(AccountManager.class));

        // Create OnAccountsUpdateListener for the user and set to
        // AppSearchManager
        OnAccountsUpdateListener listener =
                accounts -> {
                    try {
                        updateAccount(accountManager, appsearchImpl, accounts);
                    } catch (AppSearchException e) {
                        Log.e(TAG, "Unable to update account for " + user, e);
                        ExceptionUtil.handleException(e);
                    }
                };
        // Use a dedicated background handler for account updates. Since
        // AppSearch is a mainline module, it cannot access internal system
        // threads. Performing this off the main thread prevents ANRs during
        // database I/O.
        // Set updateImmediately = true to update appsearch account cache
        // immediately.
        accountManager.addOnAccountsUpdatedListener(
                listener, mBackgroundHandler, /* updateImmediately= */ true);
        return listener;
    }

    /**
     * Updates the AppSearch internal state to reflect the current list of Android system accounts.
     *
     * <p>This method retrieves the current list of accounts from {@link AccountManager}. It detects
     * any accounts that have been renamed by checking {@link
     * AccountManager#getPreviousName(Account)}. Finally, it propagates the set of current accounts
     * and the map of renamed accounts to the underlying implementation to perform necessary cleanup
     * (removing data for deleted accounts) or migration (updating data owner for renamed accounts).
     *
     * @param accountManager The {@link AccountManager} system service to get account update
     *     information.
     * @param appsearchImpl The {@link AppSearchImpl} to update.
     * @throws AppSearchException If an error occurs within the AppSearch implementation while
     *     updating the account mapping.
     */
    private static void updateAccount(
            @NonNull AccountManager accountManager,
            @NonNull AppSearchImpl appsearchImpl,
            @NonNull Account[] aliveAccounts)
            throws AppSearchException {
        Set<Account> allExistingAccounts = new ArraySet<>(aliveAccounts.length);
        Map<Account, Account> renamedAccounts = new ArrayMap<>();
        for (int i = 0; i < aliveAccounts.length; i++) {
            String oldName = accountManager.getPreviousName(aliveAccounts[i]);
            if (oldName != null) {
                Account oldAccount = new Account(oldName, aliveAccounts[i].type);
                renamedAccounts.put(oldAccount, aliveAccounts[i]);
            }
            allExistingAccounts.add(aliveAccounts[i]);
        }
        appsearchImpl.updateAccountStore(allExistingAccounts, renamedAccounts);
    }

    /**
     * Get a valid user instance future given a user handle.
     *
     * <p>A valid user instance future is one that is
     *
     * <ul>
     *   <li>Still ongoing i.e. the user instance creation is still happening.
     *   <li>Completed without any exceptions i.e. the user instance creation successfully
     *       completed.
     * </ul>
     *
     * If not valid, null will be returned.
     *
     * <p>To get just the user instance use getUserInstance or getUserInstanceOrNull.
     *
     * @param userHandle The userHandle of the user instance future we are retrieving.
     * @return Future of an AppSearchUserInstance that is either not done or done successfully. Null
     *     if the future was never created, or did not complete successfully.
     */
    @GuardedBy("mFutureInstanceMapLock")
    private Future<AppSearchUserInstance> getValidUserInstanceFutureLocked(UserHandle userHandle) {
        Future<AppSearchUserInstance> instanceFuture;
        instanceFuture = mFutureInstancesLocked.get(userHandle);
        if (instanceFuture == null) {
            if (LogUtil.INFO) {
                Log.i(
                        TAG,
                        "Future was not created. Creating instance for userHandle: " + userHandle);
            }
            return null;
        }
        if (!instanceFuture.isDone()) {
            if (LogUtil.INFO) {
                Log.i(TAG, "User instance creation is ongoing for userHandle: " + userHandle);
            }
            return instanceFuture;
        }
        try {
            instanceFuture.get();
            return instanceFuture;
        } catch (ExecutionException | InterruptedException e) {
            Log.w(
                    TAG,
                    "User instance creation encountered an error for userHandle: " + userHandle,
                    e);
            return null;
        } catch (CancellationException e) {
            Log.w(TAG, "User instance creation was cancelled for userHandle: " + userHandle, e);
            return null;
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
                    AppSearchUserInstance instance = instanceFuture.get();
                    if (instance.getAccountManager() != null) {
                        instance.getAccountManager()
                                .removeOnAccountsUpdatedListener(
                                        instance.getOnAccountsUpdateListener());
                    }

                    // Cancel alarms before destroying AppSearchImpl.
                    instance.cancelAllAlarms();

                    AppSearchImpl appSearchImpl = instance.getAppSearchImpl();
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

    /**
     * Gets the isolated icing engine for the user.
     *
     * @param userContext The calling user's context.
     * @param userHandle The user handle.
     * @param config The service configuration.
     * @param executorManager The executor manager for scheduling tasks.
     * @param skipDataMigration If true, data migration will be skipped from host to VM.
     * @param isolatedStorageServiceManager The manager for isolated storage service.
     * @param logger The AppSearch logger.
     * @param enableIsolatedStorageReverseDataMigration Whether reverse data migration is enabled.
     * @return IcingSearchEngineInterface or null if isolated storage can't be used right away due
     *     to, e.g. pending data migration.
     */
    @Nullable
    private IcingSearchEngineInterface maybeGetIsolatedIcingSearchEngine(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @NonNull ServiceAppSearchConfig config,
            @NonNull ExecutorManager executorManager,
            boolean skipDataMigration,
            @NonNull IsolatedStorageServiceManager isolatedStorageServiceManager,
            @NonNull AppSearchLogger logger,
            boolean enableIsolatedStorageReverseDataMigration) {
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(config);
        Objects.requireNonNull(executorManager);
        Objects.requireNonNull(isolatedStorageServiceManager);
        Objects.requireNonNull(logger);

        final File appSearchDir =
                AppSearchEnvironmentFactory.getEnvironmentInstance()
                        .getAppSearchDir(userContext, userHandle);
        // Check whether this is the first time VM is booted after the feature is enabled.
        boolean vmFirstRun = !DataMigrationUtil.migrationStatusFileExists(userHandle, appSearchDir);
        // Force migration to the VM if the override_isolated_storage_migration_freeze
        // system_property is set
        boolean overrideMigrationFreeze =
                IsolatedStorageServiceManager.overrideIsolatedStorageMigrationFreeze();
        if (vmFirstRun
                && !config.getIsolatedStorageEnableUnfreezingMigration()
                && !overrideMigrationFreeze) {
            // This device hasn't switched to IsolatedStorage yet and migrations are not unfrozen.
            // Do not migrate and just start the normal Icing.
            Log.i(
                    TAG,
                    "Device has not migrated to isolated storage and migration is disabled."
                     + " Falling back to regular AppSearch.");
            return null;
        }

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

        // Check whether this is the first time VM is booted after the feature is enabled.
        if (vmFirstRun) {
            // We always create the migration file if vm is created. It will be empty until data
            // migration actually runs(migration might not needed so it will remain empty). With
            // this file created, it can help us to remove the vm if
            // it gets disabled. During startup, we can check if this file exists, and call vm
            // removal if it does.
            DataMigrationUtil.writeMigrationStatus(appSearchDir, /* migrationStats= */ null);
        }

        if (DataMigrationUtil.needDataMigration(userContext, userHandle)
                        == DataMigrationUtil.MIGRATION_COMPLETED
                || (enableIsolatedStorageReverseDataMigration && skipDataMigration)) {
            // Data migration is not needed. But we still want to log an entry to
            // indicate that data migration is correctly skipped.
            if (vmFirstRun) {
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
                logger.logStats(callStatsBuilder.build());
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
                                                            /* enableVm= */ true,
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

    // Schedule DataMigration to move data from the VM to host.
    private void scheduleReverseDataMigration(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @NonNull ServiceAppSearchConfig config,
            @NonNull ExecutorManager executorManager) {
        Runnable doReverseDataMigration =
                new Runnable() {
                    @Override
                    public void run() {
                        AppSearchUserInstance instance = getUserInstanceOrNull(userHandle);
                        if (instance != null) {
                            File appSearchDir =
                                    AppSearchEnvironmentFactory.getEnvironmentInstance()
                                            .getAppSearchDir(userContext, userHandle);
                            File icingDir = new File(appSearchDir, "icing");
                            IcingSearchEngineOptions options =
                                    config.toIcingSearchEngineOptions(
                                            icingDir.getAbsolutePath(), /* isVMEnabled= */ false);
                            // Create an empty Icing instance on destination as data will be
                            // migrated
                            // back from VM to this host instance.
                            IcingSearchEngineInterface hostIcingInstance =
                                    new com.google.android.icing.IcingSearchEngine(options);

                            DataMigrationUtil.runDataMigrationForUser(
                                    userContext,
                                    userHandle,
                                    instance.getAppSearchImpl(),
                                    hostIcingInstance,
                                    /* enableVm= */ false,
                                    instance.getLogger());
                            // Delete the VM and data migration status file.
                            // TODO(b/465465109): Though we don't want to run reverse migration over
                            // and over if we can't delete the vm.
                            IsolatedStorageServiceManager.cleanUp(
                                    userContext,
                                    unused ->
                                            DataMigrationUtil.deleteMigrationStatus(
                                                    userHandle, appSearchDir));
                        }
                    }
                };
        synchronized (mPerUserMigrationFutureLocked) {
            ScheduledFuture<?> migrationFuture = mPerUserMigrationFutureLocked.get(userHandle);
            if (migrationFuture == null) {
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
                                            doReverseDataMigration);
                                },
                                1, // Add a 1 minute delay to avoid migrating immediately after user
                                // unlock.
                                TimeUnit.MINUTES);
                mPerUserMigrationFutureLocked.put(userHandle, migrationFuture);
                Log.i(TAG, "Reverse Data migration for " + userHandle + " scheduled.");
            }
        }
    }

    @VisibleForTesting
    void lockInstanceMap() {
        mFutureInstanceMapLock.lock();
    }

    @VisibleForTesting
    void unlockInstanceMap() {
        mFutureInstanceMapLock.unlock();
    }

    /**
     * Get the user instance future for a given userHandle for tests only.
     *
     * <p>Unlike getValidUserInstanceFuture, this method will just return the future, regardless of
     * whether its null, ongoing, successfully completed, or unsuccessfully completed.
     *
     * @param userHandle The user that we are trying to retrieve a user instance creation future
     *     for.
     * @return A future of a user instance creation, or null if the future was never created.
     */
    @VisibleForTesting
    Future<AppSearchUserInstance> getUserInstanceCreationFuture(UserHandle userHandle) {
        Future<AppSearchUserInstance> future;
        mFutureInstanceMapLock.lock();
        try {
            future = mFutureInstancesLocked.get(userHandle);
        } finally {
            mFutureInstanceMapLock.unlock();
        }
        return future;
    }
}
