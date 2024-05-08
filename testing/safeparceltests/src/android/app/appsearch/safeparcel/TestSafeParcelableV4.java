/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.appsearch.safeparcel;

import android.os.Parcel;

@SafeParcelable.Class(creator = "TestSafeParcelableV4Creator")
public class TestSafeParcelableV4<T> extends AbstractSafeParcelable {

    @SuppressWarnings("rawtypes")
    public static final Creator<TestSafeParcelableV4> CREATOR = new TestSafeParcelableV4Creator();

    @Field(id = 1)
    public String publicString;

    @Constructor
    public TestSafeParcelableV4(@Param(id = 1) String publicString) {
        this.publicString = publicString;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        TestSafeParcelableV4Creator.writeToParcel(this, out, flags);
    }
}
