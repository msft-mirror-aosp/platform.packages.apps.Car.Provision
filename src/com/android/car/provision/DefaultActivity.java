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

import static android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_TRIGGER;
import static android.app.admin.DevicePolicyManager.PROVISIONING_TRIGGER_QR_CODE;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.android.car.setupwizardlib.util.CarDrivingStateMonitor;

import java.util.HashMap;
import java.util.Map;

/**
 * Reference implementeation for a Car SetupWizard.
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Shows UI where user can confirm setup.
 *   <li>Listen to UX restriction events, so it exits setup when the car moves.
 *   <li>Add option to setup DeviceOwner mode.
 *   <li>Sets car-specific properties.
 * </ul>
 */
public final class DefaultActivity extends Activity {

    static final String TAG = "CarProvision";

    // TODO(b/170333009): copied from android.car.settings.CarSettings, as they're hidden
    private static final String KEY_ENABLE_INITIAL_NOTICE_SCREEN_TO_USER =
            "android.car.ENABLE_INITIAL_NOTICE_SCREEN_TO_USER";
    private static final String KEY_SETUP_WIZARD_IN_PROGRESS =
            "android.car.SETUP_WIZARD_IN_PROGRESS";

    private static final int REQUEST_CODE_SET_DO = 42;

    private static final int NOTIFICATION_ID = 108;
    private static final String IMPORTANCE_DEFAULT_ID = "importance_default";

    private static final Map<String, DpcInfo> sSupportedDpcApps = new HashMap<>(1);

    static {
        // TODO(b/170333009): add a UI with multiple options once AAOS provides a CarTestDPC app.
        DpcInfo testDpc = new DpcInfo("TestDPC",
                "com.afwsamples.testdpc",
                "com.afwsamples.testdpc.SetupManagementLaunchActivity",
                "com.afwsamples.testdpc.DeviceAdminReceiver",
                // TODO(b/170333009): add UI to set checkSum for local built app
                "gJD2YwtOiWJHkSMkkIfLRlj-quNqG1fb6v100QmzM9w=",
                "https://testdpc-latest-apk.appspot.com/preview");
        sSupportedDpcApps.put(testDpc.name, testDpc);
    }

    private CarDrivingStateMonitor mCarDrivingStateMonitor;

    private TextView mErrorsTextView;
    private Button mFinishSetupButton;
    private Button mDoProvisioningLegacyWorkflowButton;
    private Button mDoProvisioningButton;

