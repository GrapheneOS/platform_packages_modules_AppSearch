/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.NonNull;
import android.app.UiAutomation;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.FrameworkAppSearchEnvironment;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.InternalSetSchemaResponse;
import android.app.appsearch.aidl.IAppSearchManager;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.testutil.AppSearchEmail;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.appsearch.flags.Flags;
import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.modules.utils.testing.TestableDeviceConfig;
import com.android.server.appsearch.ServiceTestUtil.MockServiceManager;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.io.File;
import java.util.Collections;

/** Tests for broadcast receivers of AppSearchManagerService. */
public class AppSearchManagerServiceReceiverTest {
    private static final String DATABASE_NAME = "databaseName";

    private final UserHandle mUserHandle = Process.myUserHandle();
    private final MockServiceManager mMockServiceManager = new MockServiceManager();
    private final PackageManager mPackageManager = Mockito.mock(PackageManager.class);

    private Context mContext;
    private UiAutomation mUiAutomation;
    private IntentFilter mCapturedPackageReceiverFilter;
    private BroadcastReceiver mCapturedPackageReceiver;

    private AppSearchManagerService mAppSearchManagerService;
    private AppSearchUserInstance mUserInstance;

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Rule public final RuleChain mRuleChain = AppSearchTestUtils.createCommonTestRules();

    @Rule
    public ExtendedMockitoRule mExtendedMockitoRule =
            new ExtendedMockitoRule.Builder()
                    .addStaticMockFixtures(() -> mMockServiceManager, TestableDeviceConfig::new)
                    .build();

