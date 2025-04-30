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

#include <cutils/properties.h>
#include <jni.h>
#include <log/log.h>

__attribute__ ((visibility ("default")))
extern "C" void
Java_com_android_server_appsearch_isolated_1storage_1service_VmStateSignaler_notifyIdle__Z(
        JNIEnv *env, jclass clazz, jboolean value) {
    (void) env;
    (void) clazz;

    if (0 != property_set("appsearch_vm.idle", value ? "1" : "0")) {
        ALOGE("AppSearch could not set idle property!");
    }
}

