/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.appsearch;

import static com.google.common.truth.Truth.assertThat;

import android.app.appsearch.testutil.AppSearchTestUtils;
import android.app.appsearch.testutil.FakeAppSearchConfig;
import android.platform.test.annotations.RequiresFlagsEnabled;

import com.android.appsearch.flags.Flags;
import com.android.server.appsearch.icing.proto.GetOptimizeInfoResultProto;
import com.android.server.appsearch.icing.proto.StatusProto;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import java.util.concurrent.TimeUnit;

// NOTE: The tests in this class are based on the underlying assumption that
// time_optimize_threshold > min_time_optimize_threshold. This ensures that setting
// timeSinceLastOptimize to time_optimize_threshold - 1 does not make it lesser than
// min_time_optimize_threshold (otherwise shouldOptimize() would return false for test cases that
// check byteThreshold and docCountThreshold).
public class ServiceOptimizeStrategyTest {
    ServiceAppSearchConfig mAppSearchConfig = new FakeAppSearchConfig();
    ServiceOptimizeStrategy mServiceOptimizeStrategy =
            new ServiceOptimizeStrategy(mAppSearchConfig);
    @Rule public final RuleChain mRuleChain = AppSearchTestUtils.createCommonTestRules();

    @Test
    public void testTimeOptimizeThreshold_isGreaterThan_minTimeOptimizeThreshold() {
        assertThat(mAppSearchConfig.getCachedTimeOptimizeThresholdMs())
                .isGreaterThan(mAppSearchConfig.getCachedMinTimeOptimizeThresholdMs());
    }

    @Test
    public void testShouldNotOptimize_underAllThresholds() {
        GetOptimizeInfoResultProto optimizeInfo =
                GetOptimizeInfoResultProto.newBuilder()
                        .setTimeSinceLastOptimizeMs(
                                mAppSearchConfig.getCachedTimeOptimizeThresholdMs() - 1)
                        .setEstimatedOptimizableBytes(
                                mAppSearchConfig.getCachedBytesOptimizeThreshold() - 1)
                        .setOptimizableDocs(
                                mAppSearchConfig.getCachedDocCountOptimizeThreshold() - 1)
                        .setStatus(StatusProto.newBuilder().setCode(StatusProto.Code.OK).build())
                        .build();
        assertThat(mServiceOptimizeStrategy.shouldOptimize(optimizeInfo)).isFalse();
    }

    @Test
    public void testShouldOptimize_byteThreshold() {
        GetOptimizeInfoResultProto optimizeInfo =
                GetOptimizeInfoResultProto.newBuilder()
                        .setTimeSinceLastOptimizeMs(
                                mAppSearchConfig.getCachedTimeOptimizeThresholdMs() - 1)
                        .setEstimatedOptimizableBytes(
                                mAppSearchConfig.getCachedBytesOptimizeThreshold())
                        .setOptimizableDocs(
                                mAppSearchConfig.getCachedDocCountOptimizeThreshold() - 1)
                        .setStatus(StatusProto.newBuilder().setCode(StatusProto.Code.OK).build())
                        .build();
        assertThat(mServiceOptimizeStrategy.shouldOptimize(optimizeInfo)).isTrue();
    }

    @Test
    public void testShouldOptimize_timeThreshold() {
        GetOptimizeInfoResultProto optimizeInfo =
                GetOptimizeInfoResultProto.newBuilder()
                        .setTimeSinceLastOptimizeMs(
                                mAppSearchConfig.getCachedTimeOptimizeThresholdMs())
                        .setEstimatedOptimizableBytes(
                                mAppSearchConfig.getCachedBytesOptimizeThreshold() - 1)
                        .setOptimizableDocs(
                                mAppSearchConfig.getCachedDocCountOptimizeThreshold() - 1)
                        .setStatus(StatusProto.newBuilder().setCode(StatusProto.Code.OK).build())
                        .build();
        assertThat(mServiceOptimizeStrategy.shouldOptimize(optimizeInfo)).isTrue();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_NEW_OPTIMIZE_STRATEGY_FOR_ACTIVE_RESULT_STATES)
    public void testShouldOptimize_timeThresholdWithActiveResultStates() {
        GetOptimizeInfoResultProto optimizeInfo =
                GetOptimizeInfoResultProto.newBuilder()
                        .setTimeSinceLastOptimizeMs(
                                mAppSearchConfig.getCachedTimeOptimizeThresholdMs())
                        .setEstimatedOptimizableBytes(
                                mAppSearchConfig.getCachedBytesOptimizeThreshold() - 1)
                        .setOptimizableDocs(
                                mAppSearchConfig.getCachedDocCountOptimizeThreshold() - 1)
                        .setNumActiveResultStates(1)
                        .setStatus(StatusProto.newBuilder().setCode(StatusProto.Code.OK).build())
                        .build();
        assertThat(mServiceOptimizeStrategy.shouldOptimize(optimizeInfo)).isTrue();
    }

    @Test
    public void testShouldOptimize_docCountThreshold() {
        GetOptimizeInfoResultProto optimizeInfo =
                GetOptimizeInfoResultProto.newBuilder()
                        .setTimeSinceLastOptimizeMs(
                                mAppSearchConfig.getCachedTimeOptimizeThresholdMs() - 1)
                        .setEstimatedOptimizableBytes(
                                mAppSearchConfig.getCachedBytesOptimizeThreshold() - 1)
                        .setOptimizableDocs(mAppSearchConfig.getCachedDocCountOptimizeThreshold())
                        .setStatus(StatusProto.newBuilder().setCode(StatusProto.Code.OK).build())
                        .build();
        assertThat(mServiceOptimizeStrategy.shouldOptimize(optimizeInfo)).isTrue();
    }

