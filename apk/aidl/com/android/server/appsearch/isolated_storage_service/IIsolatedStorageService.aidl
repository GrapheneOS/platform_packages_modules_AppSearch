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
package com.android.server.appsearch.isolated_storage_service;

import android.os.ParcelFileDescriptor;
import com.android.server.appsearch.isolated_storage_service.ServiceConfig;
import com.android.server.appsearch.isolated_storage_service.VmStartResult;

/**
 * Isolated storage service.
 *
 * <p> This provides an isolated storage backed by a pVM for better protection. Inside the pVM, the
 * service host the storage is isloated from the main Android operating system, and the underlying
 * storage is encrypted.
 */
interface IIsolatedStorageService {
    /**
     * Starts the pVM.
     *
     * @param config The service configuration.
     * @param timeoutSeconds The timeout in seconds when waiting for the pVm payload to be ready.
     * @param forceRestart Whether to force the pVM to restart if it's already running.
     * @return VmStartResult object with essential values for the caller.
     */
    // TODO(b/416035857): remove forceRestart once VM status is fixed.
    VmStartResult startVm(in ServiceConfig config, in long timeoutSeconds, in boolean forceRestart);

    /**
     * Deletes the pVM.
     */
    boolean deleteVm();

    /**
     * Gets the connection to the pVM.
     */
    ParcelFileDescriptor getVmConnection();

    /**
     * Gets the status of the pVM.
     */
    int getVmStatus();

    /**
     * Cleans up old pVMs in the CE directory.
     */
    oneway void cleanUpOldVms();
}
