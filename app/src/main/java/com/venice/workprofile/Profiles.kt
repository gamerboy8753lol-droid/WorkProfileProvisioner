package com.venice.workprofile

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

/** Read-only introspection helpers. */
object Profiles {

    private const val PREFS = "workprofile"
    private const val KEY_PROFILE_NAME = "profile_name"

    /** Non-null primitive class, for reflection signatures. */
    private val INT_CLASS: Class<*> = java.lang.Integer.TYPE

    fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    fun admin(context: Context): ComponentName = WorkProfileAdmin.componentName(context)

    fun isAdminActive(context: Context): Boolean =
        runCatching { dpm(context).isAdminActive(admin(context)) }.getOrDefault(false)

    fun isProfileOwnerHere(context: Context): Boolean =
        runCatching { dpm(context).isProfileOwnerApp(context.packageName) }.getOrDefault(false)

    fun isDeviceOwnerHere(context: Context): Boolean =
        runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    /** True when the process runs inside a managed profile. */
    fun isRunningInProfile(context: Context): Boolean {
        val um = context.getSystemService(UserManager::class.java)
        if (um != null) {
            val managed: Boolean? = runCatching {
                UserManager::class.java
                    .getMethod("isManagedProfile")
                    .invoke(um) as? Boolean
            }.getOrNull()
            if (managed != null) return managed
        }
        return UserHandle.myUserId() != 0
    }

    /** Other users that are managed profiles, excluding the calling user. */
    fun otherProfiles(context: Context): List<UserHandle> {
        val um = context.getSystemService(UserManager::class.java) ?: return emptyList()
        val users: List<UserHandle> =
            runCatching { um.userProfiles }.getOrNull() ?: return emptyList()
        return users
            .filter { it != Process.myUserHandle() }
            .filter { isManagedProfile(context, it) }
    }

    private fun isManagedProfile(context: Context, user: UserHandle): Boolean {
        val um = context.getSystemService(UserManager::class.java) ?: return false
        val managed: Boolean? = runCatching {
            UserManager::class.java
                .getMethod("isManagedProfile", INT_CLASS)
                .invoke(um, user.identifier) as? Boolean
        }.getOrNull()
        if (managed != null) return managed
        return profileOwnerOf(context, user) != null
    }

    fun profileOwnerOf(context: Context, user: UserHandle): ComponentName? = runCatching {
        DevicePolicyManager::class.java
            .getMethod("getProfileOwnerAsUser", INT_CLASS)
            .invoke(dpm(context), user.identifier) as? ComponentName
    }.getOrNull()

    fun saveProfileName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILE_NAME, name).apply()
    }

    fun profileName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROFILE_NAME, null)
            ?: context.getString(R.string.profile_name)
}
