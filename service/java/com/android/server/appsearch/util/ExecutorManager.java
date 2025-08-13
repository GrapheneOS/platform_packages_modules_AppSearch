/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.appsearch.util;

import static android.app.appsearch.AppSearchResult.RESULT_RATE_LIMITED;
import static android.app.appsearch.AppSearchResult.throwableToFailedResult;

import static com.android.server.appsearch.util.ServiceImplHelper.invokeCallbackOnError;
import static com.android.server.appsearch.util.ServiceImplHelper.invokeCallbackOnResult;

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.aidl.AppSearchResultParcel;
import android.app.appsearch.aidl.IAppSearchBatchResultCallback;
import android.app.appsearch.aidl.IAppSearchResultCallback;
import android.app.appsearch.annotation.CanIgnoreReturnValue;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;

import com.android.appsearch.flags.Flags;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.AppSearchRateLimitConfig;
import com.android.server.appsearch.FrameworkServiceAppSearchConfig;
import com.android.server.appsearch.ServiceAppSearchConfig;
import com.android.server.appsearch.external.localstorage.stats.CallStats;
import com.android.server.appsearch.isolated_storage_service.IsolatedStorageServiceManager;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Manages executors within AppSearch.
 *
 * <p>This class is thread-safe.
 *
 * @hide
 */
public class ExecutorManager {
    private static final long AWAIT_DURATION_MS = 30 * 1000;

    /**
     * Creates a new {@link ExecutorService} with default settings for use in AppSearch.
     *
     * <p>The default settings are to use as many threads as there are CPUs. The core pool size is 1
     * if cached executors should be used, or also the CPU number if fixed executors should be used.
     */
    @NonNull
    public static ExecutorService createDefaultExecutorService() {
        boolean useFixedExecutorService =
                FrameworkServiceAppSearchConfig.getUseFixedExecutorService();
        int corePoolSize = useFixedExecutorService ? Runtime.getRuntime().availableProcessors() : 1;
        long keepAliveTime = useFixedExecutorService ? 0L : 60L;

        return AppSearchEnvironmentFactory.getEnvironmentInstance()
                .createExecutorService(
                        /* corePoolSize= */ corePoolSize,
                        /* maxConcurrency= */ Runtime.getRuntime().availableProcessors(),
                        /* keepAliveTime= */ keepAliveTime,
                        /* unit= */ TimeUnit.SECONDS,
                        /* workQueue= */ new LinkedBlockingQueue<>(),
                        /* priority= */ 0); // priority is unused.
    }

    /**
     * Creates a new {@link ExecutorService} with fixed thread settings for use in AppSearch.
     */
    @NonNull
    public static ExecutorService createReadOnlyExecutorService() {
        return AppSearchEnvironmentFactory.getEnvironmentInstance()
                .createExecutorService(
                        /* corePoolSize= */ 2,
                        /* maxConcurrency= */ 2,
                        /* keepAliveTime= */ 0L,
                        /* unit= */ TimeUnit.SECONDS,
                        /* workQueue= */ new LinkedBlockingQueue<>(),
                        /* priority= */ 0); // priority is unused.
    }

    /**
     * Creates a new single-threaded {@link ScheduledExecutorService} for scheduling and running
     * background tasks in AppSearch.
     */
    @NonNull
    public static ScheduledExecutorService createDefaultScheduledExecutorService() {
        return Executors.newSingleThreadScheduledExecutor();
    }

    private final ServiceAppSearchConfig mAppSearchConfig;
    private final boolean mIsIsolatedStorageAvailable;

    /**
     * A map of per-user executors for queued work. These can be started or shut down via this
     * class's public API.
     */
    @GuardedBy("mPerUserExecutorsLocked")
    private final Map<UserHandle, ExecutorService> mPerUserExecutorsLocked = new ArrayMap<>();

    /**
     * A map of per-user executors for queued read-only work. These can be started or shut down via
     * this class's public API.
     */
    @GuardedBy("mPerUserReadOnlyExecutorsLocked")
    private final Map<UserHandle, ExecutorService> mPerUserReadOnlyExecutorsLocked =
            new ArrayMap<>();

