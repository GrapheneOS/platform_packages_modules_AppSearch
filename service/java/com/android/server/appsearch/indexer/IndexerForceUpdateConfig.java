/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.provider.DeviceConfig;
import android.provider.DeviceConfig.OnPropertiesChangedListener;

import org.jspecify.annotations.NonNull;

import java.util.concurrent.Executor;

/**
 * An interface which exposes config flags to AppSearch Indexers
 *
 * <p>Implementations of this interface must be thread-safe.
 *
 * @hide
 */
public interface IndexerForceUpdateConfig {
    /** Returns whether the indexer Force Update is enabled */
    boolean isIndexerForceUpdateEnabled();

    /** Returns the indexer Force Update Emergency Counter */
    int getIndexerForceUpdateEmergencyCounter();

    /**
     * Configures a {@link DeviceConfig.OnPropertiesChangedListener} that runs the given callback on
     * the given executor when there is a properties change in the AppSearch namespace. This
     * listener is registered asynchronously using the given executor.
     *
     * @param executor The executor to run the callback in.
     */
    static OnPropertiesChangedListener addListener(
            @NonNull Executor executor, @NonNull Runnable callback) {
        final OnPropertiesChangedListener onDeviceConfigChangedListener =
                properties -> {
                    if (!properties.getNamespace().equals(DeviceConfig.NAMESPACE_APPSEARCH)) {
                        return;
                    }
                    callback.run();
                };

        executor.execute(
                () -> {
                    DeviceConfig.addOnPropertiesChangedListener(
                            DeviceConfig.NAMESPACE_APPSEARCH,
                            executor,
                            onDeviceConfigChangedListener);
                });

        return onDeviceConfigChangedListener;
    }
}