    @Before
    public void setUp() throws Exception {
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
                        // AppSearchManagerService calls registerReceiver multiple times -- for
                        // example to register the user add/remove and the package add/remove
                        // receivers. In this test, we want to capture the correct receivers. We do
                        // so by distinguishing by filter. The package receiver has handlers for
                        // PACKAGE_REMOVED, PACKAGE_FULLY_REMOVED and DATA_CLEARED. The DATA_CLEARED
                        // has been chosen somewhat arbitrarily as the most stable-seeming of the
                        // filters.
                        if (filter.hasAction(Intent.ACTION_PACKAGE_DATA_CLEARED)) {
                            mCapturedPackageReceiverFilter = filter;
                            mCapturedPackageReceiver = receiver;
                        }
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
        mUiAutomation.adoptShellPermissionIdentity(INTERACT_ACROSS_USERS_FULL);

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
                        };
                    }
                });

        // In AppSearchManagerService, ServiceAppSearchConfig is a singleton. During tearDown for
        // TestableDeviceConfig, the propertyChangedListeners are removed. Therefore we have to set
        // a fresh config with listeners in setUp in order to set new properties.
        ServiceAppSearchConfig appSearchConfig =
                FrameworkServiceAppSearchConfig.create(DIRECT_EXECUTOR, context);
        AppSearchComponentFactory.setConfigInstanceForTest(appSearchConfig);

        // Start the service
        mAppSearchManagerService =
                new AppSearchManagerService(mContext, new AppSearchModule.Lifecycle(mContext));
        mAppSearchManagerService.onStart();
        IAppSearchManager.Stub appSearchManagerServiceStub =
                mMockServiceManager.mStubCaptor.getValue();
        assertThat(appSearchManagerServiceStub).isNotNull();

        mUserInstance =
                AppSearchUserInstanceManager.getInstance()
                        .getOrCreateUserInstance(
                                mContext,
                                mUserHandle,
                                appSearchConfig,
                                mAppSearchManagerService.getExecutorManager(),
                                /* isolatedStorageServiceManager= */ null,
                                /* enableIsolatedStorageReverseMigration= */ false,
                                /* isIsolatedStorageAvailable= */ false);
    }

    @After
    public void tearDown() throws Exception {
        // The TemporaryFolder rule's teardown will delete the current test folder; by removing the
        // current user instance, the next test will be able to create a new AppSearchImpl with
        // a new test folder
        AppSearchUserInstanceManager.getInstance()
                .closeAndRemoveUserInstance(mUserHandle, /* removeUserData= */ true);
        mUiAutomation.dropShellPermissionIdentity();
    }

    @Test
    public void testUninstallSystemApp_usingPackageFullyRemoved() throws Exception {
        // Disable threading so we don't have to wait for async activity.
        mAppSearchManagerService
                .getExecutorManager()
                .setUserExecutorForTest(mUserHandle, MoreExecutors.newDirectExecutorService());

        // Index some AppSearchData.
        AppSearchImpl appSearchImpl = mUserInstance.getAppSearchImpl();
        InternalSetSchemaResponse setSchemaResponse =
                appSearchImpl.setSchema(
                        mContext.getPackageName(),
                        DATABASE_NAME,
                        ImmutableList.of(AppSearchEmail.SCHEMA),
                        /* visibilityConfigs= */ ImmutableList.of(),
                        /* accountPropertyPaths= */ Collections.emptyMap(),
                        /* forceOverride= */ true,
                        /* version= */ 0,
                        /* setSchemaStatsBuilder= */ null,
                        /* callStatsBuilder= */ null);
        assertThat(setSchemaResponse.isSuccess()).isTrue();

        GenericDocument inputEmail =
                new AppSearchEmail.Builder("namespace", "id").setBody("body").build();
        appSearchImpl.putDocument(
                mContext.getPackageName(),
                DATABASE_NAME,
                inputEmail,
                /* sendChangeNotifications= */ false,
                /* logger= */ null,
                /* callStatsBuilder= */ null);

        // Make sure it can be retrieved
        GenericDocument get1 =
                appSearchImpl.getDocument(
                        mContext.getPackageName(),
                        DATABASE_NAME,
                        "namespace",
                        "id",
                        /* typePropertyPaths= */ ImmutableMap.of(),
                        /* callStatsBuilder= */ null);
        assertThat(get1).isEqualTo(inputEmail);

        // Delete a package and trigger an update
        Intent fakeIntent = new Intent(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        fakeIntent.setData(Uri.parse("package:" + mContext.getPackageName()));
        fakeIntent.putExtra(Intent.EXTRA_UID, Process.myUid());
        // Make sure the filter matches the intent
        assertThat(mCapturedPackageReceiverFilter.asPredicate().test(fakeIntent)).isTrue();
        // Send it
        mCapturedPackageReceiver.onReceive(mContext, fakeIntent);

        // Make sure the doc is now gone
        AppSearchException get2Exception =
                Assert.assertThrows(
                        AppSearchException.class,
                        () ->
                                appSearchImpl.getDocument(
                                        mContext.getPackageName(),
                                        DATABASE_NAME,
                                        "namespace",
                                        "id",
                                        /* typePropertyPaths= */ ImmutableMap.of(),
                                        /* callStatsBuilder= */ null));
        assertThat(get2Exception.getResultCode()).isEqualTo(AppSearchResult.RESULT_NOT_FOUND);
    }

    @Test
    // This test requires Disabled because the flag is a killswitch
    @RequiresFlagsDisabled(Flags.FLAG_DISABLE_ACTION_PACKAGE_REMOVED_PRUNING)
    public void testUninstallSystemApp_usingPackageRemoved() throws Exception {
        // Disable threading so we don't have to wait for async activity.
        mAppSearchManagerService
                .getExecutorManager()
                .setUserExecutorForTest(mUserHandle, MoreExecutors.newDirectExecutorService());

        // Index some AppSearchData.
        AppSearchImpl appSearchImpl = mUserInstance.getAppSearchImpl();
        InternalSetSchemaResponse setSchemaResponse =
                appSearchImpl.setSchema(
                        mContext.getPackageName(),
                        DATABASE_NAME,
                        ImmutableList.of(AppSearchEmail.SCHEMA),
                        /* visibilityConfigs= */ ImmutableList.of(),
                        /* accountPropertyPaths= */ Collections.emptyMap(),
                        /* forceOverride= */ true,
                        /* version= */ 0,
                        /* setSchemaStatsBuilder= */ null,
                        /* callStatsBuilder= */ null);
        assertThat(setSchemaResponse.isSuccess()).isTrue();

        GenericDocument inputEmail =
                new AppSearchEmail.Builder("namespace", "id").setBody("body").build();
        appSearchImpl.putDocument(
                mContext.getPackageName(),
                DATABASE_NAME,
                inputEmail,
                /* sendChangeNotifications= */ false,
                /* logger= */ null,
                /* callStatsBuilder= */ null);

        // Make sure it can be retrieved
        GenericDocument get1 =
                appSearchImpl.getDocument(
                        mContext.getPackageName(),
                        DATABASE_NAME,
                        "namespace",
                        "id",
                        /* typePropertyPaths= */ ImmutableMap.of(),
                        /* callStatsBuilder= */ null);
        assertThat(get1).isEqualTo(inputEmail);

        // Delete a package and trigger an update
        Intent fakeIntent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        fakeIntent.setData(Uri.parse("package:" + mContext.getPackageName()));
        fakeIntent.putExtra(Intent.EXTRA_UID, Process.myUid());
        fakeIntent.putExtra(Intent.EXTRA_REPLACING, true);
        fakeIntent.putExtra(Intent.EXTRA_DATA_REMOVED, true);

        // Make sure the filter matches the intent
        assertThat(mCapturedPackageReceiverFilter.asPredicate().test(fakeIntent)).isTrue();
        // Send it
        mCapturedPackageReceiver.onReceive(mContext, fakeIntent);

        // Make sure the doc is now gone
        AppSearchException get2Exception =
                Assert.assertThrows(
                        AppSearchException.class,
                        () ->
                                appSearchImpl.getDocument(
                                        mContext.getPackageName(),
                                        DATABASE_NAME,
                                        "namespace",
                                        "id",
                                        /* typePropertyPaths= */ ImmutableMap.of(),
                                        /* callStatsBuilder= */ null));
        assertThat(get2Exception.getResultCode()).isEqualTo(AppSearchResult.RESULT_NOT_FOUND);
    }

    /**
     * Tests to make sure AppSearch data is retained on system app downgrades if the feature's kill
     * switch is triggered.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DISABLE_ACTION_PACKAGE_REMOVED_PRUNING)
    public void testUninstallSystemApp_dataRetainedOnDowngradeWithKillSwitch() throws Exception {
        // Disable threading so we don't have to wait for async activity.
        mAppSearchManagerService
                .getExecutorManager()
                .setUserExecutorForTest(mUserHandle, MoreExecutors.newDirectExecutorService());

        // Index some AppSearchData.
        AppSearchImpl appSearchImpl = mUserInstance.getAppSearchImpl();
        InternalSetSchemaResponse setSchemaResponse =
                appSearchImpl.setSchema(
                        mContext.getPackageName(),
                        DATABASE_NAME,
                        ImmutableList.of(AppSearchEmail.SCHEMA),
                        /* visibilityConfigs= */ ImmutableList.of(),
                        /* accountPropertyPaths= */ Collections.emptyMap(),
                        /* forceOverride= */ true,
                        /* version= */ 0,
                        /* setSchemaStatsBuilder= */ null,
                        /* callStatsBuilder= */ null);
        assertThat(setSchemaResponse.isSuccess()).isTrue();

        GenericDocument inputEmail =
                new AppSearchEmail.Builder("namespace", "id").setBody("body").build();
        appSearchImpl.putDocument(
                mContext.getPackageName(),
                DATABASE_NAME,
                inputEmail,
                /* sendChangeNotifications= */ false,
                /* logger= */ null,
                /* callStatsBuilder= */ null);

        // Make sure it can be retrieved
        GenericDocument get1 =
                appSearchImpl.getDocument(
                        mContext.getPackageName(),
                        DATABASE_NAME,
                        "namespace",
                        "id",
                        /* typePropertyPaths= */ ImmutableMap.of(),
                        /* callStatsBuilder= */ null);
        assertThat(get1).isEqualTo(inputEmail);

        // Delete a package and trigger an update
        Intent fakeIntent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        fakeIntent.setData(Uri.parse("package:" + mContext.getPackageName()));
        fakeIntent.putExtra(Intent.EXTRA_UID, Process.myUid());
        fakeIntent.putExtra(Intent.EXTRA_REPLACING, true);
        fakeIntent.putExtra(Intent.EXTRA_DATA_REMOVED, true);

        // Make sure the filter no longer matches the intent
        assertThat(mCapturedPackageReceiverFilter.asPredicate().test(fakeIntent)).isFalse();
        // Send it
        mCapturedPackageReceiver.onReceive(mContext, fakeIntent);

        // Make sure the doc is still serving
        GenericDocument get2 =
                appSearchImpl.getDocument(
                        mContext.getPackageName(),
                        DATABASE_NAME,
                        "namespace",
                        "id",
                        /* typePropertyPaths= */ ImmutableMap.of(),
                        /* callStatsBuilder= */ null);
        assertThat(get2).isEqualTo(inputEmail);
    }

    @Test
    // Although data should always be retained on upgrade regardless of the value of the kill
    // switch, this particular test sends a PACKAGE_REMOVED simulating an upgrade and asserts that a
    // receiver is registered to receive it, so without RequiresFlagsDisabled it would start failing
    // if we ever disabled the kill switch.
    @RequiresFlagsDisabled(Flags.FLAG_DISABLE_ACTION_PACKAGE_REMOVED_PRUNING)
    public void testUninstallSystemApp_dataRetainedOnUpgrade() throws Exception {
        // Disable threading so we don't have to wait for async activity.
        mAppSearchManagerService
                .getExecutorManager()
                .setUserExecutorForTest(mUserHandle, MoreExecutors.newDirectExecutorService());

        // Index some AppSearchData.
        AppSearchImpl appSearchImpl = mUserInstance.getAppSearchImpl();
        InternalSetSchemaResponse setSchemaResponse =
                appSearchImpl.setSchema(
                        mContext.getPackageName(),
                        DATABASE_NAME,
                        ImmutableList.of(AppSearchEmail.SCHEMA),
                        /* visibilityConfigs= */ ImmutableList.of(),
                        /* accountPropertyPaths= */ Collections.emptyMap(),
                        /* forceOverride= */ true,
                        /* version= */ 0,
                        /* setSchemaStatsBuilder= */ null,
                        /* callStatsBuilder= */ null);
        assertThat(setSchemaResponse.isSuccess()).isTrue();

        GenericDocument inputEmail =
                new AppSearchEmail.Builder("namespace", "id").setBody("body").build();
        appSearchImpl.putDocument(
                mContext.getPackageName(),
                DATABASE_NAME,
                inputEmail,
                /* sendChangeNotifications= */ false,
                /* logger= */ null,
                /* callStatsBuilder= */ null);

        // Make sure it can be retrieved
        GenericDocument get1 =
                appSearchImpl.getDocument(
                        mContext.getPackageName(),
                        DATABASE_NAME,
                        "namespace",
                        "id",
                        /* typePropertyPaths= */ ImmutableMap.of(),
                        /* callStatsBuilder= */ null);
        assertThat(get1).isEqualTo(inputEmail);

        // Delete a package and trigger an update
        Intent fakeIntent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        fakeIntent.setData(Uri.parse("package:" + mContext.getPackageName()));
        fakeIntent.putExtra(Intent.EXTRA_UID, Process.myUid());
        fakeIntent.putExtra(Intent.EXTRA_REPLACING, true);
        // Only difference from testUninstallSystemApp_usingPackageRemoved:
        fakeIntent.putExtra(Intent.EXTRA_DATA_REMOVED, false);

        // Make sure the filter matches the intent
        assertThat(mCapturedPackageReceiverFilter.asPredicate().test(fakeIntent)).isTrue();
        // Send it
        mCapturedPackageReceiver.onReceive(mContext, fakeIntent);

        // Make sure the doc is still serving
        GenericDocument get2 =
                appSearchImpl.getDocument(
                        mContext.getPackageName(),
                        DATABASE_NAME,
                        "namespace",
                        "id",
                        /* typePropertyPaths= */ ImmutableMap.of(),
                        /* callStatsBuilder= */ null);
        assertThat(get2).isEqualTo(inputEmail);
    }

    /**
     * Tests that a PACKAGE_REMOVED intent where REPLACING==false will not clear data.
     *
     * <p>Such data will be cleared by an eventual PACKAGE_FULLY_REMOVED intent, which is separately
     * tested by {{@link #testUninstallSystemApp_usingPackageFullyRemoved()}}
     */
    @Test
    // This test sends a PACKAGE_REMOVED simulating an uninstall and asserts that a receiver is
    // registered to receive it, so without RequiresFlagsDisabled it would start failing if we ever
    // disabled the kill switch.
    @RequiresFlagsDisabled(Flags.FLAG_DISABLE_ACTION_PACKAGE_REMOVED_PRUNING)
    public void testUninstallApp_dataRetainedIfNotReplacing() throws Exception {
        // Disable threading so we don't have to wait for async activity.
        mAppSearchManagerService
                .getExecutorManager()
                .setUserExecutorForTest(mUserHandle, MoreExecutors.newDirectExecutorService());

        // Index some AppSearchData.
        AppSearchImpl appSearchImpl = mUserInstance.getAppSearchImpl();
        InternalSetSchemaResponse setSchemaResponse =
                appSearchImpl.setSchema(
                        mContext.getPackageName(),
                        DATABASE_NAME,
                        ImmutableList.of(AppSearchEmail.SCHEMA),
                        /* visibilityConfigs= */ ImmutableList.of(),
                        /* accountPropertyPaths= */ Collections.emptyMap(),
                        /* forceOverride= */ true,
                        /* version= */ 0,
                        /* setSchemaStatsBuilder= */ null,
                        /* callStatsBuilder= */ null);
        assertThat(setSchemaResponse.isSuccess()).isTrue();

        GenericDocument inputEmail =
                new AppSearchEmail.Builder("namespace", "id").setBody("body").build();
        appSearchImpl.putDocument(
                mContext.getPackageName(),
                DATABASE_NAME,
                inputEmail,
                /* sendChangeNotifications= */ false,
                /* logger= */ null,
                /* callStatsBuilder= */ null);

        // Make sure it can be retrieved
        GenericDocument get1 =
                appSearchImpl.getDocument(
                        mContext.getPackageName(),
                        DATABASE_NAME,
                        "namespace",
                        "id",
                        /* typePropertyPaths= */ ImmutableMap.of(),
                        /* callStatsBuilder= */ null);
        assertThat(get1).isEqualTo(inputEmail);

        // Delete a package and trigger an uninstall
        Intent fakeIntent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        fakeIntent.setData(Uri.parse("package:" + mContext.getPackageName()));
        fakeIntent.putExtra(Intent.EXTRA_UID, Process.myUid());
        fakeIntent.putExtra(Intent.EXTRA_REPLACING, false); // Key line
        fakeIntent.putExtra(Intent.EXTRA_DATA_REMOVED, true);

        // Make sure the filter matches the intent
        assertThat(mCapturedPackageReceiverFilter.asPredicate().test(fakeIntent)).isTrue();
        // Send it
        mCapturedPackageReceiver.onReceive(mContext, fakeIntent);

        // Make sure the doc is still serving
        GenericDocument get2 =
                appSearchImpl.getDocument(
                        mContext.getPackageName(),
                        DATABASE_NAME,
                        "namespace",
                        "id",
                        /* typePropertyPaths= */ ImmutableMap.of(),
                        /* callStatsBuilder= */ null);
        assertThat(get2).isEqualTo(inputEmail);
    }
}
