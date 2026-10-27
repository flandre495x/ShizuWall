package com.flandre.netguard.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.flandre.netguard.BuildConfig
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

object ShizukuUserServiceManager {

    private const val TAG = "ShizukuUserService"
    private const val BIND_TIMEOUT_MS = 10_000L

    private const val SERVICE_REVISION = 4
    private val SERVICE_VERSION = BuildConfig.VERSION_CODE * 100 + SERVICE_REVISION

    private val lock = Mutex()

    @Volatile
    private var service: INetGuardUserService? = null

    @Volatile
    private var pending: CancellableContinuation<INetGuardUserService?>? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, NetGuardUserService::class.java.name)
        )
            .daemon(true)
            .processNameSuffix("uidfw")
            .debuggable(BuildConfig.DEBUG)
            .version(SERVICE_VERSION)
    }

    suspend fun obtain(): INetGuardUserService? {
        alive()?.let { return it }
        return lock.withLock {
            alive() ?: bind()?.also { service = it }
        }
    }

    fun peek(): INetGuardUserService? = alive()

    private fun alive(): INetGuardUserService? =
        service?.takeIf { runCatching { it.asBinder().pingBinder() }.getOrDefault(false) }

    private suspend fun bind(): INetGuardUserService? {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val bound = binder
                    ?.takeIf { runCatching { it.pingBinder() }.getOrDefault(false) }
                    ?.let { INetGuardUserService.Stub.asInterface(it) }
                service = bound
                pending?.takeIf { it.isActive }?.resume(bound)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }

        val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                pending = continuation
                try {
                    Shizuku.bindUserService(args, connection)
                } catch (t: Throwable) {
                    Log.w(TAG, "bindUserService failed", t)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        pending = null

        if (bound == null) {
            runCatching { Shizuku.unbindUserService(args, connection, false) }
            Log.w(TAG, "user service did not bind within ${BIND_TIMEOUT_MS}ms")
        }
        return bound
    }
}
