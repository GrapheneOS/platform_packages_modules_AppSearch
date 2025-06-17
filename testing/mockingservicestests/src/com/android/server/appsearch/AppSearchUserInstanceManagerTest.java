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

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.FrameworkAppSearchEnvironment;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.content.Context;
import android.os.UserHandle;

import androidx.test.core.app.ApplicationProvider;

import com.android.modules.utils.testing.TestableDeviceConfig;
import com.android.server.appsearch.util.ExecutorManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class AppSearchUserInstanceManagerTest {
    @Rule public final RuleChain mRuleChain = AppSearchTestUtils.createCommonTestRules();

    @Rule
    public final TestableDeviceConfig.TestableDeviceConfigRule mDeviceConfigRule =
            new TestableDeviceConfig.TestableDeviceConfigRule();

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private Context mContext;
    private UserHandle mUserHandle;

    private ServiceAppSearchConfig mServiceConfig;
    private ExecutorManager mExecutorManager;

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mUserHandle = mContext.getUser();


        // Set a test environment that provides a temporary folder for AppSearch
        File mAppSearchDir = mTemporaryFolder.newFolder();
        AppSearchEnvironmentFactory.setEnvironmentInstanceForTest(
                new FrameworkAppSearchEnvironment() {
                    @Override
                    public File getAppSearchDir(
                            @NonNull Context unused, @NonNull UserHandle userHandle) {
                        return mAppSearchDir;
                    }
                });

        mServiceConfig = FrameworkServiceAppSearchConfig.create(DIRECT_EXECUTOR, mContext);

        mExecutorManager = new ExecutorManager(mServiceConfig);
    }

    @Test
    public void getInstance_returnsInstance() {
        assertThat(AppSearchUserInstanceManager.getInstance()).isNotNull();
    }

    @Test
    public void getInstance_sameThread_getInstanceTwice_returnsSameInstance() {
        AppSearchUserInstanceManager originalInstance = AppSearchUserInstanceManager.getInstance();
        AppSearchUserInstanceManager newInstance = AppSearchUserInstanceManager.getInstance();
        assertThat(originalInstance).isSameInstanceAs(newInstance);
    }

    @Test
    public void getInstance_multipleThreads_getInstanceTwice_returnsSameInstance()
            throws ExecutionException, InterruptedException {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AppSearchUserInstanceManager> originalInstanceFuture =
                    executor.submit(AppSearchUserInstanceManager::getInstance);
            Future<AppSearchUserInstanceManager> newInstanceFuture =
                    executor.submit(AppSearchUserInstanceManager::getInstance);

            AppSearchUserInstanceManager originalInstance = originalInstanceFuture.get();
            AppSearchUserInstanceManager newInstance = newInstanceFuture.get();
            assertThat(originalInstance).isEqualTo(newInstance);
        }
    }

    @Test
    public void getOrCreateUserInstance_returnsInstance() throws AppSearchException {
        AppSearchUserInstance userInstance =
                AppSearchUserInstanceManager.getInstance()
                        .getOrCreateUserInstance(
                                mContext, mUserHandle, mServiceConfig, mExecutorManager, null);

        assertThat(userInstance).isNotNull();
        assertThat(userInstance.isVMEnabled()).isFalse();
    }

    @Test
    public void getOrCreateUserInstance_sameThread_callTwice_returnsSameInstance()
            throws AppSearchException {
        AppSearchUserInstance originalInstance =
                AppSearchUserInstanceManager.getInstance()
                        .getOrCreateUserInstance(
                                mContext, mUserHandle, mServiceConfig, mExecutorManager, null);

        AppSearchUserInstance newInstance =
                AppSearchUserInstanceManager.getInstance()
                        .getOrCreateUserInstance(
                                mContext, mUserHandle, mServiceConfig, mExecutorManager, null);
        assertThat(originalInstance).isSameInstanceAs(newInstance);
    }

    @Test
    public void getOrCreateUserInstance_multipleThreads_callTwice_returnsSameInstance()
            throws InterruptedException, ExecutionException {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();
            Future<AppSearchUserInstance> originalInstanceFuture =
                    executor.submit(
                            () ->
                                    manager.getOrCreateUserInstance(
                                            mContext,
                                            mUserHandle,
                                            mServiceConfig,
                                            mExecutorManager,
                                            null));

            Future<AppSearchUserInstance> newInstanceFuture =
                    executor.submit(
                            () ->
                                    manager.getOrCreateUserInstance(
                                            mContext,
                                            mUserHandle,
                                            mServiceConfig,
                                            mExecutorManager,
                                            null));
            AppSearchUserInstance originalInstance = originalInstanceFuture.get();
            AppSearchUserInstance newInstance = newInstanceFuture.get();
            assertThat(originalInstance).isSameInstanceAs(newInstance);
        }
    }

    @Test
    public void getAllUserHandles_returnsCorrectUserHandles() throws AppSearchException {
        AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();
        List<UserHandle> userHandles =
                List.of(new UserHandle(0), new UserHandle(1), new UserHandle(2));
        for (UserHandle userHandle : userHandles) {
            manager.getOrCreateUserInstance(
                    mContext, userHandle, mServiceConfig, mExecutorManager, null);
        }
        assertThat(manager.getAllUserHandles()).containsExactlyElementsIn(userHandles);
    }

    @Test
    public void closeAndRemoveUserInstance_removesInstance() throws AppSearchException {
        AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();
        manager.getOrCreateUserInstance(
                mContext, mUserHandle, mServiceConfig, mExecutorManager, null);

        manager.closeAndRemoveUserInstance(mUserHandle, /* removeUserData= */ true);
        assertThat(manager.getAllUserHandles()).doesNotContain(mUserHandle);
    }

    @Test
    public void getUserInstance_instanceNotFound_throwsException() {
        AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();

        assertThrows(
                IllegalStateException.class, () -> manager.getUserInstance(new UserHandle(-1)));
    }

    @Test
    public void getUserInstanceOrNull_instanceNotFound_returnsNull() {
        AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();

        AppSearchUserInstance instance = manager.getUserInstanceOrNull(new UserHandle(-1));
        assertThat(instance).isNull();
    }

    @Test
    public void getUserInstanceOrNull_tryLocks_returnsNullForBlocked()
            throws AppSearchException, ExecutionException, InterruptedException {
        AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();
        manager.getOrCreateUserInstance(
                mContext, mUserHandle, mServiceConfig, mExecutorManager, null);

        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            manager.lockInstanceMap();
            AppSearchUserInstance newInstance =
                    executor.submit(() -> manager.getUserInstanceOrNull(mUserHandle)).get();
            manager.unlockInstanceMap();
            assertThat(newInstance).isNull();
        }
    }
}
