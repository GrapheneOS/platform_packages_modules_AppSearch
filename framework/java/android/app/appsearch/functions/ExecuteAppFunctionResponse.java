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

package android.app.appsearch.functions;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.flags.Flags;
import android.app.appsearch.safeparcel.AbstractSafeParcelable;
import android.app.appsearch.safeparcel.GenericDocumentParcel;
import android.app.appsearch.safeparcel.SafeParcelable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Represents a response of an execution of an app function.
 *
 * @see AppFunctionManager#executeAppFunction(ExecuteAppFunctionRequest, Executor, Consumer)
 */
@SafeParcelable.Class(creator = "ExecuteAppFunctionResponseCreator")
@FlaggedApi(Flags.FLAG_ENABLE_APP_FUNCTIONS)
public final class ExecuteAppFunctionResponse extends AbstractSafeParcelable {
    /**
     * The key used to store the result within the result {@link GenericDocument}.
     *
     * @see #getResult().
     */
    public static final String KEY_RESULT = "result";

    @NonNull
    public static final Parcelable.Creator<ExecuteAppFunctionResponse> CREATOR =
            new ExecuteAppFunctionResponseCreator();

    @Field(id = 1)
    @NonNull
    final GenericDocumentParcel mResult;

    @Field(id = 2, getter = "getExtras")
    @NonNull
    private final Bundle mExtras;

    @NonNull
    private final GenericDocument mResultCached;

    @Constructor
    ExecuteAppFunctionResponse(
            @Param(id = 1) @NonNull GenericDocumentParcel result,
            @Param(id = 2) @NonNull Bundle extras) {
        mResult = Objects.requireNonNull(result);
        mResultCached = new GenericDocument(mResult);
        mExtras = extras;
    }

    private ExecuteAppFunctionResponse(@NonNull GenericDocument result, @NonNull Bundle extras) {
        mResultCached = Objects.requireNonNull(result);
        mResult = mResultCached.getDocumentParcel();
        mExtras = Objects.requireNonNull(extras);
    }

    /**
     * Returns the return value of the executed function. An empty document indicates that the
     * function does not produce a return value.
     */
    @NonNull
    public GenericDocument getResult() {
        return mResultCached;
    }

    /** Returns the additional data relevant to the result of a function execution. */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        ExecuteAppFunctionResponseCreator.writeToParcel(this, dest, flags);
    }

    /** The builder for creating {@link ExecuteAppFunctionResponse} instances. */
    @FlaggedApi(Flags.FLAG_ENABLE_APP_FUNCTIONS)
    public static final class Builder {
        @Nullable
        private GenericDocument mResult;
        @Nullable
        private Bundle mExtras;

        /**
         * Sets the result of the app function execution. A {@code null} value indicates that the
         * function does not produce a return value.
         */
        @NonNull
        public Builder setResult(@Nullable GenericDocument result) {
            mResult = result;
            return this;
        }

        /** Sets the additional data relevant to the result of a function execution. */
        @NonNull
        public Builder setExtras(@Nullable Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Constructs a new {@link ExecuteAppFunctionResponse from the contents of this builder.
         */
        @NonNull
        public ExecuteAppFunctionResponse build() {
            return new ExecuteAppFunctionResponse(
                    mResult == null ? GenericDocument.EMPTY : mResult,
                    mExtras == null ? Bundle.EMPTY : mExtras);
        }
    }
}
