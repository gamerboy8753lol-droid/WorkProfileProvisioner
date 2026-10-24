package com.venice.workprofile

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.venice.workprofile.databinding.ActivityMainBinding
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MIGRATE_PRIMARY_ACCOUNT = true
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var dpm: DevicePolicyManager

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1500L)
        }
    }

    private val adminLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) startProvisioning()
            else {
                toast(getString(R.string.admin_declined))
                render()
            }
        }

    private val provisionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                toast(getString(R.string.provision_ok))
                render()
            } else {
                toast(getString(R.string.provision_failed, describeFailure(result.resultCode, result.data)))
                render()
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startProvisioning(skipPermissionCheck = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        dpm = getSystemService(DevicePolicyManager::class.java)
        binding.removeLink.setOnClickListener { confirmRemoval() }
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.postDelayed(poll, 1500L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    // ------------------------------------------------------------- rendering

    private fun render() {
        val inProfile = Profiles.isRunningInProfile(this)
        val isOwner = Profiles.isProfileOwnerHere(this)
        val adminActive = Profiles.isAdminActive(this)
        val existing = if (inProfile) emptyList() else Profiles.otherProfiles(this)
        val owners = existing.mapNotNull { Profiles.profileOwnerOf(this, it)?.flattenToShortString() }

        binding.diagnostics.text = getString(
            R.string.diag,
            UserHandle.myUserId(),
            adminActive,
            isOwner,
            existing.size,
            owners.joinToString().ifEmpty { "-" }
        )

        binding.removeLink.visibility = if (isOwner) View.VISIBLE else View.GONE

        when {
            inProfile && isOwner -> {
                status(
                    getString(R.string.state_active),
                    getString(R.string.state_active_detail, Profiles.profileName(this))
                )
                button(getString(R.string.action_open_settings), true) { openSettings() }
            }

            inProfile -> {
                status(getString(R.string.state_in_profile_not_owner), getString(R.string.state_idle_detail))
                button(getString(R.string.action_provision), false) {}
            }

            existing.isNotEmpty() -> {
                status(
                    getString(R.string.state_exists),
                    getString(R.string.state_exists_detail, owners.firstOrNull() ?: "unknown")
                )
                button(getString(R.string.action_open_settings), true) { openSettings() }
            }

            !adminActive -> {
                status(getString(R.string.state_no_admin), getString(R.string.state_no_admin_detail))
                button(getString(R.string.action_grant_admin), true) { requestAdmin() }
            }

            else -> {
                status(getString(R.string.state_idle), getString(R.string.state_idle_detail))
                button(getString(R.string.action_provision), true) { startProvisioning() }
            }
        }
    }

    private fun status(title: String, detail: String) {
        binding.statusTitle.text = title
        binding.statusDetail.text = detail
    }

    private fun button(label: String, enabled: Boolean, onClick: () -> Unit) {
        binding.actionButton.text = label
        binding.actionButton.isEnabled = enabled
        binding.actionButton.setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------- flow

    private fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Profiles.admin(this@MainActivity))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_explanation))
        }
        if (intent.resolveActivity(packageManager) == null) {
            toast(getString(R.string.no_handler)); return
        }
        adminLauncher.launch(intent)
    }

    private fun startProvisioning(skipPermissionCheck: Boolean = false) {
        if (Profiles.otherProfiles(this).isNotEmpty()) {
            render(); return
        }

        if (MIGRATE_PRIMARY_ACCOUNT && !skipPermissionCheck &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.GET_ACCOUNTS)
            return
        }

        if (!Profiles.isAdminActive(this)) {
            requestAdmin(); return
        }

        val admin = Profiles.admin(this)
        val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin)
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME, packageName)
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, false)
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED, true)
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_LOCALE, Locale.getDefault().toLanguageTag())
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_TIME_ZONE, TimeZone.getDefault().id)
            putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                Bundle().apply {
                    putString(WorkProfileAdmin.EXTRA_ORG_NAME, getString(R.string.org_name))
                    putString(WorkProfileAdmin.EXTRA_PROFILE_NAME, getString(R.string.profile_name))
                }
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && MIGRATE_PRIMARY_ACCOUNT) {
                googleAccount()?.let {
                    putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE, it)
                }
            }
        }

        if (intent.resolveActivity(packageManager) == null) {
            toast(getString(R.string.no_handler)); return
        }

        status(getString(R.string.state_provisioning), getString(R.string.state_provisioning_detail))
        provisionLauncher.launch(intent)
    }

    private fun googleAccount(): Account? = runCatching {
        AccountManager.get(this).getAccountsByType("com.google").firstOrNull()
    }.getOrNull()

    /** Reads whatever failure key the platform put in the result, without relying on constants. */
    private fun describeFailure(resultCode: Int, data: Intent?): String {
        val extras = data?.extras
        if (extras != null) {
            for (key in arrayOf(
                "android.app.extra.PROVISIONING_ERROR",
                "android.app.extra.PROVISIONING_STATUS",
                "android.app.extra.PROVISIONING_RESULT"
            )) {
                extras.get(key)?.let { return "$key=$it" }
            }
        }
        return "resultCode=$resultCode"
    }

    // ------------------------------------------------------------- actions

    private fun openSettings() {
        for (intent in listOf(
            Intent("android.settings.MANAGED_PROFILE_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(packageManager) != null) {
                runCatching { startActivity(intent) }
                return
            }
        }
        toast(getString(R.string.no_handler))
    }

    private fun confirmRemoval() {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_remove)
            .setMessage(R.string.remove_detail)
            .setPositiveButton(R.string.action_remove) { _, _ ->
                runCatching { dpm.removeActiveAdmin(Profiles.admin(this)) }
                toast(getString(R.string.removed))
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
