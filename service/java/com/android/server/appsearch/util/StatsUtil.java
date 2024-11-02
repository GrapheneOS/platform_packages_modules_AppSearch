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

package com.android.server.appsearch.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

/**
 * This utility class to provide helper functions for AppSearch logging.
 *
 * @hide
 */
public class StatsUtil {

    private static final Random sRng = new Random();

    /**
     * Calculate the hash code as an integer by returning the last four bytes of its MD5.
     *
     * @param str a string to hash.
     * @return hash code as an integer. returns -1 if str is null.
     * @throws AppSearchException if either algorithm or encoding does not exist.
     */
    public static int calculateHashCodeMd5(@Nullable String str)
            throws NoSuchAlgorithmException, UnsupportedEncodingException {
        if (str == null) {
            return -1;
        }

        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(str.getBytes(/* charsetName= */ UTF_8));
        byte[] digest = md.digest();

        // Since MD5 generates 16 bytes digest, we don't need to check the length here to see
        // if it is smaller than sizeof(int)(4).
        //
        // We generate the same value as BigInteger(digest).intValue().
        // BigInteger takes bytes[] and treat it as big endian. And its intValue() would get the
        // lower 4 bytes. So here we take the last 4 bytes and treat them as big endian.
        return (digest[12] & 0xFF) << 24
                | (digest[13] & 0xFF) << 16
                | (digest[14] & 0xFF) << 8
                | (digest[15] & 0xFF);
    }

    /**
     * Checks if the stats should be sampled for logging based on the provided sampling interval.
     *
     * <p>The probability of sampling is 1/samplingInterval. For example:
     *
     * <ul>
     *   <li>If the samplingInterval is 1, all stats will be sampled (100% sampling).
     *   <li>If the samplingInterval is 10, 1 in 10 stats will be sampled (10% sampling).
     * </ul>
     *
     * @param samplingInterval the interval used to calculate the sampling probability.
     * @return true if the stats should be sampled, false otherwise.
     */
    public static boolean shouldSample(int samplingInterval) {
        if (samplingInterval <= 0) {
            return false;
        }

        return sRng.nextInt((int) samplingInterval) == 0;
    }

    private StatsUtil() {}
}
