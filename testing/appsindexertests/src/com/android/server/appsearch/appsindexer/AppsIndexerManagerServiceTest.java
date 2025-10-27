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

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.READ_DEVICE_CONFIG;
import static android.Manifest.permission.RECEIVE_BOOT_COMPLETED;

import static com.android.server.appsearch.appsindexer.TestUtils.createFakeAppIndexerSession;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakeLaunchResolveInfo;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakePackageInfo;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakePackageInfos;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakeResolveInfos;
import static com.android.server.appsearch.appsindexer.TestUtils.setupMockPackageManager;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.UiAutomation;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.AppSearchSessionShim;
import android.app.appsearch.FrameworkAppSearchEnvironment;
import android.app.appsearch.GlobalSearchSessionShim;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResultsShim;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.app.appsearch.testutil.GlobalSearchSessionShimImpl;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.appsearch.flags.Flags;
import com.android.modules.utils.testing.TestableDeviceConfig;
import com.android.server.SystemService;
import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AppsIndexerManagerServiceTest extends AppsIndexerTestBase {
    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Rule public final RuleChain mRuleChain = AppSearchTestUtils.createCommonTestRules().around(
            new TestableDeviceConfig.TestableDeviceConfigRule());

    private final ExecutorService mSingleThreadedExecutor = Executors.newSingleThreadExecutor();
    private AppsIndexerManagerService mAppsIndexerManagerService;
    private UiAutomation mUiAutomation;
    private BroadcastReceiver mCapturedReceiver;
    // Saving to class so we can unregister the callback
    private final PackageManager mPackageManager = Mockito.mock(PackageManager.class);

    private Locale mLocale = null;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        Context context = ApplicationProvider.getApplicationContext();
        mContext =
                new ContextWrapper(context) {
                    @Override
                    public Context createContextAsUser(UserHandle user, int flags) {
                        return new ContextWrapper(super.createContextAsUser(user, flags)) {
                            @Override
                            public PackageManager getPackageManager() {
                                return mPackageManager;
                            }
                        };
                    }

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
                                Context.RECEIVER_NOT_EXPORTED);
                    }
                };
        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        // INTERACT_ACROSS_USERS_FULL: needed when we do registerReceiverForAllUsers for getting
        // package change notifications.
        // READ_DEVICE_CONFIG: AppsIndexerManagerService reads from DeviceConfig, which may cause
        // permission_denied issues on certain test setups without this permission.
        mUiAutomation.adoptShellPermissionIdentity(INTERACT_ACROSS_USERS_FULL, READ_DEVICE_CONFIG);

        File mAppSearchDir = mTemporaryFolder.newFolder();
        AppSearchEnvironmentFactory.setEnvironmentInstanceForTest(
                new FrameworkAppSearchEnvironment() {
                    @Override
                    public File getAppSearchDir(
                            @NonNull Context unused, @NonNull UserHandle userHandle) {
                        return mAppSearchDir;
                    }

                    @Override
                    public Context createContextAsUser(
                            @NonNull Context context, @NonNull UserHandle userHandle) {
                        return new ContextWrapper(super.createContextAsUser(context, userHandle)) {
                            @Override
                            public PackageManager getPackageManager() {
                                return mPackageManager;
                            }

                            @Override
                            public Resources getResources() {
                                if (mLocale == null) {
                                    return super.getResources();
                                }
                                Configuration overrideConfig =
                                        new Configuration(super.getResources().getConfiguration());
                                overrideConfig.setLocale(mLocale);
                                Context configContext = createConfigurationContext(overrideConfig);
                                return configContext.getResources();
                            }
                        };
                    }
                });

        mAppsIndexerManagerService =
                new AppsIndexerManagerService(mContext, new TestAppsIndexerConfig());
        try {
            mAppsIndexerManagerService.onStart();
        } catch (Exception e) {
            // This might fail due to LocalService already being registered. Ignore it for the test
        }
    }

    @After
    @Override
    public void tearDown() throws Exception {
        // Wipe the data in AppSearchHelper.DATABASE_NAME.
        AppSearchSessionShim db = createFakeAppIndexerSession(mContext, mSingleThreadedExecutor);

        db.setSchemaAsync(new SetSchemaRequest.Builder().setForceOverride(true).build()).get();

        mUiAutomation.dropShellPermissionIdentity();
        super.tearDown();
    }

    @Test
    public void testBootstrapPackages() throws Exception {
        // Populate fake PackageManager with fake Packages.
        int numFakePackages = 3;
        List<PackageInfo> fakePackages = new ArrayList<>(createFakePackageInfos(numFakePackages));
        List<ResolveInfo> fakeActivities = new ArrayList<>(createFakeResolveInfos(numFakePackages));

        setupMockPackageManager(
                mPackageManager,
                fakePackages,
                fakeActivities,
                /* appFunctionServices= */ ImmutableList.of());

        UserInfo userInfo =
                new UserInfo(
                        mContext.getUser().getIdentifier(), /* name= */ "default", /* flags= */ 0);
        SystemService.TargetUser targetUser = new SystemService.TargetUser(userInfo);
        GlobalSearchSessionShim db =
                GlobalSearchSessionShimImpl.createGlobalSearchSessionAsync(mContext).get();
        // Apps indexer schedules a full-update job for bootstrapping from PackageManager,
        // and JobScheduler API requires BOOT_COMPLETED permission for persisting the job.
        mUiAutomation.adoptShellPermissionIdentity(RECEIVE_BOOT_COMPLETED);
        try {
            CountDownLatch bootstrapLatch =
                    setupLatch(numFakePackages, /* listenForSchemaChanges= */ false);
            mAppsIndexerManagerService.onUserUnlocking(targetUser);
            assertTrue(bootstrapLatch.await(10000L, TimeUnit.MILLISECONDS));
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }

        // Ensure that we can query the package documents added to AppSearch
        SearchResultsShim results =
                db.search(
                        "",
                        new SearchSpec.Builder()
                                .setRankingStrategy("this.creationTimestamp()")
                                .addFilterNamespaces(MobileApplication.APPS_NAMESPACE)
                                .addFilterPackageNames(mContext.getPackageName())
                                .build());

        List<SearchResult> page = results.getNextPageAsync().get();
        assertThat(page).hasSize(numFakePackages);
        List<String> schemaNames = new ArrayList<>();
        for (int i = 0; i < page.size(); i++) {
            schemaNames.add(page.get(i).getGenericDocument().getSchemaType());
        }
        assertThat(schemaNames)
                .containsExactly(
                        "builtin:MobileApplication-com.fake.package2",
                        "builtin:MobileApplication-com.fake.package1",
                        "builtin:MobileApplication-com.fake.package0");

        mAppsIndexerManagerService.onUserStopping(targetUser);
    }

    @Test
    public void testAddPackage() throws Exception {
        // Populate fake PackageManager with fake Packages.
        int numFakePackages = 3;
        List<PackageInfo> fakePackages = new ArrayList<>(createFakePackageInfos(numFakePackages));
        List<ResolveInfo> fakeActivities = new ArrayList<>(createFakeResolveInfos(numFakePackages));

        setupMockPackageManager(
                mPackageManager,
                fakePackages,
                fakeActivities,
                /* appFunctionServices= */ ImmutableList.of());

        UserInfo userInfo =
                new UserInfo(
                        mContext.getUser().getIdentifier(), /* name= */ "default", /* flags= */ 0);

        SystemService.TargetUser targetUser = new SystemService.TargetUser(userInfo);
        GlobalSearchSessionShim db =
                GlobalSearchSessionShimImpl.createGlobalSearchSessionAsync(mContext).get();
        // Apps indexer schedules a full-update job for bootstrapping from PackageManager,
        // and JobScheduler API requires BOOT_COMPLETED permission for persisting the job.
        mUiAutomation.adoptShellPermissionIdentity(RECEIVE_BOOT_COMPLETED);
        CountDownLatch bootstrapLatch = null;
        try {
            bootstrapLatch = setupLatch(numFakePackages, /* listenForSchemaChanges= */ false);
            mAppsIndexerManagerService.onUserUnlocking(targetUser);
            assertTrue(bootstrapLatch.await(10000L, TimeUnit.MILLISECONDS));
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }

        // Add a package and trigger an update directly
        Intent fakeIntent = new Intent(Intent.ACTION_PACKAGE_ADDED);
        fakeIntent.setData(Uri.parse("package:" + mContext.getPackageName()));
        fakeIntent.putExtra(Intent.EXTRA_UID, targetUser.getUserHandle().getUid(20));

        // Add a package at index numFakePackages
        fakePackages.add(createFakePackageInfo(numFakePackages));
        fakeActivities.add(createFakeLaunchResolveInfo(numFakePackages));
        CountDownLatch latch = setupLatch(1, /* listenForSchemaChanges= */ false);

        mCapturedReceiver.onReceive(mContext, fakeIntent);
        assertTrue(latch.await(10000L, TimeUnit.MILLISECONDS));

        // Wait for the change then Check AppSearch
        SearchResultsShim results =
                db.search(
                        "",
                        new SearchSpec.Builder()
                                .addFilterPackageNames(mContext.getPackageName())
                                .setResultCountPerPage(10)
                                .build());
        List<SearchResult> page = results.getNextPageAsync().get();
        // 10 is greater than the expected number of results, which is numFakePackage + 1 = 4
        assertThat(page).hasSize(numFakePackages + 1);

        mAppsIndexerManagerService.onUserStopping(targetUser);
    }

    @Test
    public void testUpdatePackage() throws Exception {
        // Populate fake PackageManager with fake Packages.
        int numFakePackages = 3;
        List<PackageInfo> fakePackages = new ArrayList<>(createFakePackageInfos(numFakePackages));
        List<ResolveInfo> fakeActivities = new ArrayList<>(createFakeResolveInfos(numFakePackages));

        setupMockPackageManager(
                mPackageManager,
                fakePackages,
                fakeActivities,
                /* appFunctionServices= */ ImmutableList.of());

        UserInfo userInfo =
                new UserInfo(
                        mContext.getUser().getIdentifier(), /* name= */ "default", /* flags= */ 0);
        SystemService.TargetUser targetUser = new SystemService.TargetUser(userInfo);
        GlobalSearchSessionShim db =
                GlobalSearchSessionShimImpl.createGlobalSearchSessionAsync(mContext).get();
        // Apps indexer schedules a full-update job for bootstrapping from PackageManager,
        // and JobScheduler API requires BOOT_COMPLETED permission for persisting the job.
        mUiAutomation.adoptShellPermissionIdentity(RECEIVE_BOOT_COMPLETED);
        CountDownLatch bootstrapLatch = null;
        try {
            bootstrapLatch = setupLatch(numFakePackages, /* listenForSchemaChanges= */ false);
            mAppsIndexerManagerService.onUserUnlocking(targetUser);
            assertTrue(bootstrapLatch.await(10000L, TimeUnit.MILLISECONDS));
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }

        // Update a package by updating the timestamp and trigger an update
        Intent fakeIntent = new Intent(Intent.ACTION_PACKAGE_CHANGED);
        fakeIntent.setData(Uri.parse("package:" + mContext.getPackageName()));
        fakeIntent.putExtra(Intent.EXTRA_UID, targetUser.getUserHandle().getUid(20));
        // This has to match the package in data to indicate that this was not just a component
        // change, but that the entire package was changed.
        fakeIntent.putExtra(
                Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, new String[] {mContext.getPackageName()});

        int updateIndex = 1;
        fakePackages.get(updateIndex).lastUpdateTime = 1000;
        CountDownLatch latch = setupLatch(1, /* listenForSchemaChanges= */ false);

        mCapturedReceiver.onReceive(mContext, fakeIntent);
        assertTrue(latch.await(10000L, TimeUnit.MILLISECONDS));

        // Check AppSearch
        SearchResultsShim results =
                db.search(
                        "",
                        new SearchSpec.Builder()
                                .setResultCountPerPage(10)
                                .addFilterPackageNames(mContext.getPackageName())
                                .build());
        List<SearchResult> page = results.getNextPageAsync().get();
        // 10 is greater than the expected number of results, which is numFakePackage = 3
        assertThat(page).hasSize(numFakePackages);

        List<Long> timestamps = new ArrayList<>();
        for (SearchResult result : page) {
            timestamps.add(
                    result.getGenericDocument()
                            .getPropertyLong(MobileApplication.APP_PROPERTY_UPDATED_TIMESTAMP));
        }
        assertThat(timestamps).contains(1000L);

        mAppsIndexerManagerService.onUserStopping(targetUser);
    }

    @Test
    public void testRemovePackage() throws Exception {
        // Populate fake PackageManager with fake Packages.
        int numFakePackages = 3;
        List<PackageInfo> fakePackages = new ArrayList<>(createFakePackageInfos(numFakePackages));
        List<ResolveInfo> fakeActivities = new ArrayList<>(createFakeResolveInfos(numFakePackages));

        setupMockPackageManager(
                mPackageManager,
                fakePackages,
                fakeActivities,
                /* appFunctionServices= */ ImmutableList.of());

        UserInfo userInfo =
                new UserInfo(
                        mContext.getUser().getIdentifier(), /* name= */ "default", /* flags= */ 0);
        SystemService.TargetUser targetUser = new SystemService.TargetUser(userInfo);
        GlobalSearchSessionShim db =
                GlobalSearchSessionShimImpl.createGlobalSearchSessionAsync(mContext).get();
        // Apps indexer schedules a full-update job for bootstrapping from PackageManager,
        // and JobScheduler API requires BOOT_COMPLETED permission for persisting the job.
        mUiAutomation.adoptShellPermissionIdentity(RECEIVE_BOOT_COMPLETED);
        CountDownLatch bootstrapLatch = null;
        try {
            bootstrapLatch = setupLatch(numFakePackages, /* listenForSchemaChanges= */ false);
            mAppsIndexerManagerService.onUserUnlocking(targetUser);
            assertTrue(bootstrapLatch.await(10000L, TimeUnit.MILLISECONDS));
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }

        // Delete a package and trigger an update
        Intent fakeIntent = new Intent(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        fakeIntent.setData(Uri.parse("package:" + mContext.getPackageName()));
        fakeIntent.putExtra(Intent.EXTRA_UID, targetUser.getUserHandle().getUid(20));

        fakePackages.remove(0);
        fakeActivities.remove(0);
        CountDownLatch latch = setupLatch(1, /* listenForSchemaChanges= */ true);

        mCapturedReceiver.onReceive(mContext, fakeIntent);
        assertTrue(latch.await(10000L, TimeUnit.MILLISECONDS));

        // Check AppSearch
        SearchResultsShim results =
                db.search(
                        "",
                        new SearchSpec.Builder()
                                .addFilterPackageNames(mContext.getPackageName())
                                .setResultCountPerPage(10)
                                .build());
        List<SearchResult> page = results.getNextPageAsync().get();
        // 10 is greater than the expected number of results, which is numFakePackage - 1 = 2
        assertThat(page).hasSize(numFakePackages - 1);

        mAppsIndexerManagerService.onUserStopping(targetUser);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APPS_INDEXER_LOCALE_CHANGE_FULL_UPDATE)
    public void testLocaleChange() throws Exception {
        // Populate fake PackageManager with 1 fake package
        List<PackageInfo> fakePackages = ImmutableList.of(createFakePackageInfo(0));
        List<ResolveInfo> fakeActivities = ImmutableList.of(createFakeLaunchResolveInfo(0));

        // Set locale
        mLocale = new Locale("en", "US");

        setupMockPackageManager(
                mPackageManager,
                fakePackages,
                fakeActivities,
                /* appFunctionServices= */ ImmutableList.of());

        // Mock the initial application label
        String originalLabel = "Original English Label";
        when(mPackageManager.getApplicationLabel(any())).thenReturn(originalLabel);

        UserInfo userInfo =
                new UserInfo(
                        mContext.getUser().getIdentifier(), /* name= */ "default", /* flags= */ 0);
        SystemService.TargetUser targetUser = new SystemService.TargetUser(userInfo);
        GlobalSearchSessionShim db =
                GlobalSearchSessionShimImpl.createGlobalSearchSessionAsync(mContext).get();
        // Apps indexer schedules a full-update job for bootstrapping from PackageManager,
        // and JobScheduler API requires BOOT_COMPLETED permission for persisting the job.
        mUiAutomation.adoptShellPermissionIdentity(RECEIVE_BOOT_COMPLETED);
        try {
            CountDownLatch bootstrapLatch = setupLatch(1, /* listenForSchemaChanges= */ false);
            mAppsIndexerManagerService.onUserUnlocking(targetUser);
            assertTrue(bootstrapLatch.await(10000L, TimeUnit.MILLISECONDS));
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }

        // Verify the initial label in AppSearch
        SearchResultsShim results =
                db.search(
                        "",
                        new SearchSpec.Builder()
                                .addFilterNamespaces(MobileApplication.APPS_NAMESPACE)
                                .addFilterPackageNames(mContext.getPackageName())
                                .build());
        List<SearchResult> page = results.getNextPageAsync().get();
        assertThat(page).hasSize(1);

        assertThat(
                        page.get(0)
                                .getGenericDocument()
                                .getPropertyString(MobileApplication.APP_PROPERTY_ALTERNATE_NAMES))
                .isEqualTo(originalLabel);

        // Create a fake locale changed intent
        Intent fakeIntent = new Intent(Intent.ACTION_LOCALE_CHANGED);
        fakeIntent.setData(Uri.parse("package:" + mContext.getPackageName()));
        fakeIntent.putExtra(Intent.EXTRA_UID, targetUser.getUserHandle().getUid(20));

        // Mock a new label
        String newLabel = "New French Label";
        when(mPackageManager.getApplicationLabel(any())).thenReturn(newLabel);

        // The fake locale intent will start a sync, but to trigger a full update we also need to
        // mock a locale change
        mLocale = new Locale("fr", "FR");

        CountDownLatch latch = setupLatch(1, /* listenForSchemaChanges= */ false);
        mCapturedReceiver.onReceive(mContext, fakeIntent);
        assertTrue(latch.await(10000L, TimeUnit.MILLISECONDS));

        // Verify the label has been updated in AppSearch
        results =
                db.search(
                        "",
                        new SearchSpec.Builder()
                                .addFilterNamespaces(MobileApplication.APPS_NAMESPACE)
                                .addFilterPackageNames(mContext.getPackageName())
                                .build());
        page = results.getNextPageAsync().get();
        assertThat(page).hasSize(1);

        assertThat(
                        page.get(0)
                                .getGenericDocument()
                                .getPropertyString(MobileApplication.APP_PROPERTY_ALTERNATE_NAMES))
                .isEqualTo(newLabel);
        mAppsIndexerManagerService.onUserStopping(targetUser);
    }
}
