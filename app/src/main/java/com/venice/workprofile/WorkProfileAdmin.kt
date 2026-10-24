package com.venice.workprofile

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class WorkProfileAdmin : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "WorkProfileAdmin"
        const val EXTRA_ORG_NAME = "com.venice.workprofile.extra.ORG_NAME"
        const val EXTRA_PROFILE_NAME = "com.venice.workprofile.extra.PROFILE_NAME"

        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, WorkProfileAdmin::class.java)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin disabled")
    }

    /**
     * Runs on this receiver's instance INSIDE the new profile, after the system
     * finished creating it. The profile is still disabled here.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null) {
            Log.e(TAG, "no DevicePolicyManager")
            return
        }

        val admin = componentName(context)
        val extras = intent.getBundleExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        val org = extras?.getString(EXTRA_ORG_NAME) ?: context.getString(R.string.org_name)
        val name = extras?.getString(EXTRA_PROFILE_NAME) ?: context.getString(R.string.profile_name)

        runCatching { dpm.setOrganizationName(admin, org) }
            .onFailure { Log.w(TAG, "setOrganizationName: $it") }
        runCatching { dpm.setProfileName(admin, name) }
            .onFailure { Log.w(TAG, "setProfileName: $it") }
        runCatching { dpm.setShortSupportMessage(admin, context.getString(R.string.admin_description)) }

        runCatching { dpm.setCrossProfileCallerIdDisabled(admin, false) }
        runCatching { dpm.setCrossProfileContactsSearchDisabled(admin, false) }
        runCatching { dpm.setBluetoothContactSharingDisabled(admin, false) }

        Profiles.saveProfileName(context, name)

        // Mandatory: a fresh profile stays disabled until the DPC enables it.
        runCatching { dpm.setProfileEnabled(admin) }
            .onFailure {
                Log.e(TAG, "setProfileEnabled: $it")
                Toast.makeText(context, "Could not enable profile: ${it.message}", Toast.LENGTH_LONG).show()
            }

        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        runCatching { context.startActivity(launch) }
            .onFailure { Log.e(TAG, "launch failed: $it") }
    }
}
