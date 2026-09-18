package com.bintianqi.owndroid

import android.app.admin.DelegatedAdminReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.RequiresApi
import com.bintianqi.owndroid.utils.retrieveNetworkLogs
import com.bintianqi.owndroid.utils.retrieveSecurityLogs

@RequiresApi(29)
class DelegatedReceiver : DelegatedAdminReceiver() {
    override fun onNetworkLogsAvailable(
        context: Context,
        intent: Intent,
        batchToken: Long,
        networkLogsCount: Int
    ) {
        retrieveNetworkLogs(context.applicationContext as MyApplication, batchToken)
    }

    @RequiresApi(31)
    override fun onSecurityLogsAvailable(context: Context, intent: Intent) {
        retrieveSecurityLogs(context.applicationContext as MyApplication)
    }

    override fun onChoosePrivateKeyAlias(
        context: Context,
        intent: Intent,
        uid: Int,
        uri: Uri?,
        alias: String?
    ): String? {
        return null
    }
}
