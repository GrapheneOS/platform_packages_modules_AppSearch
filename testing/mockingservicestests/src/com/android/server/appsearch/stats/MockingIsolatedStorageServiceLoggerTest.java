/*
 * Copyright 2025 The Android Open Source Project
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
package com.android.server.appsearch.stats;

import static com.android.server.appsearch.stats.VMPayloadStats.CALLBACK_TYPE_ERROR;
import static com.android.server.appsearch.stats.VMPayloadStats.CALLBACK_TYPE_FINISH;
import static com.android.server.appsearch.stats.VMPayloadStats.CALLBACK_TYPE_READY;
import static com.android.server.appsearch.stats.VMPayloadStats.CALLBACK_TYPE_START;
import static com.android.server.appsearch.stats.VMPayloadStats.CALLBACK_TYPE_STOP;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.NonNull;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.modules.utils.testing.StaticMockFixture;
import com.android.server.appsearch.isolated_storage_service.ServiceConfig;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class MockingIsolatedStorageServiceLoggerTest {

    @Rule
    public ExtendedMockitoRule mExtendedMockitoRule =
            new ExtendedMockitoRule.Builder().addStaticMockFixtures(TestMockFixture::new).build();

    @Test
    public void testLogStats_start() throws Exception {
        ServiceConfig config = new ServiceConfig();
        config.pCachedSamplingInterval = 1;
        IsolateStorageServiceLogger logger = new IsolateStorageServiceLogger(config);
        VMPayloadStats stats = new VMPayloadStats.Builder(CALLBACK_TYPE_START).build();
        logger.logStats(stats);

        ArgumentCaptor<Integer> callType = ArgumentCaptor.forClass(int.class);
        ExtendedMockito.verify(
                () ->
                        AppSearchStatsLog.write(
                                Mockito.eq(AppSearchStatsLog.APP_SEARCH_VM_PAYLOAD_STATS_REPORTED),
                                Mockito.anyInt(),
                                Mockito.anyInt(),
                                callType.capture(),
                                Mockito.anyInt(),
                                Mockito.anyInt(),
                                Mockito.anyInt()));

        assertThat(callType.getValue()).isEqualTo(CALLBACK_TYPE_START);
    }

    @Test
    public void testLogStats_ready() throws Exception {
        ServiceConfig config = new ServiceConfig();
        config.pCachedSamplingInterval = 1;
        IsolateStorageServiceLogger logger = new IsolateStorageServiceLogger(config);
        VMPayloadStats stats = new VMPayloadStats.Builder(CALLBACK_TYPE_READY).build();
        logger.logStats(stats);

        ArgumentCaptor<Integer> callType = ArgumentCaptor.forClass(int.class);
        ExtendedMockito.verify(
                () ->
                        AppSearchStatsLog.write(
                                Mockito.eq(AppSearchStatsLog.APP_SEARCH_VM_PAYLOAD_STATS_REPORTED),
                                Mockito.anyInt(),
                                Mockito.anyInt(),
                                callType.capture(),
                                Mockito.anyInt(),
                                Mockito.anyInt(),
                                Mockito.anyInt()));

        assertThat(callType.getValue()).isEqualTo(CALLBACK_TYPE_READY);
    }

    @Test
    public void testLogStats_finish() throws Exception {
        ServiceConfig config = new ServiceConfig();
        config.pCachedSamplingInterval = 1;
        IsolateStorageServiceLogger logger = new IsolateStorageServiceLogger(config);
        VMPayloadStats stats =
                new VMPayloadStats.Builder(CALLBACK_TYPE_FINISH).setExitCode(2).build();
        logger.logStats(stats);

        ArgumentCaptor<Integer> callType = ArgumentCaptor.forClass(int.class);
        ArgumentCaptor<Integer> exitCode = ArgumentCaptor.forClass(int.class);
        ExtendedMockito.verify(
                () ->
                        AppSearchStatsLog.write(
                                Mockito.eq(AppSearchStatsLog.APP_SEARCH_VM_PAYLOAD_STATS_REPORTED),
                                Mockito.anyInt(),
                                Mockito.anyInt(),
                                callType.capture(),
                                Mockito.anyInt(),
                                exitCode.capture(),
                                Mockito.anyInt()));

        assertThat(callType.getValue()).isEqualTo(CALLBACK_TYPE_FINISH);
        assertThat(exitCode.getValue()).isEqualTo(2);
    }

    @Test
    public void testLogStats_error() throws Exception {
        ServiceConfig config = new ServiceConfig();
        config.pCachedSamplingInterval = 1;
        IsolateStorageServiceLogger logger = new IsolateStorageServiceLogger(config);
        VMPayloadStats stats =
                new VMPayloadStats.Builder(CALLBACK_TYPE_ERROR).setErrorCode(3).build();
        logger.logStats(stats);

        ArgumentCaptor<Integer> callType = ArgumentCaptor.forClass(int.class);
        ArgumentCaptor<Integer> errorCode = ArgumentCaptor.forClass(int.class);
        ExtendedMockito.verify(
                () ->
                        AppSearchStatsLog.write(
                                Mockito.eq(AppSearchStatsLog.APP_SEARCH_VM_PAYLOAD_STATS_REPORTED),
                                Mockito.anyInt(),
                                Mockito.anyInt(),
                                callType.capture(),
                                errorCode.capture(),
                                Mockito.anyInt(),
                                Mockito.anyInt()));

        assertThat(callType.getValue()).isEqualTo(CALLBACK_TYPE_ERROR);
        assertThat(errorCode.getValue()).isEqualTo(3);
    }

    @Test
    public void testLogStats_stop() throws Exception {
        ServiceConfig config = new ServiceConfig();
        config.pCachedSamplingInterval = 1;
        IsolateStorageServiceLogger logger = new IsolateStorageServiceLogger(config);
        VMPayloadStats stats =
                new VMPayloadStats.Builder(CALLBACK_TYPE_STOP).setStopReason(4).build();
        logger.logStats(stats);

        ArgumentCaptor<Integer> callType = ArgumentCaptor.forClass(int.class);
        ArgumentCaptor<Integer> stopReason = ArgumentCaptor.forClass(int.class);
        ExtendedMockito.verify(
                () ->
                        AppSearchStatsLog.write(
                                Mockito.eq(AppSearchStatsLog.APP_SEARCH_VM_PAYLOAD_STATS_REPORTED),
                                Mockito.anyInt(),
                                Mockito.anyInt(),
                                callType.capture(),
                                Mockito.anyInt(),
                                Mockito.anyInt(),
                                stopReason.capture()));

        assertThat(callType.getValue()).isEqualTo(CALLBACK_TYPE_STOP);
        assertThat(stopReason.getValue()).isEqualTo(4);
    }

    private static class TestMockFixture implements StaticMockFixture {
        @Override
        public StaticMockitoSessionBuilder setUpMockedClasses(
                @NonNull StaticMockitoSessionBuilder sessionBuilder) {
            sessionBuilder.spyStatic(AppSearchStatsLog.class);
            return sessionBuilder;
        }

        @Override
        public void setUpMockBehaviors() {}

        @Override
        public void tearDown() {}
    }
}
