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
import static com.android.server.appsearch.FrameworkServiceAppSearchConfig.KEY_ISOLATED_STORAGE_ENABLE_UNFREEZING_MIGRATION;
import static com.android.server.appsearch.stats.VmStartAttemptStats.VM_START_STATUS_ERROR;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.FrameworkAppSearchEnvironment;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.flags.Flags;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.app.test.TestAlarmManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.provider.DeviceConfig;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

import com.android.isolated_storage_service.IIcingSearchEngine;
import com.android.isolated_storage_service.IIsolatedStorageService;
import com.android.modules.utils.testing.TestableDeviceConfig;
import com.android.server.appsearch.icing.proto.IcingSearchEngineOptions;
import com.android.server.appsearch.icing.proto.PersistType;
import com.android.server.appsearch.isolated_storage_service.IsolatedStorageServiceManager;
import com.android.server.appsearch.isolated_storage_service.ServiceConfig;
import com.android.server.appsearch.isolated_storage_service.VmStartResult;
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
import java.util.concurrent.TimeUnit;

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
    private TestAlarmManager mAlarmManager;

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
        mExecutorManager =
                new ExecutorManager(mServiceConfig, /* isIsolatedStorageAvailable= */ true);
        mAlarmManager = new TestAlarmManager();
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
                                mContext,
                                mUserHandle,
                                mServiceConfig,
                                mExecutorManager,
                                null,
                                /* enableIsolatedStorageReverseMigration= */ false,
                                /* isIsolatedStorageAvailable= */ false);

        assertThat(userInstance).isNotNull();
        assertThat(userInstance.isVMEnabled()).isFalse();
    }

    @Test
    public void getOrCreateUserInstance_sameThread_callTwice_returnsSameInstance()
            throws AppSearchException {
        AppSearchUserInstance originalInstance =
                AppSearchUserInstanceManager.getInstance()
                        .getOrCreateUserInstance(
                                mContext,
                                mUserHandle,
                                mServiceConfig,
                                mExecutorManager,
                                null,
                                /* enableIsolatedStorageReverseMigration= */ false,
                                /* isIsolatedStorageAvailable= */ false);

        AppSearchUserInstance newInstance =
                AppSearchUserInstanceManager.getInstance()
                        .getOrCreateUserInstance(
                                mContext,
                                mUserHandle,
                                mServiceConfig,
                                mExecutorManager,
                                null,
                                /* enableIsolatedStorageReverseMigration= */ false,
                                /* isIsolatedStorageAvailable= */ false);
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
                                            null,
                                            /* enableIsolatedStorageReverseMigration= */ false,
                                            /* isIsolatedStorageAvailable= */ false));

            Future<AppSearchUserInstance> newInstanceFuture =
                    executor.submit(
                            () ->
                                    manager.getOrCreateUserInstance(
                                            mContext,
                                            mUserHandle,
                                            mServiceConfig,
                                            mExecutorManager,
                                            null,
                                            /* enableIsolatedStorageReverseMigration= */ false,
                                            /* isIsolatedStorageAvailable= */ false));
            AppSearchUserInstance originalInstance = originalInstanceFuture.get();
            AppSearchUserInstance newInstance = newInstanceFuture.get();
            assertThat(originalInstance).isSameInstanceAs(newInstance);
        }
    }

    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_DELETE_PROPAGATION_RW)
    @Test
    public void getOrCreateUserInstance_createShouldResetHandleExpiredDocumentsAlarm()
            throws AppSearchException {
        AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();

        AppSearchUserInstance instance =
                manager.getOrCreateUserInstance(
                        setupContextWithMockAlarmManager(mContext, mAlarmManager.getAlarmManager()),
                        mUserHandle,
                        mServiceConfig,
                        mExecutorManager,
                        null,
                        /* enableIsolatedStorageReverseMigration= */ false,
                        /* isIsolatedStorageAvailable= */ false);
        assertThat(instance).isNotNull();

        // Verify handle expired documents alarm was reset.
        verify(mAlarmManager.getAlarmManager(), never())
                .cancel(any(AlarmManager.OnAlarmListener.class));
        verify(mAlarmManager.getAlarmManager(), times(1))
                .set(
                        eq(AlarmManager.RTC),
                        anyLong(),
                        eq("handleExpiredDocumentsAlarm_user" + mUserHandle.getIdentifier()),
                        any(AlarmManager.OnAlarmListener.class),
                        any(Handler.class));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_USER_INSTANCE_FUTURES)
    public void getOrCreateUserInstanceFuture_futureCancelled_retries()
            throws InterruptedException, AppSearchException {
        AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();
        ServiceAppSearchConfig cancellingConfig =
                createMockConfig(
                        mServiceConfig,
                        manager,
                        /* shouldCancelFirstInvocation= */ true,
                        /* shouldThrowFirstInvocation= */ false);

        AppSearchException exception =
                assertThrows(
                        AppSearchException.class,
                        () ->
                                manager.getOrCreateUserInstance(
                                        mContext,
                                        mUserHandle,
                                        cancellingConfig,
                                        null,
                                        null,
                                        /* enableIsolatedStorageReverseMigration= */ false,
                                        /* isIsolatedStorageAvailable= */ false));
        assertThat(exception.getCause()).isInstanceOf(CancellationException.class);
        Future<AppSearchUserInstance> cancelledFuture =
                manager.getUserInstanceCreationFuture(mUserHandle);
        assertThat(cancelledFuture.isCancelled()).isTrue();
        // On second run the cancellingConfig will not cancel user creation.
        AppSearchUserInstance userInstance =
                manager.getOrCreateUserInstance(
                        mContext,
                        mUserHandle,
                        cancellingConfig,
                        null,
                        null,
                        /* enableIsolatedStorageReverseMigration= */ false,
                        /* isIsolatedStorageAvailable= */ false);
        assertThat(userInstance).isNotNull();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_USER_INSTANCE_FUTURES)
    public void getOrCreateUserInstanceFuture_futureEncountersException_retries()
            throws InterruptedException, AppSearchException {
        AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();
        ServiceAppSearchConfig exceptionConfig =
                createMockConfig(
                        mServiceConfig,
                        manager,
                        /* shouldCancelFirstInvocation= */ false,
                        /* shouldThrowFirstInvocation= */ true);

        AppSearchException exception =
                assertThrows(
                        AppSearchException.class,
                        () ->
                                manager.getOrCreateUserInstance(
                                        mContext,
                                        mUserHandle,
                                        exceptionConfig,
                                        null,
                                        null,
                                        /* enableIsolatedStorageReverseMigration= */ false,
                                        /* isIsolatedStorageAvailable= */ false));
        assertThat(exception.getCause()).isInstanceOf(ExecutionException.class);
        Future<AppSearchUserInstance> exceptionInstance =
                manager.getUserInstanceCreationFuture(mUserHandle);
        assertThat(exceptionInstance.isDone()).isTrue();

        AppSearchUserInstance instance =
                manager.getOrCreateUserInstance(
                        mContext,
                        mUserHandle,
                        exceptionConfig,
                        null,
                        null,
                        /* enableIsolatedStorageReverseMigration= */ false,
                        /* isIsolatedStorageAvailable= */ false);
        assertThat(instance).isNotNull();
    }

    @Test
    public void getAllUserHandles_returnsCorrectUserHandles() throws AppSearchException {
        AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();
        List<UserHandle> userHandles =
                List.of(new UserHandle(0), new UserHandle(1), new UserHandle(2));
        for (UserHandle userHandle : userHandles) {
            manager.getOrCreateUserInstance(
                    mContext,
                    userHandle,
                    mServiceConfig,
                    mExecutorManager,
                    null,
                    /* enableIsolatedStorageReverseMigration= */ false,
                    /* isIsolatedStorageAvailable= */ false);
        }
        assertThat(manager.getAllUserHandles()).containsExactlyElementsIn(userHandles);
    }

    @Test
    public void closeAndRemoveUserInstance_removesInstance() throws AppSearchException {
        AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();
        manager.getOrCreateUserInstance(
                mContext,
                mUserHandle,
                mServiceConfig,
                mExecutorManager,
                null,
                /* enableIsolatedStorageReverseMigration= */ false,
                /* isIsolatedStorageAvailable= */ false);

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
                mContext,
                mUserHandle,
                mServiceConfig,
                mExecutorManager,
                null,
                /* enableIsolatedStorageReverseMigration= */ false,
                /* isIsolatedStorageAvailable= */ false);

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
    public void cancelUserCreation_beforeISSConnection_cancelsCreation()
            throws InterruptedException {
        assumeTrue(IsolatedStorageServiceManager.deviceSupportsVmsAndNewApis(mContext));
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_ISOLATED_STORAGE_ENABLE_UNFREEZING_MIGRATION,
                Boolean.toString(true),
                false);
        AppSearchUserInstanceManager manager = AppSearchUserInstanceManager.getInstance();
        ContextWrapper blockingContext = setupContextThatCancels(manager);
        try {
            AppSearchException exception =
                    assertThrows(
                            AppSearchException.class,
                            () ->
                                    manager.getOrCreateUserInstance(
                                            blockingContext,
                                            mUserHandle,
                                            mServiceConfig,
                                            mExecutorManager,
                                            new IsolatedStorageServiceManager(
                                                    blockingContext,
                                                    mServiceConfig,
                                                    mExecutorManager
                                                            .getOrCreateUserScheduledExecutor(
                                                                    mUserHandle)),
                                            /* enableIsolatedStorageReverseMigration= */ false,
                                            /* isIsolatedStorageAvailable= */ false));
            assertThat(exception.getCause()).isInstanceOf(CancellationException.class);
            assertThat(manager.getAllUserHandles()).containsExactly(mUserHandle);
        } finally {
            cleanUpUserExecutor(mUserHandle);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_USER_INSTANCE_FUTURES)
    public void cancelUserCreation_duringVMConnection_cancelsCreation()
            throws RemoteException, InterruptedException {
        assumeTrue(IsolatedStorageServiceManager.deviceSupportsVmsAndNewApis(mContext));
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_ISOLATED_STORAGE_ENABLE_UNFREEZING_MIGRATION,
                Boolean.toString(true),
                false);
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
                            VmStartResult result = new VmStartResult();
                            result.pStatusCode = VM_START_STATUS_ERROR;
                            result.pTotalLatencyMillis = 0;
                            return result;
                        });

        IsolatedStorageServiceManager issManager =
                new IsolatedStorageServiceManager(
                        mContext,
                        mServiceConfig,
                        mExecutorManager.getOrCreateUserScheduledExecutor(mUserHandle));
        issManager.setIsolatedStorageServiceForTest(isolatedStorageService);
        try {
            AppSearchException exception =
                    assertThrows(
                            AppSearchException.class,
                            () ->
                                    manager.getOrCreateUserInstance(
                                            mContext,
                                            mUserHandle,
                                            mServiceConfig,
                                            mExecutorManager,
                                            issManager,
                                            /* enableIsolatedStorageReverseMigration= */ false,
                                            /* isIsolatedStorageAvailable= */ false));
            assertThat(exception.getCause()).isInstanceOf(CancellationException.class);
        } finally {
            cleanUpUserExecutor(mUserHandle);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_USER_INSTANCE_FUTURES)
    public void cancelUserCreation_afterVMConnection_cancelsCreation()
            throws AppSearchException, RemoteException, InterruptedException {
        assumeTrue(IsolatedStorageServiceManager.deviceSupportsVmsAndNewApis(mContext));
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_ISOLATED_STORAGE_ENABLE_UNFREEZING_MIGRATION,
                Boolean.toString(true),
                false);
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
        try {
            AppSearchException exception =
                    assertThrows(
                            AppSearchException.class,
                            () ->
                                    manager.getOrCreateUserInstance(
                                            mContext,
                                            mUserHandle,
                                            mServiceConfig,
                                            mExecutorManager,
                                            spyIsolatedStorageServiceManager,
                                            /* enableIsolatedStorageReverseMigration= */ false,
                                            /* isIsolatedStorageAvailable= */ false));
            assertThat(exception.getCause()).isInstanceOf(CancellationException.class);
        } finally {
            cleanUpUserExecutor(mUserHandle);
        }
    }

    /** Shuts down running tasks and waits on running tasks for the given user handle's executor. */
    private void cleanUpUserExecutor(UserHandle userHandle) throws InterruptedException {
        mExecutorManager.getOrCreateUserScheduledExecutor(userHandle).shutdown();
        // shutdownNow() only attempts to terminate running tasks. Do an awaitTermination() here
        // to ensure that there are no remaining running tasks.
        assertThat(
                        mExecutorManager
                                .getOrCreateUserScheduledExecutor(userHandle)
                                .awaitTermination(/* timeout= */ 30, TimeUnit.SECONDS))
                .isTrue();
    }

    /** Setup a context that will cancel before connecting to IsolatedStorageService */
    private ContextWrapper setupContextThatCancels(AppSearchUserInstanceManager manager) {
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

                    // Also simulate cancellation before connecting to AiSeal.
                    @Override
                    public Object getSystemService(String name) {
                        if (name.equals(Context.AISEAL_HOST_SERVICE)) {
                            manager.cancelUserCreation(mUserHandle);
                        }
                        return getBaseContext().getSystemService(name);
                    }
                };

        // Mock package name return. This is so that when the VM tries to bind to isolated storage
        // it will get a package name, so that it create an Intent that it will use to bind to
        // the isolated storage service. That way it will be able to make the call to
        // bindServiceAsUser and call the mock method that will cancel user instance creation.
        ResolveInfo dummyResolveInfo = new ResolveInfo();
        dummyResolveInfo.serviceInfo = new ServiceInfo();
        dummyResolveInfo.serviceInfo.packageName = "dummy_package";
        doReturn(List.of(dummyResolveInfo))
                .when(blockingContext.getPackageManager())
                .queryIntentServices(any(), any());
        return blockingContext;
    }

    private static ContextWrapper setupContextWithMockAlarmManager(
            Context context, AlarmManager alarmManager) {
        return new ContextWrapper(context) {
            @Nullable
            @Override
            public Object getSystemService(String name) {
                if (Context.ALARM_SERVICE.equals(name)) {
                    return alarmManager;
                }
                return super.getSystemService(name);
            }
        };
    }

    /**
     * Creates a ServiceAppSearchConfig that can be mocked as other implementations are final in
     * order to be thread safe
     *
     * @return An instance of ServiceAppSearchConfig that returns smart zeros
     */
    private ServiceAppSearchConfig createMockConfig(
            ServiceAppSearchConfig realConfig,
            AppSearchUserInstanceManager manager,
            boolean shouldCancelFirstInvocation,
            boolean shouldThrowFirstInvocation) {
        return new ServiceAppSearchConfig() {

            boolean mShouldCancel = shouldCancelFirstInvocation;
            boolean mShouldThrow = shouldThrowFirstInvocation;

            @Override
            public long getCachedMinTimeIntervalBetweenSamplesMillis() {
                return realConfig.getCachedMinTimeIntervalBetweenSamplesMillis();
            }

            @Override
            public int getCachedSamplingIntervalDefault() {
                return realConfig.getCachedSamplingIntervalDefault();
            }

            @Override
            public int getCachedSamplingIntervalForBatchCallStats() {
                return realConfig.getCachedSamplingIntervalForBatchCallStats();
            }

            @Override
            public int getCachedSamplingIntervalForPutDocumentStats() {
                return realConfig.getCachedSamplingIntervalForPutDocumentStats();
            }

            @Override
            public int getCachedSamplingIntervalForInitializeStats() {
                return realConfig.getCachedSamplingIntervalForInitializeStats();
            }

            @Override
            public int getCachedSamplingIntervalForSearchStats() {
                return realConfig.getCachedSamplingIntervalForSearchStats();
            }

            @Override
            public int getCachedSamplingIntervalForGlobalSearchStats() {
                return realConfig.getCachedSamplingIntervalForGlobalSearchStats();
            }

            @Override
            public int getCachedSamplingIntervalForOptimizeStats() {
                return realConfig.getCachedSamplingIntervalForOptimizeStats();
            }

            @Override
            public int getCachedBytesOptimizeThreshold() {
                return realConfig.getCachedBytesOptimizeThreshold();
            }

            @Override
            public int getCachedTimeOptimizeThresholdMs() {
                return realConfig.getCachedTimeOptimizeThresholdMs();
            }

            @Override
            public int getCachedDocCountOptimizeThreshold() {
                return realConfig.getCachedDocCountOptimizeThreshold();
            }

            @Override
            public int getCachedMinTimeOptimizeThresholdMs() {
                return realConfig.getCachedMinTimeOptimizeThresholdMs();
            }

            @Override
            public long getCachedCheckOptimizeDelayMillis() {
                return realConfig.getCachedCheckOptimizeDelayMillis();
            }

            @Override
            public long getCachedMaxBytesOptimizeThreshold() {
                return realConfig.getCachedMaxBytesOptimizeThreshold();
            }

            @Override
            public long getCachedMaxDocCountOptimizeThreshold() {
                return realConfig.getCachedMaxDocCountOptimizeThreshold();
            }

            @Override
            public int getCachedApiCallStatsLimit() {
                return realConfig.getCachedApiCallStatsLimit();
            }

            @Override
            public Denylist getCachedDenylist() {
                return realConfig.getCachedDenylist();
            }

            @Override
            public boolean getCachedRateLimitEnabled() {
                return realConfig.getCachedRateLimitEnabled();
            }

            @Override
            public AppSearchRateLimitConfig getCachedRateLimitConfig() {
                return realConfig.getCachedRateLimitConfig();
            }

            @Override
            public long getAppFunctionCallTimeoutMillis() {
                return realConfig.getAppFunctionCallTimeoutMillis();
            }

            @Override
            public long getCachedFullyPersistJobIntervalMillis() {
                return realConfig.getCachedFullyPersistJobIntervalMillis();
            }

            @Override
            public long getCachedPersistDelayMillis() {
                return realConfig.getCachedPersistDelayMillis();
            }

            @Override
            public void close() {
                realConfig.close();
            }

            @Override
            public boolean shouldStoreParentInfoAsSyntheticProperty() {
                return realConfig.shouldStoreParentInfoAsSyntheticProperty();
            }

            @Override
            public boolean shouldRetrieveParentInfo() {
                return realConfig.shouldRetrieveParentInfo();
            }

            @Override
            public PersistType.@org.jspecify.annotations.NonNull Code getLightweightPersistType() {
                return realConfig.getLightweightPersistType();
            }

            @Override
            public int getMaxTokenLength() {
                return realConfig.getMaxTokenLength();
            }

            @Override
            public int getIndexMergeSize() {
                return realConfig.getIndexMergeSize();
            }

            @Override
            public boolean getDocumentStoreNamespaceIdFingerprint() {
                return realConfig.getDocumentStoreNamespaceIdFingerprint();
            }

            @Override
            public float getOptimizeRebuildIndexThreshold() {
                return realConfig.getOptimizeRebuildIndexThreshold();
            }

            @Override
            public int getCompressionLevel() {
                return realConfig.getCompressionLevel();
            }

            @Override
            public int getCompressionMemLevel() {
                return realConfig.getCompressionMemLevel();
            }

            @Override
            public boolean getAllowCircularSchemaDefinitions() {
                return realConfig.getAllowCircularSchemaDefinitions();
            }

            @Override
            public boolean getUseReadOnlySearch() {
                return realConfig.getUseReadOnlySearch();
            }

            @Override
            public boolean getUsePreMappingWithFileBackedVector() {
                return realConfig.getUsePreMappingWithFileBackedVector();
            }

            @Override
            public boolean getUsePersistentHashMap() {
                return realConfig.getUsePersistentHashMap();
            }

            @Override
            public int getMaxPageBytesLimit() {
                return realConfig.getMaxPageBytesLimit();
            }

            @Override
            public int getMaxPageBytesLimitForVm() {
                return realConfig.getMaxPageBytesLimitForVm();
            }

            @Override
            public int getIntegerIndexBucketSplitThreshold() {
                return realConfig.getIntegerIndexBucketSplitThreshold();
            }

            @Override
            public boolean getLiteIndexSortAtIndexing() {
                return realConfig.getLiteIndexSortAtIndexing();
            }

            @Override
            public int getLiteIndexSortSize() {
                return realConfig.getLiteIndexSortSize();
            }

            @Override
            public boolean getUseNewQualifiedIdJoinIndex() {
                return realConfig.getUseNewQualifiedIdJoinIndex();
            }

            @Override
            public boolean getBuildPropertyExistenceMetadataHits() {
                return realConfig.getBuildPropertyExistenceMetadataHits();
            }

            @Override
            public long getOrphanBlobTimeToLiveMs() {
                return realConfig.getOrphanBlobTimeToLiveMs();
            }

            @Override
            public @org.jspecify.annotations.NonNull String getIcuDataFileAbsolutePath() {
                return realConfig.getIcuDataFileAbsolutePath();
            }

            @Override
            public int getCompressionThresholdBytes() {
                return realConfig.getCompressionThresholdBytes();
            }

            @Override
            public int getMaxDocumentSizeBytes() {
                return realConfig.getMaxDocumentSizeBytes();
            }

            @Override
            public int getPerPackageDocumentCountLimit() {
                return realConfig.getPerPackageDocumentCountLimit();
            }

            @Override
            public int getDocumentCountLimitStartThreshold() {
                return realConfig.getDocumentCountLimitStartThreshold();
            }

            @Override
            public int getMaxSuggestionCount() {
                return realConfig.getMaxSuggestionCount();
            }

            @Override
            public int getMaxOpenBlobCount() {
                return realConfig.getMaxOpenBlobCount();
            }

            @Override
            public int getMaxByteLimitForBatchPut() {
                return realConfig.getMaxByteLimitForBatchPut();
            }

            @Override
            public int getEmbeddingIndexNumShards() {
                return realConfig.getEmbeddingIndexNumShards();
            }

            @Override
            public @org.jspecify.annotations.NonNull IcingSearchEngineOptions
                    toIcingSearchEngineOptions(
                            @org.jspecify.annotations.NonNull String baseDir, boolean isVMEnabled) {
                if (mShouldCancel) {
                    mShouldCancel = false;
                    manager.cancelUserCreation(mUserHandle);
                    return realConfig.toIcingSearchEngineOptions(baseDir, isVMEnabled);
                } else if (mShouldThrow) {
                    mShouldThrow = false;
                    throw new RuntimeException("Mock exception thrown");
                }
                return realConfig.toIcingSearchEngineOptions(baseDir, isVMEnabled);
            }
        };
    }
}
