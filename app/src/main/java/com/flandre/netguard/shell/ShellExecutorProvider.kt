package com.flandre.netguard.shell

import android.content.Context
import com.flandre.netguard.WorkingMode
import com.flandre.netguard.ui.MainActivity
import com.flandre.netguard.shizuku.ShizukuShellExecutor

object ShellExecutorProvider {
    fun forContext(context: Context): ShellExecutor {
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        val modeName = prefs.getString(MainActivity.KEY_WORKING_MODE, WorkingMode.SHIZUKU.name)
        val executor = when (WorkingMode.fromName(modeName)) {
            WorkingMode.ROOT -> RootShellExecutor()
            else -> ShizukuShellExecutor()
        }
        return PerUidRoutingExecutor(context, executor)
    }
}
