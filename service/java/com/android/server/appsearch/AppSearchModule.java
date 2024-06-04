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

import static com.android.server.appsearch.indexer.IndexerMaintenanceConfig.CONTACTS_INDEXER;

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.util.ExceptionUtil;
import android.app.appsearch.util.LogUtil;
import android.content.Context;
import android.os.UserHandle;
import android.util.Log;

import com.android.server.SystemService;
import com.android.server.appsearch.contactsindexer.ContactsIndexerConfig;
import com.android.server.appsearch.contactsindexer.ContactsIndexerManagerService;
import com.android.server.appsearch.contactsindexer.FrameworkContactsIndexerConfig;
import com.android.server.appsearch.indexer.IndexerMaintenanceService;

import java.io.PrintWriter;

/** This class encapsulate the lifecycle methods of AppSearch module. */
public class AppSearchModule {
    private static final String TAG = "AppSearchModule";

    /** Lifecycle definition for AppSearch module. */
    public static final class Lifecycle extends SystemService {
        private AppSearchManagerService mAppSearchManagerService;
        @Nullable private ContactsIndexerManagerService mContactsIndexerManagerService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mAppSearchManagerService =
                    new AppSearchManagerService(getContext(), /* lifecycle= */ this);

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
                        new ContactsIndexerManagerService(getContext(), contactsIndexerConfig);
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
        }

        @Override
        public void onUserStopping(@NonNull TargetUser user) {
            mAppSearchManagerService.onUserStopping(user);
            if (mContactsIndexerManagerService != null) {
                mContactsIndexerManagerService.onUserStopping(user);
            }
        }
    }
}
