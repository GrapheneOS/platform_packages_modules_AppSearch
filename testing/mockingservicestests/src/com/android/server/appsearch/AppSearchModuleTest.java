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

import static com.android.server.appsearch.appsindexer.AppsIndexerConfig.DEFAULT_APPS_INDEXER_ENABLED;
import static com.android.server.appsearch.contactsindexer.ContactsIndexerConfig.DEFAULT_CONTACTS_INDEXER_ENABLED;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.UserInfo;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.DeviceConfig;

import androidx.test.core.app.ApplicationProvider;

import com.android.appsearch.flags.Flags;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.SystemService.TargetUser;
import com.android.server.appsearch.AppSearchModule.Lifecycle;
import com.android.server.appsearch.appsindexer.AppsIndexerConfig;
import com.android.server.appsearch.appsindexer.AppsIndexerManagerService;
import com.android.server.appsearch.contactsindexer.ContactsIndexerConfig;
import com.android.server.appsearch.contactsindexer.ContactsIndexerManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RequiresFlagsEnabled(Flags.FLAG_APPS_INDEXER_ENABLED)
public class AppSearchModuleTest {
    private static final String NAMESPACE_APPSEARCH = "appsearch";
    private static final String KEY_CONTACTS_INDEXER_ENABLED = "contacts_indexer_enabled";
    private static final String KEY_APPS_INDEXER_ENABLED = "apps_indexer_enabled";

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private final ContactsIndexerManagerService mContactsIndexerService =
            mock(ContactsIndexerManagerService.class);
    private final AppsIndexerManagerService mAppsIndexerService =
            mock(AppsIndexerManagerService.class);
    private final AppSearchManagerService mAppSearchService = mock(AppSearchManagerService.class);

    private TargetUser mUser;
    private Lifecycle mLifecycle;
    private MockitoSession mMockitoSession;

