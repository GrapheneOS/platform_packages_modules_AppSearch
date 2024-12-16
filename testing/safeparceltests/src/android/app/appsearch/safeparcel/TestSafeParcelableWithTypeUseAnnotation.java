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
import android.os.Parcelable;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

@SafeParcelable.Class(creator = "TestSafeParcelableWithTypeUseAnnotationCreator")
public class TestSafeParcelableWithTypeUseAnnotation extends AbstractSafeParcelable {

    public static final
            Parcelable.@TypeUseAnnotation Creator<TestSafeParcelableWithTypeUseAnnotation>
            CREATOR = new TestSafeParcelableWithTypeUseAnnotationCreator();

    @Field(id = 1)
    public String publicString;

    @Constructor
    public TestSafeParcelableWithTypeUseAnnotation(@Param(id = 1) String publicString) {
        this.publicString = publicString;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        TestSafeParcelableWithTypeUseAnnotationCreator.writeToParcel(this, out, flags);
    }

    @Target(ElementType.TYPE_USE)
    public static @interface TypeUseAnnotation {}
}
