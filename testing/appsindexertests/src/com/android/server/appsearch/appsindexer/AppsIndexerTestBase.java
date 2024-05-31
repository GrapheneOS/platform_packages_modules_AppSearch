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

import android.annotation.NonNull;
import android.app.appsearch.GlobalSearchSessionShim;
import android.app.appsearch.observer.DocumentChangeInfo;
import android.app.appsearch.observer.ObserverCallback;
import android.app.appsearch.observer.ObserverSpec;
import android.app.appsearch.observer.SchemaChangeInfo;
import android.app.appsearch.testutil.GlobalSearchSessionShimImpl;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;

import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import org.junit.After;
import org.junit.Before;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class AppsIndexerTestBase {
    protected GlobalSearchSessionShim mShim;
    protected ObserverCallback mCallback;
    protected BroadcastReceiver mCapturedReceiver;
    private static final Executor EXECUTOR = Executors.newCachedThreadPool();
    protected Context mContext;

    @Before
    public void setUp() throws Exception {
        mContext =
                new ContextWrapper(ApplicationProvider.getApplicationContext()) {

                    @Nullable
                    @Override
                    public Intent registerReceiverForAllUsers(
                            @Nullable BroadcastReceiver receiver,
                            @NonNull IntentFilter filter,
                            @Nullable String broadcastPermission,
                            @Nullable Handler scheduler) {
                        mCapturedReceiver = receiver;
                        return super.registerReceiverForAllUsers(
                                receiver,
                                filter,
                                broadcastPermission,
                                scheduler,
                                RECEIVER_EXPORTED);
                    }
                };
        mShim = GlobalSearchSessionShimImpl.createGlobalSearchSessionAsync(mContext).get();
    }

    @After
    public void tearDown() throws Exception {
        if (mShim != null && mCallback != null) {
            mShim.unregisterObserverCallback(mContext.getPackageName(), mCallback);
        }
    }

    protected CountDownLatch setupLatch(int numChanges) throws Exception {
        return setupLatch(numChanges, /* listenForSchemaChanges= */ false);
    }

    /**
     * Sets up or resets the latch for observing changes, and registers a universal observer
     * callback if it hasn't been registered before. The method configures the callback to listen
     * for either schema or document changes based on the boolean parameter.
     *
     * @param numChanges the number of changes to count down
     * @param listenForSchemaChanges if true, listens for schema changes; if false, listens for
     *     document changes
     */
    protected CountDownLatch setupLatch(int numChanges, boolean listenForSchemaChanges)
            throws Exception {
        CountDownLatch latch = new CountDownLatch(numChanges);
        // Unregister existing callback if any
        if (mCallback != null) {
            mShim.unregisterObserverCallback(mContext.getPackageName(), mCallback);
        }
        mCallback =
                new ObserverCallback() {
                    @Override
                    public void onSchemaChanged(@NonNull SchemaChangeInfo changeInfo) {
                        if (!listenForSchemaChanges) {
                            return;
                        }
                        // When we delete apps, we delete the schema.
                        Set<String> changedSchemas = changeInfo.getChangedSchemaNames();
                        for (String changedSchema : changedSchemas) {
                            if (changedSchema.startsWith(MobileApplication.SCHEMA_TYPE)) {
                                latch.countDown();
                            }
                        }
                    }

                    @Override
                    public void onDocumentChanged(@NonNull DocumentChangeInfo changeInfo) {
                        if (listenForSchemaChanges) {
                            return;
                        }
                        if (!changeInfo.getSchemaName().startsWith(MobileApplication.SCHEMA_TYPE)) {
                            return;
                        }
                        for (int i = 0; i < changeInfo.getChangedDocumentIds().size(); i++) {
                            latch.countDown();
                        }
                    }
                };
        mShim.registerObserverCallback(
                mContext.getPackageName(), new ObserverSpec.Builder().build(), EXECUTOR, mCallback);
        return latch;
    }
}
