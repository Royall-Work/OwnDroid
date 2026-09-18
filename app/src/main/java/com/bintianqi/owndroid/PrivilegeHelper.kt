package com.bintianqi.owndroid

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build.VERSION
import com.bintianqi.owndroid.utils.DhizukuError
import com.bintianqi.owndroid.utils.DhizukuException
import com.bintianqi.owndroid.utils.binderWrapperDevicePolicyManager
import com.rosan.dhizuku.api.Dhizuku
import kotlinx.coroutines.flow.MutableStateFlow

class PrivilegeHelper(
    val context: Context, var dhizuku: Boolean, val dhizukuError: MutableStateFlow<DhizukuError?>
) {
    val myDpm = context.getSystemService(DevicePolicyManager::class.java)!!
    val myDar = ComponentName(context, Receiver::class.java)

    private var _delegatedScopes = emptySet<String>()

    init {
        refreshDelegatedScopes()
    }

    val delegatedScopes: Set<String>
        get() = _delegatedScopes

    val delegatedAdmin: Boolean
        get() = !dhizuku && _delegatedScopes.isNotEmpty() && !isOwnAdmin()

    val canManageUserControlDisabledPackages: Boolean
        get() = !delegatedAdmin ||
                context.checkSelfPermission(
                    "android.permission.MANAGE_DEVICE_POLICY_APPS_CONTROL"
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    val delegatedDar: ComponentName?
        get() = if (delegatedAdmin) null else dar

    val dpm: DevicePolicyManager
        get() {
            return if (dhizuku) getDhizukuDpm() else myDpm
        }

    val dar: ComponentName
        get() {
            return if (dhizuku) Dhizuku.getOwnerComponent() else myDar
        }

    class SafeDpmCallScope(
        val dpm: DevicePolicyManager,
        val dar: ComponentName,
        val delegatedDar: ComponentName?,
        val admin: ComponentName? = delegatedDar
    )

    fun refreshDelegatedScopes() {
        _delegatedScopes = if (
            VERSION.SDK_INT >= 26 &&
            !dhizuku &&
            !isOwnAdmin()
        ) {
            try {
                myDpm.getDelegatedScopes(null, context.packageName).toSet()
            } catch (_: SecurityException) {
                emptySet()
            }
        } else {
            emptySet()
        }
    }

    private fun isOwnAdmin(): Boolean {
        return myDpm.isDeviceOwnerApp(context.packageName) ||
                (VERSION.SDK_INT >= 24 && myDpm.isProfileOwnerApp(context.packageName))
    }

    fun safeDpmCall(block: SafeDpmCallScope.() -> Unit) {
        try {
            SafeDpmCallScope(dpm, dar, delegatedDar).block()
        } catch (e: DhizukuException) {
            dhizukuError.value = e.reason
        }
    }

    private fun getDhizukuDpm(): DevicePolicyManager {
        try {
            if (!Dhizuku.init(context)) throw DhizukuException(DhizukuError.Init)
            if (!Dhizuku.isPermissionGranted()) throw DhizukuException(DhizukuError.Permission)
            return binderWrapperDevicePolicyManager(context)
        } catch(e: Exception) {
            if (e !is DhizukuException) {
                throw DhizukuException(DhizukuError.Binder, e)
            }
            throw e
        }
    }
}
