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

import android.app.appsearch.AppSearchSession;

/**
 * The package contains the implementation of the AppsIndexer used to index metadata about apps and
 * app functions exposed by apps into AppSearch.
 *
 * <p>App function documents are indexed into AppSearch via {@link AppsIndexerImpl#doIncrementalUpdate} in a
 * single {@link AppSearchHelper#APP_DATABASE} database. Within the database, each schema type is
 * named dynamically to be unique to the app package name to control the schema visibility by the
 * result of {@link android.content.pm.PackageManager#canPackageQuery}. This was preferred over
 * defining one database per app because {@link AppSearchSession#setSchema} was a bottleneck for the
 * inserting schemas into per app database.
 */
