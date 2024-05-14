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

import static org.testng.Assert.assertEquals;

import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/** Tests utility methods for serializing and deserializing SafeParcelables. */
@RunWith(AndroidJUnit4.class)
public class SafeParcelableSerializerTest {
    private static final String KEY = "key";

    @Test
    public void testRoundTripArrayListAsBundleSafe() {
        List<TestSafeParcelable> objArrayList = new ArrayList<>();
        objArrayList.add(new TestSafeParcelable("foo0", "bar0"));
        objArrayList.add(new TestSafeParcelable("foo1", "bar1"));
        objArrayList.add(new TestSafeParcelable("foo2", "bar2"));
        Bundle bundle = new Bundle();
        SafeParcelableSerializer.serializeIterableToBundleSafe(bundle, KEY, objArrayList);
        List<TestSafeParcelable> objArrayList2 =
                SafeParcelableSerializer.deserializeIterableFromBundleSafe(
                        bundle, KEY, TestSafeParcelable.CREATOR);
        assertEquals(objArrayList, objArrayList2);
    }

    @Test
    public void testRoundTripEmptyArrayListAsBundleSafe() {
        List<TestSafeParcelable> objArrayList = new ArrayList<>();
        // objArrayList left intentionally empty
        Bundle bundle = new Bundle();
        SafeParcelableSerializer.serializeIterableToBundleSafe(bundle, KEY, objArrayList);
        List<TestSafeParcelable> objArrayList2 =
                SafeParcelableSerializer.deserializeIterableFromBundleSafe(
                        bundle, KEY, TestSafeParcelable.CREATOR);
        assertEquals(objArrayList, objArrayList2);
    }
}
