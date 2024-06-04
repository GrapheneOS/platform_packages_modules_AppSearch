/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.appsearch;

import static android.Manifest.permission.RECEIVE_BOOT_COMPLETED;

import static com.android.server.appsearch.AppSearchMaintenanceService.MIN_APPSEARCH_MAINTENANCE_JOB_ID;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.annotation.Nullable;
import android.app.UiAutomation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.CancellationSignal;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.LocalManagerRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

public class AppSearchMaintenanceServiceTest {
    private static final int DEFAULT_USER_ID = 0;

    private Context mContext = ApplicationProvider.getApplicationContext();
    private Context mContextWrapper;
    private AppSearchMaintenanceService mAppSearchMaintenanceService;
    private MockitoSession session;
    @Mock
    private JobScheduler mockJobScheduler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
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
        mAppSearchMaintenanceService = spy(new AppSearchMaintenanceService());
        doNothing().when(mAppSearchMaintenanceService).jobFinished(any(), anyBoolean());
        session = ExtendedMockito.mockitoSession().
                mockStatic(LocalManagerRegistry.class).
                startMocking();
    }

    @After
    public void tearDown() {
        session.finishMocking();
    }

    @Test
    public void testScheduleFullPersistJob() {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        long intervalMillis = 37 * 60 * 1000; // 37 Min
        try {
            uiAutomation.adoptShellPermissionIdentity(RECEIVE_BOOT_COMPLETED);
            AppSearchMaintenanceService.scheduleFullyPersistJob(mContext, /*userId=*/123,
                    intervalMillis);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }

        int jobId = MIN_APPSEARCH_MAINTENANCE_JOB_ID + 123;
        JobInfo jobInfo = mContext.getSystemService(JobScheduler.class).getPendingJob(jobId);
        assertThat(jobInfo).isNotNull();
        assertThat(jobInfo.isRequireBatteryNotLow()).isTrue();
        assertThat(jobInfo.isRequireDeviceIdle()).isTrue();
        assertThat(jobInfo.isPersisted()).isTrue();
        assertThat(jobInfo.isPeriodic()).isTrue();
        assertThat(jobInfo.getIntervalMillis()).isEqualTo(intervalMillis);

        int userId = jobInfo.getExtras().getInt("user_id", /*defaultValue=*/ -1);
        assertThat(userId).isEqualTo(123);
    }

    @Test
    public void testDoFullPersistForUser_withInitializedLocalService_isSuccessful() {
        ExtendedMockito.doReturn(Mockito.mock(AppSearchManagerService.LocalService.class))
                .when(() -> LocalManagerRegistry.getManager(
                        AppSearchManagerService.LocalService.class));
        assertThat(mAppSearchMaintenanceService
                .doFullyPersistJobForUser(mContextWrapper, null, 0, new CancellationSignal()))
                .isTrue();
    }

    @Test
    public void testDoFullPersistForUser_withUninitializedLocalService_failsGracefully() {
        ExtendedMockito.doReturn(null)
                .when(() -> LocalManagerRegistry.getManager(
                        AppSearchManagerService.LocalService.class));
        assertThat(mAppSearchMaintenanceService
                .doFullyPersistJobForUser(mContextWrapper, null, 0, new CancellationSignal()))
                .isFalse();
    }

    @Test
    public void testDoFullPersistForUser_onEncounteringException_failsGracefully()
            throws Exception {
        AppSearchManagerService.LocalService mockService = Mockito.mock(
                AppSearchManagerService.LocalService.class);
        doThrow(RuntimeException.class).when(mockService).doFullyPersistForUser(anyInt());
        ExtendedMockito.doReturn(mockService)
                .when(() -> LocalManagerRegistry.getManager(
                        AppSearchManagerService.LocalService.class));

        assertThat(mAppSearchMaintenanceService
                .doFullyPersistJobForUser(mContextWrapper, null, 0, new CancellationSignal()))
                .isFalse();
    }

    @Test
    public void testDoFullPersistForUser_checkPendingJobIfNotInitialized() {
        ExtendedMockito.doReturn(null)
                .when(() -> LocalManagerRegistry.getManager(
                        AppSearchManagerService.LocalService.class));

        mAppSearchMaintenanceService.doFullyPersistJobForUser(
                mContextWrapper, /*params=*/null, /*userId=*/123, new CancellationSignal());

        // The server is not initialized, we should check and cancel any pending job. There will
      // be a
        // getPendingJob call to the job scheduler only. Since we haven't schedule any job.
        verify(mockJobScheduler).getPendingJob(MIN_APPSEARCH_MAINTENANCE_JOB_ID + 123);
    }

    @Test
    public void testCancelPendingFullPersistJob_succeeds() {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            uiAutomation.adoptShellPermissionIdentity(RECEIVE_BOOT_COMPLETED);
            AppSearchMaintenanceService.scheduleFullyPersistJob(mContext, DEFAULT_USER_ID,
                    /*intervalMillis=*/456L);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
        int jobId = MIN_APPSEARCH_MAINTENANCE_JOB_ID + DEFAULT_USER_ID;
        JobInfo jobInfo = mContext.getSystemService(JobScheduler.class).getPendingJob(jobId);
        assertThat(jobInfo).isNotNull();

        AppSearchMaintenanceService.cancelFullyPersistJobIfScheduled(mContext, DEFAULT_USER_ID);

        jobInfo = mContext.getSystemService(JobScheduler.class).getPendingJob(jobId);
        assertThat(jobInfo).isNull();
    }

    @Test
    public void test_onStartJob_handlesExceptionGracefully() {
        mAppSearchMaintenanceService.onStartJob(null);
    }

    @Test
    public void test_onStopJob_handlesExceptionGracefully() {
        mAppSearchMaintenanceService.onStopJob(null);
    }
}
