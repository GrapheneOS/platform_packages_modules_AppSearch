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
 * This version supports multiple mock accounts for isolation testing.
 */
public class MockAccountService extends Service {

    // The account type MUST match the one defined in res/xml/authenticator.xml
    public static final String ACCOUNT_TYPE = "com.android.appsearch.coretests.account";

    // Define constants for two separate test accounts
    public static final String ACCOUNT_NAME_1 = "appsearch_test_user_1";
    public static final String ACCOUNT_NAME_2 = "appsearch_test_user_2";

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

        /**
         * Creates a result bundle for the specified account name.
         *
         * @param accountName The name of the account to be included in the response.
         */
        private Bundle createResultBundle(String accountName) {
            Bundle result = new Bundle();
            result.putString(AccountManager.KEY_ACCOUNT_NAME, accountName);
            result.putString(AccountManager.KEY_ACCOUNT_TYPE, ACCOUNT_TYPE);
            result.putString(AccountManager.KEY_AUTHTOKEN, "mockToken_" + accountName);
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
            // Check if a specific account name was requested via options
            String name =
                    (options != null && options.containsKey(AccountManager.KEY_ACCOUNT_NAME))
                            ? options.getString(AccountManager.KEY_ACCOUNT_NAME)
                            : ACCOUNT_NAME_1;

            return createResultBundle(name);
        }

        @Override
        public Bundle getAuthToken(AccountAuthenticatorResponse r, Account a, String s, Bundle b) {
            // Return token based on the provided Account object
            return createResultBundle(a.name);
        }

        @Override
        public Bundle editProperties(AccountAuthenticatorResponse r, String s) {
            return createResultBundle(ACCOUNT_NAME_1);
        }

        @Override
        public Bundle updateCredentials(
                AccountAuthenticatorResponse r, Account a, String s, Bundle b) {
            return createResultBundle(a.name);
        }

        @Override
        public Bundle confirmCredentials(AccountAuthenticatorResponse r, Account a, Bundle b) {
            return createResultBundle(a.name);
        }

        @Override
        public String getAuthTokenLabel(String s) {
            return "mockLabel";
        }

        @Override
        public Bundle hasFeatures(AccountAuthenticatorResponse r, Account a, String[] s) {
            return createResultBundle(a.name);
        }
    }
}
