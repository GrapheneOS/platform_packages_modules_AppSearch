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

package com.android.server.appsearch.contactsindexer;

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.RECEIVE_BOOT_COMPLETED;

import static com.android.server.appsearch.contactsindexer.ContactsIndexerMaintenanceConfig.MIN_CONTACTS_INDEXER_JOB_ID;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.UiAutomation;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchSessionShim;
import android.app.appsearch.GlobalSearchSessionShim;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.observer.DocumentChangeInfo;
import android.app.appsearch.observer.ObserverCallback;
import android.app.appsearch.observer.ObserverSpec;
import android.app.appsearch.observer.SchemaChangeInfo;
import android.app.appsearch.testutil.AppSearchSessionShimImpl;
import android.app.appsearch.testutil.GlobalSearchSessionShimImpl;
import android.app.appsearch.testutil.TestContactsIndexerConfig;
import android.app.job.JobScheduler;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.UserInfo;
import android.provider.ContactsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.SystemUtil;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.modules.utils.testing.StaticMockFixture;
import com.android.server.LocalManagerRegistry;
import com.android.server.SystemService;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class ContactsIndexerManagerServiceTest extends FakeContactsProviderTestBase {
    private final ExecutorService mSingleThreadedExecutor = Executors.newSingleThreadExecutor();
    private ContactsIndexerManagerService mContactsIndexerManagerService;
    private UiAutomation mUiAutomation;
    private JobScheduler mJobScheduler;
    // Job id for full update job for the test context's user id
    private int mJobId = -1;

    private final MockLocalManagerRegistry mMockLocalManagerRegistry =
            new MockLocalManagerRegistry();

    @Rule
    public ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder()
            .addStaticMockFixtures(() -> mMockLocalManagerRegistry)
            .build();

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        mContactsIndexerManagerService = new ContactsIndexerManagerService(mContext,
                new TestContactsIndexerConfig());
        // Ensure no scheduled job
        mJobScheduler = mContext.getSystemService(JobScheduler.class);
        mJobId = MIN_CONTACTS_INDEXER_JOB_ID + mContext.getUserId();
        mJobScheduler.cancel(mJobId);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        // Wipe the data in AppSearchHelper.DATABASE_NAME.
        AppSearchSessionShim db = AppSearchSessionShimImpl.createSearchSessionAsync(mContext,
                new AppSearchManager.SearchContext.Builder(AppSearchHelper.DATABASE_NAME).build(),
                mSingleThreadedExecutor).get();
        db.setSchemaAsync(new SetSchemaRequest.Builder().setForceOverride(true).build()).get();
        // Clean up scheduled job
        if (mJobScheduler != null && mJobId != -1) {
            mJobScheduler.cancel(mJobId);
        }
        super.tearDown();
    }

    @Test
    public void testCP2Clear_runsFullUpdate() throws Exception {
        String contactsProviderPackageName = getContactsProviderPackageName();
        Assume.assumeNotNull(contactsProviderPackageName);

        // This config prevents delta updates from indexing any contacts
        ContactsIndexerConfig config = new TestContactsIndexerConfig() {
            @Override
            public int getContactsFirstRunIndexingLimit() {
                return 0;
            }

            @Override
            public int getContactsDeltaUpdateLimit() {
                return 0;
            }
        };
        mContactsIndexerManagerService = new ContactsIndexerManagerService(mContext, config);
        UserInfo userInfo = new UserInfo(mContext.getUser().getIdentifier(),
                /* name= */ "default", /* flags= */ 0);
        SystemService.TargetUser targetUser = new SystemService.TargetUser(userInfo);

        // Permissions required for registering receivers and scheduling jobs
        mUiAutomation.adoptShellPermissionIdentity(INTERACT_ACROSS_USERS_FULL,
                RECEIVE_BOOT_COMPLETED);
        try {
            mContactsIndexerManagerService.onStart();

            // Contacts indexer does an initial delta update on the first run but we've set the
            // limits to 0 so don't need to worry about a race condition where the delta update
            // indexes the contacts added below before the full update runs
            mContactsIndexerManagerService.onUserUnlocking(targetUser);

            // Verify full update has not run before (timestamp is 0)
            assertThat(getLastFullUpdateTimestampFromContactsIndexerDump()).isEqualTo(0);

            // Populate fake CP2 with 100 contacts.
            ContentResolver resolver = mContext.getContentResolver();
            ContentValues values = new ContentValues();
            for (int i = 0; i < 100; i++) {
                resolver.insert(ContactsContract.Contacts.CONTENT_URI, values);
            }

            CountDownLatch fullUpdateLatch = countDownAppSearchDocumentChanges(100);
            // Clear the user data for the CP2 package which should trigger a full update
            SystemUtil.runShellCommand("pm clear --user " + mContext.getUserId() + " "
                    + contactsProviderPackageName);
            // Wait for full update to run and index all 100 contacts.
            assertThat(fullUpdateLatch.await(10L, TimeUnit.SECONDS)).isTrue();

            // Spin for 10 seconds max to wait for full update to update timestamps; the timestamps
            // are updated at the end after the contacts are indexed in a chained future so we
            // cannot guarantee a point in time in which the timestamps have been updated
            long endTimeMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
            long fullUpdateTimestampMillis = -1;
            while (System.currentTimeMillis() < endTimeMillis) {
                fullUpdateTimestampMillis = getLastFullUpdateTimestampFromContactsIndexerDump();
                if (fullUpdateTimestampMillis > 0) {
                    break;
                }
                // Sleep otherwise it's a tight loop since there's no i/o
                Thread.sleep(10);
            }
            assertThat(fullUpdateTimestampMillis).isGreaterThan(0);
        } finally {
            mContactsIndexerManagerService.onUserStopping(targetUser);
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    /** Returns null if the contacts provider package cannot be queried. */
    @Nullable private String getContactsProviderPackageName() {
        PackageManager pm = mContext.getPackageManager();
        List<ProviderInfo> providers =
                pm.queryContentProviders(
                        /* processName= */ null,
                        /* uid= */ 0,
                        PackageManager.ComponentInfoFlags.of(0));
        for (int i = 0; i < providers.size(); i++) {
            ProviderInfo providerInfo = providers.get(i);
            if (ContactsContract.AUTHORITY.equals(providerInfo.authority)) {
                return providerInfo.packageName;
            }
        }
        return null;
    }

    // This tests a local scheduled job for Contacts Indexer in the test package
    @Test
    public void testLocalScheduledJob_runsFullUpdate() throws Exception {
        // Allow first run delta update to index contacts but prevent following delta updates from
        // indexing contacts
        ContactsIndexerConfig config = new TestContactsIndexerConfig() {
            @Override
            public int getContactsFirstRunIndexingLimit() {
                return 100;
            }

            @Override
            public int getContactsDeltaUpdateLimit() {
                return 0;
            }
        };
        mContactsIndexerManagerService = new ContactsIndexerManagerService(mContext, config);
        UserInfo userInfo = new UserInfo(mContext.getUser().getIdentifier(),
                /* name= */ "default", /* flags= */ 0);
        SystemService.TargetUser targetUser = new SystemService.TargetUser(userInfo);

        // Permissions required for registering receivers and scheduling jobs
        mUiAutomation.adoptShellPermissionIdentity(INTERACT_ACROSS_USERS_FULL,
                RECEIVE_BOOT_COMPLETED);
        try {
            mContactsIndexerManagerService.onStart();

            // Populate fake CP2 and allow the first run delta update to index contacts; since a
            // full update job is scheduled right before this first run delta update, waiting for
            // the delta update to finish guarantees that the full update job is scheduled
            ContentResolver resolver = mContext.getContentResolver();
            ContentValues values = new ContentValues();
            for (int i = 0; i < 100; i++) {
                resolver.insert(ContactsContract.Contacts.CONTENT_URI, values);
            }

            CountDownLatch bootstrapLatch = countDownAppSearchDocumentChanges(100);
            mContactsIndexerManagerService.onUserUnlocking(targetUser);
            assertThat(bootstrapLatch.await(10L, TimeUnit.SECONDS)).isTrue();

            // Verify full update job was scheduled
            assertThat(mJobScheduler.getPendingJob(mJobId)).isNotNull();

            // Add more contacts and force the full update job to run immediately
            for (int i = 0; i < 100; i++) {
                resolver.insert(ContactsContract.Contacts.CONTENT_URI, values);
            }

            CountDownLatch fullUpdateLatch = countDownAppSearchDocumentChanges(100);
            // Force scheduled job in test package to run immediately
            SystemUtil.runShellCommand(mUiAutomation,
                    "cmd jobscheduler run -f " + mContext.getPackageName() + " " + mJobId);
            assertThat(fullUpdateLatch.await(10L, TimeUnit.SECONDS)).isTrue();
        } finally {
            mContactsIndexerManagerService.onUserStopping(targetUser);
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    private long getLastFullUpdateTimestampFromContactsIndexerDump() {
        StringWriter stringWriter = new StringWriter();
        PrintWriter pw = new PrintWriter(stringWriter);
        mContactsIndexerManagerService.dumpContactsIndexerForUser(mContext.getUser(), pw,
                /* verbose= */ false);
        String[] output = stringWriter.toString().split(System.lineSeparator());
        return getTimestampOutOfDump(output[0]);
    }

    private long getTimestampOutOfDump(String dumpOutputOneLine) {
        // e.g. "last_full_update_timestamp_millis: 12345"
        String[] arrs = dumpOutputOneLine.split(" ");
        assertThat(arrs).hasLength(2);
        return Long.parseLong(arrs[1]);
    }

    @Test
    public void test_onUserUnlocking_handlesExceptionGracefully() {
        mContactsIndexerManagerService.onUserUnlocking(null);
    }

    @Test
    public void test_onUserStopping_handlesExceptionGracefully() {
        mContactsIndexerManagerService.onUserStopping(null);
    }

    @Test
    public void test_dumpContactsIndexerForUser_handlesExceptionGracefully() {
        mContactsIndexerManagerService.dumpContactsIndexerForUser(null, null, false);
    }

    /**
     * Returns a latch to count down the given number of document changes in the Person corpus.
     *
     * <p>The latch counts down to 0, and can be used to wait until the expected number of document
     * changes have occurred.
     */
    @NonNull
    private CountDownLatch countDownAppSearchDocumentChanges(int numChanges) throws Exception {
        CountDownLatch latch = new CountDownLatch(numChanges);
        GlobalSearchSessionShim shim =
                GlobalSearchSessionShimImpl.createGlobalSearchSessionAsync(mContext).get();
        ObserverCallback callback = new ObserverCallback() {
            @Override
            public void onSchemaChanged(SchemaChangeInfo changeInfo) {
                // Do nothing
            }

            @Override
            public void onDocumentChanged(DocumentChangeInfo changeInfo) {
                for (int i = 0; i < changeInfo.getChangedDocumentIds().size(); i++) {
                    latch.countDown();
                }
            }
        };
        shim.registerObserverCallback(mContext.getPackageName(),
                new ObserverSpec.Builder().addFilterSchemas("builtin:Person").build(),
                mSingleThreadedExecutor,
                callback);
        return latch;
    }

    /**
     * Prevents actually adding a manager to the registry since the registry is static and will
     * throw an exception across tests if multiple ContactsIndexerManagerServices try to register
     * a LocalService from onStart(). Instead, captures the LocalService and does nothing on
     * addManager and supplies the captured LocalService during getManager.
     */
    private static class MockLocalManagerRegistry implements StaticMockFixture {
        ArgumentCaptor<ContactsIndexerManagerService.LocalService> mLocalServiceCaptor =
                ArgumentCaptor.forClass(ContactsIndexerManagerService.LocalService.class);

        @Override
        public StaticMockitoSessionBuilder setUpMockedClasses(
                @NonNull StaticMockitoSessionBuilder sessionBuilder) {
            sessionBuilder.mockStatic(LocalManagerRegistry.class);
            return sessionBuilder;
        }

        @Override
        public void setUpMockBehaviors() {
            ExtendedMockito.doNothing().when(
                    () -> LocalManagerRegistry.addManager(any(), mLocalServiceCaptor.capture()));
            ExtendedMockito.doAnswer(invocation -> mLocalServiceCaptor.getValue()).when(
                    () -> LocalManagerRegistry.getManager(
                            ContactsIndexerManagerService.LocalService.class));
        }

        @Override
        public void tearDown() {
        }
    }
}
