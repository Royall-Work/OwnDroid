package com.bintianqi.owndroid

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import com.bintianqi.owndroid.feature.applications.ExistingAppsRelayEntry
import com.bintianqi.owndroid.feature.applications.ExistingAppsRelayResult

object CrossProfileAppsRelay {
    const val ACTION_REQUEST_PRIMARY = "com.bintianqi.owndroid.action.REQUEST_PRIMARY_APPS"
    const val ACTION_REQUEST_MANAGED = "com.bintianqi.owndroid.action.REQUEST_MANAGED_APPS"
    const val ACTION_RESULT_TO_MANAGED = "com.bintianqi.owndroid.action.RESULT_TO_MANAGED"
    const val ACTION_RESULT_TO_PRIMARY = "com.bintianqi.owndroid.action.RESULT_TO_PRIMARY"
    const val EXTRA_REQUEST_ID = "relay_request_id"
    const val EXTRA_PACKAGES = "relay_packages"
    const val EXTRA_LABELS = "relay_labels"
    const val EXTRA_INSTALLED = "relay_installed"
    const val EXTRA_ERROR = "relay_error"
    private const val PREFS = "cross_profile_app_relay"
    private const val FILTERS_CONFIGURED = "filters_configured"
    private const val USER_ID_RANGE = 100000

    fun configureComponents(context: Context) {
        val states = when (Process.myUid() / USER_ID_RANGE) {
            0 -> listOf(true, false, false, true)
            10 -> listOf(false, true, true, false)
            else -> listOf(false, false, false, false)
        }
        setComponent(context, PrimaryAppsRequestActivity::class.java, states[0])
        setComponent(context, ManagedAppsRequestActivity::class.java, states[1])
        setComponent(context, ManagedAppsResultActivity::class.java, states[2])
        setComponent(context, PrimaryAppsResultActivity::class.java, states[3])
    }

    private fun setComponent(context: Context, type: Class<out Activity>, enabled: Boolean) {
        try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, type),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            android.util.Log.w("CrossProfileAppsRelay", "Unable to configure " + type.simpleName, e)
        }
    }

    fun intentFilters(): List<Pair<IntentFilter, Int>> = listOf(
        createFilter(ACTION_REQUEST_PRIMARY) to DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED,
        createFilter(ACTION_RESULT_TO_MANAGED) to DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT,
        createFilter(ACTION_REQUEST_MANAGED) to DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT,
        createFilter(ACTION_RESULT_TO_PRIMARY) to DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
    )

    private fun createFilter(action: String) = IntentFilter(action).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    fun ensureIntentFilters(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        if (Process.myUid() / USER_ID_RANGE != 10) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(FILTERS_CONFIGURED, false)) return
        intentFilters().forEach { (filter, direction) -> dpm.addCrossProfileIntentFilter(admin, filter, direction) }
        prefs.edit().putBoolean(FILTERS_CONFIGURED, true).apply()
    }

    fun createRequestIntent(context: Context, currentUserId: Int, requestId: String): Intent {
        val action = when (currentUserId) {
            10 -> ACTION_REQUEST_PRIMARY
            0 -> ACTION_REQUEST_MANAGED
            else -> throw IllegalArgumentException("Only users 0 and 10 are supported")
        }
        return Intent(action).apply {
            setPackage(context.packageName)
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(EXTRA_REQUEST_ID, requestId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun collectApps(context: Context, sourceUserId: Int): List<ExistingAppsRelayEntry> {
        val pm = context.packageManager
        val packageInfos = linkedMapOf<String, ApplicationInfo>()
        if (sourceUserId == 0) {
            pm.getInstalledApplications(0).forEach { info ->
                if (info.flags and ApplicationInfo.FLAG_SYSTEM == 0) packageInfos[info.packageName] = info
            }
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                try {
                    val dpm = context.getSystemService(DevicePolicyManager::class.java)!!
                    val admin = ComponentName(context, Receiver::class.java)
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        dpm.getKeepUninstalledPackages(admin).orEmpty().forEach { name ->
                            if (name !in packageInfos) {
                                try {
                                    val info = pm.getApplicationInfo(name, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                                    if (info.flags and ApplicationInfo.FLAG_SYSTEM == 0) packageInfos[name] = info
                                } catch (_: PackageManager.NameNotFoundException) {
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("CrossProfileAppsRelay", "Unable to read retained packages", e)
                }
            }
        } else {
            pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES).forEach { info ->
                if (info.flags and ApplicationInfo.FLAG_SYSTEM == 0) packageInfos[info.packageName] = info
            }
        }
        return packageInfos.values.map { info ->
            val label = try { info.loadLabel(pm).toString().ifBlank { info.packageName } }
            catch (_: Exception) { info.packageName }
            ExistingAppsRelayEntry(info.packageName, label, info.flags and ApplicationInfo.FLAG_INSTALLED != 0)
        }.sortedBy { it.label.lowercase() }
    }

    fun sendResult(activity: Activity, requestIntent: Intent, sourceUserId: Int) {
        val requestId = requestIntent.getStringExtra(EXTRA_REQUEST_ID)
        if (requestId.isNullOrBlank()) {
            activity.finish()
            return
        }
        val responseAction = if (sourceUserId == 0) ACTION_RESULT_TO_MANAGED else ACTION_RESULT_TO_PRIMARY
        val response = Intent(responseAction).apply {
            setPackage(activity.packageName)
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
        try {
            val entries = collectApps(activity, sourceUserId)
            response.putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(entries.map { it.packageName }))
            response.putStringArrayListExtra(EXTRA_LABELS, ArrayList(entries.map { it.label }))
            response.putExtra(EXTRA_INSTALLED, BooleanArray(entries.size) { entries[it].installedInSource })
        } catch (e: Exception) {
            response.putExtra(EXTRA_ERROR, e.message ?: e.javaClass.simpleName)
        }
        activity.startActivity(response)
        activity.finish()
    }

    fun deliverResult(context: Context, intent: Intent, expectedUserId: Int, sourceUserId: Int) {
        if (Process.myUid() / USER_ID_RANGE != expectedUserId) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val packages = intent.getStringArrayListExtra(EXTRA_PACKAGES).orEmpty()
        val labels = intent.getStringArrayListExtra(EXTRA_LABELS).orEmpty()
        val installed = intent.getBooleanArrayExtra(EXTRA_INSTALLED) ?: BooleanArray(packages.size)
        val entries = packages.mapIndexed { index, name ->
            ExistingAppsRelayEntry(name, labels.getOrNull(index)?.takeIf { it.isNotBlank() } ?: name, installed.getOrElse(index) { false })
        }
        (context.applicationContext as MyApplication).container.existingAppsRelayResult.value =
            ExistingAppsRelayResult(requestId, sourceUserId, entries, intent.getStringExtra(EXTRA_ERROR))
    }
}

class PrimaryAppsRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (Process.myUid() / 100000 != 0) { finish(); return }
        CrossProfileAppsRelay.sendResult(this, intent, 0)
    }
}
class ManagedAppsRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (Process.myUid() / 100000 != 10) { finish(); return }
        CrossProfileAppsRelay.sendResult(this, intent, 10)
    }
}
class ManagedAppsResultActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        CrossProfileAppsRelay.deliverResult(this, intent, 10, 0)
        finish()
    }
}
class PrimaryAppsResultActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        CrossProfileAppsRelay.deliverResult(this, intent, 0, 10)
        finish()
    }
}