    /**
     * A map of per-user scheduled executors for scheduled work. These can be started or shut down
     * via this class's public API.
     */
    // TODO: b/433949020 - merge scheduled executor with mPerUserExecutorsLocked
    @GuardedBy("mPerUserScheduledExecutorsLocked")
    private final Map<UserHandle, ScheduledExecutorService> mPerUserScheduledExecutorsLocked =
            new ArrayMap<>();

    /**
     * Constructs a new ExecutorManager.
     *
     * @param appSearchConfig Configuration to use for configuring executors
     * @param isIsolatedStorageAvailable Whether Isolated Storage is enabled, which overrides some
     *     settings which otherwise come from the config.
     */
    public ExecutorManager(
            @NonNull ServiceAppSearchConfig appSearchConfig, boolean isIsolatedStorageAvailable) {
        mAppSearchConfig = Objects.requireNonNull(appSearchConfig);
        mIsIsolatedStorageAvailable = isIsolatedStorageAvailable;
    }

    /** Sets the user executor for a given {@link UserHandle} for testing. */
    @VisibleForTesting
    public void setUserExecutorForTest(@NonNull UserHandle userHandle,
            @NonNull ExecutorService executorService) {
        synchronized (mPerUserExecutorsLocked) {
            mPerUserExecutorsLocked.put(userHandle, executorService);
        }
    }

    /** Sets the read-only user executor for a given {@link UserHandle} for testing. */
    @VisibleForTesting
    public void setReadOnlyUserExecutorForTest(@NonNull UserHandle userHandle,
            @NonNull ExecutorService executorService) {
        synchronized (mPerUserReadOnlyExecutorsLocked) {
            mPerUserReadOnlyExecutorsLocked.put(userHandle, executorService);
        }
    }

    /**
     * Gets the executor service for the given user, creating it if it does not exist.
     *
     * <p>If AppSearch rate limiting is enabled, the input rate Limit config will be non-null, and
     * the returned executor will be a RateLimitedExecutor instance.
     *
     * <p>You are responsible for making sure not to call this for locked users. The executor will
     * be created without problems but most operations on locked users will fail.
     */
    @NonNull
    public Executor getOrCreateUserExecutor(@NonNull UserHandle userHandle, boolean isReadOnly) {
        Objects.requireNonNull(userHandle);
        if (areSeparateReadWriteExecutorsEnabled(userHandle) && isReadOnly) {
            synchronized (mPerUserReadOnlyExecutorsLocked) {
                if (mAppSearchConfig.getCachedRateLimitEnabled()) {
                    return getOrCreateUserRateLimitedExecutorLocked(userHandle,
                            mPerUserReadOnlyExecutorsLocked,
                            ExecutorManager::createReadOnlyExecutorService,
                            mAppSearchConfig.getCachedRateLimitConfig());
                } else {
                    return getOrCreateUserExecutorLocked(userHandle,
                            mPerUserReadOnlyExecutorsLocked,
                            ExecutorManager::createReadOnlyExecutorService);
                }
            }
        } else {
            synchronized (mPerUserExecutorsLocked) {
                if (mAppSearchConfig.getCachedRateLimitEnabled()) {
                    return getOrCreateUserRateLimitedExecutorLocked(userHandle,
                            mPerUserExecutorsLocked, ExecutorManager::createDefaultExecutorService,
                            mAppSearchConfig.getCachedRateLimitConfig());
                } else {
                    return getOrCreateUserExecutorLocked(userHandle, mPerUserExecutorsLocked,
                            ExecutorManager::createDefaultExecutorService);
                }
            }
        }
    }

    /** Gets the scheduled executor service for the given user, creating it if it does not exist. */
    @NonNull
    public ScheduledExecutorService getOrCreateUserScheduledExecutor(
            @NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        synchronized (mPerUserScheduledExecutorsLocked) {
            ScheduledExecutorService executor = mPerUserScheduledExecutorsLocked.get(userHandle);
            if (executor == null) {
                executor = createDefaultScheduledExecutorService();
                mPerUserScheduledExecutorsLocked.put(userHandle, executor);
            }
            return executor;
        }
    }

