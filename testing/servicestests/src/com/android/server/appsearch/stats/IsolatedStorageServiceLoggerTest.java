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

import static org.junit.Assert.assertTrue;

import com.android.server.appsearch.isolated_storage_service.ServiceConfig;

import org.junit.Test;

public class IsolatedStorageServiceLoggerTest {

    @Test
    @SuppressWarnings("GuardedBy")
    public void testShouldLogForTypeLocked_true() throws Exception {
        ServiceConfig config = new ServiceConfig();
        config.pCachedSamplingInterval = 1;
        IsolateStorageServiceLogger logger = new IsolateStorageServiceLogger(config);
        assertTrue(logger.shouldLogForTypeLocked(CALLBACK_TYPE_START));
        assertTrue(logger.shouldLogForTypeLocked(CALLBACK_TYPE_READY));
        assertTrue(logger.shouldLogForTypeLocked(CALLBACK_TYPE_FINISH));
        assertTrue(logger.shouldLogForTypeLocked(CALLBACK_TYPE_ERROR));
        assertTrue(logger.shouldLogForTypeLocked(CALLBACK_TYPE_STOP));
        assertThat(logger.mSkippedSampleCountLocked).hasSize(6); // 5 types + unknown.
    }
}
