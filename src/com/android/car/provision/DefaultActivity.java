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
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

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

    // TODO(b/170957342): implement the features mentioned above

    private static final String TAG = "CarProvision";

    private static final int REQUEST_CODE_SET_DO = 42;

    private static final Map<String, String> sSupportedDpcApps = new HashMap<>(1);

    static {
        // TODO(b/170143095): add a UI with multiple options once AAOS provides a CarTestDPC app.
        sSupportedDpcApps.put("com.afwsamples.testdpc",
                "com.afwsamples.testdpc.SetupManagementLaunchActivity");
    }

    private TextView mErrorsTextView;
    private Button mCancelSetupButton;
    private Button mFinishSetupButton;
    private Button mDoProvisioningButton;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        Log.i(TAG, "onCreate() for user " + getUserId());

        setContentView(R.layout.default_activity);

        mErrorsTextView = findViewById(R.id.error_message);
        mCancelSetupButton = findViewById(R.id.cancel_setup);
        mFinishSetupButton = findViewById(R.id.finish_setup);
        mDoProvisioningButton = findViewById(R.id.do_provisioning);

        mCancelSetupButton.setOnClickListener((v) -> cancelSetup());
        mFinishSetupButton.setOnClickListener((v) -> finishSetup());

        setDoProvisioning();
    }

    private void setDoProvisioning() {
        if (!getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)) {
            Log.i(TAG, "Disabling DeviceOwner buttom because device does not have the "
                    + PackageManager.FEATURE_DEVICE_ADMIN + " feature");
            return;
        }
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        if (!dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE)) {
            Log.w(TAG, "Disabling DeviceOwner buttom because it cannot be provisioned - it can only"
                    + " be set on first boot");
            return;
        }

        // TODO(b/170143095): populate UI with supported options instead
        String dpcApp = sSupportedDpcApps.keySet().iterator().next();
        if (!checkDpcAppExists(dpcApp)) {
            Log.i(TAG, "Disabling DeviceOwner buttom because device does not have any DPC app");
            return;
        }

        mDoProvisioningButton.setEnabled(true);
        mDoProvisioningButton.setOnClickListener((v) -> provisionDeviceOwner());
    }

    private boolean checkDpcAppExists(String dpcApp) {
        if (!checkAppExists(dpcApp, UserHandle.USER_SYSTEM)) return false;
        if (!checkAppExists(dpcApp, getUserId())) return false;
        return true;
    }

    private boolean checkAppExists(String app, int userId) {
        Log.d(TAG, "Checking if " + app + " exits for user " + userId);
        try {
            PackageInfo info = getPackageManager().getPackageInfoAsUser(app, /* flags= */ 0,
                    userId);
            if (info == null) {
                Log.i(TAG, "No app " + app + " for user " + userId);
                return false;
            }
            Log.d(TAG, "Found it: " + info);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking if " + app + " exists for user " + userId, e);
            return false;
        }
    }

    private void finishSetup() {
        Log.i(TAG, "finishing setup for user " + getUserId());
        // Add a persistent setting to allow other apps to know the device has been provisioned.
        Settings.Global.putInt(getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 1);
        Settings.Secure.putInt(getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 1);

        // TODO(b/170143095): set android.car.SETUP_WIZARD_IN_PROGRESS and
        // android.car.ENABLE_INITIAL_NOTICE_SCREEN_TO_USER as well

        // TODO(b/170143095): listen to driving safety restriction events

        disableSelf();

        // Terminate the activity.
        finish();
    }

    private void cancelSetup() {
        Log.i(TAG, "cancelling setup for user " + getUserId());
        finish();
    }

    private void provisionDeviceOwner() {
        // TODO(b/170957342): add a UI with multiple options once AAOS provides a CarTestDPC app.
        Intent intent = new Intent();
        Map.Entry<String, String> dpc = sSupportedDpcApps.entrySet().iterator().next();
        intent.setComponent(new ComponentName(dpc.getKey(), dpc.getValue()));
        Log.i(TAG, "provisioning device owner, while running as user " + getUserId() + "Intent: "
                + intent);
        startActivityForResult(intent, REQUEST_CODE_SET_DO);
    }

    private void disableSelf() {
        // Remove this activity from the package manager.
        PackageManager pm = getPackageManager();
        ComponentName name = new ComponentName(this, DefaultActivity.class);
        Log.i(TAG, "Disabling itself (" + name + ") for user " + getUserId());
        pm.setComponentEnabledSetting(name, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "onActivityResult(): request=" + requestCode + ", result=" + resultCode
                + ", data=" + data);
        if (requestCode != REQUEST_CODE_SET_DO) {
            showErrorMessage("onActivityResult(): got invalid request code " + requestCode);
            return;
        }
        if (resultCode != Activity.RESULT_OK) {
            showErrorMessage("onActivityResult(): got invalid result code "
                    + resultCodeToString(resultCode));
            return;
        }
        Log.i(TAG, "Device owner  mode provisioned, nothing left to do...");
        disableSelf();
    };

    private static String resultCodeToString(int resultCode)  {
        switch (resultCode) {
            case Activity.RESULT_OK:
                return "RESULT_OK";
            case Activity.RESULT_CANCELED:
                return "RESULT_CANCELED";
            case Activity.RESULT_FIRST_USER:
                return "RESULT_FIRST_USER";
            default:
                return "UNKNOWN_CODE_" + resultCode;
        }
    }

    private void showErrorMessage(String message) {
        Log.e(TAG, "Error: " + message);
        mErrorsTextView.setText(message);
        findViewById(R.id.errors_container).setVisibility(View.VISIBLE);
    }
}