    @Test
    public void testShouldNotOptimize_underMinTimeThreshold() {
        GetOptimizeInfoResultProto optimizeInfo =
                GetOptimizeInfoResultProto.newBuilder()
                        .setTimeSinceLastOptimizeMs(
                                mAppSearchConfig.getCachedMinTimeOptimizeThresholdMs() - 1)
                        .setEstimatedOptimizableBytes(
                                mAppSearchConfig.getCachedBytesOptimizeThreshold())
                        .setOptimizableDocs(mAppSearchConfig.getCachedDocCountOptimizeThreshold())
                        .setStatus(StatusProto.newBuilder().setCode(StatusProto.Code.OK).build())
                        .build();
        assertThat(mServiceOptimizeStrategy.shouldOptimize(optimizeInfo)).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FOUR_HOUR_MIN_TIME_OPTIMIZE_THRESHOLD)
    public void testShouldNotOptimize_underFourHourMinTimeThreshold() {
        GetOptimizeInfoResultProto optimizeInfo =
                GetOptimizeInfoResultProto.newBuilder()
                        .setTimeSinceLastOptimizeMs(TimeUnit.HOURS.toMillis(4) - 1)
                        .setEstimatedOptimizableBytes(
                                mAppSearchConfig.getCachedBytesOptimizeThreshold())
                        .setOptimizableDocs(mAppSearchConfig.getCachedDocCountOptimizeThreshold())
                        .setStatus(StatusProto.newBuilder().setCode(StatusProto.Code.OK).build())
                        .setNoPreviousOptimizeInfo(false)
                        .build();
        assertThat(mServiceOptimizeStrategy.shouldOptimize(optimizeInfo)).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FOUR_HOUR_MIN_TIME_OPTIMIZE_THRESHOLD)
    public void testShouldOptimize_firstOptimize() {
        GetOptimizeInfoResultProto optimizeInfo =
                GetOptimizeInfoResultProto.newBuilder()
                        .setTimeSinceLastOptimizeMs(0)
                        .setEstimatedOptimizableBytes(
                                mAppSearchConfig.getCachedBytesOptimizeThreshold())
                        .setOptimizableDocs(mAppSearchConfig.getCachedDocCountOptimizeThreshold())
                        .setStatus(StatusProto.newBuilder().setCode(StatusProto.Code.OK).build())
                        .setNoPreviousOptimizeInfo(true)
                        .build();
        assertThat(mServiceOptimizeStrategy.shouldOptimize(optimizeInfo)).isTrue();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_NEW_OPTIMIZE_STRATEGY_FOR_ACTIVE_RESULT_STATES)
    public void testShouldOptimize_noActiveResultStates() {
        GetOptimizeInfoResultProto optimizeInfo =
                GetOptimizeInfoResultProto.newBuilder()
                        .setTimeSinceLastOptimizeMs(
                                mAppSearchConfig.getCachedMinTimeOptimizeThresholdMs())
                        .setEstimatedOptimizableBytes(
                                mAppSearchConfig.getCachedBytesOptimizeThreshold())
                        .setOptimizableDocs(mAppSearchConfig.getCachedDocCountOptimizeThreshold())
                        .setNumActiveResultStates(0)
                        .setStatus(StatusProto.newBuilder().setCode(StatusProto.Code.OK).build())
                        .build();
        assertThat(mServiceOptimizeStrategy.shouldOptimize(optimizeInfo)).isTrue();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_NEW_OPTIMIZE_STRATEGY_FOR_ACTIVE_RESULT_STATES)
    public void testShouldOptimize_noActiveResultStates_firstTimeOptimize() {
        GetOptimizeInfoResultProto optimizeInfo =
                GetOptimizeInfoResultProto.newBuilder()
                        .setTimeSinceLastOptimizeMs(
                                mAppSearchConfig.getCachedMinTimeOptimizeThresholdMs() - 1)
                        .setEstimatedOptimizableBytes(
                                mAppSearchConfig.getCachedBytesOptimizeThreshold())
                        .setOptimizableDocs(mAppSearchConfig.getCachedDocCountOptimizeThreshold())
                        .setNumActiveResultStates(0)
                        .setStatus(StatusProto.newBuilder().setCode(StatusProto.Code.OK).build())
                        .setNoPreviousOptimizeInfo(true)
                        .build();
        assertThat(mServiceOptimizeStrategy.shouldOptimize(optimizeInfo)).isTrue();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_NEW_OPTIMIZE_STRATEGY_FOR_ACTIVE_RESULT_STATES)
    public void testShouldNotOptimize_hasActiveResultStates() {
        GetOptimizeInfoResultProto optimizeInfo =
                GetOptimizeInfoResultProto.newBuilder()
                        .setTimeSinceLastOptimizeMs(
                                mAppSearchConfig.getCachedMinTimeOptimizeThresholdMs())
                        .setEstimatedOptimizableBytes(
                                mAppSearchConfig.getCachedBytesOptimizeThreshold())
                        .setOptimizableDocs(mAppSearchConfig.getCachedDocCountOptimizeThreshold())
                        .setNumActiveResultStates(1)
                        .setStatus(StatusProto.newBuilder().setCode(StatusProto.Code.OK).build())
                        .build();
        assertThat(mServiceOptimizeStrategy.shouldOptimize(optimizeInfo)).isFalse();
    }
}
