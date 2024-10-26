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

import static android.Manifest.permission.RECEIVE_BOOT_COMPLETED;

import static com.android.server.appsearch.indexer.IndexerMaintenanceConfig.CONTACTS_INDEXER;
import static com.android.server.appsearch.contactsindexer.ContactsIndexerMaintenanceConfig.MIN_CONTACTS_INDEXER_JOB_ID;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.annotation.UserIdInt;
import android.app.UiAutomation;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.UserInfo;
import android.os.CancellationSignal;
import android.os.PersistableBundle;
import android.os.UserHandle;

import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.LocalManagerRegistry;
import com.android.server.SystemService;
import com.android.server.appsearch.indexer.IndexerMaintenanceService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class ContactsIndexerMaintenanceTest {
    private static final int DEFAULT_USER_ID = 0;
    private static final UserHandle DEFAULT_USER_HANDLE = new UserHandle(0);

    private Context mContext;
    private Context mContextWrapper;
    private IndexerMaintenanceService mIndexerMaintenanceService;
    private MockitoSession session;
    @Mock private JobScheduler mockJobScheduler;
    private JobParameters mParams;
    private PersistableBundle mExtras;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = ApplicationProvider.getApplicationContext();
        mContextWrapper = new ContextWrapper(mContext) {
            @Override
            @Nullable
            public Object getSystemService(String name) {
                if (Context.JOB_SCHEDULER_SERVICE.equals(name)) {
                    return mockJobScheduler;
                }
                return getSystemService(name);
            }
        };
        mIndexerMaintenanceService = spy(new IndexerMaintenanceService());
        doNothing().when(mIndexerMaintenanceService).jobFinished(any(), anyBoolean());
        session = ExtendedMockito.mockitoSession().
                mockStatic(LocalManagerRegistry.class).
                startMocking();
        mExtras = new PersistableBundle();
        mParams = Mockito.mock(JobParameters.class);
    }

    @After
    public void tearDown() {
        session.finishMocking();
    }

    @Test
    public void testScheduleFullUpdateJob_oneOff_isNotPeriodic() {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            uiAutomation.adoptShellPermissionIdentity(RECEIVE_BOOT_COMPLETED);
            IndexerMaintenanceService.scheduleUpdateJob(
                    mContext,
                    DEFAULT_USER_HANDLE,
                    CONTACTS_INDEXER,
                    /* periodic= */ false,
                    /* intervalMillis= */ -1);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
        JobInfo jobInfo = getPendingFullUpdateJob(DEFAULT_USER_ID);
        assertThat(jobInfo).isNotNull();
        assertThat(jobInfo.isRequireBatteryNotLow()).isTrue();
        assertThat(jobInfo.isRequireDeviceIdle()).isTrue();
        assertThat(jobInfo.isPersisted()).isTrue();
        assertThat(jobInfo.isPeriodic()).isFalse();
    }

    @Test
    public void testScheduleFullUpdateJob_periodic_isPeriodic() {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            uiAutomation.adoptShellPermissionIdentity(RECEIVE_BOOT_COMPLETED);
            IndexerMaintenanceService.scheduleUpdateJob(
                    mContext,
                    /* userId= */ DEFAULT_USER_HANDLE,
                    /* indexerType= */ CONTACTS_INDEXER,
                    /* periodic= */ true,
                    /* intervalMillis= */ TimeUnit.DAYS.toMillis(7));
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
        JobInfo jobInfo = getPendingFullUpdateJob(DEFAULT_USER_ID);
        assertThat(jobInfo).isNotNull();
        assertThat(jobInfo.isRequireBatteryNotLow()).isTrue();
        assertThat(jobInfo.isRequireDeviceIdle()).isTrue();
        assertThat(jobInfo.isPersisted()).isTrue();
        assertThat(jobInfo.isPeriodic()).isTrue();
        assertThat(jobInfo.getIntervalMillis()).isEqualTo(TimeUnit.DAYS.toMillis(7));
        assertThat(jobInfo.getFlexMillis()).isEqualTo(TimeUnit.DAYS.toMillis(7)/2);
    }

    @Test
    public void testScheduleFullUpdateJob_oneOffThenPeriodic_isRescheduled() {
        IndexerMaintenanceService.scheduleUpdateJob(
                mContextWrapper,
                DEFAULT_USER_HANDLE,
                /* indexerType= */ CONTACTS_INDEXER,
                /* periodic= */ false,
                /* intervalMillis= */ -1);
        ArgumentCaptor<JobInfo> firstJobInfoCaptor = ArgumentCaptor.forClass(JobInfo.class);
        verify(mockJobScheduler).schedule(firstJobInfoCaptor.capture());
        JobInfo firstJobInfo = firstJobInfoCaptor.getValue();

        when(mockJobScheduler.getPendingJob(eq(MIN_CONTACTS_INDEXER_JOB_ID)))
                .thenReturn(firstJobInfo);
        IndexerMaintenanceService.scheduleUpdateJob(
                mContextWrapper,
                DEFAULT_USER_HANDLE,
                /* indexerType= */ CONTACTS_INDEXER,
                /* periodic= */ true,
                /* intervalMillis= */ TimeUnit.DAYS.toMillis(7));
        ArgumentCaptor<JobInfo> argumentCaptor = ArgumentCaptor.forClass(JobInfo.class);
        verify(mockJobScheduler, times(2)).schedule(argumentCaptor.capture());
        List<JobInfo> jobInfos = argumentCaptor.getAllValues();
        JobInfo jobInfo = jobInfos.get(1);
        assertThat(jobInfo.isRequireBatteryNotLow()).isTrue();
        assertThat(jobInfo.isRequireDeviceIdle()).isTrue();
        assertThat(jobInfo.isPersisted()).isTrue();
        assertThat(jobInfo.isPeriodic()).isTrue();
        assertThat(jobInfo.getIntervalMillis()).isEqualTo(TimeUnit.DAYS.toMillis(7));
    }

    @Test
    public void testScheduleFullUpdateJob_differentParams_isRescheduled() {
        IndexerMaintenanceService.scheduleUpdateJob(
                mContextWrapper,
                DEFAULT_USER_HANDLE,
                /* indexerType= */ CONTACTS_INDEXER,
                /* periodic= */ true,
                /* intervalMillis= */ TimeUnit.DAYS.toMillis(7));
        ArgumentCaptor<JobInfo> firstJobInfoCaptor = ArgumentCaptor.forClass(JobInfo.class);
        verify(mockJobScheduler).schedule(firstJobInfoCaptor.capture());
        JobInfo firstJobInfo = firstJobInfoCaptor.getValue();

        when(mockJobScheduler.getPendingJob(eq(MIN_CONTACTS_INDEXER_JOB_ID)))
                .thenReturn(firstJobInfo);
        IndexerMaintenanceService.scheduleUpdateJob(
                mContextWrapper,
                DEFAULT_USER_HANDLE,
                /* indexerType= */ CONTACTS_INDEXER,
                /* periodic= */ true,
                /* intervalMillis= */ TimeUnit.DAYS.toMillis(30));
        ArgumentCaptor<JobInfo> argumentCaptor = ArgumentCaptor.forClass(JobInfo.class);
        // Mockito.verify() counts the number of occurrences from the beginning of the test.
        // This verify() uses times(2) to also account for the call to JobScheduler.schedule() above
        // where the first JobInfo is captured.
        verify(mockJobScheduler, times(2)).schedule(argumentCaptor.capture());
        List<JobInfo> jobInfos = argumentCaptor.getAllValues();
        JobInfo jobInfo = jobInfos.get(1);
        assertThat(jobInfo.isRequireBatteryNotLow()).isTrue();
        assertThat(jobInfo.isRequireDeviceIdle()).isTrue();
        assertThat(jobInfo.isPersisted()).isTrue();
        assertThat(jobInfo.isPeriodic()).isTrue();
        assertThat(jobInfo.getIntervalMillis()).isEqualTo(TimeUnit.DAYS.toMillis(30));
    }

    @Test
    public void testScheduleFullUpdateJob_sameParams_isNotRescheduled() {
        IndexerMaintenanceService.scheduleUpdateJob(
                mContextWrapper,
                DEFAULT_USER_HANDLE,
                /* indexerType= */ CONTACTS_INDEXER,
                /* periodic= */ true,
                /* intervalMillis= */ TimeUnit.DAYS.toMillis(7));
        ArgumentCaptor<JobInfo> argumentCaptor = ArgumentCaptor.forClass(JobInfo.class);
        verify(mockJobScheduler).schedule(argumentCaptor.capture());
        JobInfo firstJobInfo = argumentCaptor.getValue();

        when(mockJobScheduler.getPendingJob(eq(MIN_CONTACTS_INDEXER_JOB_ID)))
                .thenReturn(firstJobInfo);
        IndexerMaintenanceService.scheduleUpdateJob(
                mContextWrapper,
                DEFAULT_USER_HANDLE,
                /* indexerType= */ CONTACTS_INDEXER,
                /* periodic= */ true,
                /* intervalMillis= */ TimeUnit.DAYS.toMillis(7));
        // Mockito.verify() counts the number of occurrences from the beginning of the test.
        // This verify() uses the default count of 1 (equivalent to times(1)) to account for the
        // call to JobScheduler.schedule() above where the first JobInfo is captured.
        verify(mockJobScheduler).schedule(any(JobInfo.class));
    }

    @Test
    public void testDoUpdateForUser_withInitializedLocalService_isSuccessful() {
        when(mParams.getExtras()).thenReturn(mExtras);
        mExtras.putInt(IndexerMaintenanceService.INDEXER_TYPE, CONTACTS_INDEXER);
        ContactsIndexerManagerService.LocalService mockService = Mockito.mock(
                ContactsIndexerManagerService.LocalService.class);
        ExtendedMockito.doReturn(mockService).when(() -> LocalManagerRegistry.getManager(
                ContactsIndexerManagerService.LocalService.class));
        CancellationSignal signal = new CancellationSignal();
        boolean updateSucceeded =
                mIndexerMaintenanceService.doUpdateForUser(
                        mContextWrapper, mParams, DEFAULT_USER_HANDLE, signal);
        assertThat(updateSucceeded).isTrue();
        verify(mockService).doUpdateForUser(DEFAULT_USER_HANDLE, signal);
    }

    @Test
    public void testDoUpdateForUser_withInitializedLocalService_withoutIndexerType_isSuccessful() {
        when(mParams.getExtras()).thenReturn(mExtras);
        ContactsIndexerManagerService.LocalService mockService = Mockito.mock(
                ContactsIndexerManagerService.LocalService.class);
        ExtendedMockito.doReturn(mockService).when(() -> LocalManagerRegistry.getManager(
                ContactsIndexerManagerService.LocalService.class));
        CancellationSignal signal = new CancellationSignal();
        boolean updateSucceeded =
                mIndexerMaintenanceService.doUpdateForUser(
                        mContextWrapper, mParams, DEFAULT_USER_HANDLE, signal);
        assertThat(updateSucceeded).isTrue();
        verify(mockService).doUpdateForUser(DEFAULT_USER_HANDLE, signal);
    }

    @Test
    public void testOnStartJob_withInitializedLocalService_withoutIndexerType_isSuccessful() {
        when(mParams.getExtras()).thenReturn(mExtras);
        mExtras.putInt(IndexerMaintenanceService.EXTRA_USER_ID, 0);
        ContactsIndexerManagerService.LocalService mockService = Mockito.mock(
                ContactsIndexerManagerService.LocalService.class);
        ExtendedMockito.doReturn(mockService).when(() -> LocalManagerRegistry.getManager(
                ContactsIndexerManagerService.LocalService.class));
        assertThat(mIndexerMaintenanceService.onStartJob(mParams)).isTrue();
        verify(mIndexerMaintenanceService, timeout(10000)).doUpdateForUser(any(), any(), any(),
                any());
        verify(mockService, timeout(10000)).doUpdateForUser(any(), any());
    }

    @Test
    public void testDoUpdateForUser_withUninitializedLocalService_failsGracefully() {
        when(mParams.getExtras()).thenReturn(mExtras);
        mExtras.putInt(IndexerMaintenanceService.INDEXER_TYPE, CONTACTS_INDEXER);
        ExtendedMockito.doReturn(null)
                .when(() -> LocalManagerRegistry.getManager(
                        ContactsIndexerManagerService.LocalService.class));
        boolean updateSucceeded =
                mIndexerMaintenanceService.doUpdateForUser(
                        mContextWrapper, mParams, DEFAULT_USER_HANDLE, new CancellationSignal());
        assertThat(updateSucceeded).isFalse();
    }

    @Test
    public void testDoUpdateForUser_onEncounteringException_failsGracefully() {
        when(mParams.getExtras()).thenReturn(mExtras);
        mExtras.putInt(IndexerMaintenanceService.INDEXER_TYPE, CONTACTS_INDEXER);
        ContactsIndexerManagerService.LocalService mockService = Mockito.mock(
                ContactsIndexerManagerService.LocalService.class);
        doThrow(RuntimeException.class).when(mockService).doUpdateForUser(any(), any());
        ExtendedMockito.doReturn(mockService)
                .when(() -> LocalManagerRegistry.getManager(
                        ContactsIndexerManagerService.LocalService.class));

        boolean updateSucceeded =
                mIndexerMaintenanceService.doUpdateForUser(
                        mContextWrapper, mParams, DEFAULT_USER_HANDLE, new CancellationSignal());

        assertThat(updateSucceeded).isFalse();
    }

    @Test
    public void testDoUpdateForUser_cancelsBackgroundJob_whenCiDisabled() {
        when(mParams.getExtras()).thenReturn(mExtras);
        mExtras.putInt(IndexerMaintenanceService.INDEXER_TYPE, CONTACTS_INDEXER);
        ExtendedMockito.doReturn(null)
                .when(() -> LocalManagerRegistry.getManager(
                        ContactsIndexerManagerService.LocalService.class));

        mIndexerMaintenanceService.doUpdateForUser(
                mContextWrapper, mParams, DEFAULT_USER_HANDLE, new CancellationSignal());

        verify(mockJobScheduler).cancel(MIN_CONTACTS_INDEXER_JOB_ID);
    }

    @Test
    public void testDoUpdateForUser_doesNotCancelBackgroundJob_whenCiEnabled() {
        when(mParams.getExtras()).thenReturn(mExtras);
        mExtras.putInt(IndexerMaintenanceService.INDEXER_TYPE, CONTACTS_INDEXER);
        ExtendedMockito.doReturn(Mockito.mock(ContactsIndexerManagerService.LocalService.class))
                .when(() -> LocalManagerRegistry.getManager(
                        ContactsIndexerManagerService.LocalService.class));

        mIndexerMaintenanceService.doUpdateForUser(
                mContextWrapper, mParams, DEFAULT_USER_HANDLE, new CancellationSignal());

        verifyZeroInteractions(mockJobScheduler);
    }

    @Test
    public void testCancelPendingFullUpdateJob_succeeds() {
        UserInfo userInfo = new UserInfo(DEFAULT_USER_ID, /*name=*/ "default", /*flags=*/ 0);
        SystemService.TargetUser user = new SystemService.TargetUser(userInfo);
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            uiAutomation.adoptShellPermissionIdentity(RECEIVE_BOOT_COMPLETED);
            IndexerMaintenanceService.scheduleUpdateJob(
                    mContext,
                    DEFAULT_USER_HANDLE,
                    /* indexerType= */ CONTACTS_INDEXER,
                    /* periodic= */ true,
                    /* intervalMillis= */ TimeUnit.DAYS.toMillis(7));
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
        JobInfo jobInfo = getPendingFullUpdateJob(DEFAULT_USER_ID);
        assertThat(jobInfo).isNotNull();

        IndexerMaintenanceService.cancelUpdateJobIfScheduled(
                mContext, user.getUserHandle(), CONTACTS_INDEXER);

        jobInfo = getPendingFullUpdateJob(DEFAULT_USER_ID);
        assertThat(jobInfo).isNull();
    }

    @Test
    public void test_onStartJob_handlesExceptionGracefully() {
        mIndexerMaintenanceService.onStartJob(null);
    }

    @Test
    public void test_onStopJob_handlesExceptionGracefully() {
        mIndexerMaintenanceService.onStopJob(null);
    }

    @Nullable
    private JobInfo getPendingFullUpdateJob(@UserIdInt int userId) {
        int jobId = MIN_CONTACTS_INDEXER_JOB_ID + userId;
        return mContext.getSystemService(JobScheduler.class).getPendingJob(jobId);
    }
}
