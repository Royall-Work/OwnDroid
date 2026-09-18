package com.bintianqi.owndroid

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build.VERSION
import androidx.core.content.ContextCompat
import com.bintianqi.owndroid.utils.DhizukuException
import com.bintianqi.owndroid.utils.NotificationUtils
import com.bintianqi.owndroid.utils.getPrivilegeStatus
import org.lsposed.hiddenapibypass.HiddenApiBypass

class MyApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        if (VERSION.SDK_INT >= 28) HiddenApiBypass.setHiddenApiExemptions("")
        container = AppContainer(this)
        val ph = container.privilegeHelper
        val ps = container.privilegeState

        if (VERSION.SDK_INT >= 26) {
            ContextCompat.registerReceiver(
                this,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action == DevicePolicyManager.ACTION_APPLICATION_DELEGATION_SCOPES_CHANGED) {
                            ph.refreshDelegatedScopes()
                            ps.value = getPrivilegeStatus(
                                ph.dpm, ph.dar, ph.dhizuku,
                ph.delegatedScopes
                            )
                        }
                    }
                },
                IntentFilter(DevicePolicyManager.ACTION_APPLICATION_DELEGATION_SCOPES_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        try {
            ph.refreshDelegatedScopes()
            ps.value = getPrivilegeStatus(
                ph.dpm, ph.dar, ph.dhizuku,
                                ph.delegatedScopes
            )
        } catch (e: DhizukuException) {
            ph.refreshDelegatedScopes()
            ps.value = getPrivilegeStatus(
                ph.myDpm,
                if (ph.delegatedAdmin) null else ph.myDar,
                false,
                ph.delegatedScopes
            )
            if (ps.value.activated) { // Device owner transferred from Dhizuku
                container.settingsRepo.update { it.privilege.dhizuku = false }
                ph.dhizuku = false
            } else {
                container.dhizukuErrorState.value = e.reason
            }
        }
        NotificationUtils.createChannels(this)
    }
}
