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

package com.android.server.appsearch.appsindexer;

import static com.android.server.appsearch.appsindexer.FrameworkAppsIndexerForceUpdateConfig.DEFAULT_APPS_INDEXER_FORCE_UPDATE_EMERGENCY_COUNTER;
import static com.android.server.appsearch.appsindexer.FrameworkAppsIndexerForceUpdateConfig.DEFAULT_APPS_INDEXER_FORCE_UPDATE_ENABLED;
import static com.android.server.appsearch.appsindexer.FrameworkAppsIndexerForceUpdateConfig.KEY_APPS_INDEXER_FORCE_UPDATE_EMERGENCY_COUNTER;
import static com.android.server.appsearch.appsindexer.FrameworkAppsIndexerForceUpdateConfig.KEY_APPS_INDEXER_FORCE_UPDATE_ENABLED;

import static com.google.common.truth.Truth.assertThat;

import android.app.appsearch.testutil.AppSearchTestUtils;
import android.provider.DeviceConfig;

import com.android.modules.utils.testing.TestableDeviceConfig;
import com.android.server.appsearch.indexer.IndexerForceUpdateConfig;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class FrameworkAppsIndexerForceUpdateConfigTest {
    @Rule
    public final RuleChain mRuleChain =
            AppSearchTestUtils.createCommonTestRules()
                    .around(new TestableDeviceConfig.TestableDeviceConfigRule());

    @Test
    public void testDefaultValues() {
        IndexerForceUpdateConfig config = new FrameworkAppsIndexerForceUpdateConfig();

        assertThat(config.getIndexerForceUpdateEmergencyCounter())
                .isEqualTo(DEFAULT_APPS_INDEXER_FORCE_UPDATE_EMERGENCY_COUNTER);
        assertThat(config.isIndexerForceUpdateEnabled())
                .isEqualTo(DEFAULT_APPS_INDEXER_FORCE_UPDATE_ENABLED);
    }

    @Test
    public void testCustomizedValues() {
        IndexerForceUpdateConfig config = new FrameworkAppsIndexerForceUpdateConfig();
        final boolean enabled = true;
        final int emergencyCounter = 1;

        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_APPS_INDEXER_FORCE_UPDATE_ENABLED,
                Boolean.toString(enabled),
                false);
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_APPS_INDEXER_FORCE_UPDATE_EMERGENCY_COUNTER,
                Integer.toString(emergencyCounter),
                false);

        assertThat(config.isIndexerForceUpdateEnabled()).isTrue();
        assertThat(config.getIndexerForceUpdateEmergencyCounter()).isEqualTo(emergencyCounter);
    }

    @Test
    public void testDeviceConfigListener_OnPropertyChange() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        IndexerForceUpdateConfig config = new FrameworkAppsIndexerForceUpdateConfig();

        // Use directExecutor() for testing since listener is registered asynchronously
        IndexerForceUpdateConfig.addListener(MoreExecutors.directExecutor(), latch::countDown);

        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_APPS_INDEXER_FORCE_UPDATE_EMERGENCY_COUNTER,
                Integer.toString(2),
                false);

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
