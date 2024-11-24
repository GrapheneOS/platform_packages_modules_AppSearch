/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.server.appsearch.indexer.IndexerMaintenanceConfig.APPS_INDEXER;
import static com.android.server.appsearch.indexer.IndexerMaintenanceConfig.APP_OPEN_EVENT_INDEXER;
import static com.android.server.appsearch.indexer.IndexerMaintenanceConfig.CONTACTS_INDEXER;

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.util.ExceptionUtil;
import android.app.appsearch.util.LogUtil;
import android.content.Context;
import android.os.UserHandle;
import android.util.Log;

import com.android.appsearch.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;
import com.android.server.appsearch.appsindexer.AppOpenEventIndexerConfig;
import com.android.server.appsearch.appsindexer.AppOpenEventIndexerManagerService;
import com.android.server.appsearch.appsindexer.AppsIndexerConfig;
import com.android.server.appsearch.appsindexer.AppsIndexerManagerService;
import com.android.server.appsearch.appsindexer.FrameworkAppOpenEventIndexerConfig;
import com.android.server.appsearch.appsindexer.FrameworkAppsIndexerConfig;
import com.android.server.appsearch.contactsindexer.ContactsIndexerConfig;
import com.android.server.appsearch.contactsindexer.ContactsIndexerManagerService;
import com.android.server.appsearch.contactsindexer.FrameworkContactsIndexerConfig;
import com.android.server.appsearch.indexer.IndexerMaintenanceService;

import java.io.PrintWriter;
import java.util.Objects;

/** This class encapsulate the lifecycle methods of AppSearch module. */
public class AppSearchModule {
    private static final String TAG = "AppSearchModule";

    /** Lifecycle definition for AppSearch module. */
    public static class Lifecycle extends SystemService {
        private AppSearchManagerService mAppSearchManagerService;
        @VisibleForTesting @Nullable ContactsIndexerManagerService mContactsIndexerManagerService;

        @VisibleForTesting @Nullable AppsIndexerManagerService mAppsIndexerManagerService;

        @VisibleForTesting @Nullable
        AppOpenEventIndexerManagerService mAppOpenEventIndexerManagerService;

        public Lifecycle(Context context) {
            super(context);
        }

        /** Added primarily for testing purposes. */
        @VisibleForTesting
        @NonNull
        AppSearchManagerService createAppSearchManagerService(
                @NonNull Context context, @NonNull AppSearchModule.Lifecycle lifecycle) {
            Objects.requireNonNull(context);
            Objects.requireNonNull(lifecycle);
            return new AppSearchManagerService(context, lifecycle);
        }

        /** Added primarily for testing purposes. */
        @VisibleForTesting
        @NonNull
        AppsIndexerManagerService createAppsIndexerManagerService(
                @NonNull Context context, @NonNull AppsIndexerConfig config) {
            Objects.requireNonNull(context);
            Objects.requireNonNull(config);
            return new AppsIndexerManagerService(context, config);
        }

        /** Added primarily for testing purposes. */
        @VisibleForTesting
        @NonNull
        ContactsIndexerManagerService createContactsIndexerManagerService(
                @NonNull Context context, @NonNull ContactsIndexerConfig config) {
            Objects.requireNonNull(context);
            Objects.requireNonNull(config);
            return new ContactsIndexerManagerService(context, config);
        }

        /** Added primarily for testing purposes. */
        @VisibleForTesting
        @NonNull
        AppOpenEventIndexerManagerService createAppOpenEventIndexerManagerService(
                @NonNull Context context, @NonNull AppOpenEventIndexerConfig config) {
            Objects.requireNonNull(context);
            Objects.requireNonNull(config);
            return new AppOpenEventIndexerManagerService(context, config);
        }