    /**
     * Gracefully shuts down the executor for the given user if there is one, waiting up to 30
     * seconds for jobs to finish.
     */
    public void shutDownAndRemoveUserExecutor(@NonNull UserHandle userHandle)
            throws InterruptedException {
        Objects.requireNonNull(userHandle);
        ExecutorService executor;
        synchronized (mPerUserExecutorsLocked) {
            executor = mPerUserExecutorsLocked.remove(userHandle);
        }
        if (executor != null) {
            executor.shutdown();
        }

        ExecutorService readOnlyExecutor = null;
        if (areSeparateReadWriteExecutorsEnabled(userHandle)) {
            synchronized (mPerUserReadOnlyExecutorsLocked) {
                readOnlyExecutor = mPerUserReadOnlyExecutorsLocked.remove(userHandle);
            }
            if (readOnlyExecutor != null) {
                readOnlyExecutor.shutdown();
            }
        }

        ScheduledExecutorService scheduleExecutor;
        synchronized (mPerUserScheduledExecutorsLocked) {
            scheduleExecutor = mPerUserScheduledExecutorsLocked.remove(userHandle);
        }
        if (scheduleExecutor != null) {
            scheduleExecutor.shutdown();
        }

        // Wait a little bit to finish outstanding requests. It's important not to call
        // shutdownNow because nothing would pass a final result to the caller, leading to
        // hangs. If we are interrupted or the timeout elapses, just move on to closing the
        // user instance, meaning pending tasks may crash when AppSearchImpl closes under
        // them.
        long awaitStartTimeMs = SystemClock.elapsedRealtime();
        long awaitDurationMs = AWAIT_DURATION_MS;
        if (executor != null) {
            executor.awaitTermination(awaitDurationMs, TimeUnit.MILLISECONDS);
        }
        if (areSeparateReadWriteExecutorsEnabled(userHandle)) {
            awaitDurationMs =
                    AWAIT_DURATION_MS - (SystemClock.elapsedRealtime() - awaitStartTimeMs);
            if (readOnlyExecutor != null && awaitDurationMs > 0) {
                readOnlyExecutor.awaitTermination(awaitDurationMs, TimeUnit.MILLISECONDS);
            }
        }
        awaitDurationMs = AWAIT_DURATION_MS - (SystemClock.elapsedRealtime() - awaitStartTimeMs);
        if (scheduleExecutor != null && awaitDurationMs > 0) {
            scheduleExecutor.awaitTermination(awaitDurationMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Helper to execute the implementation of some AppSearch functionality on the executor for that
     * user.
     *
     * @param targetUser The verified user the call should run as.
     * @param errorCallback Callback to complete with an error if starting the lambda fails.
     *     Otherwise this callback is not triggered.
     * @param callingPackageName Package making this lambda call.
     * @param apiType Api type of this lambda call.
     * @param lambda The lambda to execute on the user-provided executor.
     * @return true if the call is accepted by the executor and false otherwise.
     */
    @BinderThread
    @CanIgnoreReturnValue
    public boolean executeLambdaForUserAsync(
            @NonNull UserHandle targetUser,
            @NonNull IAppSearchResultCallback errorCallback,
            @NonNull String callingPackageName,
            @CallStats.CallType int apiType,
            @NonNull Runnable lambda) {
        Objects.requireNonNull(targetUser);
        Objects.requireNonNull(errorCallback);
        Objects.requireNonNull(callingPackageName);
        Objects.requireNonNull(lambda);
        try {
            boolean isReadOnly = isReadOnlyCall(apiType);
            Executor executor = getOrCreateUserExecutor(targetUser, isReadOnly);
            if (executor instanceof RateLimitedExecutor) {
                boolean callAccepted =
                        ((RateLimitedExecutor) executor)
                                .execute(lambda, callingPackageName, apiType);
                if (!callAccepted) {
                    invokeCallbackOnResult(
                            errorCallback,
                            AppSearchResultParcel.fromFailedResult(
                                    AppSearchResult.newFailedResult(
                                            RESULT_RATE_LIMITED,
                                            "AppSearch rate limit reached.")));
                    return false;
                }
            } else {
                executor.execute(lambda);
            }
        } catch (RuntimeException e) {
            AppSearchResult<?> failedResult = throwableToFailedResult(e);
            invokeCallbackOnResult(
                    errorCallback, AppSearchResultParcel.fromFailedResult(failedResult));
        }
        return true;
    }

    /**
     * Helper to execute the implementation of some AppSearch functionality on the executor for that
     * user.
     *
     * @param targetUser The verified user the call should run as.
     * @param errorCallback Callback to complete with an error if starting the lambda fails.
     *     Otherwise this callback is not triggered.
     * @param callingPackageName Package making this lambda call.
     * @param apiType Api type of this lambda call.
     * @param lambda The lambda to execute on the user-provided executor.
     * @return true if the call is accepted by the executor and false otherwise.
     */
    @BinderThread
    public boolean executeLambdaForUserAsync(
            @NonNull UserHandle targetUser,
            @NonNull IAppSearchBatchResultCallback errorCallback,
            @NonNull String callingPackageName,
            @CallStats.CallType int apiType,
            @NonNull Runnable lambda) {
        Objects.requireNonNull(targetUser);
        Objects.requireNonNull(errorCallback);
        Objects.requireNonNull(callingPackageName);
        Objects.requireNonNull(lambda);
        try {
            boolean isReadOnly = isReadOnlyCall(apiType);
            Executor executor = getOrCreateUserExecutor(targetUser, isReadOnly);
            if (executor instanceof RateLimitedExecutor) {
                boolean callAccepted =
                        ((RateLimitedExecutor) executor)
                                .execute(lambda, callingPackageName, apiType);
                if (!callAccepted) {
                    invokeCallbackOnError(
                            errorCallback,
                            AppSearchResult.newFailedResult(
                                    RESULT_RATE_LIMITED, "AppSearch rate limit reached."));
                    return false;
                }
            } else {
                executor.execute(lambda);
            }
        } catch (RuntimeException e) {
            invokeCallbackOnError(errorCallback, e);
        }
        return true;
    }

    /**
     * Helper to execute the implementation of some AppSearch functionality on the executor for that
     * user, without invoking callback for the user.
     *
     * @param targetUser The verified user the call should run as.
     * @param callingPackageName Package making this lambda call.
     * @param apiType Api type of this lambda call.
     * @param lambda The lambda to execute on the user-provided executor.
     * @return true if the call is accepted by the executor and false otherwise.
     */
    @BinderThread
    public boolean executeLambdaForUserNoCallbackAsync(
            @NonNull UserHandle targetUser,
            @NonNull String callingPackageName,
            @CallStats.CallType int apiType,
            @NonNull Runnable lambda) {
        Objects.requireNonNull(targetUser);
        Objects.requireNonNull(callingPackageName);
        Objects.requireNonNull(lambda);
        boolean isReadOnly = isReadOnlyCall(apiType);
        Executor executor = getOrCreateUserExecutor(targetUser, isReadOnly);
        if (executor instanceof RateLimitedExecutor) {
            return ((RateLimitedExecutor) executor).execute(lambda, callingPackageName, apiType);
        } else {
            executor.execute(lambda);
            return true;
        }
    }

    /**
     * Helper to execute the implementation of some AppSearch functionality on the executor for that
     * user, without invoking callback for the user.
     *
     * @param targetUser The verified user the call should run as.
     * @param isReadOnly Whether the given lambda performs only AppSearch read operations.
     * @param lambda The lambda to execute on the user-provided executor.
     */
    public void executeLambdaForUserNoCallbackAsync(
            @NonNull UserHandle targetUser, boolean isReadOnly, @NonNull Runnable lambda) {
        Objects.requireNonNull(targetUser);
        Objects.requireNonNull(lambda);
        getOrCreateUserExecutor(targetUser, isReadOnly).execute(lambda);
    }

    /**
     * Schedules a task to be executed on the ScheduledExecutorService for the given user.
     *
     * @param targetUser The user for whom the task should be scheduled.
     * @param lambda     The task to be executed.
     * @param delay      The time from now to delay execution.
     * @param unit       The time unit of the delay parameter.
     * @return the ScheduledFuture for the task.
     */
    public ScheduledFuture<?> scheduleLambdaForUserNoCallbackAsync(
            @NonNull UserHandle targetUser,
            @NonNull Runnable lambda,
            long delay,
            @NonNull TimeUnit unit) {
        Objects.requireNonNull(targetUser);
        Objects.requireNonNull(lambda);
        Objects.requireNonNull(unit);

        synchronized (mPerUserScheduledExecutorsLocked) {
            return getOrCreateUserScheduledExecutor(targetUser).schedule(lambda, delay, unit);
        }
    }

    @NonNull
    private Executor getOrCreateUserExecutorLocked(@NonNull UserHandle userHandle,
            @NonNull Map<UserHandle, ExecutorService> userExecutorMap,
            @NonNull Supplier<ExecutorService> executorSupplier) {
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(userExecutorMap);
        Objects.requireNonNull(executorSupplier);
        ExecutorService executor = userExecutorMap.get(userHandle);
        if (executor == null) {
            executor = executorSupplier.get();
            userExecutorMap.put(userHandle, executor);
        } else if (executor instanceof RateLimitedExecutor) {
            executor = ((RateLimitedExecutor) executor).getExecutor();
        }
        return executor;
    }

    @NonNull
    private Executor getOrCreateUserRateLimitedExecutorLocked(
            @NonNull UserHandle userHandle,
            @NonNull Map<UserHandle, ExecutorService> userExecutorMap,
            @NonNull Supplier<ExecutorService> executorSupplier,
            @NonNull AppSearchRateLimitConfig rateLimitConfig) {
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(userExecutorMap);
        Objects.requireNonNull(executorSupplier);
        Objects.requireNonNull(rateLimitConfig);
        ExecutorService executor = userExecutorMap.get(userHandle);
        if (executor instanceof RateLimitedExecutor) {
            ((RateLimitedExecutor) executor).setRateLimitConfig(rateLimitConfig);
        } else {
            executor = new RateLimitedExecutor(executorSupplier.get(), rateLimitConfig);
            userExecutorMap.put(userHandle, executor);
        }
        return executor;
    }

    /** Returns whether or not an API call is a read-only AppSearch operation. */
    private boolean isReadOnlyCall(@CallStats.CallType int apiType) {
        switch (apiType) {
            case CallStats.CALL_TYPE_INITIALIZE: // creates AppSearch user instance which is a
                // different lock
            case CallStats.CALL_TYPE_GET_DOCUMENTS:
            case CallStats.CALL_TYPE_GET_DOCUMENT:
            case CallStats.CALL_TYPE_SEARCH:
            case CallStats.CALL_TYPE_GLOBAL_SEARCH:
            case CallStats.CALL_TYPE_GLOBAL_GET_DOCUMENT_BY_ID:
            case CallStats.CALL_TYPE_GLOBAL_GET_SCHEMA:
            case CallStats.CALL_TYPE_GET_SCHEMA:
            case CallStats.CALL_TYPE_GET_NAMESPACES:
            case CallStats.CALL_TYPE_GET_NEXT_PAGE:
            case CallStats.CALL_TYPE_INVALIDATE_NEXT_PAGE_TOKEN:
            case CallStats.CALL_TYPE_WRITE_SEARCH_RESULTS_TO_FILE: // search + write to file
            case CallStats.CALL_TYPE_SEARCH_SUGGESTION:
            case CallStats.CALL_TYPE_GET_STORAGE_INFO:
            case CallStats.CALL_TYPE_REGISTER_OBSERVER_CALLBACK: // doesn't actually use executor
            case CallStats.CALL_TYPE_UNREGISTER_OBSERVER_CALLBACK: // doesn't actually use executor
            case CallStats.CALL_TYPE_GLOBAL_GET_NEXT_PAGE:
            case CallStats.CALL_TYPE_OPEN_READ_BLOB:
            case CallStats.CALL_TYPE_GLOBAL_OPEN_READ_BLOB:
                return true;
            default:
                return false;
        }
    }

    private boolean areSeparateReadWriteExecutorsEnabled(@NonNull UserHandle userHandle) {
        if (Flags.enableSeparateReadWriteExecutors()) {
            return true;
        }
        // Even without the manual flag, separate executors are always used for isolated storage.
        if (mIsIsolatedStorageAvailable
                && IsolatedStorageServiceManager.isUserAllowed(userHandle)) {
            return true;
        }

        return false;
    }
}
