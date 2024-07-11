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
import android.annotation.WorkerThread;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.BatchResultCallback;
import android.app.appsearch.exceptions.AppSearchException;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Contains common methods for converting async methods to sync. */
public class SyncAppSearchBase {
    protected final Object mSessionLock = new Object();
    protected final Executor mExecutor;

    public SyncAppSearchBase(@NonNull Executor executor) {
        mExecutor = Objects.requireNonNull(executor);
    }

    @WorkerThread
    protected <T> T executeAppSearchResultOperation(
            Consumer<Consumer<AppSearchResult<T>>> operation) throws AppSearchException {
        final CompletableFuture<AppSearchResult<T>> futureResult = new CompletableFuture<>();

        // Without this catch + completeExceptionally, this crashes the device if the operation
        // throws an error.
        mExecutor.execute(
                () -> {
                    try {
                        operation.accept(futureResult::complete);
                    } catch (Exception e) {
                        futureResult.completeExceptionally(e);
                    }
                });

        try {
            // TODO(b/275592563): Change to get timeout value from config
            AppSearchResult<T> result = futureResult.get();

            if (!result.isSuccess()) {
                throw new AppSearchException(result.getResultCode(), result.getErrorMessage());
            }

            return result.getResultValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppSearchException(
                    AppSearchResult.RESULT_INTERNAL_ERROR, "Operation was interrupted.", e);
        } catch (ExecutionException e) {
            throw new AppSearchException(
                    AppSearchResult.RESULT_UNKNOWN_ERROR,
                    "Error executing operation.",
                    e.getCause());
        }
    }

    @WorkerThread
    protected <T, V> AppSearchBatchResult<T, V> executeAppSearchBatchResultOperation(
            Consumer<BatchResultCallback<T, V>> operation) throws AppSearchException {
        final CompletableFuture<AppSearchBatchResult<T, V>> futureResult =
                new CompletableFuture<>();

        mExecutor.execute(
                () -> {
                    try {
                        operation.accept(
                                new BatchResultCallback<>() {
                                    @Override
                                    public void onResult(
                                            @NonNull AppSearchBatchResult<T, V> value) {
                                        futureResult.complete(value);
                                    }

                                    @Override
                                    public void onSystemError(@Nullable Throwable throwable) {
                                        futureResult.completeExceptionally(throwable);
                                    }
                                });
                    } catch (Exception e) {
                        futureResult.completeExceptionally(e);
                    }
                });

        try {
            // TODO(b/275592563): Change to get timeout value from config
            return futureResult.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppSearchException(
                    AppSearchResult.RESULT_INTERNAL_ERROR, "Operation was interrupted.", e);
        } catch (ExecutionException e) {
            throw new AppSearchException(
                    AppSearchResult.RESULT_UNKNOWN_ERROR,
                    "Error executing operation.",
                    e.getCause());
        }
    }
}
