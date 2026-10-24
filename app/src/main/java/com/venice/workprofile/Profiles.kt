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

    /** Not exposed in every compile SDK; used when available. */
    private val getIdentifier = runCatching {
        UserHandle::class.java.getMethod("getIdentifier")
    }.getOrNull()

    fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    fun admin(context: Context): ComponentName = WorkProfileAdmin.componentName(context)

    fun isAdminActive(context: Context): Boolean =
        runCatching { dpm(context).isAdminActive(admin(context)) }.getOrDefault(false)

    fun isProfileOwnerHere(context: Context): Boolean =
        runCatching { dpm(context).isProfileOwnerApp(context.packageName) }.getOrDefault(false)

    fun isDeviceOwnerHere(context: Context): Boolean =
        runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    /** User id behind a handle. UserHandle.hashCode() returns the raw user id. */
    fun userIdOf(user: UserHandle): Int =
        runCatching { getIdentifier?.invoke(user) as? Int }.getOrNull() ?: user.hashCode()

    fun currentUserId(): Int = userIdOf(Process.myUserHandle())

    /** True when the process runs inside a managed profile. */
    fun isRunningInProfile(context: Context): Boolean {
        if (isProfileOwnerHere(context)) return true
        val um = context.getSystemService(UserManager::class.java) ?: return false
        val managed: Boolean? = runCatching {
            UserManager::class.java
                .getMethod("isManagedProfile")
                .invoke(um) as? Boolean
        }.getOrNull()
        return managed ?: false
    }

    /** Other users that are managed profiles, excluding the calling user. */
    fun otherProfiles(context: Context): List<UserHandle> {
        val um = context.getSystemService(UserManager::class.java) ?: return emptyList()
        val users: List<UserHandle> =
            runCatching { um.userProfiles }.getOrNull() ?: return emptyList()
        val me = Process.myUserHandle()
        return users.filter { it != me }.filter { isManagedProfile(context, it) }
    }

    private fun isManagedProfile(context: Context, user: UserHandle): Boolean {
        val um = context.getSystemService(UserManager::class.java) ?: return false
        val managed: Boolean? = runCatching {
            UserManager::class.java
                .getMethod("isManagedProfile", INT_CLASS)
                .invoke(um, userIdOf(user)) as? Boolean
        }.getOrNull()
        if (managed != null) return managed
        return profileOwnerOf(context, user) != null
    }

    fun profileOwnerOf(context: Context, user: UserHandle): ComponentName? = runCatching {
        DevicePolicyManager::class.java
            .getMethod("getProfileOwnerAsUser", INT_CLASS)
            .invoke(dpm(context), userIdOf(user)) as? ComponentName
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
