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
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.util.LogUtil;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.CancellationSignal;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import com.android.appsearch.flags.Flags;
import com.android.server.LocalManagerRegistry;
import com.android.server.SystemService;
import com.android.server.appsearch.indexer.IndexerForceUpdateConfig;
import com.android.server.appsearch.indexer.IndexerLocalService;

import java.io.PrintWriter;
import java.util.List;
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

    private final AppsIndexerConfig mAppsIndexerConfig;
    private final IndexerForceUpdateConfig mAppsIndexerForceUpdateConfig;

    /** Constructs a {@link AppsIndexerManagerService}. */
    public AppsIndexerManagerService(
            @NonNull Context context, @NonNull AppsIndexerConfig appsIndexerConfig) {
        super(context);
        mContext = Objects.requireNonNull(context);
        mAppsIndexerConfig = Objects.requireNonNull(appsIndexerConfig);
        mAppsIndexerForceUpdateConfig = new FrameworkAppsIndexerForceUpdateConfig();
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
            AppsIndexerUserInstanceManager.getInstance()
                    .handleUserUnlock(
                            mContext,
                            userHandle,
                            mAppsIndexerConfig,
                            mAppsIndexerForceUpdateConfig);
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
            AppsIndexerUserInstanceManager.getInstance().removeInstance(userHandle);
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
            AppsIndexerUserInstance instance =
                    AppsIndexerUserInstanceManager.getInstance().getUserInstanceOrNull(userHandle);
            if (instance != null) {
                instance.dump(pw);
            } else {
                pw.println("AppsIndexerUserInstance is not created for " + userHandle);
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
        if (Flags.enableAppsIndexerLocaleChangeFullUpdate()) {
            appChangedFilter.addAction(Intent.ACTION_LOCALE_CHANGED);
        }
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
         * Return true if the entire package was changed, or if the AppFunction Component was
         * changed, false otherwise.
         */
        private boolean shouldRunIndexerOnPackageChange(
                @NonNull Intent intent, @NonNull UserHandle userHandle) {
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

                // If the state of AppFunctionService component is changed within the package,
                // indexer should be rerun. AppFunctionService component is identified by checking
                // if it matches the app function intent filter.
                Intent appFunctionServiceIntent =
                        new Intent("android.app.appfunctions.AppFunctionService")
                                .setPackage(changedPackage);
                // Include disabled components in the query because if this broadcast is
                // disabling AppFunctionService, the component may already be disabled and would
                // otherwise be skipped
                List<ResolveInfo> services =
                        mContext.createContextAsUser(userHandle, /* flags= */ 0)
                                .getPackageManager()
                                .queryIntentServices(
                                        appFunctionServiceIntent,
                                        /* flags= */ PackageManager.MATCH_DISABLED_COMPONENTS);

                for (int j = 0; j < services.size(); j++) {
                    if (changedComponent.equals(services.get(j).serviceInfo.name)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Handles intents related to package changes. */
        @Override
        public void onReceive(@NonNull Context context, @NonNull Intent intent) {
            int uid = intent.getIntExtra(Intent.EXTRA_UID, INVALID_UID);
            if (uid == INVALID_UID) {
                Log.w(TAG, "uid is missing in the intent: " + intent);
                return;
            }
            UserHandle userHandle = UserHandle.getUserHandleForUid(uid);
            try {
                Objects.requireNonNull(context);
                Objects.requireNonNull(intent);

                switch (intent.getAction()) {
                    case Intent.ACTION_PACKAGE_CHANGED:
                        if (!shouldRunIndexerOnPackageChange(intent, userHandle)) {
                            // If it was just a component change, do not run the indexer
                            return;
                        }
                    // fall through
                    case Intent.ACTION_PACKAGE_ADDED:
                    case Intent.ACTION_PACKAGE_REPLACED:
                    case Intent.ACTION_PACKAGE_FULLY_REMOVED:
                    case Intent.ACTION_LOCALE_CHANGED:
                        // TODO(b/437400460): Remove if statement once flag is rolled out.
                        if (intent.getAction().equals(Intent.ACTION_LOCALE_CHANGED)
                                && !Flags.enableAppsIndexerLocaleChangeFullUpdate()) {
                            // Skip if flag is not enabled
                            break;
                        }
                        // TODO(b/275592563): handle more efficiently based on package event type
                        // TODO(b/275592563): determine if batching is necessary in the case of
                        //  rapid updates
                        if (LogUtil.DEBUG) {
                            Log.d(TAG, "userid in package receiver: " + uid);
                        }
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
            AppsIndexerUserInstanceManager.getInstance()
                    .handleUpdateForUser(
                            userHandle, /* firstRun= */ false, /* isForceUpdateTriggered= */ false);
        }
    }
}
