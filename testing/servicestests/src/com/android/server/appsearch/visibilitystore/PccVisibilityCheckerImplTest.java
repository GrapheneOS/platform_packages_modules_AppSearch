/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.appsearch.visibilitystore;

import static android.Manifest.permission.READ_SMS;
import static android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import android.annotation.NonNull;
import android.app.appsearch.InternalVisibilityConfig;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.aidl.AppSearchAttributionSource;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.app.appsearch.testutil.FakeAppSearchConfig;
import android.app.appsearch.testutil.FrameworkFlagUtils;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArrayMap;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.OptimizeStrategy;
import com.android.server.appsearch.external.localstorage.util.PrefixUtil;
import com.android.server.appsearch.external.localstorage.visibilitystore.VisibilityStore;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.util.Map;

/** Tests for PCC-related visibility in {@link VisibilityCheckerImpl}. */
public class PccVisibilityCheckerImplTest {
    /**
     * Always trigger optimize in this class. OptimizeStrategy will be tested in its own test class.
     */
    private static final OptimizeStrategy ALWAYS_OPTIMIZE = optimizeInfo -> true;

    private static final int PRIVATE_COMPUTE_CORE_UID_ACCESS = 12;

    @Rule public final RuleChain mRuleChain = AppSearchTestUtils.createCommonTestRules();

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    private final Map<UserHandle, PackageManager> mMockPackageManagers = new ArrayMap<>();
    private VisibilityCheckerImpl mVisibilityChecker;
    private VisibilityStore mVisibilityStore;

    @Before
    public void setUp() throws Exception {
        FrameworkFlagUtils.assumeFlagIsEnabled(FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT);
        Context context = ApplicationProvider.getApplicationContext();
        Context contextWrapper =
                new ContextWrapper(context) {
                    @Override
                    public Context createContextAsUser(UserHandle user, int flags) {
                        return new ContextWrapper(super.createContextAsUser(user, flags)) {
                            @Override
                            public PackageManager getPackageManager() {
                                return getMockPackageManager(user);
                            }
                        };
                    }

                    @Override
                    public PackageManager getPackageManager() {
                        return createContextAsUser(getUser(), /* flags= */ 0).getPackageManager();
                    }
                };
        mVisibilityChecker = Mockito.spy(new VisibilityCheckerImpl(contextWrapper));
        // Give ourselves global query permissions
        AppSearchImpl appSearchImpl =
                AppSearchImpl.create(
                        mTemporaryFolder.newFolder(),
                        new FakeAppSearchConfig(),
                        /* initStatsBuilder= */ null,
                        /* callStatsBuilder= */ null,
                        mVisibilityChecker,
                        /* revocableFileDescriptorStore= */ null,
                        /* icingSearchEngine= */ null,
                        ALWAYS_OPTIMIZE);
        mVisibilityStore =
                VisibilityStore.createDocumentVisibilityStore(
                        appSearchImpl, /* callStatsBuilder= */ null);
    }

    @Test
    public void testSetSchema_visibleToPrivateComputeCoreUid() throws Exception {
        doReturn(false).when(mVisibilityChecker).isPccTrustedSystemComponent(any());
        String prefix = PrefixUtil.createPrefix("package", "database");

        // Create a doc that requires PRIVATE_COMPUTE_CORE_UID_ACCESS permission.
        InternalVisibilityConfig visibilityConfig =
                new InternalVisibilityConfig.Builder(/* id= */ prefix + "Schema")
                        .addVisibleToPermissions(ImmutableSet.of(PRIVATE_COMPUTE_CORE_UID_ACCESS))
                        .build();
        mVisibilityStore.setVisibility(
                ImmutableList.of(visibilityConfig), /* callStatsBuilder= */ null);

        // Caller is a PCC UID, should have access.
        int pccUid = Process.FIRST_PCC_UID;
        doReturn(true).when(mVisibilityChecker).isPrivateComputeCoreUid(pccUid);
        assertThat(
                        mVisibilityChecker.isSchemaSearchableByCaller(
                                new FrameworkCallerAccess(
                                        new AppSearchAttributionSource(
                                                "package", pccUid, /* callingPid= */ 1),
                                        /* callerHasSystemAccess= */ false,
                                        /* isForEnterprise= */ false),
                                "package",
                                prefix + "Schema",
                                mVisibilityStore))
                .isTrue();

        // Caller is NOT a PCC UID, should NOT have access.
        int nonPccUid = 1000;
        doReturn(false).when(mVisibilityChecker).isPrivateComputeCoreUid(nonPccUid);
        assertThat(
                        mVisibilityChecker.isSchemaSearchableByCaller(
                                new FrameworkCallerAccess(
                                        new AppSearchAttributionSource(
                                                "package", nonPccUid, /* callingPid= */ 1),
                                        /* callerHasSystemAccess= */ false,
                                        /* isForEnterprise= */ false),
                                "package",
                                prefix + "Schema",
                                mVisibilityStore))
                .isFalse();
    }