    @Before
    public void setUp() {
        mMockitoSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(DeviceConfig.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        Context context = ApplicationProvider.getApplicationContext();
        UserInfo userInfo = new UserInfo(context.getUserId(), "default", 0);
        mUser = new TargetUser(userInfo);

        mLifecycle =
                new Lifecycle(context) {
                    @NonNull
                    @Override
                    AppsIndexerManagerService createAppsIndexerManagerService(
                            @NonNull Context context, @NonNull AppsIndexerConfig config) {
                        return mAppsIndexerService;
                    }

                    @NonNull
                    @Override
                    ContactsIndexerManagerService createContactsIndexerManagerService(
                            @NonNull Context context, @NonNull ContactsIndexerConfig config) {
                        return mContactsIndexerService;
                    }

                    @NonNull
                    @Override
                    AppSearchManagerService createAppSearchManagerService(
                            @NonNull Context context, @NonNull Lifecycle lifecycle) {
                        return mAppSearchService;
                    }
                };

        // Enable contacts indexer and apps indexer by default. Some tests will turn them off
        ExtendedMockito.doReturn(true)
                .when(
                        () ->
                                DeviceConfig.getBoolean(
                                        NAMESPACE_APPSEARCH,
                                        KEY_CONTACTS_INDEXER_ENABLED,
                                        DEFAULT_CONTACTS_INDEXER_ENABLED));
        ExtendedMockito.doReturn(true)
                .when(
                        () ->
                                DeviceConfig.getBoolean(
                                        NAMESPACE_APPSEARCH,
                                        KEY_APPS_INDEXER_ENABLED,
                                        DEFAULT_APPS_INDEXER_ENABLED));
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void testBothIndexersEnabled() {
        mLifecycle.onStart();
        assertThat(mLifecycle.mAppsIndexerManagerService).isNotNull();
        assertThat(mLifecycle.mContactsIndexerManagerService).isNotNull();

        mLifecycle.onUserUnlocking(mUser);
        verify(mContactsIndexerService).onUserUnlocking(mUser);
        verify(mAppsIndexerService).onUserUnlocking(mUser);

        mLifecycle.onUserStopping(mUser);
        verify(mContactsIndexerService).onUserStopping(mUser);
        verify(mAppsIndexerService).onUserStopping(mUser);
    }

    @Test
    public void testContactsIndexerDisabled() {
        ExtendedMockito.doReturn(false)
                .when(
                        () ->
                                DeviceConfig.getBoolean(
                                        NAMESPACE_APPSEARCH,
                                        KEY_CONTACTS_INDEXER_ENABLED,
                                        DEFAULT_CONTACTS_INDEXER_ENABLED));

        mLifecycle.onStart();
        assertNull(mLifecycle.mContactsIndexerManagerService);

        mLifecycle.onUserUnlocking(mUser);
        verify(mAppsIndexerService).onUserUnlocking(mUser);
        assertNull(mLifecycle.mContactsIndexerManagerService);

        mLifecycle.onUserStopping(mUser);
        verify(mAppsIndexerService).onUserStopping(mUser);
        assertNull(mLifecycle.mContactsIndexerManagerService);
    }

    @Test
    public void testAppsIndexerDisabled() {
        ExtendedMockito.doReturn(false)
                .when(
                        () ->
                                DeviceConfig.getBoolean(
                                        NAMESPACE_APPSEARCH,
                                        KEY_APPS_INDEXER_ENABLED,
                                        DEFAULT_APPS_INDEXER_ENABLED));

        mLifecycle.onStart();
        assertNull(mLifecycle.mAppsIndexerManagerService);

        mLifecycle.onUserUnlocking(mUser);
        verify(mContactsIndexerService).onUserUnlocking(mUser);
        assertNull(mLifecycle.mAppsIndexerManagerService);

        mLifecycle.onUserStopping(mUser);
        verify(mContactsIndexerService).onUserStopping(mUser);
        assertNull(mLifecycle.mAppsIndexerManagerService);
    }

    @Test
    public void testServicesSetToNullWhenDisabled() {
        ExtendedMockito.doReturn(false)
                .when(
                        () ->
                                DeviceConfig.getBoolean(
                                        NAMESPACE_APPSEARCH,
                                        KEY_CONTACTS_INDEXER_ENABLED,
                                        DEFAULT_CONTACTS_INDEXER_ENABLED));
        ExtendedMockito.doReturn(false)
                .when(
                        () ->
                                DeviceConfig.getBoolean(
                                        NAMESPACE_APPSEARCH,
                                        KEY_APPS_INDEXER_ENABLED,
                                        DEFAULT_APPS_INDEXER_ENABLED));

        mLifecycle.onStart();
        assertNull(mLifecycle.mContactsIndexerManagerService);
        assertNull(mLifecycle.mAppsIndexerManagerService);

        mLifecycle.onUserUnlocking(mUser);
        assertNull(mLifecycle.mContactsIndexerManagerService);
        assertNull(mLifecycle.mAppsIndexerManagerService);

        mLifecycle.onUserStopping(mUser);
        assertNull(mLifecycle.mContactsIndexerManagerService);
        assertNull(mLifecycle.mAppsIndexerManagerService);
    }

    @Test
    public void testIndexerOnStart_clearsService() {
        // Setup AppsIndexerManagerService to throw an error on start
        doThrow(new RuntimeException("Apps indexer exception")).when(mAppsIndexerService).onStart();

        mLifecycle.onStart();
        assertThat(mLifecycle.mAppsIndexerManagerService).isNull();
        assertThat(mLifecycle.mContactsIndexerManagerService).isNotNull();

        // Setup ContactsIndexerManagerService to throw an error on start
        doNothing().when(mAppsIndexerService).onStart();
        doThrow(new RuntimeException("Contacts indexer exception"))
                .when(mContactsIndexerService)
                .onStart();

        mLifecycle.onStart();
        assertThat(mLifecycle.mAppsIndexerManagerService).isNotNull();
        assertThat(mLifecycle.mContactsIndexerManagerService).isNull();
    }
}
