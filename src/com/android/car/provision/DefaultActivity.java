/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.provision;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

/**
 * Reference implementeation for a Car SetupWizard.
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Shows UI where user can confirm setup.
 *   <li>Listen to UX restriction events, so it defers setup when the car moves.
 *   <li>Add option to setup DeviceOwner mode.
 *   <li>Sets car-specific properties.
 * </ul>
 */
public final class DefaultActivity extends Activity {

    // TODO(b/170143095): implement the features mentioned above

    private static final String TAG = "CarProvision";

    private static final int REQUEST_CODE_SET_DO = 42;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate() for user " + getUserId());

        finishSetup();
    }

    private void finishSetup() {
        Log.i(TAG, "finishing setup for user " + getUserId());
        // Add a persistent setting to allow other apps to know the device has been provisioned.
        Settings.Global.putInt(getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 1);
        Settings.Secure.putInt(getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 1);
        // TODO(b/170143095): set android.car.SETUP_WIZARD_IN_PROGRESS and
        // android.car.ENABLE_INITIAL_NOTICE_SCREEN_TO_USER as well

        // Terminate the activity.
        disableSelf();
        finish();
    }

    private void disableSelf() {
        // Remove this activity from the package manager.
        PackageManager pm = getPackageManager();
        ComponentName name = new ComponentName(this, DefaultActivity.class);
        Log.i(TAG, "Disabling itself (" + name + ") for user " + getUserId());
        pm.setComponentEnabledSetting(name, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
