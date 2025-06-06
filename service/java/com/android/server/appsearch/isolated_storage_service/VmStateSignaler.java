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
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Signals VM state changes.
 *
 * <p>The signaler tries to set the VM to idle if there is no activity for a certain amount of time.
 * The signaler needs to be enabled explicitly.
 */
final class VmStateSignaler {
    private static final String TAG = "VmStateSignaler";
    private static final long INACTIVITY_TIMEOUT_MS = 20 * 1000; // 20 seconds
    private static final long ENABLEMENT_DELAY_MS = 60 * 1000; // 60 seconds
    private final Handler mHandler;
    private final Runnable mVmStateIdleSetter;
    private final ReentrantLock mLock = new ReentrantLock();

    @GuardedBy("mLock")
    private int mNumActivitiesLocked = 0;

    @GuardedBy("mLock")
    private boolean mEnabledLocked = false;

    @GuardedBy("mLock")
    private Boolean mIsIdleLocked = null;

    public VmStateSignaler() {
        System.loadLibrary("appsearchservice");
        mHandler = new Handler(Looper.getMainLooper());
        mVmStateIdleSetter =
                () -> {
                    synchronized (mLock) {
                        if (mIsIdleLocked == null || !mIsIdleLocked) {
                            mIsIdleLocked = true;
                            notifyIdle(true);
                        }
                    }
                };
    }

    /** Schedules the signaler to be enabled. */
    public void scheduleEnablement() {
        synchronized (mLock) {
            if (mEnabledLocked) {
                Log.i(TAG, "already enabled");
                return;
            }
            mHandler.postDelayed(
                    () -> {
                        synchronized (mLock) {
                            mEnabledLocked = true;
                        }
                    },
                    ENABLEMENT_DELAY_MS);
        }
    }

    /** Signals that a VM activity starts. */
    public void signalActivityStarts() {
        synchronized (mLock) {
            mNumActivitiesLocked++;

            if (!mEnabledLocked) {
                return;
            }
            mHandler.removeCallbacks(mVmStateIdleSetter);

            if (mIsIdleLocked == null || mIsIdleLocked) {
                mIsIdleLocked = false;
                notifyIdle(false);
            }
        }
    }

    /**
     * Signals that the a VM activity ends.
     *
     * <p>Resets the inactivity timeout.
     */
    public void signalActivityEnds() {
        synchronized (mLock) {
            mNumActivitiesLocked--;
            if (mNumActivitiesLocked < 0) {
                Log.wtf(TAG, "mNumActivitiesLocked =" + mNumActivitiesLocked);
            }
            if (mEnabledLocked && mNumActivitiesLocked == 0) {
                mHandler.postDelayed(mVmStateIdleSetter, INACTIVITY_TIMEOUT_MS);
            }
        }
    }

    /** Signals that the VM is idle. */
    private static native void notifyIdle(boolean idle);

    @Override
    protected void finalize() throws Throwable {
        if (mLock.tryLock()) {
            try {
                if (mNumActivitiesLocked != 0) {
                    Log.wtf(
                            TAG,
                            "finalizing VmStateSignaler with mNumActivitiesLocked ="
                                    + mNumActivitiesLocked);
                }
            } finally {
                mLock.unlock();
            }
        }
        super.finalize();
    }
}
