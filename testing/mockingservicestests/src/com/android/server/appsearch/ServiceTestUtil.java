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

package com.android.server.appsearch;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;

import android.annotation.NonNull;
import android.app.appsearch.aidl.IAppSearchManager;
import android.os.ServiceManager;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.modules.utils.testing.StaticMockFixture;
import com.android.server.LocalManagerRegistry;
import com.android.server.usage.StorageStatsManagerLocal;

import org.mockito.ArgumentCaptor;

public class ServiceTestUtil {
    public static final class MockServiceManager implements StaticMockFixture {
        ArgumentCaptor<IAppSearchManager.Stub> mStubCaptor =
                ArgumentCaptor.forClass(IAppSearchManager.Stub.class);

        @Override
        public StaticMockitoSessionBuilder setUpMockedClasses(
                @NonNull StaticMockitoSessionBuilder sessionBuilder) {
            sessionBuilder.mockStatic(LocalManagerRegistry.class);
            sessionBuilder.spyStatic(ServiceManager.class);
            return sessionBuilder;
        }

        @Override
        public void setUpMockBehaviors() {
            ExtendedMockito.doReturn(mock(StorageStatsManagerLocal.class))
                    .when(() -> LocalManagerRegistry.getManager(StorageStatsManagerLocal.class));
            ExtendedMockito.doNothing()
                    .when(
                            () ->
                                    ServiceManager.addService(
                                            anyString(),
                                            mStubCaptor.capture(),
                                            anyBoolean(),
                                            anyInt()));
        }

        @Override
        public void tearDown() {}
    }

    private ServiceTestUtil() {}
}
