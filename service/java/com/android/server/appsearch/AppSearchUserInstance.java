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
package com.android.server.appsearch;

import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.HandlerThread;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.visibilitystore.VisibilityChecker;

import java.util.Objects;

/**
 * Container for AppSearch classes that should only be initialized once per device-user and make up
 * the core of the AppSearch system.
 */
public final class AppSearchUserInstance {
    private volatile InternalAppSearchLogger mLogger;
    private final AppSearchImpl mAppSearchImpl;
    private final VisibilityChecker mVisibilityChecker;
    @Nullable private AccountManager mAccountManager;
    @Nullable private OnAccountsUpdateListener mOnAccountsUpdateListener;
    @Nullable private HandlerThread mAlarmHandlerThread;
    @Nullable private HandleExpiredDocumentsAlarmListener mHandleExpiredDocumentsAlarmListener;

    AppSearchUserInstance(
            @NonNull InternalAppSearchLogger logger,
            @NonNull AppSearchImpl appSearchImpl,
            @NonNull VisibilityChecker visibilityChecker,
            @Nullable AccountManager accountManager,
            @Nullable OnAccountsUpdateListener onAccountsUpdateListener,
            @Nullable HandlerThread alarmHandlerThread,
            @Nullable HandleExpiredDocumentsAlarmListener handleExpiredDocumentsAlarmListener) {
        mLogger = Objects.requireNonNull(logger);
        mAppSearchImpl = Objects.requireNonNull(appSearchImpl);
        mVisibilityChecker = Objects.requireNonNull(visibilityChecker);
        mAccountManager = accountManager;
        mOnAccountsUpdateListener = onAccountsUpdateListener;
        mAlarmHandlerThread = alarmHandlerThread;
        mHandleExpiredDocumentsAlarmListener = handleExpiredDocumentsAlarmListener;
    }

    @NonNull
    public InternalAppSearchLogger getLogger() {
        return mLogger;
    }

    @NonNull
    public AppSearchImpl getAppSearchImpl() {
        return mAppSearchImpl;
    }

    @NonNull
    public VisibilityChecker getVisibilityChecker() {
        return mVisibilityChecker;
    }

    /**
     * Gets the {@link AccountManager} that is used to retrieve account update information service.
     */
    @Nullable
    public AccountManager getAccountManager() {
        return mAccountManager;
    }

    /**
     * Gets the {@link OnAccountsUpdateListener} that is used to listen for account changes. Null if
     * the feature is disabled.
     */
    @Nullable
    public OnAccountsUpdateListener getOnAccountsUpdateListener() {
        return mOnAccountsUpdateListener;
    }

    /**
     * Gets the {@link HandleExpiredDocumentsAlarmListener} that is used to reset alarm for handle
     * expired documents background task. Null if delete propagation feature is disabled.
     */
    @Nullable
    public HandleExpiredDocumentsAlarmListener getHandleExpiredDocumentsAlarmListener() {
        return mHandleExpiredDocumentsAlarmListener;
    }

    /** Cancels all alarms and quits the thread. */
    public void cancelAllAlarms() {
        // Cancel handleExpiredDocuments alarm.
        if (mHandleExpiredDocumentsAlarmListener != null) {
            mHandleExpiredDocumentsAlarmListener.terminate();
        }

        // Finally, quit the alarm handler thread.
        if (mAlarmHandlerThread != null) {
            mAlarmHandlerThread.quit();
        }
    }

    /** Whether the pVM is enabled in AppSearch */
    public boolean isVMEnabled() {
        return mAppSearchImpl.isVMEnabled();
    }

    @VisibleForTesting
    void setLoggerForTest(@NonNull InternalAppSearchLogger logger) {
        mLogger = Objects.requireNonNull(logger);
    }
}
