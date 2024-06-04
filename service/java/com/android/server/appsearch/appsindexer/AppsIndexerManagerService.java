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

import static android.os.Process.INVALID_UID;

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchEnvironment;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.util.LogUtil;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.CancellationSignal;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalManagerRegistry;
import com.android.server.SystemService;
import com.android.server.appsearch.indexer.IndexerLocalService;

import java.io.File;
import java.io.PrintWriter;
import java.util.Map;
import java.util.Objects;

/**
 * Manages the per device-user AppsIndexer instance to index apps into AppSearch.
 *
 * <p>This class is thread-safe.
 *
 * @hide
 */
public final class AppsIndexerManagerService extends SystemService {
    private static final String TAG = "AppSearchAppsIndexerManagerS";

    private final Context mContext;
    private final LocalService mLocalService;

    // Map of AppsIndexerUserInstances indexed by the UserHandle
    @GuardedBy("mAppsIndexersLocked")
    private final Map<UserHandle, AppsIndexerUserInstance> mAppsIndexersLocked = new ArrayMap<>();

    private final AppsIndexerConfig mAppsIndexerConfig;

    /** Constructs a {@link AppsIndexerManagerService}. */
    public AppsIndexerManagerService(
            @NonNull Context context, @NonNull AppsIndexerConfig appsIndexerConfig) {
        super(context);
        mContext = Objects.requireNonNull(context);
        mAppsIndexerConfig = Objects.requireNonNull(appsIndexerConfig);
        mLocalService = new LocalService();
    }

    @Override
    public void onStart() {
        registerReceivers();
        LocalManagerRegistry.addManager(LocalService.class, mLocalService);
    }

