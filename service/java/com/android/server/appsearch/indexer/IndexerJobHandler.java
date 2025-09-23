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

package com.android.server.appsearch.indexer;

import android.annotation.IntDef;
import android.content.Context;
import android.os.UserHandle;

import org.jspecify.annotations.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An interface that abstracts environment-specific logic and contains information for handling
 * maintenance jobs for the indexers.
 *
 * @hide
 */
public interface IndexerJobHandler {

    int APPS_INDEXER = 0;
    int CONTACTS_INDEXER = 1;
    int APP_OPEN_EVENT_INDEXER = 2;

    /** The type of built-in indexer. */
    @IntDef(
            value = {
                APPS_INDEXER,
                CONTACTS_INDEXER,
                APP_OPEN_EVENT_INDEXER,
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface IndexerType {}

    /** Schedule a maintenance job for a given a user and indexer. */
    void scheduleUpdateJob(
            @NonNull Context context,
            @NonNull UserHandle userHandle,
            @IndexerType int indexerType,
            boolean periodic,
            long intervalMillis);

    /** Cancel the maintenance job for a given user and indexer. */
    void cancelUpdateJobIfScheduled(
            @NonNull Context context, @NonNull UserHandle userHandle, @IndexerType int indexerType);

    /** Check if a update job is scheduled for the given user. */
    boolean isUpdateJobScheduled(
            @NonNull Context context, @NonNull UserHandle userHandle, @IndexerType int indexerType);

    /** Check if an update job is scheduled for the given user with the expected parameters. */
    boolean isUpdateJobScheduledWithExpectedParams(
            @NonNull Context context,
            @NonNull UserHandle userHandle,
            @IndexerType int indexerType,
            long intervalMillis);
}
