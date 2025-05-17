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
package com.android.server.appsearch.isolated_storage_service;

import android.os.Handler;
import android.os.Looper;

/** Signals VM state changes. */
public final class VmStateSignaler {
    private static final long INACTIVITY_TIMEOUT_MS = 20 * 1000; // 20 seconds
    private final Handler mHandler;
    private final Runnable mVmStateIdleSetter;

    private volatile Boolean mIsIdle = null;

    public VmStateSignaler() {
        System.loadLibrary("appsearchservice");
        mHandler = new Handler(Looper.getMainLooper());
        mVmStateIdleSetter =
                () -> {
                    if (mIsIdle == null || !mIsIdle) {
                        mIsIdle = true;
                        notifyIdle(true);
                    }
                };
    }

    /**
     * Signals that the VM is active.
     *
     * <p>Also resets the inactivity timeout.
     */
    public void signalActive() {
        mHandler.removeCallbacks(mVmStateIdleSetter);

        if (mIsIdle == null || mIsIdle) {
            mIsIdle = false;
            notifyIdle(false);
        }

        mHandler.postDelayed(mVmStateIdleSetter, INACTIVITY_TIMEOUT_MS);
    }

    /** Signals that the VM is idle. */
    private static native void notifyIdle(boolean idle);
}