    /** Runs when a user is unlocked. This will attempt to run an initial sync. */
    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        try {
            Objects.requireNonNull(user);
            UserHandle userHandle = user.getUserHandle();
            synchronized (mAppsIndexersLocked) {
                AppsIndexerUserInstance instance = mAppsIndexersLocked.get(userHandle);
                if (instance == null) {
                    AppSearchEnvironment appSearchEnvironment =
                            AppSearchEnvironmentFactory.getEnvironmentInstance();
                    Context userContext =
                            appSearchEnvironment.createContextAsUser(mContext, userHandle);
                    File appSearchDir =
                            appSearchEnvironment.getAppSearchDir(userContext, userHandle);
                    File appsDir = new File(appSearchDir, "apps");
                    instance =
                            AppsIndexerUserInstance.createInstance(
                                    userContext, appsDir, mAppsIndexerConfig);
                    if (LogUtil.DEBUG) {
                        Log.d(TAG, "Created Apps Indexer instance for user " + userHandle);
                    }
                    mAppsIndexersLocked.put(userHandle, instance);
                }

                instance.updateAsync(/* firstRun= */ true);
            }
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "AppsIndexerManagerService.onUserUnlocking() failed ", e);
        } catch (AppSearchException e) {
            Log.e(TAG, "Error while start Apps Indexer", e);
        }
    }

    /** Handles user stopping by shutting down the instance for the user. */
    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        try {
            Objects.requireNonNull(user);
            UserHandle userHandle = user.getUserHandle();
            synchronized (mAppsIndexersLocked) {
                AppsIndexerUserInstance instance = mAppsIndexersLocked.get(userHandle);
                if (instance != null) {
                    mAppsIndexersLocked.remove(userHandle);
                    try {
                        instance.shutdown();
                    } catch (InterruptedException e) {
                        Log.w(TAG, "Failed to shutdown apps indexer for " + userHandle, e);
                    }
                }
            }
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "AppsIndexerManagerService.onUserStopping() failed ", e);
        }
    }

    /** Dumps AppsIndexer internal state for the user. */
    @BinderThread
    public void dumpAppsIndexerForUser(@NonNull UserHandle userHandle, @NonNull PrintWriter pw) {
        try {
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(pw);
            synchronized (mAppsIndexersLocked) {
                AppsIndexerUserInstance instance = mAppsIndexersLocked.get(userHandle);
                if (instance != null) {
                    instance.dump(pw);
                } else {
                    pw.println("AppsIndexerUserInstance is not created for " + userHandle);
                }
            }
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "AppsIndexerManagerService.dumpAppsIndexerForUser() failed ", e);
        }
    }

    /**
     * Registers a broadcast receiver to get package changed (disabled/enabled) and package data
     * cleared events.
     */
    private void registerReceivers() {
        IntentFilter appChangedFilter = new IntentFilter();
        appChangedFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        appChangedFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        appChangedFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        appChangedFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        appChangedFilter.addDataScheme("package");

        mContext.registerReceiverForAllUsers(
                new AppsProviderChangedReceiver(),
                appChangedFilter,
                /* broadcastPermission= */ null,
                /* scheduler= */ null);
        if (LogUtil.DEBUG) {
            Log.v(TAG, "Registered receiver for package events");
        }
    }

    /**
     * Broadcast receiver to handle package events and index them into the AppSearch
     * "builtin:MobileApplication" schema.
     *
     * <p>This broadcast receiver allows the apps indexer to listen to events which indicate that
     * app info was changed.
     */
    private class AppsProviderChangedReceiver extends BroadcastReceiver {

        /**
         * Checks if the entire package was changed, or if the intent just represents a component
         * change.
         */
        private boolean isEntirePackageChanged(@NonNull Intent intent) {
            Objects.requireNonNull(intent);
            String[] changedComponents =
                    intent.getStringArrayExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);
            if (changedComponents == null) {
                Log.e(TAG, "Received ACTION_PACKAGE_CHANGED event with null changed components");
                return false;
            }
            if (intent.getData() == null) {
                Log.e(TAG, "Received ACTION_PACKAGE_CHANGED event with null data");
                return false;
            }
            String changedPackage = intent.getData().getSchemeSpecificPart();
            for (int i = 0; i < changedComponents.length; i++) {
                String changedComponent = changedComponents[i];
                // If the state of the overall package has changed, then it will contain
                // an entry with the package name itself.
                if (changedComponent.equals(changedPackage)) {
                    return true;
                }
            }
            return false;
        }

        /** Handles intents related to package changes. */
        @Override
        public void onReceive(@NonNull Context context, @NonNull Intent intent) {
            try {
                Objects.requireNonNull(context);
                Objects.requireNonNull(intent);

                switch (intent.getAction()) {
                    case Intent.ACTION_PACKAGE_CHANGED:
                        if (!isEntirePackageChanged(intent)) {
                            // If it was just a component change, do not run the indexer
                            return;
                        }
                        // fall through
                    case Intent.ACTION_PACKAGE_ADDED:
                    case Intent.ACTION_PACKAGE_REPLACED:
                    case Intent.ACTION_PACKAGE_FULLY_REMOVED:
                        // TODO(b/275592563): handle more efficiently based on package event type
                        // TODO(b/275592563): determine if batching is necessary in the case of
                        //  rapid updates

                        int uid = intent.getIntExtra(Intent.EXTRA_UID, INVALID_UID);
                        if (uid == INVALID_UID) {
                            Log.w(TAG, "uid is missing in the intent: " + intent);
                            return;
                        }
                        Log.d(TAG, "userid in package receiver: " + uid);
                        UserHandle userHandle = UserHandle.getUserHandleForUid(uid);
                        mLocalService.doUpdateForUser(userHandle, /* unused= */ null);
                        break;
                    default:
                        Log.w(TAG, "Received unknown intent: " + intent);
                }
            } catch (RuntimeException e) {
                Slog.wtf(TAG, "AppsProviderChangedReceiver.onReceive() failed ", e);
            }
        }
    }

    public class LocalService implements IndexerLocalService {
        /** Runs an update for a user. */
        @Override
        public void doUpdateForUser(
                @NonNull UserHandle userHandle, @Nullable CancellationSignal unused) {
            // TODO(b/275592563): handle cancellation signal to abort the job.
            Objects.requireNonNull(userHandle);
            synchronized (mAppsIndexersLocked) {
                AppsIndexerUserInstance instance = mAppsIndexersLocked.get(userHandle);
                if (instance != null) {
                    instance.updateAsync(/* firstRun= */ false);
                }
            }
        }
    }
}
