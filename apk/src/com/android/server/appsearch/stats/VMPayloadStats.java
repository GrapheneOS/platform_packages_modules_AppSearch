/*
 * Copyright 2025 The Android Open Source Project
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
package com.android.server.appsearch.stats;

import androidx.annotation.IntDef;

import org.jspecify.annotations.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents statistics related to a VM payload execution. This class captures information about
 * the lifecycle of a payload execution, including callback types, exit codes, error codes, and stop
 * reasons.
 *
 * @hide
 */
public class VMPayloadStats {
    /** Call types for the VM payload execution. */
    @IntDef(
            value = {
                CALLBACK_TYPE_UNKNOWN,
                CALLBACK_TYPE_START,
                CALLBACK_TYPE_READY,
                CALLBACK_TYPE_FINISH,
                CALLBACK_TYPE_ERROR,
                CALLBACK_TYPE_STOP,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PayloadCallbackType {}

    /** Represents an unknown callback type. */
    public static final int CALLBACK_TYPE_UNKNOWN = 0;

    /** Represents the start of the payload execution. */
    public static final int CALLBACK_TYPE_START = 1;

    /** Represents the payload being ready for execution. */
    public static final int CALLBACK_TYPE_READY = 2;

    /** Represents the successful completion of the payload execution. */
    public static final int CALLBACK_TYPE_FINISH = 3;

    /** Represents an error during the payload execution. */
    public static final int CALLBACK_TYPE_ERROR = 4;

    /** Represents the stopping of the payload execution. */
    public static final int CALLBACK_TYPE_STOP = 5;

    /** The last callback type represents the number of callback types. */
    public static final int PAYLOAD_CALLBACK_TYPE_SIZE = CALLBACK_TYPE_STOP + 1;

    /** The callback type of the payload execution. */
    @PayloadCallbackType private final int mCallbackType;

    /**
     * The exit code of the payload execution. {@code
     * IsolateStorageService.VmCallback#onPayloadFinished}.
     */
    private final int mExitCode;

    /** The error code of the payload execution {@code IsolateStorageService.VmCallback#onError}. */
    private final int mErrorCode;

    /**
     * The reason for stopping the payload execution in {@code
     * IsolateStorageService.VmCallback#onStop}.
     */
    private final int mStopReason;

    /**
     * Constructs a new {@link VMPayloadStats} object using the provided builder.
     *
     * @param builder The builder containing the statistics.
     */
    VMPayloadStats(@NonNull Builder builder) {
        mCallbackType = builder.mCallbackType;
        mExitCode = builder.mExitCode;
        mErrorCode = builder.mErrorCode;
        mStopReason = builder.mStopReason;
    }

    /** Returns the callback type of the payload execution. */
    public int getCallbackType() {
        return mCallbackType;
    }

    /** Returns the exit code of the payload execution. */
    public int getExitCode() {
        return mExitCode;
    }

    /** Returns the error code of the payload execution. */
    public int getErrorCode() {
        return mErrorCode;
    }

    /** Returns the reason for stopping the payload execution. */
    public int getStopReason() {
        return mStopReason;
    }

    /** Builder for {@link VMPayloadStats}. */
    public static class Builder {
        int mCallbackType;
        int mExitCode;
        int mErrorCode;
        int mStopReason;

        /** Constructs a new {@link Builder} with the specified callback type. */
        public Builder(@PayloadCallbackType int callbackType) {
            mCallbackType = callbackType;
        }

        /** Sets the exit code of the payload execution. */
        public @NonNull Builder setExitCode(int exitCode) {
            mExitCode = exitCode;
            return this;
        }

        /** Sets the error code of the payload execution. */
        public @NonNull Builder setErrorCode(int errorCode) {
            mErrorCode = errorCode;
            return this;
        }

        /** Sets the reason for stopping the payload execution. */
        public @NonNull Builder setStopReason(int stopReason) {
            mStopReason = stopReason;
            return this;
        }

        /** Builds a new {@link VMPayloadStats} object. */
        public VMPayloadStats build() {
            return new VMPayloadStats(/* builder= */ this);
        }
    }
}
