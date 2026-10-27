package com.flandre.netguard.firewall

import android.content.Context
import android.util.Log
import com.flandre.netguard.WorkingMode
import com.flandre.netguard.shell.RootUidFirewallSession
import com.flandre.netguard.shizuku.ShizukuUserServiceManager
import com.flandre.netguard.ui.MainActivity
import com.flandre.netguard.utils.AppKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

object ForegroundTaskProbe {

    private const val TAG = "ForegroundTaskProbe"
    private const val COMMAND = "fg-task"
    private const val FAILURE_BACKOFF_MS = 60_000L

    @Volatile
    var isAvailable = false
        private set

    @Volatile
    private var retryAt = 0L

    suspend fun query(context: Context): String? = withContext(Dispatchers.IO) {
        if (!isAvailable && System.currentTimeMillis() < retryAt) return@withContext null
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        val raw = try {
            when (WorkingMode.fromName(prefs.getString(MainActivity.KEY_WORKING_MODE, null))) {
                WorkingMode.SHIZUKU ->
                    if (!Shizuku.pingBinder()) null
                    else ShizukuUserServiceManager.obtain()?.foregroundTask
                WorkingMode.ROOT -> RootUidFirewallSession.execute(
                    PerUidFirewall.extractHelperDex(context).absolutePath,
                    COMMAND
                )
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Foreground task query failed", t)
            null
        }
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.startsWith("Error (code 1)") || value.startsWith("Error:")) {
            isAvailable = false
            retryAt = System.currentTimeMillis() + FAILURE_BACKOFF_MS
            if (value.isNotEmpty()) Log.d(TAG, "Foreground task helper unavailable: $value")
            return@withContext null
        }
        isAvailable = true
        retryAt = 0L
        parse(value)
    }

    internal fun parse(value: String): String? {
        if (value.startsWith("Error", ignoreCase = true)) return null
        val separator = value.indexOf(':')
        if (separator <= 0) return null
        val userId = value.substring(0, separator).toIntOrNull() ?: return null
        val packageName = value.substring(separator + 1).trim()
        if (packageName.isEmpty()) return null
        return AppKey.of(userId, packageName)
    }
}
