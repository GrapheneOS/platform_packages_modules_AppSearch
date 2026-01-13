/*
 * Copyright (C) 2026 The Android Open Source Project
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

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.app.AlarmManager;
import android.app.appsearch.util.LogUtil;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;

import com.android.appsearch.flags.Flags;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;

import com.google.android.icing.proto.HandleExpiredDocumentsResultProto;

import java.util.Objects;

/**
 * An AlarmListener for handle expired documents background task.
 *
 * @hide
 */
public class HandleExpiredDocumentsAlarmListener implements AlarmManager.OnAlarmListener {
    private static final String TAG = "AppSearchHandleExpDocAL";

    @NonNull private final AlarmManager mAlarmManager;
    @NonNull private final Handler mHandler;

    @NonNull private final UserHandle mUserHandle;
    @NonNull private final AppSearchImpl mAppSearchImpl;

    private @CurrentTimeMillisLong long mTriggerAtMillis;
    private boolean mTerminated;

    public HandleExpiredDocumentsAlarmListener(
            @NonNull AlarmManager alarmManager,
            @NonNull Handler handler,
            @NonNull UserHandle userHandle,
            @NonNull AppSearchImpl appSearchImpl) {
        mAlarmManager = Objects.requireNonNull(alarmManager);
        mHandler = Objects.requireNonNull(handler);
        mUserHandle = Objects.requireNonNull(userHandle);
        mAppSearchImpl = Objects.requireNonNull(appSearchImpl);
        mTriggerAtMillis = -1;
        mTerminated = false;
    }

    /**
     * Resets handle expired documents alarm at {@code triggerAtMillis} if necessary.
     *
     * <p>{@code triggerAtMillis} is in the {@link System#currentTimeMillis} time base.
     *
     * <p>When the alarm fires, it will purge expired documents and propagate deletion to child
     * documents with delete propagation enabled on the schema property definition.
     *
     * <ul>
     *   <li>Functions only if {@link Flags#enableDeletePropagationRw} is enabled.
     *   <li>If {@code targetTimestampMillis} is smaller than 0 or {@link Long#MAX_VALUE}, then
     *       no-op.
     *   <li>If there is no pending alarm scheduled previously OR {@code triggerAtMillis} is smaller
     *       than the trigger timestamp of the pending alarm, then a new alarm will be set and
     *       replace the pending one.
     *   <li>Otherwise, it is no-op.
     * </ul>
     *
     * @param triggerAtMillis the target timestamp (in millis) for the alarm.
     */
    public void maybeReset(@CurrentTimeMillisLong long triggerAtMillis) {
        if (!Flags.enableDeletePropagationRw()
                || triggerAtMillis < 0
                || triggerAtMillis == Long.MAX_VALUE) {
            return;
        }

        synchronized (this) {
            if (mTerminated) {
                return;
            }

            boolean needsSet = mTriggerAtMillis < 0 || triggerAtMillis < mTriggerAtMillis;
            if (!needsSet) {
                return;
            }

            // Cancel the pending alarm if scheduled previously.
            if (mTriggerAtMillis >= 0) {
                mAlarmManager.cancel(this);
            }

            mTriggerAtMillis = triggerAtMillis;
            mAlarmManager.set(
                    AlarmManager.RTC,
                    triggerAtMillis,
                    /* tag= */ "handleExpiredDocumentsAlarm_user" + mUserHandle.getIdentifier(),
                    this,
                    mHandler);

            if (LogUtil.DEBUG) {
                Log.d(
                        TAG,
                        "Successfully reset handleExpiredDocuments alarm for user "
                                + mUserHandle.getIdentifier());
            }
        }
    }

    /**
     * Terminates the alarm listener. It will cancel pending alarms and avoid setting any new
     * alarms.
     */
    public void terminate() {
        synchronized (this) {
            if (mTriggerAtMillis >= 0) {
                mAlarmManager.cancel(this);
                mTriggerAtMillis = -1;
            }
            mTerminated = true;
        }
    }

    @Override
    public void onAlarm() {
        // Once this method is called the alarm has already been fired and removed from
        // AlarmManager. The alarm can now be marked as unscheduled so that it can be rescheduled by
        // other places.
        boolean needsRun = false;
        synchronized (this) {
            if (mTriggerAtMillis >= 0 && !mTerminated) {
                needsRun = true;
            }
            mTriggerAtMillis = -1;
        }

        if (needsRun) {
            if (LogUtil.DEBUG) {
                Log.d(TAG, "handleExpiredDocuments alarm fires");
            }

            try {
                HandleExpiredDocumentsResultProto resultProto =
                        mAppSearchImpl.handleExpiredDocuments();
                // Reset the alarm according to the result proto.
                maybeReset(resultProto.getNextExpirationTimestampMs());
            } catch (Throwable e) {
                Log.e(TAG, "Failed to run handleExpiredDocuments alarm", e);
            }
        }
    }
}