        @Override
        public void onStart() {
            mAppSearchManagerService =
                    createAppSearchManagerService(getContext(), /* lifecycle= */ this);

            try {
                mAppSearchManagerService.onStart();
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to start AppSearch service", e);
                // If AppSearch service fails to start, skip starting ContactsIndexer service
                // since it indexes CP2 contacts into AppSearch builtin:Person corpus
                ExceptionUtil.handleException(e);
                return;
            }

            // It is safe to check DeviceConfig here, since SettingsProvider, which DeviceConfig
            // uses, starts before AppSearch.
            ContactsIndexerConfig contactsIndexerConfig = new FrameworkContactsIndexerConfig();
            if (contactsIndexerConfig.isContactsIndexerEnabled()) {

                mContactsIndexerManagerService =
                        createContactsIndexerManagerService(getContext(), contactsIndexerConfig);
                try {
                    mContactsIndexerManagerService.onStart();
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to start ContactsIndexer service", t);
                    // Release the Contacts Indexer instance as it won't be started until the next
                    // system_server restart on a device reboot.
                    mContactsIndexerManagerService = null;
                }
            } else if (LogUtil.INFO) {
                Log.i(TAG, "ContactsIndexer service is disabled.");
            }

            AppsIndexerConfig appsIndexerConfig = new FrameworkAppsIndexerConfig();
            // Flags.appsIndexerEnabled will be rolled out through gantry, and this check will be
            // removed once it is fully rolled out. appsIndexerConfig.isAppsIndexerEnabled checks
            // DeviceConfig, so we can keep this check here in case we need to turn off apps
            // indexer.
            if (Flags.appsIndexerEnabled() && appsIndexerConfig.isAppsIndexerEnabled()) {
                mAppsIndexerManagerService =
                        createAppsIndexerManagerService(getContext(), appsIndexerConfig);
                try {
                    mAppsIndexerManagerService.onStart();
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to start AppsIndexer service", t);
                    mAppsIndexerManagerService = null;
                }
            } else if (LogUtil.INFO) {
                Log.i(TAG, "AppsIndexer service is disabled.");
            }

            AppOpenEventIndexerConfig appOpenEventIndexerConfig =
                    new FrameworkAppOpenEventIndexerConfig();
            // Flags.appOpenEventIndexerEnabled will be rolled out through gantry, and this check
            // will be removed once it is fully rolled out.
            // appOpenEventIndexerConfig.isAppOpenEventIndexerEnabled checks DeviceConfig, so we can
            // keep this check here in case we need to turn off app open event indexer.
            if (Flags.appOpenEventIndexerEnabled()
                    && appOpenEventIndexerConfig.isAppOpenEventIndexerEnabled()) {
                mAppOpenEventIndexerManagerService =
                        createAppOpenEventIndexerManagerService(
                                getContext(), appOpenEventIndexerConfig);
                try {
                    mAppOpenEventIndexerManagerService.onStart();
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to start app open event indexer service", t);
                    mAppOpenEventIndexerManagerService = null;
                }
            } else if (LogUtil.INFO) {
                Log.i(TAG, "AppOpenEventIndexer service is disabled.");
            }
        }

        /** Dumps ContactsIndexer internal state for the user. */
        @BinderThread
        void dumpContactsIndexerForUser(
                @NonNull UserHandle userHandle, @NonNull PrintWriter pw, boolean verbose) {
            if (mContactsIndexerManagerService != null) {
                mContactsIndexerManagerService.dumpContactsIndexerForUser(userHandle, pw, verbose);
            } else {
                pw.println("No dumpsys for ContactsIndexer as it is disabled.");
            }
        }

        @BinderThread
        void dumpAppsIndexerForUser(@NonNull UserHandle userHandle, @NonNull PrintWriter pw) {
            if (mAppsIndexerManagerService != null) {
                mAppsIndexerManagerService.dumpAppsIndexerForUser(userHandle, pw);
            } else {
                pw.println("No dumpsys for AppsIndexer as it is disabled");
            }
        }

        @BinderThread
        void dumpAppOpenEventIndexerForUser(
                @NonNull UserHandle userHandle, @NonNull PrintWriter pw) {
            if (mAppOpenEventIndexerManagerService != null) {
                mAppOpenEventIndexerManagerService.dumpAppOpenEventIndexerForUser(userHandle, pw);
            } else {
                pw.println("No dumpsys for AppOpenEventIndexer as it is disabled");
            }
        }

        @Override
        public void onBootPhase(int phase) {
            mAppSearchManagerService.onBootPhase(phase);
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            mAppSearchManagerService.onUserUnlocking(user);
            if (mContactsIndexerManagerService == null) {
                IndexerMaintenanceService.cancelUpdateJobIfScheduled(
                        getContext(), user.getUserHandle(), CONTACTS_INDEXER);
            } else {
                mContactsIndexerManagerService.onUserUnlocking(user);
            }

            if (mAppsIndexerManagerService == null) {
                IndexerMaintenanceService.cancelUpdateJobIfScheduled(
                        getContext(), user.getUserHandle(), APPS_INDEXER);
            } else {
                mAppsIndexerManagerService.onUserUnlocking(user);
            }

            if (mAppOpenEventIndexerManagerService == null) {
                IndexerMaintenanceService.cancelUpdateJobIfScheduled(
                        getContext(), user.getUserHandle(), APP_OPEN_EVENT_INDEXER);
            } else {
                // App Open Event Indexer only schedules a periodic update job on user unlock and
                // does not run an update
                mAppOpenEventIndexerManagerService.onUserUnlocking(user);
            }
        }

        @Override
        public void onUserStopping(@NonNull TargetUser user) {
            mAppSearchManagerService.onUserStopping(user);
            if (mContactsIndexerManagerService != null) {
                mContactsIndexerManagerService.onUserStopping(user);
            }
            if (mAppsIndexerManagerService != null) {
                mAppsIndexerManagerService.onUserStopping(user);
            }
            if (mAppOpenEventIndexerManagerService != null) {
                mAppOpenEventIndexerManagerService.onUserStopping(user);
            }
        }
    }
}
