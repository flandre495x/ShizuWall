package com.flandre.netguard.firewall

import android.content.Context
import android.util.Log
import com.flandre.netguard.WorkingMode
import com.flandre.netguard.shell.RootForegroundWatchSession
import com.flandre.netguard.shizuku.INetGuardForegroundListener
import com.flandre.netguard.shizuku.ShizukuUserServiceManager
import com.flandre.netguard.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

object ForegroundTaskWatcher {

    private const val TAG = "ForegroundTaskWatcher"
    private const val KEEPALIVE = "."

    @Volatile
    var isActive = false
        private set

    @Volatile
    var mode: WorkingMode? = null
        private set
    private var shizukuListener: INetGuardForegroundListener? = null

    suspend fun start(context: Context, onTask: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        stop()
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        val working = WorkingMode.fromName(prefs.getString(MainActivity.KEY_WORKING_MODE, null))
        val started = try {
            when (working) {
                WorkingMode.SHIZUKU -> startShizuku(onTask)
                WorkingMode.ROOT -> RootForegroundWatchSession.start(
                    PerUidFirewall.extractHelperDex(context).absolutePath
                ) { line -> dispatch(line, onTask) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Foreground watch failed to start", t)
            false
        }
        mode = working.takeIf { started }
        isActive = started
        started
    }

    fun stop() {
        isActive = false
        when (mode) {
            WorkingMode.SHIZUKU -> runCatching { ShizukuUserServiceManager.peek()?.stopForegroundWatch() }
            WorkingMode.ROOT -> RootForegroundWatchSession.stop()
            null -> Unit
        }
        shizukuListener = null
        mode = null
    }

    private suspend fun startShizuku(onTask: (String) -> Unit): Boolean {
        if (!Shizuku.pingBinder()) return false
        val service = ShizukuUserServiceManager.obtain() ?: return false
        val listener = object : INetGuardForegroundListener.Stub() {
            override fun onForegroundTask(task: String?) {
                dispatch(task, onTask)
            }
        }
        shizukuListener = listener
        service.startForegroundWatch(listener)
        return true
    }

    private fun dispatch(line: String?, onTask: (String) -> Unit) {
        val value = line?.trim().orEmpty()
        if (value.isEmpty() || value == KEEPALIVE) return
        if (value.startsWith("Error", ignoreCase = true)) {
            Log.w(TAG, "Foreground watch helper stopped: $value")
            isActive = false
            return
        }
        ForegroundTaskProbe.parse(value)?.let(onTask)
    }
}
