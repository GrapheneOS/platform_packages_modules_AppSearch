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
import android.os.UserHandle;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.server.appsearch.AppSearchRateLimitConfig;
import com.android.server.appsearch.FrameworkServiceAppSearchConfig;
import com.android.server.appsearch.ServiceAppSearchConfig;
import com.android.server.appsearch.external.localstorage.stats.CallStats;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Manages executors within AppSearch.
 *
 * <p>This class is thread-safe.
 *
 * @hide
 */
public class ExecutorManager {
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

    private final ServiceAppSearchConfig mAppSearchConfig;

    /**
     * A map of per-user executors for queued work. These can be started or shut down via this
     * class's public API.
     */
    @GuardedBy("mPerUserExecutorsLocked")
    private final Map<UserHandle, ExecutorService> mPerUserExecutorsLocked = new ArrayMap<>();

    public ExecutorManager(@NonNull ServiceAppSearchConfig appSearchConfig) {
        mAppSearchConfig = Objects.requireNonNull(appSearchConfig);
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
    public Executor getOrCreateUserExecutor(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        synchronized (mPerUserExecutorsLocked) {
            if (mAppSearchConfig.getCachedRateLimitEnabled()) {
                return getOrCreateUserRateLimitedExecutorLocked(
                        userHandle, mAppSearchConfig.getCachedRateLimitConfig());
            } else {
                return getOrCreateUserExecutorLocked(userHandle);
            }
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
            // Wait a little bit to finish outstanding requests. It's important not to call
            // shutdownNow because nothing would pass a final result to the caller, leading to
            // hangs. If we are interrupted or the timeout elapses, just move on to closing the
            // user instance, meaning pending tasks may crash when AppSearchImpl closes under
            // them.
            executor.awaitTermination(30, TimeUnit.SECONDS);
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
            synchronized (mPerUserExecutorsLocked) {
                Executor executor = getOrCreateUserExecutor(targetUser);
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
            }
        } catch (RuntimeException e) {
            AppSearchResult failedResult = throwableToFailedResult(e);
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
            synchronized (mPerUserExecutorsLocked) {
                Executor executor = getOrCreateUserExecutor(targetUser);
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
        synchronized (mPerUserExecutorsLocked) {
            Executor executor = getOrCreateUserExecutor(targetUser);
            if (executor instanceof RateLimitedExecutor) {
                return ((RateLimitedExecutor) executor)
                        .execute(lambda, callingPackageName, apiType);
            } else {
                executor.execute(lambda);
                return true;
            }
        }
    }

    /**
     * Helper to execute the implementation of some AppSearch functionality on the executor for that
     * user, without invoking callback for the user.
     *
     * @param targetUser The verified user the call should run as.
     * @param lambda The lambda to execute on the user-provided executor.
     */
    public void executeLambdaForUserNoCallbackAsync(
            @NonNull UserHandle targetUser, @NonNull Runnable lambda) {
        Objects.requireNonNull(targetUser);
        Objects.requireNonNull(lambda);

        synchronized (mPerUserExecutorsLocked) {
            getOrCreateUserExecutor(targetUser).execute(lambda);
        }
    }

    @GuardedBy("mPerUserExecutorsLocked")
    @NonNull
    private Executor getOrCreateUserExecutorLocked(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        ExecutorService executor = mPerUserExecutorsLocked.get(userHandle);
        if (executor == null) {
            executor = ExecutorManager.createDefaultExecutorService();
            mPerUserExecutorsLocked.put(userHandle, executor);
        } else if (executor instanceof RateLimitedExecutor) {
            executor = ((RateLimitedExecutor) executor).getExecutor();
        }
        return executor;
    }

    @GuardedBy("mPerUserExecutorsLocked")
    @NonNull
    private Executor getOrCreateUserRateLimitedExecutorLocked(
            @NonNull UserHandle userHandle, @NonNull AppSearchRateLimitConfig rateLimitConfig) {
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(rateLimitConfig);
        ExecutorService executor = mPerUserExecutorsLocked.get(userHandle);
        if (executor instanceof RateLimitedExecutor) {
            ((RateLimitedExecutor) executor).setRateLimitConfig(rateLimitConfig);
        } else {
            executor =
                    new RateLimitedExecutor(
                            ExecutorManager.createDefaultExecutorService(), rateLimitConfig);
            mPerUserExecutorsLocked.put(userHandle, executor);
        }
        return executor;
    }
}