    @Test
    public void testSetSchema_visibleToPccTrustedSystemComponent() throws Exception {
        String prefix = PrefixUtil.createPrefix("package", "database");

        // Create a doc that requires PRIVATE_COMPUTE_CORE_UID_ACCESS permission.
        InternalVisibilityConfig visibilityConfig =
                new InternalVisibilityConfig.Builder(/* id= */ prefix + "Schema")
                        .addVisibleToPermissions(ImmutableSet.of(PRIVATE_COMPUTE_CORE_UID_ACCESS))
                        .build();
        mVisibilityStore.setVisibility(
                ImmutableList.of(visibilityConfig), /* callStatsBuilder= */ null);

        // Caller is NOT a PCC UID, but IS a trusted system component.
        // We simulate this by having isPrivateComputeCoreUid return false...
        int nonPccUid = 12345;
        String packageName = "com.example.trusted";
        doReturn(false).when(mVisibilityChecker).isPrivateComputeCoreUid(nonPccUid);

        // ...and having isPccTrustedSystemComponent return true.
        doReturn(true).when(mVisibilityChecker).isPccTrustedSystemComponent(any());

        assertThat(
                        mVisibilityChecker.isSchemaSearchableByCaller(
                                new FrameworkCallerAccess(
                                        new AppSearchAttributionSource(
                                                packageName, nonPccUid, /* callingPid= */ 1),
                                        /* callerHasSystemAccess= */ false,
                                        /* isForEnterprise= */ false),
                                "package",
                                prefix + "Schema",
                                mVisibilityStore))
                .isTrue();

        // Caller is NOT a PCC UID and is NOT a trusted system component.
        doReturn(false).when(mVisibilityChecker).isPccTrustedSystemComponent(any());

        assertThat(
                        mVisibilityChecker.isSchemaSearchableByCaller(
                                new FrameworkCallerAccess(
                                        new AppSearchAttributionSource(
                                                packageName, nonPccUid, /* callingPid= */ 1),
                                        /* callerHasSystemAccess= */ false,
                                        /* isForEnterprise= */ false),
                                "package",
                                prefix + "Schema",
                                mVisibilityStore))
                .isFalse();
    }

    @Test
    public void testSetSchema_visibleToPrivateComputeCoreUidAndReadSms() throws Exception {
        doReturn(false).when(mVisibilityChecker).isPccTrustedSystemComponent(any());
        String prefix = PrefixUtil.createPrefix("package", "database");

        // Create a doc that requires BOTH PRIVATE_COMPUTE_CORE_UID_ACCESS and READ_SMS.
        InternalVisibilityConfig visibilityConfig =
                new InternalVisibilityConfig.Builder(/* id= */ prefix + "Schema")
                        .addVisibleToPermissions(
                                ImmutableSet.of(
                                        PRIVATE_COMPUTE_CORE_UID_ACCESS, SetSchemaRequest.READ_SMS))
                        .build();
        mVisibilityStore.setVisibility(
                ImmutableList.of(visibilityConfig), /* callStatsBuilder= */ null);

        int pccUid = Process.FIRST_PCC_UID;
        doReturn(true).when(mVisibilityChecker).isPrivateComputeCoreUid(pccUid);

        // Caller is a PCC UID but doesn't have READ_SMS, should NOT have access.
        doReturn(false)
                .when(mVisibilityChecker)
                .checkPermissionForDataDeliveryGranted(eq(READ_SMS), any(), any());
        assertThat(
                        mVisibilityChecker.isSchemaSearchableByCaller(
                                new FrameworkCallerAccess(
                                        new AppSearchAttributionSource(
                                                "package", pccUid, /* callingPid= */ 1),
                                        /* callerHasSystemAccess= */ false,
                                        /* isForEnterprise= */ false),
                                "package",
                                prefix + "Schema",
                                mVisibilityStore))
                .isFalse();

        // Caller is a PCC UID and has READ_SMS, should have access.
        doReturn(true)
                .when(mVisibilityChecker)
                .checkPermissionForDataDeliveryGranted(eq(READ_SMS), any(), any());
        assertThat(
                        mVisibilityChecker.isSchemaSearchableByCaller(
                                new FrameworkCallerAccess(
                                        new AppSearchAttributionSource(
                                                "package", pccUid, /* callingPid= */ 1),
                                        /* callerHasSystemAccess= */ false,
                                        /* isForEnterprise= */ false),
                                "package",
                                prefix + "Schema",
                                mVisibilityStore))
                .isTrue();

        // Caller has READ_SMS but is NOT a PCC UID, should NOT have access.
        int nonPccUid = 1000;
        doReturn(false).when(mVisibilityChecker).isPrivateComputeCoreUid(nonPccUid);
        assertThat(
                        mVisibilityChecker.isSchemaSearchableByCaller(
                                new FrameworkCallerAccess(
                                        new AppSearchAttributionSource(
                                                "package", nonPccUid, /* callingPid= */ 1),
                                        /* callerHasSystemAccess= */ false,
                                        /* isForEnterprise= */ false),
                                "package",
                                prefix + "Schema",
                                mVisibilityStore))
                .isFalse();
    }

    @NonNull
    private PackageManager getMockPackageManager(@NonNull UserHandle user) {
        PackageManager pm = mMockPackageManagers.get(user);
        if (pm == null) {
            pm = Mockito.mock(PackageManager.class);
            mMockPackageManagers.put(user, pm);
        }
        return pm;
    }
}
