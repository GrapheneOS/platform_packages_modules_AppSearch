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

import static android.Manifest.permission.RECEIVE_BOOT_COMPLETED;

import static com.android.server.appsearch.appsindexer.AppsIndexerMaintenanceConfig.MIN_APPS_INDEXER_JOB_ID;
import static com.android.server.appsearch.indexer.IndexerMaintenanceConfig.APPS_INDEXER;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AppsIndexerMaintenanceTest {
    private static final int DEFAULT_USER_ID = 0;
    private static final UserHandle DEFAULT_USER_HANDLE = new UserHandle(DEFAULT_USER_ID);

    private Context mContext = ApplicationProvider.getApplicationContext();
    private Context mContextWrapper;
    private IndexerMaintenanceService mAppsIndexerMaintenanceService;
    private MockitoSession mSession;
    @Mock private JobScheduler mMockJobScheduler;
    private JobParameters mParams;
    private PersistableBundle mExtras;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContextWrapper =
                new ContextWrapper(mContext) {
                    @Override
                    @Nullable
                    public Object getSystemService(String name) {
                        if (Context.JOB_SCHEDULER_SERVICE.equals(name)) {
                            return mMockJobScheduler;
                        }
                        return getSystemService(name);
                    }
                };
        mAppsIndexerMaintenanceService = spy(new IndexerMaintenanceService());
        doNothing().when(mAppsIndexerMaintenanceService).jobFinished(any(), anyBoolean());
        mSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(LocalManagerRegistry.class)
                        .startMocking();
        mExtras = new PersistableBundle();
        mExtras.putInt("indexer_type", APPS_INDEXER);
        mParams = Mockito.mock(JobParameters.class);
    }

    @After
    public void tearDown() {
        mSession.finishMocking();
        mAppsIndexerMaintenanceService.destroy();
    }

    @Test
    public void testScheduleUpdateJob_oneOff_isNotPeriodic() {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            uiAutomation.adoptShellPermissionIdentity(RECEIVE_BOOT_COMPLETED);
            IndexerMaintenanceService.scheduleUpdateJob(
                    mContext,
                    DEFAULT_USER_HANDLE,
                    APPS_INDEXER,
                    /* periodic= */ false,
                    /* intervalMillis= */ -1);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
        JobInfo jobInfo = getPendingUpdateJob(DEFAULT_USER_ID);
        assertThat(jobInfo).isNotNull();
        assertThat(jobInfo.isRequireBatteryNotLow()).isTrue();
        assertThat(jobInfo.isRequireDeviceIdle()).isTrue();
        assertThat(jobInfo.isPersisted()).isTrue();
        assertThat(jobInfo.isPeriodic()).isFalse();
    }

    @Test
    public void testScheduleUpdateJob_periodic_isPeriodic() {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            uiAutomation.adoptShellPermissionIdentity(RECEIVE_BOOT_COMPLETED);
            IndexerMaintenanceService.scheduleUpdateJob(
                    mContext,
                    /* userId= */ DEFAULT_USER_HANDLE,
                    /* indexerType= */ APPS_INDEXER,
                    /* periodic= */ true,
                    /* intervalMillis= */ TimeUnit.DAYS.toMillis(7));
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
        JobInfo jobInfo = getPendingUpdateJob(DEFAULT_USER_ID);
        assertThat(jobInfo).isNotNull();
        assertThat(jobInfo.isRequireBatteryNotLow()).isTrue();
        assertThat(jobInfo.isRequireDeviceIdle()).isTrue();
        assertThat(jobInfo.isPersisted()).isTrue();
        assertThat(jobInfo.isPeriodic()).isTrue();
        assertThat(jobInfo.getIntervalMillis()).isEqualTo(TimeUnit.DAYS.toMillis(7));
        assertThat(jobInfo.getFlexMillis()).isEqualTo(TimeUnit.DAYS.toMillis(7) / 2);
    }

    @Test
    public void testScheduleUpdateJob_oneOffThenPeriodic_isRescheduled() {
        IndexerMaintenanceService.scheduleUpdateJob(
                mContextWrapper,
                DEFAULT_USER_HANDLE,
                /* indexerType= */ APPS_INDEXER,
                /* periodic= */ false,
                /* intervalMillis= */ -1);
        ArgumentCaptor<JobInfo> firstJobInfoCaptor = ArgumentCaptor.forClass(JobInfo.class);
        verify(mMockJobScheduler).schedule(firstJobInfoCaptor.capture());
        JobInfo firstJobInfo = firstJobInfoCaptor.getValue();

        when(mMockJobScheduler.getPendingJob(eq(MIN_APPS_INDEXER_JOB_ID))).thenReturn(firstJobInfo);
        IndexerMaintenanceService.scheduleUpdateJob(
                mContextWrapper,
                DEFAULT_USER_HANDLE,
                /* indexerType= */ APPS_INDEXER,
                /* periodic= */ true,
                /* intervalMillis= */ TimeUnit.DAYS.toMillis(7));
        ArgumentCaptor<JobInfo> argumentCaptor = ArgumentCaptor.forClass(JobInfo.class);
        verify(mMockJobScheduler, times(2)).schedule(argumentCaptor.capture());
        List<JobInfo> jobInfos = argumentCaptor.getAllValues();
        JobInfo jobInfo = jobInfos.get(1);
        assertThat(jobInfo.isRequireBatteryNotLow()).isTrue();
        assertThat(jobInfo.isRequireDeviceIdle()).isTrue();
        assertThat(jobInfo.isPersisted()).isTrue();
        assertThat(jobInfo.isPeriodic()).isTrue();
        assertThat(jobInfo.getIntervalMillis()).isEqualTo(TimeUnit.DAYS.toMillis(7));
    }

    @Test
    public void testScheduleUpdateJob_differentParams_isRescheduled() {
        IndexerMaintenanceService.scheduleUpdateJob(
                mContextWrapper,
                DEFAULT_USER_HANDLE,
                /* indexerType= */ APPS_INDEXER,
                /* periodic= */ true,
                /* intervalMillis= */ TimeUnit.DAYS.toMillis(7));
        ArgumentCaptor<JobInfo> firstJobInfoCaptor = ArgumentCaptor.forClass(JobInfo.class);
        verify(mMockJobScheduler).schedule(firstJobInfoCaptor.capture());
        JobInfo firstJobInfo = firstJobInfoCaptor.getValue();

        when(mMockJobScheduler.getPendingJob(eq(MIN_APPS_INDEXER_JOB_ID))).thenReturn(firstJobInfo);
        IndexerMaintenanceService.scheduleUpdateJob(
                mContextWrapper,
                DEFAULT_USER_HANDLE,
                /* indexerType= */ APPS_INDEXER,
                /* periodic= */ true,
                /* intervalMillis= */ TimeUnit.DAYS.toMillis(30));
        ArgumentCaptor<JobInfo> argumentCaptor = ArgumentCaptor.forClass(JobInfo.class);
        // Mockito.verify() counts the number of occurrences from the beginning of the test.
        // This verify() uses times(2) to also account for the call to JobScheduler.schedule() above
        // where the first JobInfo is captured.
        verify(mMockJobScheduler, times(2)).schedule(argumentCaptor.capture());
        List<JobInfo> jobInfos = argumentCaptor.getAllValues();
        JobInfo jobInfo = jobInfos.get(1);
        assertThat(jobInfo.isRequireBatteryNotLow()).isTrue();
        assertThat(jobInfo.isRequireDeviceIdle()).isTrue();
        assertThat(jobInfo.isPersisted()).isTrue();
        assertThat(jobInfo.isPeriodic()).isTrue();
        assertThat(jobInfo.getIntervalMillis()).isEqualTo(TimeUnit.DAYS.toMillis(30));
    }

    @Test
    public void testScheduleUpdateJob_sameParams_isNotRescheduled() {
        IndexerMaintenanceService.scheduleUpdateJob(
                mContextWrapper,
                DEFAULT_USER_HANDLE,
                /* indexerType= */ APPS_INDEXER,
                /* periodic= */ true,
                /* intervalMillis= */ TimeUnit.DAYS.toMillis(7));
        ArgumentCaptor<JobInfo> argumentCaptor = ArgumentCaptor.forClass(JobInfo.class);
        verify(mMockJobScheduler).schedule(argumentCaptor.capture());
        JobInfo firstJobInfo = argumentCaptor.getValue();

        when(mMockJobScheduler.getPendingJob(eq(MIN_APPS_INDEXER_JOB_ID))).thenReturn(firstJobInfo);
        IndexerMaintenanceService.scheduleUpdateJob(
                mContextWrapper,
                DEFAULT_USER_HANDLE,
                /* indexerType= */ APPS_INDEXER,
                /* periodic= */ true,
                /* intervalMillis= */ TimeUnit.DAYS.toMillis(7));
        // Mockito.verify() counts the number of occurrences from the beginning of the test.
        // This verify() uses the default count of 1 (equivalent to times(1)) to account for the
        // call to JobScheduler.schedule() above where the first JobInfo is captured.
        verify(mMockJobScheduler).schedule(any(JobInfo.class));
    }

    @Test
    public void testDoUpdateForUser_withInitializedLocalService_isSuccessful() {
        when(mParams.getExtras()).thenReturn(mExtras);
        ExtendedMockito.doReturn(Mockito.mock(AppsIndexerManagerService.LocalService.class))
                .when(
                        () ->
                                LocalManagerRegistry.getManager(
                                        AppsIndexerManagerService.LocalService.class));
        boolean updateSucceeded =
                mAppsIndexerMaintenanceService.doUpdateForUser(
                        mContextWrapper, mParams, DEFAULT_USER_HANDLE, new CancellationSignal());
        assertThat(updateSucceeded).isTrue();
    }

    @Test
    public void testDoUpdateForUser_withUninitializedLocalService_failsGracefully() {
        when(mParams.getExtras()).thenReturn(mExtras);
        ExtendedMockito.doReturn(null)
                .when(
                        () ->
                                LocalManagerRegistry.getManager(
                                        AppsIndexerManagerService.LocalService.class));
        boolean updateSucceeded =
                mAppsIndexerMaintenanceService.doUpdateForUser(
                        mContextWrapper, mParams, DEFAULT_USER_HANDLE, new CancellationSignal());
        assertThat(updateSucceeded).isFalse();
    }

    @Test
    public void testDoUpdateForUser_onEncounteringException_failsGracefully() {
        when(mParams.getExtras()).thenReturn(mExtras);
        AppsIndexerManagerService.LocalService mockService =
                Mockito.mock(AppsIndexerManagerService.LocalService.class);
        doThrow(RuntimeException.class)
                .when(mockService)
                .doUpdateForUser((UserHandle) any(), (CancellationSignal) any());
        ExtendedMockito.doReturn(mockService)
                .when(
                        () ->
                                LocalManagerRegistry.getManager(
                                        AppsIndexerManagerService.LocalService.class));

        boolean updateSucceeded =
                mAppsIndexerMaintenanceService.doUpdateForUser(
                        mContextWrapper, mParams, DEFAULT_USER_HANDLE, new CancellationSignal());

        assertThat(updateSucceeded).isFalse();
    }

    @Test
    public void testDoUpdateForUser_cancelsBackgroundJob_whenIndexerDisabled() {
        when(mParams.getExtras()).thenReturn(mExtras);
        ExtendedMockito.doReturn(null)
                .when(
                        () ->
                                LocalManagerRegistry.getManager(
                                        AppsIndexerManagerService.LocalService.class));

        mAppsIndexerMaintenanceService.doUpdateForUser(
                mContextWrapper, mParams, DEFAULT_USER_HANDLE, new CancellationSignal());

        verify(mMockJobScheduler).cancel(MIN_APPS_INDEXER_JOB_ID);
    }

    @Test
    public void testDoUpdateForUser_doesNotCancelBackgroundJob_whenIndexerEnabled() {
        when(mParams.getExtras()).thenReturn(mExtras);
        ExtendedMockito.doReturn(Mockito.mock(AppsIndexerManagerService.LocalService.class))
                .when(
                        () ->
                                LocalManagerRegistry.getManager(
                                        AppsIndexerManagerService.LocalService.class));

        mAppsIndexerMaintenanceService.doUpdateForUser(
                mContextWrapper, mParams, DEFAULT_USER_HANDLE, new CancellationSignal());

        verifyZeroInteractions(mMockJobScheduler);
    }

    @Test
    public void testCancelPendingUpdateJob_succeeds() throws IOException {
        UserInfo userInfo = new UserInfo(DEFAULT_USER_ID, /* name= */ "default", /* flags= */ 0);
        SystemService.TargetUser user = new SystemService.TargetUser(userInfo);
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            uiAutomation.adoptShellPermissionIdentity(RECEIVE_BOOT_COMPLETED);
            IndexerMaintenanceService.scheduleUpdateJob(
                    mContext,
                    DEFAULT_USER_HANDLE,
                    /* indexerType= */ APPS_INDEXER,
                    /* periodic= */ true,
                    /* intervalMillis= */ TimeUnit.DAYS.toMillis(7));
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
        JobInfo jobInfo = getPendingUpdateJob(DEFAULT_USER_ID);
        assertThat(jobInfo).isNotNull();

        IndexerMaintenanceService.cancelUpdateJobIfScheduled(
                mContext, user.getUserHandle(), APPS_INDEXER);

        jobInfo = getPendingUpdateJob(DEFAULT_USER_ID);
        assertThat(jobInfo).isNull();
    }

    @Test
    public void test_onStartJob_handlesExceptionGracefully() {
        mAppsIndexerMaintenanceService.onStartJob(mParams);
    }

    @Test
    public void test_onStopJob_handlesExceptionGracefully() {
        mAppsIndexerMaintenanceService.onStopJob(mParams);
    }

    @Nullable
    private JobInfo getPendingUpdateJob(@UserIdInt int userId) {
        int jobId = MIN_APPS_INDEXER_JOB_ID + userId;
        return mContext.getSystemService(JobScheduler.class).getPendingJob(jobId);
    }
}
