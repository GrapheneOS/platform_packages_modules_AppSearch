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

import android.annotation.NonNull;
import android.app.appsearch.util.LogUtil;
import android.util.Log;

import com.android.appsearch.flags.Flags;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.OptimizeStrategy;

import com.google.android.icing.proto.GetOptimizeInfoResultProto;

import java.util.Objects;

/**
 * An implementation of {@link OptimizeStrategy} will determine when to trigger {@link
 * AppSearchImpl#optimize()} based on last time optimize ran and number of bytes to optimize. This
 * implementation is used by environments with AppSearch service running like Framework and GMSCore.
 *
 * @hide
 */
public class ServiceOptimizeStrategy implements OptimizeStrategy {
    private static final String TAG = "AppSearchOptimize";
    private final ServiceAppSearchConfig mAppSearchConfig;

    // vm is not guaranteed to be running for the user until data migration is done. But it is ok to
    // still set the value at beginning to enable the better optimization logic on devices with VM
    // capability enabled.
    private final boolean mIsVmEnabledForUser;

    ServiceOptimizeStrategy(@NonNull ServiceAppSearchConfig config, boolean isVmEnabledForUser) {
        mAppSearchConfig = Objects.requireNonNull(config);
        mIsVmEnabledForUser = isVmEnabledForUser;
    }

    @Override
    public boolean shouldOptimize(@NonNull GetOptimizeInfoResultProto optimizeInfo) {
        boolean hasWaitedForMinimumInterval =
                optimizeInfo.getNoPreviousOptimizeInfo()
                        || optimizeInfo.getTimeSinceLastOptimizeMs()
                                >= mAppSearchConfig.getCachedMinTimeOptimizeThresholdMs();

        // TODO(b/435251329) this flag can be cleaned up.
        if (Flags.enableNewOptimizeStrategyForActiveResultStates()) {
            boolean forceOptimize =
                    optimizeInfo.getTimeSinceLastOptimizeMs()
                            >= Math.max(
                                    mAppSearchConfig.getCachedTimeOptimizeThresholdMs(),
                                    mAppSearchConfig.getCachedMinTimeOptimizeThresholdMs());
            boolean optionalOptimize =
                    optimizeInfo.getNumActiveResultStates() == 0
                            && (optimizeInfo.getOptimizableDocs()
                                            >= mAppSearchConfig.getCachedDocCountOptimizeThreshold()
                                    || optimizeInfo.getEstimatedOptimizableBytes()
                                            >= mAppSearchConfig.getCachedBytesOptimizeThreshold());
            if (Flags.enableThrottlingCheckOptimizeInfo() || mIsVmEnabledForUser) {
                // If there are too many docs or bytes to optimize, we should force optimize.
                forceOptimize |=
                        optimizeInfo.getOptimizableDocs()
                                        >= mAppSearchConfig.getCachedMaxDocCountOptimizeThreshold()
                                || optimizeInfo.getEstimatedOptimizableBytes()
                                        >= mAppSearchConfig.getCachedMaxBytesOptimizeThreshold();
            }

            if (forceOptimize) {
                Log.i(
                        TAG,
                        String.format(
                                "Forcing optimize:\n"
                                    + "  Time since last optimize: %d ms (threshold: %d ms)\n"
                                    + "  Optimizable docs: %d (threshold: %d)\n"
                                    + "  Estimated optimizable bytes: %d (threshold: %d)",
                                optimizeInfo.getTimeSinceLastOptimizeMs(),
                                Math.max(
                                    mAppSearchConfig.getCachedTimeOptimizeThresholdMs(),
                                    mAppSearchConfig.getCachedMinTimeOptimizeThresholdMs()),
                                optimizeInfo.getOptimizableDocs(),
                                mAppSearchConfig.getCachedMaxDocCountOptimizeThreshold(),
                                optimizeInfo.getEstimatedOptimizableBytes(),
                                mAppSearchConfig.getCachedMaxBytesOptimizeThreshold()));
                return true;
            }

            if (optionalOptimize && !hasWaitedForMinimumInterval) {
                // TODO(b/271890504): Produce a log message for statsd when we skip a potential
                //  compaction because the time since the last compaction has not reached
                //  the minimum threshold.
                if (LogUtil.INFO) {
                    Log.i(
                            TAG,
                            "Skipping optimization because time since last optimize ["
                                    + optimizeInfo.getTimeSinceLastOptimizeMs()
                                    + " ms] is lesser than the threshold for minimum time between"
                                    + " optimizations ["
                                    + mAppSearchConfig.getCachedMinTimeOptimizeThresholdMs()
                                    + " ms]");
                }
                return false;
            }
            return optionalOptimize;
        }

        boolean wantsOptimize =
                optimizeInfo.getOptimizableDocs()
                                >= mAppSearchConfig.getCachedDocCountOptimizeThreshold()
                        || optimizeInfo.getEstimatedOptimizableBytes()
                                >= mAppSearchConfig.getCachedBytesOptimizeThreshold()
                        || optimizeInfo.getTimeSinceLastOptimizeMs()
                                >= mAppSearchConfig.getCachedTimeOptimizeThresholdMs();
        if (wantsOptimize && !hasWaitedForMinimumInterval) {
            // TODO(b/271890504): Produce a log message for statsd when we skip a potential
            //  compaction because the time since the last compaction has not reached
            //  the minimum threshold.
            if (LogUtil.INFO) {
                Log.i(
                        TAG,
                        "Skipping optimization because time since last optimize ["
                                + optimizeInfo.getTimeSinceLastOptimizeMs()
                                + " ms] is lesser than the threshold for minimum time between"
                                + " optimizations ["
                                + mAppSearchConfig.getCachedMinTimeOptimizeThresholdMs()
                                + " ms]");
            }
            return false;
        }
        return wantsOptimize;
    }
}
