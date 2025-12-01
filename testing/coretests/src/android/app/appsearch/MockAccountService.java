/*
 * Copyright 2025 The Android Open Source Project
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
package android.app.appsearch;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.NetworkErrorException;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

/**
 * A Service containing a Mock Authenticator, used for testing account functions in CTS/Unit Tests.
 */
public class MockAccountService extends Service {

    // 1. Define the account type here. It MUST match exactly with the one in
    // res/xml/authenticator.xml!
    public static final String ACCOUNT_TYPE = "com.android.appsearch.coretests.account";
    public static final String ACCOUNT_NAME = "appsearch_test_user";

    private MockAccountAuthenticator mAuthenticator;

    @Override
    public void onCreate() {
        super.onCreate();
        mAuthenticator = new MockAccountAuthenticator(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mAuthenticator.getIBinder();
    }

    // ==========================================
    // Inner class: The actual Authenticator logic
    // ==========================================
    static class MockAccountAuthenticator extends AbstractAccountAuthenticator {

        MockAccountAuthenticator(Context context) {
            super(context);
        }

        private Bundle createResultBundle() {
            Bundle result = new Bundle();
            result.putString(AccountManager.KEY_ACCOUNT_NAME, ACCOUNT_NAME);
            result.putString(AccountManager.KEY_ACCOUNT_TYPE, ACCOUNT_TYPE);
            result.putString(AccountManager.KEY_AUTHTOKEN, "mockToken");
            return result;
        }

        @Override
        public Bundle addAccount(
                AccountAuthenticatorResponse response,
                String accountType,
                String authTokenType,
                String[] requiredFeatures,
                Bundle options)
                throws NetworkErrorException {
            // Return success directly to simulate account addition
            return createResultBundle();
        }

        @Override
        public Bundle editProperties(AccountAuthenticatorResponse r, String s) {
            return createResultBundle();
        }

        @Override
        public Bundle updateCredentials(
                AccountAuthenticatorResponse r, Account a, String s, Bundle b) {
            return createResultBundle();
        }

        @Override
        public Bundle confirmCredentials(AccountAuthenticatorResponse r, Account a, Bundle b) {
            return createResultBundle();
        }

        @Override
        public Bundle getAuthToken(AccountAuthenticatorResponse r, Account a, String s, Bundle b) {
            return createResultBundle();
        }

        @Override
        public String getAuthTokenLabel(String s) {
            return "mockLabel";
        }

        @Override
        public Bundle hasFeatures(AccountAuthenticatorResponse r, Account a, String[] s) {
            return createResultBundle();
        }
    }
}
