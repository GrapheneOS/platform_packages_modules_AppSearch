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

import static android.provider.DeviceConfig.NAMESPACE_APPSEARCH;

import static com.android.server.appsearch.appsindexer.AppOpenEventIndexerConfig.DEFAULT_APP_OPEN_EVENT_INDEXER_ENABLED;
import static com.android.server.appsearch.appsindexer.AppsIndexerConfig.DEFAULT_APPS_INDEXER_ENABLED;
import static com.android.server.appsearch.contactsindexer.ContactsIndexerConfig.DEFAULT_CONTACTS_INDEXER_ENABLED;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.android.server.appsearch.appsindexer.AppOpenEventIndexerConfig;
import com.android.server.appsearch.appsindexer.AppOpenEventIndexerManagerService;
import com.android.server.appsearch.appsindexer.AppsIndexerConfig;
import com.android.server.appsearch.appsindexer.AppsIndexerManagerService;
import com.android.server.appsearch.contactsindexer.ContactsIndexerConfig;
import com.android.server.appsearch.contactsindexer.ContactsIndexerManagerService;

import com.google.common.annotations.VisibleForTesting;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RequiresFlagsEnabled(Flags.FLAG_APPS_INDEXER_ENABLED)
public class AppSearchModuleTest {
    @VisibleForTesting
    public static final String KEY_CONTACTS_INDEXER_ENABLED = "contacts_indexer_enabled";

    @VisibleForTesting public static final String KEY_APPS_INDEXER_ENABLED = "apps_indexer_enabled";

    @VisibleForTesting
    public static final String KEY_APP_OPEN_EVENT_INDEXER_ENABLED =
            "app_open_event_indexer_enabled";

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private final ContactsIndexerManagerService mContactsIndexerService =
            mock(ContactsIndexerManagerService.class);
    private final AppsIndexerManagerService mAppsIndexerService =
            mock(AppsIndexerManagerService.class);
    private final AppOpenEventIndexerManagerService mAppOpenEventIndexerService =
            mock(AppOpenEventIndexerManagerService.class);
    private final AppSearchManagerService mAppSearchService = mock(AppSearchManagerService.class);

    private TargetUser mUser;
    private Lifecycle mLifecycle;
    private MockitoSession mMockitoSession;

    private Context mContext;

    @Before
    public void setUp() {
        mMockitoSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(DeviceConfig.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        mContext = ApplicationProvider.getApplicationContext();
        UserInfo userInfo = new UserInfo(mContext.getUserId(), "default", 0);
        mUser = new TargetUser(userInfo);

        mLifecycle =
                new Lifecycle(mContext) {
                    @NonNull
                    @Override
                    AppsIndexerManagerService createAppsIndexerManagerService(
                            @NonNull Context mContext, @NonNull AppsIndexerConfig config) {
                        return mAppsIndexerService;
                    }

                    @NonNull
                    @Override
                    ContactsIndexerManagerService createContactsIndexerManagerService(
                            @NonNull Context mContext, @NonNull ContactsIndexerConfig config) {
                        return mContactsIndexerService;
                    }

                    @NonNull
                    @Override
                    AppSearchManagerService createAppSearchManagerService(
                            @NonNull Context mContext, @NonNull Lifecycle lifecycle) {
                        return mAppSearchService;
                    }

                    @NonNull
                    @Override
                    AppOpenEventIndexerManagerService createAppOpenEventIndexerManagerService(
                            @NonNull Context mContext, @NonNull AppOpenEventIndexerConfig config) {
                        return mAppOpenEventIndexerService;
                    }
                };

        // Enable contacts + apps + app open event indexers by default. Some tests will turn them
        // off
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
        ExtendedMockito.doReturn(true)
                .when(
                        () ->
                                DeviceConfig.getBoolean(
                                        NAMESPACE_APPSEARCH,
                                        KEY_APP_OPEN_EVENT_INDEXER_ENABLED,
                                        DEFAULT_APP_OPEN_EVENT_INDEXER_ENABLED));
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_APP_OPEN_EVENT_INDEXER_ENABLED)
    public void testAllIndexersEnabled_withAppOpenEventIndexer() {
        mLifecycle.onStart();
        assertThat(mLifecycle.mAppsIndexerManagerService).isNotNull();
        assertThat(mLifecycle.mContactsIndexerManagerService).isNotNull();
        assertThat(mLifecycle.mAppOpenEventIndexerManagerService).isNotNull();

        mLifecycle.onUserUnlocking(mUser);
        verify(mContactsIndexerService).onUserUnlocking(mUser);
        verify(mAppsIndexerService).onUserUnlocking(mUser);
        verify(mAppOpenEventIndexerService).onStart();

        mLifecycle.onUserStopping(mUser);
        verify(mContactsIndexerService).onUserStopping(mUser);
        verify(mAppsIndexerService).onUserStopping(mUser);
        verify(mAppOpenEventIndexerService).onUserStopping(mUser);
    }

    @Test
    public void testContactsAndAppsIndexersEnabled() {
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
    public void testAppOpenEventIndexerDisabled() {
        ExtendedMockito.doReturn(false)
                .when(
                        () ->
                                DeviceConfig.getBoolean(
                                        NAMESPACE_APPSEARCH,
                                        KEY_APP_OPEN_EVENT_INDEXER_ENABLED,
                                        DEFAULT_APP_OPEN_EVENT_INDEXER_ENABLED));

        mLifecycle.onStart();
        assertNull(mLifecycle.mAppOpenEventIndexerManagerService);

        mLifecycle.onUserUnlocking(mUser);
        verify(mContactsIndexerService).onUserUnlocking(mUser);
        verify(mAppOpenEventIndexerService, never()).onStart();
        assertNull(mLifecycle.mAppOpenEventIndexerManagerService);

        mLifecycle.onUserStopping(mUser);
        verify(mContactsIndexerService).onUserStopping(mUser);
        verify(mAppOpenEventIndexerService, never()).onUserStopping(mUser);
        assertNull(mLifecycle.mAppOpenEventIndexerManagerService);
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
        ExtendedMockito.doReturn(false)
                .when(
                        () ->
                                DeviceConfig.getBoolean(
                                        NAMESPACE_APPSEARCH,
                                        KEY_APP_OPEN_EVENT_INDEXER_ENABLED,
                                        DEFAULT_APP_OPEN_EVENT_INDEXER_ENABLED));

        mLifecycle.onStart();
        assertNull(mLifecycle.mContactsIndexerManagerService);
        assertNull(mLifecycle.mAppsIndexerManagerService);
        assertNull(mLifecycle.mAppOpenEventIndexerManagerService);

        mLifecycle.onUserUnlocking(mUser);
        assertNull(mLifecycle.mContactsIndexerManagerService);
        assertNull(mLifecycle.mAppsIndexerManagerService);
        assertNull(mLifecycle.mAppOpenEventIndexerManagerService);

        mLifecycle.onUserStopping(mUser);
        assertNull(mLifecycle.mContactsIndexerManagerService);
        assertNull(mLifecycle.mAppsIndexerManagerService);
        assertNull(mLifecycle.mAppOpenEventIndexerManagerService);
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
