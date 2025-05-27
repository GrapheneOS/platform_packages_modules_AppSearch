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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.FrameworkAppSearchEnvironment;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.flags.Flags;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

import com.android.isolated_storage_service.IIcingSearchEngine;
import com.android.isolated_storage_service.IIsolatedStorageService;
import com.android.modules.utils.testing.TestableDeviceConfig;
import com.android.server.appsearch.isolated_storage_service.IsolatedStorageServiceManager;
import com.android.server.appsearch.isolated_storage_service.ServiceConfig;
import com.android.server.appsearch.util.ExecutorManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.io.File;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class AppSearchUserInstanceManagerTest {
    @Rule
    public final RuleChain mRuleChain =
            AppSearchTestUtils.createCommonTestRules()
                    .around(new TestableDeviceConfig.TestableDeviceConfigRule());

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

    @After
    public void tearDown() throws Exception {
        AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();
        for (UserHandle userHandle : manager.getAllUserHandles()) {
            manager.cancelUserCreation(userHandle);
            manager.closeAndRemoveUserInstance(userHandle, /* removeUserData= */ true);
        }
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

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_USER_INSTANCE_FUTURES)
    public void cancelUserCreation_beforeISSConnection_cancelsCreation() {
        AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();
        ContextWrapper blockingContext =
                new ContextWrapper(mContext) {
                    final PackageManager mPackageManager = spy(mContext.getPackageManager());

                    @Override
                    public PackageManager getPackageManager() {
                        Log.i("MockContext", "Trying to get package manager");
                        return mPackageManager;
                    }

                    // Simulate cancellation before connection to ISS by cancelling right when we
                    // try to connect.
                    @Override
                    public boolean bindServiceAsUser(
                            Intent service, ServiceConnection conn, int flags, UserHandle user) {
                        manager.cancelUserCreation(mUserHandle);
                        return true;
                    }
                };

        // Mock package name return
        ResolveInfo dummyResolveInfo = new ResolveInfo();
        dummyResolveInfo.serviceInfo = new ServiceInfo();
        dummyResolveInfo.serviceInfo.packageName = "dummy_package";
        doReturn(List.of(dummyResolveInfo))
                .when(blockingContext.getPackageManager())
                .queryIntentServices(any(), any());

        assertThrows(
                CancellationException.class,
                () ->
                        manager.getOrCreateUserInstance(
                                blockingContext,
                                mUserHandle,
                                mServiceConfig,
                                mExecutorManager,
                                new IsolatedStorageServiceManager(
                                        blockingContext,
                                        mServiceConfig,
                                        mExecutorManager.getOrCreateUserScheduledExecutor(
                                                mUserHandle))));
        assertThat(manager.getAllUserHandles()).containsExactly(mUserHandle);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_USER_INSTANCE_FUTURES)
    public void cancelUserCreation_duringVMConnection_cancelsCreation() throws RemoteException {
        AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();
        com.android.server.appsearch.isolated_storage_service.IIsolatedStorageService
                isolatedStorageService =
                        Mockito.mock(
                                com.android.server.appsearch.isolated_storage_service
                                        .IIsolatedStorageService.class);

        // Simulate cancellation during VM connection by cancelling when we try starting VM.
        when(isolatedStorageService.startVm(any(ServiceConfig.class), anyLong(), anyBoolean()))
                .thenAnswer(
                        invocation -> {
                            manager.cancelUserCreation(mUserHandle);
                            return false;
                        });

        IsolatedStorageServiceManager issManager =
                new IsolatedStorageServiceManager(
                        mContext,
                        mServiceConfig,
                        mExecutorManager.getOrCreateUserScheduledExecutor(mUserHandle));
        issManager.setIsolatedStorageServiceForTest(isolatedStorageService);

        assertThrows(
                CancellationException.class,
                () ->
                        manager.getOrCreateUserInstance(
                                mContext,
                                mUserHandle,
                                mServiceConfig,
                                mExecutorManager,
                                issManager));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_USER_INSTANCE_FUTURES)
    public void cancelUserCreation_afterVMConnection_cancelsCreation()
            throws AppSearchException, RemoteException {
        AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();

        IsolatedStorageServiceManager isolatedStorageServiceManager =
                new IsolatedStorageServiceManager(
                        mContext,
                        mServiceConfig,
                        mExecutorManager.getOrCreateUserScheduledExecutor(mUserHandle));

        IsolatedStorageServiceManager spyIsolatedStorageServiceManager =
                spy(isolatedStorageServiceManager);

        com.android.server.appsearch.isolated_storage_service.IIsolatedStorageService
                isolatedStorageService =
                        Mockito.mock(
                                com.android.server.appsearch.isolated_storage_service
                                        .IIsolatedStorageService.class);

        IIsolatedStorageService vmIsolatedStorageService =
                Mockito.mock(IIsolatedStorageService.class);
        IIcingSearchEngine icingSearchEngine = Mockito.mock(IIcingSearchEngine.class);
        when(vmIsolatedStorageService.getOrCreateIcingConnection(anyInt()))
                .thenReturn(icingSearchEngine);

        doAnswer(
                        invocation -> {
                            spyIsolatedStorageServiceManager.setIsolatedStorageServiceForTest(
                                    isolatedStorageService);
                            spyIsolatedStorageServiceManager.setVmIsolatedStorageServiceForTest(
                                    vmIsolatedStorageService);
                            manager.cancelUserCreation(mUserHandle);
                            invocation.callRealMethod();
                            return null;
                        })
                .when(spyIsolatedStorageServiceManager)
                .initialize();

        assertThrows(
                CancellationException.class,
                () ->
                        manager.getOrCreateUserInstance(
                                mContext,
                                mUserHandle,
                                mServiceConfig,
                                mExecutorManager,
                                spyIsolatedStorageServiceManager));
    }
}
