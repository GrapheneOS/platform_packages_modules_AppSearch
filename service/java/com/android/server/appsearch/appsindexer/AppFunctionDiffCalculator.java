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

package com.android.server.appsearch.appsindexer;

import android.annotation.NonNull;
import android.app.appsearch.GenericDocument;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionDocument;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AppFunctionDiffCalculator {

    /**
     * Represents the difference between the currently stored and newly provided app functions.
     *
     * <p>This class is used to determine changes in app functions, including which functions have
     * been added, updated, or removed, and whether the schema needs to be updated.
     */
    public static final class AppFunctionDiff {
        /**
         * Indicates whether {@code setSchema} should be called based on the diff.
         *
         * <p>This will be {@code true} if either of the following conditions is met:
         *
         * <ul>
         *   <li>A new package with app functions has been added.
         *   <li>A previously indexed package no longer contains app functions.
         * </ul>
         */
        public final boolean modifySchema;

        /** A map of newly added app function documents, keyed by function ID. */
        public final Map<String, AppFunctionDocument> addedAppFunctions;

        /**
         * A map of updated app function documents, where the contents differ from their previously
         * stored versions. Keyed by function ID.
         */
        public final Map<String, AppFunctionDocument> updatedAppFunctions;

        /**
         * A set of function IDs that should be removed using a {@code removeByDocumentId} call to
         * AppSearch.
         */
        public final Set<String> functionIdsToRemove;

        /**
         * A set of all function document IDs that will be deleted either via a {@code setSchema}
         * call or a {@code removeByDocumentId} call. Used for logging purposes only.
         */
        public final Set<String> allDeletedFunctionIds;

        private AppFunctionDiff(
                boolean modifySchema,
                @NonNull Map<String, AppFunctionDocument> addedAppFunctions,
                @NonNull Map<String, AppFunctionDocument> updatedAppFunctions,
                @NonNull Set<String> functionIdsToRemove,
                @NonNull Set<String> allDeletedFunctionIds) {
            this.modifySchema = modifySchema;
            this.addedAppFunctions = Objects.requireNonNull(addedAppFunctions);
            this.updatedAppFunctions = Objects.requireNonNull(updatedAppFunctions);
            this.functionIdsToRemove = Objects.requireNonNull(functionIdsToRemove);
            this.allDeletedFunctionIds = Objects.requireNonNull(allDeletedFunctionIds);
        }
    }

    /**
     * Calculates the difference between the currently stored and the newly provided app functions.
     *
     * @param storedAppFunctions a map of package names to their stored app functions, keyed by
     *     function ID
     * @param currentAppFunctions a map of package names to their current app functions, keyed by
     *     function ID
     */
    public static AppFunctionDiff calculate(
            @NonNull Map<String, Map<String, AppFunctionDocument>> storedAppFunctions,
            @NonNull Map<String, Map<String, ? extends AppFunctionDocument>> currentAppFunctions) {
        // If a new package was added or removed.
        boolean modifySchema = !storedAppFunctions.keySet().equals(currentAppFunctions.keySet());
        Map<String, AppFunctionDocument> addedAppFunctions = new ArrayMap<>();
        Map<String, AppFunctionDocument> updatedAppFunctions = new ArrayMap<>();
        Set<String> functionIdsToRemove = new ArraySet<>();
        Set<String> allDeletedFunctionIds = new ArraySet<>();

        for (Map.Entry<String, Map<String, ? extends AppFunctionDocument>> packageEntry :
                currentAppFunctions.entrySet()) {
            String packageName = packageEntry.getKey();
            Map<String, ? extends AppFunctionDocument> currentAppFunctionsPerApp =
                    packageEntry.getValue();

            // This might be null, in the case of functions newly added to a package
            Map<String, AppFunctionDocument> appSearchAppFunctionsPerApp =
                    storedAppFunctions.get(packageName);

            if (appSearchAppFunctionsPerApp == null && !currentAppFunctionsPerApp.isEmpty()) {
                // Functions added to an app that didn't have
                modifySchema = true;
                addedAppFunctions.putAll(currentAppFunctionsPerApp);
            } else if (appSearchAppFunctionsPerApp != null) {
                if (currentAppFunctionsPerApp.isEmpty()) {
                    // All functions removed from an app that had them
                    modifySchema = true;
                    allDeletedFunctionIds.addAll(appSearchAppFunctionsPerApp.keySet());
                } else {
                    // App updated that had packages, we should check
                    comparePackageFunctionDocuments(
                            currentAppFunctionsPerApp,
                            appSearchAppFunctionsPerApp,
                            addedAppFunctions,
                            updatedAppFunctions,
                            functionIdsToRemove,
                            allDeletedFunctionIds);
                }
            }
        }

        return new AppFunctionDiff(
                modifySchema,
                addedAppFunctions,
                updatedAppFunctions,
                functionIdsToRemove,
                allDeletedFunctionIds);
    }

    /**
     * Compares the app function documents in PackageManager vs those in AppSearch, and updates
     * corresponding collections accordingly.
     *
     * @param currentAppFunctionDocumentsPerApp the mapping of function ids to documents
     *     corresponding to what is in the apps metadata.
     * @param appSearchAppFunctionDocumentsPerApp the mapping of function ids to documents
     *     corresponding to what is in AppSearch
     * @param addedAppFunctionsPerApp the mapping of newly added {@link AppFunctionDocument}(s) to
     *     their ids for the app.
     * @param updatedAppFunctionsPerApp the mapping of modified {@link AppFunctionDocument}(s) to
     *     their ids for the app.
     * @param functionIdsToRemove the set of ids that will be sent to a remove call in AppSearch
     * @param allDeletedFunctionIds the set of ids of all functions that will be removed from
     *     AppSearch, either by a delete call or by a setSchema call. For logging purposes.
     */
    private static void comparePackageFunctionDocuments(
            @NonNull Map<String, ? extends AppFunctionDocument> currentAppFunctionDocumentsPerApp,
            @NonNull Map<String, AppFunctionDocument> appSearchAppFunctionDocumentsPerApp,
            @NonNull Map<String, AppFunctionDocument> addedAppFunctionsPerApp,
            @NonNull Map<String, AppFunctionDocument> updatedAppFunctionsPerApp,
            @NonNull Set<String> functionIdsToRemove,
            @NonNull Set<String> allDeletedFunctionIds) {
        Objects.requireNonNull(currentAppFunctionDocumentsPerApp);
        Objects.requireNonNull(appSearchAppFunctionDocumentsPerApp);
        Objects.requireNonNull(addedAppFunctionsPerApp);
        Objects.requireNonNull(updatedAppFunctionsPerApp);
        Objects.requireNonNull(functionIdsToRemove);
        Objects.requireNonNull(allDeletedFunctionIds);

        for (Map.Entry<String, ? extends AppFunctionDocument> currentFunctionEntry :
                currentAppFunctionDocumentsPerApp.entrySet()) {
            String functionId = currentFunctionEntry.getKey();
            AppFunctionDocument currentFunctionDocument = currentFunctionEntry.getValue();
            AppFunctionDocument appSearchFunctionDocument =
                    appSearchAppFunctionDocumentsPerApp.get(functionId);
            // appSearchFunctionDocument == null means it's a new document.
            if (appSearchFunctionDocument == null) {
                addedAppFunctionsPerApp.put(functionId, currentFunctionDocument);

            } else if (!areFunctionDocumentsEqual(
                    appSearchFunctionDocument, currentFunctionDocument)) {
                updatedAppFunctionsPerApp.put(functionId, currentFunctionDocument);
            }
        }

        for (Map.Entry<String, AppFunctionDocument> appSearchFunctionEntry :
                appSearchAppFunctionDocumentsPerApp.entrySet()) {
            String functionId = appSearchFunctionEntry.getKey();
            if (!currentAppFunctionDocumentsPerApp.containsKey(functionId)) {
                functionIdsToRemove.add(functionId);
                allDeletedFunctionIds.add(functionId);
            }
        }
    }

    /**
     * Checks if two AppFunction documents are equal. It isn't enough to call equals. We also need
     * to ignore creation timestamp and parent types. These are set in AppSearch, but aren't set for
     * the "about to be indexed" docs
     *
     * @return true if the documents are equal, false otherwise.
     */
    private static boolean areFunctionDocumentsEqual(
            @NonNull GenericDocument document1, @NonNull GenericDocument document2) {
        Objects.requireNonNull(document1);
        Objects.requireNonNull(document2);

        document1 = clearTimestampsAndParentTypesInDocument(document1);
        document2 = clearTimestampsAndParentTypesInDocument(document2);

        return document1.equals(document2);
    }

    private static GenericDocument clearTimestampsAndParentTypesInDocument(
            @NonNull GenericDocument document) {
        GenericDocument.Builder<?> builder =
                new GenericDocument.Builder<>(document)
                        .setCreationTimestampMillis(0)
                        // GenericDocument#PARENT_TYPES_SYNTHETIC_PROPERTY is hidden
                        .clearProperty("$$__AppSearch__parentTypes");

        for (String propertyName : document.getPropertyNames()) {
            Object property = document.getProperty(propertyName);
            if (property instanceof GenericDocument[] nestedDocuments) {
                GenericDocument[] clearedNestedDocuments =
                        new GenericDocument[nestedDocuments.length];

                for (int i = 0; i < nestedDocuments.length; i++) {
                    clearedNestedDocuments[i] =
                            clearTimestampsAndParentTypesInDocument(nestedDocuments[i]);
                }

                builder.setPropertyDocument(propertyName, clearedNestedDocuments);
            }
        }

        return builder.build();
    }
}