    private final BroadcastReceiver mDrivingStateExitReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "onReceive(): " + intent);
            exitSetup();
        }
    };

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        int userId = getUserId();
        Log.i(TAG, "onCreate() for user " + userId + " Intent: " + getIntent());

        if (userId == UserHandle.USER_SYSTEM && UserManager.isHeadlessSystemUserMode()) {
            finishSetup();
            return;
        }

        setCarSetupInProgress(true);
        setContentView(R.layout.default_activity);

        mErrorsTextView = findViewById(R.id.error_message);
        mFinishSetupButton = findViewById(R.id.finish_setup);
        mDoProvisioningLegacyWorkflowButton = findViewById(R.id.legacy_do_provisioning);
        mDoProvisioningButton = findViewById(R.id.do_provisioning);

        mFinishSetupButton.setOnClickListener((v) -> finishSetup());

        setDoProvisioning();
        startMonitor();
    }

    private void startMonitor() {
        registerReceiver(mDrivingStateExitReceiver,
                new IntentFilter(CarDrivingStateMonitor.EXIT_BROADCAST_ACTION));

        mCarDrivingStateMonitor = CarDrivingStateMonitor.get(this);
        mCarDrivingStateMonitor.startMonitor();
    }

    @Override
    public void finish() {
        Log.i(TAG, "finish() for user " + getUserId());

        stopMonitor();

        super.finish();
    };

    private void stopMonitor() {
        if (mDrivingStateExitReceiver != null) {
            unregisterReceiver(mDrivingStateExitReceiver);
        }

        if (mCarDrivingStateMonitor != null) {
            mCarDrivingStateMonitor.stopMonitor();
        }
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

        mDoProvisioningButton.setEnabled(true);
        mDoProvisioningButton.setOnClickListener((v) -> provisionDeviceOwner());
        mDoProvisioningLegacyWorkflowButton.setEnabled(true);
        mDoProvisioningLegacyWorkflowButton
                .setOnClickListener((v) -> provisionDeviceOwnerLegacyWorkflow());
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
        provisionUserAndDevice();
        disableSelfAndFinish();
    }

    private void provisionUserAndDevice() {
        Log.d(TAG, "setting Settings properties");
        // Add a persistent setting to allow other apps to know the device has been provisioned.
        Settings.Global.putInt(getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 1);
        Settings.Secure.putInt(getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 1);

        // Set car-specific properties
        setCarSetupInProgress(false);
        Settings.Secure.putInt(getContentResolver(), KEY_ENABLE_INITIAL_NOTICE_SCREEN_TO_USER, 0);
    }

    private void setCarSetupInProgress(boolean inProgress) {
        Settings.Secure.putInt(getContentResolver(), KEY_SETUP_WIZARD_IN_PROGRESS,
                inProgress ? 1 : 0);
    }

    private void exitSetup() {
        Log.d(TAG, "exiting setup early for user " + getUserId());
        provisionUserAndDevice();
        notifySetupExited();
        disableSelfAndFinish();
    }

    private void notifySetupExited() {
        Log.d(TAG, "Sending exited setup notification");

        NotificationManager notificationMgr = getSystemService(NotificationManager.class);
        notificationMgr.createNotificationChannel(new NotificationChannel(
                IMPORTANCE_DEFAULT_ID, "Importance Default",
                NotificationManager.IMPORTANCE_DEFAULT));
        Notification notification = new Notification
                .Builder(this, IMPORTANCE_DEFAULT_ID)
                .setContentTitle(getString(R.string.exited_setup_title))
                .setContentText(getString(R.string.exited_setup_content))
                .setCategory(Notification.CATEGORY_CAR_INFORMATION)
                .setSmallIcon(R.drawable.car_ic_mode)
                .build();
        notificationMgr.notify(NOTIFICATION_ID, notification);
    }

    private void provisionDeviceOwnerLegacyWorkflow() {
        // TODO(b/170333009): add a UI with multiple options once AAOS provides a CarTestDPC app.
        DpcInfo dpcInfo = sSupportedDpcApps.values().iterator().next();
        if (!checkDpcAppExists(dpcInfo.packageName)) {
            showErrorMessage("Cannot setup DeviceOwner because " + dpcInfo.packageName
                    + " is not available.\n Make sure it's installed for both user 0 and user "
                    + getUserId());
            return;
        }

        Intent intent = new Intent();
        intent.setComponent(dpcInfo.getLegacyActivityComponentName());
        Log.i(TAG, "Provisioning device owner using LEGACY workflow while running as user "
                + getUserId() + ". DPC: " + dpcInfo + ". Intent: " + intent);
        startActivityForResult(intent, REQUEST_CODE_SET_DO);
    }

    private void provisionDeviceOwner() {
        // TODO(b/170333009): add a UI with multiple options once AAOS provides a CarTestDPC app.
        DpcInfo dpcInfo = sSupportedDpcApps.values().iterator().next();

        Intent intent = new Intent(ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE);
        // TODO(b/170333009): add a UI with options for EXTRA_PROVISIONING_TRIGGER.
        intent.putExtra(EXTRA_PROVISIONING_TRIGGER, PROVISIONING_TRIGGER_QR_CODE);
        intent.putExtra(EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                dpcInfo.getAdminReceiverComponentName());
        intent.putExtra(EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM, dpcInfo.checkSum);
        intent.putExtra(EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION,
                dpcInfo.downloadUrl);

        Log.i(TAG, "Provisioning device owner using NEW workflow while running as user "
                + getUserId() + ". DPC: " + dpcInfo + ". Intent: " + intent);

        startActivityForResult(intent, REQUEST_CODE_SET_DO);
    }

    private void disableSelfAndFinish() {
        // Remove this activity from the package manager.
        PackageManager pm = getPackageManager();
        ComponentName name = new ComponentName(this, DefaultActivity.class);
        Log.i(TAG, "Disabling itself (" + name + ") for user " + getUserId());
        pm.setComponentEnabledSetting(name, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);

        finish();
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
            String warning = getString(R.string.do_failure_message);
            showErrorMessage("onActivityResult(): got invalid result code "
                    + resultCodeToString(resultCode) + "\n" + warning);
             // TODO(b/170333009): add a button to factory reset
            return;
        }
        Log.i(TAG, "Device owner mode provisioned, nothing left to do...");
        finishSetup();
        // TODO(b/170333009): must call ManagedProvisioning again with intent with
        // PROVISION_FINALIZATION_INSIDE_SUW action and DEFAULT category.
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
