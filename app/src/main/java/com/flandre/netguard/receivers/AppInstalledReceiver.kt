package com.flandre.netguard.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flandre.netguard.services.AppMonitorService
import com.flandre.netguard.ui.MainActivity

class AppInstalledReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MainActivity.ACTION_APP_INSTALLED) return
        val csv = intent.getStringExtra(MainActivity.EXTRA_PACKAGES_CSV) ?: return
        csv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { AppMonitorService.showNewAppNotification(context, it) }
    }
}
