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

package com.android.server.appsearch.appsindexer;

import static com.google.common.truth.Truth.assertThat;

import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionDocument;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Map;

@RunWith(MockitoJUnitRunner.class)
public class AppFunctionDiffCalculatorTest {

    public static final String TEST_PACKAGE_NAME = "com.example";

    private static AppFunctionDocument createFunctionDoc(String id, String packageName) {
        return new AppFunctionDocument.Builder(
                        packageName,
                        id,
                        /* indexerPackageName= */ "android",
                        AppFunctionStaticMetadata.SCHEMA_TYPE)
                .build();
    }

    @Test
    public void testCalculate_noChanges_returnsEmptyDiff() {
        Map<String, Map<String, AppFunctionDocument>> stored =
                Map.of(
                        TEST_PACKAGE_NAME,
                        Map.of("func1", createFunctionDoc("func1", TEST_PACKAGE_NAME)));
        Map<String, Map<String, ? extends AppFunctionDocument>> current =
                Map.of(
                        TEST_PACKAGE_NAME,
                        Map.of("func1", createFunctionDoc("func1", TEST_PACKAGE_NAME)));

        AppFunctionDiffCalculator.AppFunctionDiff diff =
                AppFunctionDiffCalculator.calculate(stored, current);

        assertThat(diff.modifySchema).isFalse();
        assertThat(diff.addedAppFunctions).isEmpty();
        assertThat(diff.updatedAppFunctions).isEmpty();
        assertThat(diff.functionIdsToRemove).isEmpty();
        assertThat(diff.allDeletedFunctionIds).isEmpty();
    }

    @Test
    public void calculate_allFunctionsRemoved_detectsAllDeletedFunctionsAndSchemaModification() {
        Map<String, Map<String, AppFunctionDocument>> stored =
                Map.of(
                        TEST_PACKAGE_NAME,
                        Map.of("func1", createFunctionDoc("func1", TEST_PACKAGE_NAME)));

        AppFunctionDiffCalculator.AppFunctionDiff diff =
                AppFunctionDiffCalculator.calculate(stored, Map.of(TEST_PACKAGE_NAME, Map.of()));

        assertThat(diff.modifySchema).isTrue();
        assertThat(diff.allDeletedFunctionIds).containsExactly("func1");
    }

    @Test
    public void calculate_functionAddedToExistingPackage_detectsNewFunction() {
        AppFunctionDocument existing = createFunctionDoc("func1", TEST_PACKAGE_NAME);
        AppFunctionDocument added = createFunctionDoc("func2", TEST_PACKAGE_NAME);
        Map<String, Map<String, AppFunctionDocument>> stored =
                Map.of(TEST_PACKAGE_NAME, Map.of("func1", existing));
        Map<String, Map<String, ? extends AppFunctionDocument>> current =
                Map.of(
                        TEST_PACKAGE_NAME,
                        Map.of(
                                "func1", existing,
                                "func2", added));

        AppFunctionDiffCalculator.AppFunctionDiff diff =
                AppFunctionDiffCalculator.calculate(stored, current);

        assertThat(diff.modifySchema).isFalse();
        assertThat(diff.addedAppFunctions.keySet()).contains("func2");
    }

    @Test
    public void calculate_functionRemoved_detectsRemoval() {
        AppFunctionDocument doc1 = createFunctionDoc("func1", TEST_PACKAGE_NAME);
        AppFunctionDocument docToRemove = createFunctionDoc("funcToRemove", TEST_PACKAGE_NAME);
        Map<String, Map<String, AppFunctionDocument>> stored =
                Map.of("com.example", Map.of("func1", doc1, "funcToRemove", docToRemove));
        Map<String, Map<String, ? extends AppFunctionDocument>> current =
                Map.of("com.example", Map.of("func1", doc1));

        AppFunctionDiffCalculator.AppFunctionDiff diff =
                AppFunctionDiffCalculator.calculate(stored, current);

        assertThat(diff.functionIdsToRemove).containsExactly("funcToRemove");
        assertThat(diff.allDeletedFunctionIds).containsExactly("funcToRemove");
    }

    @Test
    public void calculate_functionUpdated_detectsChangedContent() {
        AppFunctionDocument oldDoc =
                new AppFunctionStaticMetadata.Builder(TEST_PACKAGE_NAME, "func1", "android")
                        .setEnabledByDefault(false)
                        .build();
        AppFunctionDocument newDoc =
                new AppFunctionStaticMetadata.Builder(TEST_PACKAGE_NAME, "func1", "android")
                        .setEnabledByDefault(true)
                        .build();
        Map<String, Map<String, AppFunctionDocument>> stored =
                Map.of("com.example", Map.of("func1", oldDoc));
        Map<String, Map<String, ? extends AppFunctionDocument>> current =
                Map.of("com.example", Map.of("func1", newDoc));

        AppFunctionDiffCalculator.AppFunctionDiff diff =
                AppFunctionDiffCalculator.calculate(stored, current);

        assertThat(diff.updatedAppFunctions.keySet()).contains("func1");
        assertThat(
                        diff.updatedAppFunctions
                                .get("func1")
                                .getPropertyBoolean(
                                        AppFunctionStaticMetadata.PROPERTY_ENABLED_BY_DEFAULT))
                .isTrue();
    }
}
