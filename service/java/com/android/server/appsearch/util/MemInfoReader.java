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

package com.android.server.appsearch.util;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

/**
 * Utility class to read memory info from /proc/meminfo.
 *
 * @hide
 */
public final class MemInfoReader {
    private static final String MEM_INFO_FILE_PATH = "/proc/meminfo";

    public MemInfoReader() {}

    /** Gets the amount of free RAM in KB. Returns {@code -1} if failing to get the number. */
    public long getFreeSizeKb() throws FileNotFoundException, IOException, NumberFormatException {
        BufferedReader memInfo = new BufferedReader(new FileReader(MEM_INFO_FILE_PATH));
        String line;
        while ((line = memInfo.readLine()) != null) {
            int colon = line.indexOf(':');
            if (colon == -1) {
                continue;
            }
            String keyword = line.substring(0, colon);
            if (!keyword.equals("MemAvailable")) {
                continue;
            }

            int startPos = -1;
            int len = 0;
            for (int i = colon + 1; i < line.length(); ++i) {
                if (Character.isDigit(line.charAt(i))) {
                    len += 1;
                    if (startPos == -1) {
                        startPos = i;
                    }
                } else if (startPos != -1) {
                    break;
                }
            }
            // Now we found the digit at range [startPos, startPos + len).
            return Long.parseLong(line.substring(startPos, startPos + len));
        }
        throw new IOException("Cannot find MemAvailable in /proc/meminfo");
    }
}
