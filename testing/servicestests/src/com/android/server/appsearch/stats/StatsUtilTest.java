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

package com.android.server.appsearch.stats;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.NonNull;

import com.android.server.appsearch.util.StatsUtil;

import org.junit.Test;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Tests covering the functionalities in {@link StatsUtil} NOT requiring overriding any flags in
 * {@link android.provider.DeviceConfig}.
 *
 * <p>To add tests rely on overriding the flags, please add them in the tests for {@link
 * PlatformLogger} in mockingservicestests.
 */
public class StatsUtilTest {

    @Test
    public void testCalculateHashCode_MD5_int32_shortString()
            throws NoSuchAlgorithmException, UnsupportedEncodingException {
        final String str1 = "d1";
        final String str2 = "d2";

        int hashCodeForStr1 = StatsUtil.calculateHashCodeMd5(str1);

        // hashing should be stable
        assertThat(hashCodeForStr1).isEqualTo(StatsUtil.calculateHashCodeMd5(str1));
        assertThat(hashCodeForStr1).isNotEqualTo(StatsUtil.calculateHashCodeMd5(str2));
    }

    @Test
    public void testGetCalculateCode_MD5_int32_mediumString()
            throws NoSuchAlgorithmException, UnsupportedEncodingException {
        final String str1 = "Siblings";
        final String str2 = "Teheran";

        int hashCodeForStr1 = StatsUtil.calculateHashCodeMd5(str1);

        // hashing should be stable
        assertThat(hashCodeForStr1).isEqualTo(StatsUtil.calculateHashCodeMd5(str1));
        assertThat(hashCodeForStr1).isNotEqualTo(StatsUtil.calculateHashCodeMd5(str2));
    }

    @Test
    public void testCalculateHashCode_MD5_int32_longString()
            throws NoSuchAlgorithmException, UnsupportedEncodingException {
        final String str1 = "abcdefghijkl-mnopqrstuvwxyz";
        final String str2 = "abcdefghijkl-mnopqrstuvwxy123";

        int hashCodeForStr1 = StatsUtil.calculateHashCodeMd5(str1);

        // hashing should be stable
        assertThat(hashCodeForStr1).isEqualTo(StatsUtil.calculateHashCodeMd5(str1));
        assertThat(hashCodeForStr1).isNotEqualTo(StatsUtil.calculateHashCodeMd5(str2));
    }

    @Test
    public void testCalculateHashCode_MD5_int32_sameAsBigInteger_intValue()
            throws NoSuchAlgorithmException, UnsupportedEncodingException {
        final String emptyStr = "";
        final String shortStr = "a";
        final String mediumStr = "Teheran";
        final String longStr = "abcd-efgh-ijkl-mnop-qrst-uvwx-yz";

        int emptyHashCode = StatsUtil.calculateHashCodeMd5(emptyStr);
        int shortHashCode = StatsUtil.calculateHashCodeMd5(shortStr);
        int mediumHashCode = StatsUtil.calculateHashCodeMd5(mediumStr);
        int longHashCode = StatsUtil.calculateHashCodeMd5(longStr);

        assertThat(emptyHashCode).isEqualTo(calculateHashCodeMd5withBigInteger(emptyStr));
        assertThat(shortHashCode).isEqualTo(calculateHashCodeMd5withBigInteger(shortStr));
        assertThat(mediumHashCode).isEqualTo(calculateHashCodeMd5withBigInteger(mediumStr));
        assertThat(longHashCode).isEqualTo(calculateHashCodeMd5withBigInteger(longStr));
    }

    @Test
    public void testCalculateHashCode_MD5_strIsNull()
            throws NoSuchAlgorithmException, UnsupportedEncodingException {
        assertThat(StatsUtil.calculateHashCodeMd5(/* str= */ null)).isEqualTo(-1);
    }

    private static int calculateHashCodeMd5withBigInteger(@NonNull String str)
            throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(str.getBytes(StandardCharsets.UTF_8));
        byte[] digest = md.digest();
        return new BigInteger(digest).intValue();
    }
}
