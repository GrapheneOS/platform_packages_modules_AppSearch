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
// @exportToGMSCore:skipFile()
package com.android.server.appsearch.indexer;

import android.annotation.NonNull;
import android.content.Context;
import android.os.UserHandle;

/**
 * The framework-specific implementation of {@link IndexerJobHandler} for handling indexer
 * maintenance jobs.
 *
 * @hide
 */
public class FrameworkIndexerJobHandler implements IndexerJobHandler {

    @Override
    public void scheduleUpdateJob(
            @NonNull Context context,
            @NonNull UserHandle userHandle,
            @IndexerType int indexerType,
            boolean periodic,
            long intervalMillis) {
        FrameworkIndexerMaintenanceService.scheduleUpdateJob(
                context, userHandle, indexerType, periodic, intervalMillis);
    }

    @Override
    public void cancelUpdateJobIfScheduled(
            @NonNull Context context,
            @NonNull UserHandle userHandle,
            @IndexerType int indexerType) {
        FrameworkIndexerMaintenanceService.cancelUpdateJobIfScheduled(
                context, userHandle, indexerType);
    }

    @Override
    public boolean isUpdateJobScheduled(
            @NonNull Context context,
            @NonNull UserHandle userHandle,
            @IndexerType int indexerType) {
        return FrameworkIndexerMaintenanceService.isUpdateJobScheduled(
                context, userHandle, indexerType);
    }

    @Override
    public boolean isUpdateJobScheduledWithExpectedParams(
            @NonNull Context context,
            @NonNull UserHandle userHandle,
            @IndexerType int indexerType,
            long intervalMillis) {
        return FrameworkIndexerMaintenanceService.isUpdateJobScheduledWithExpectedParams(
                context, userHandle, indexerType, intervalMillis);
    }
}
