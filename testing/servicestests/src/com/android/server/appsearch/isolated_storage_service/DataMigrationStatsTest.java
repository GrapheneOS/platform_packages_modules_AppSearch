/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.server.appsearch.isolated_storage_service;

import static com.google.common.truth.Truth.assertThat;

import android.os.PersistableBundle;

import org.junit.Test;

public class DataMigrationStatsTest {
    @Test
    public void TestGetterAndSetterForNonBundleProperties() {
        DataMigrationStats stats = new DataMigrationStats();
        int[] putStatus = {1, 2, 3};

        stats.setDataMigrationStatus(0);
        stats.setVMInitStatus(1);
        stats.setResetStatus(2);
        stats.setSetSchemaStatus(3);
        stats.setFlushStatus(4);
        stats.setQueryStatus(5);
        stats.setPutStatus(putStatus);
        stats.setNumberOfDocsSucceeded(6);
        stats.setNumberOfDocsFailed(7);

        assertThat(stats.getDataMigrationStatus()).isEqualTo(0);
        assertThat(stats.getVMInitStatus()).isEqualTo(1);
        assertThat(stats.getResetStatus()).isEqualTo(2);
        assertThat(stats.getSetSchemaStatus()).isEqualTo(3);
        assertThat(stats.getFlushStatus()).isEqualTo(4);
        assertThat(stats.getQueryStatus()).isEqualTo(5);
        assertThat(stats.getPutStatus()).asList().containsExactly(1, 2, 3);
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(6);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(7);
    }

    @Test
    public void TestGetterAndSetterForBundle() {
        DataMigrationStats stats = new DataMigrationStats();
        int[] putStatus = {1, 2, 3};
        stats.setDataMigrationStatus(0);
        stats.setVMInitStatus(1);
        stats.setResetStatus(2);
        stats.setSetSchemaStatus(3);
        stats.setFlushStatus(4);
        stats.setQueryStatus(5);
        stats.setPutStatus(putStatus);
        stats.setNumberOfDocsSucceeded(6);
        stats.setNumberOfDocsFailed(7);

        DataMigrationStats statsCopy = new DataMigrationStats();
        statsCopy.setBundle(stats.getBundle());

        assertThat(statsCopy.getDataMigrationStatus()).isEqualTo(0);
        assertThat(statsCopy.getVMInitStatus()).isEqualTo(1);
        assertThat(statsCopy.getResetStatus()).isEqualTo(2);
        assertThat(statsCopy.getSetSchemaStatus()).isEqualTo(3);
        assertThat(statsCopy.getFlushStatus()).isEqualTo(4);
        assertThat(statsCopy.getQueryStatus()).isEqualTo(5);
        assertThat(statsCopy.getPutStatus()).asList().containsExactly(1, 2, 3);
        assertThat(statsCopy.getNumberOfDocsSucceeded()).isEqualTo(6);
        assertThat(statsCopy.getNumberOfDocsFailed()).isEqualTo(7);
    }

    @Test
    public void TestDefaultValues() {
        DataMigrationStats stats = new DataMigrationStats();

        assertThat(stats.getDataMigrationStatus()).isEqualTo(DataMigrationStats.STATUS_NOT_SET);
        assertThat(stats.getVMInitStatus()).isEqualTo(DataMigrationStats.STATUS_NOT_SET);
        assertThat(stats.getResetStatus()).isEqualTo(DataMigrationStats.STATUS_NOT_SET);
        assertThat(stats.getSetSchemaStatus()).isEqualTo(DataMigrationStats.STATUS_NOT_SET);
        assertThat(stats.getFlushStatus()).isEqualTo(DataMigrationStats.STATUS_NOT_SET);
        assertThat(stats.getQueryStatus()).isEqualTo(DataMigrationStats.STATUS_NOT_SET);
        assertThat(stats.getPutStatus()).isEqualTo(null);
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(0);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(0);
    }
}
