package com.flandre.netguard.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.view.menu.MenuBuilder
import androidx.lifecycle.lifecycleScope
import com.flandre.netguard.R
import com.flandre.netguard.firewall.FirewallCommands
import com.flandre.netguard.services.AppMonitorService
import com.flandre.netguard.services.FloatingButtonService
import com.flandre.netguard.services.ForegroundDetectionService
import com.flandre.netguard.shell.RootShellExecutor
import com.flandre.netguard.shell.ShellExecutorProvider
import com.flandre.netguard.trackers.TrackerRegistry
import com.flandre.netguard.utils.ShizukuPackageResolver
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class SettingsActivity : BaseActivity() {

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)

        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setupSettingsChrome(R.id.settingsRoot, toolbar)
        setupToolbarMenu(toolbar)
        toolbar.setNavigationOnClickListener {
            if (isTaskRoot) {
                startActivity(Intent(this, MainActivity::class.java))
            }
            finish()
        }

        findViewById<LinearLayout>(R.id.rowAppearance).setOnClickListener {
            startActivity(Intent(this, AppearanceSettingsActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowFirewall).setOnClickListener {
            startActivity(Intent(this, FirewallSettingsActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowBackend).setOnClickListener {
            startActivity(Intent(this, BackendSettingsActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowNotifications).setOnClickListener {
            startActivity(Intent(this, NotificationsSettingsActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowSecurity).setOnClickListener {
            startActivity(Intent(this, SecuritySettingsActivity::class.java))
        }

        val infoButtonSwitch =
            findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchAppInfoButton)
        infoButtonSwitch.isChecked =
            sharedPreferences.getBoolean(MainActivity.KEY_SHOW_APP_INFO_BUTTON, true)
        infoButtonSwitch.setOnCheckedChangeListener { _, checked ->
            sharedPreferences.edit()
                .putBoolean(MainActivity.KEY_SHOW_APP_INFO_BUTTON, checked)
                .apply()
        }
        findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardTrackerDefinitions)
            .setOnClickListener { infoButtonSwitch.toggle() }

        val trackerSummary = findViewById<TextView>(R.id.tvTrackerDefinitionsSummary)
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                TrackerRegistry.trackers(this@SettingsActivity).size
            }
            trackerSummary.text = getString(R.string.tracker_db_description, count)
        }

        findViewById<LinearLayout>(R.id.btnGithub).setOnClickListener {
            val url = getString(R.string.github_url)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        tvVersion.text = getString(R.string.version_format, packageInfo.versionName)
    }

    @SuppressLint("RestrictedApi")
    private fun setupToolbarMenu(toolbar: MaterialToolbar) {
        toolbar.inflateMenu(R.menu.menu_settings)
        (toolbar.menu as? MenuBuilder)?.setOptionalIconsVisible(true)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reset_app -> {
                    showResetConfirmationDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun showResetConfirmationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.reset_app_title))
            .setMessage(getString(R.string.reset_app_message))
            .setPositiveButton(getString(R.string.reset)) { _, _ -> resetApp() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun resetApp() {
        lifecycleScope.launch {
            try {
                stopResetSensitiveComponents()

                val cleanupPerformed = withContext(Dispatchers.IO) {
                    performBestEffortFirewallCleanup()
                }
                if (!cleanupPerformed) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.firewall_cleanup_warning), Toast.LENGTH_LONG).show()
                }

                val clearRequested = requestSystemDataClear()
                if (clearRequested) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.app_reset_complete), Toast.LENGTH_SHORT).show()
                    delay(1200)
                    finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(0)
                    return@launch
                }

                val fallbackSuccess = withContext(Dispatchers.IO) { performManualDataClearFallback() }
                if (!fallbackSuccess) {
                    throw IllegalStateException("Manual reset fallback failed")
                }

                Toast.makeText(this@SettingsActivity, getString(R.string.app_reset_complete), Toast.LENGTH_SHORT).show()
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(0)
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, getString(R.string.reset_app_failed, e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopResetSensitiveComponents() {
        try { stopService(Intent(this, AppMonitorService::class.java)) } catch (_: Exception) {}
        try { stopService(Intent(this, ForegroundDetectionService::class.java)) } catch (_: Exception) {}
        try { FloatingButtonService.stop(this) } catch (_: Exception) {}
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancelAll() } catch (_: Exception) {}
    }

    private suspend fun performBestEffortFirewallCleanup(): Boolean {
        return try {
            if (!sharedPreferences.getBoolean(MainActivity.KEY_FIREWALL_ENABLED, false)) return true

            val activePackages = sharedPreferences.getStringSet(MainActivity.KEY_ACTIVE_PACKAGES, emptySet()) ?: emptySet()
            val selectedApps = sharedPreferences.getStringSet(MainActivity.KEY_SELECTED_APPS, emptySet()) ?: emptySet()
            val allPackages = (activePackages + selectedApps)
                .filterNot { it == packageName || ShizukuPackageResolver.isShizukuPackage(this, it) }

            val mode = sharedPreferences.getString(MainActivity.KEY_WORKING_MODE, "SHIZUKU") ?: "SHIZUKU"
            val canPerformCleanup = when (mode) {
                "SHIZUKU" -> {
                    try {
                        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
                    } catch (_: Throwable) {
                        false
                    }
                }
                "ROOT" -> RootShellExecutor.hasRootAccess()
                else -> false
            }

            if (!canPerformCleanup) return false

            val executor = ShellExecutorProvider.forContext(this)
            val chainResult = executor.exec(FirewallCommands.CHAIN3_DISABLE)

            executor.execBatch(
                FirewallCommands.unblockAll(allPackages)
            )

            chainResult.success
        } catch (_: Exception) {
            false
        }
    }

    private fun requestSystemDataClear(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.clearApplicationUserData() == true
        } catch (_: Exception) {
            false
        }
    }

    private fun performManualDataClearFallback(): Boolean {
        var ok = true

        ok = clearAllSharedPrefs(this) && ok

        try {
            val dp = createDeviceProtectedStorageContext()
            ok = clearAllSharedPrefs(dp) && ok
        } catch (_: Exception) {
            ok = false
        }

        try {
            for (dbName in databaseList()) {
                if (!deleteDatabase(dbName)) ok = false
            }
        } catch (_: Exception) {
            ok = false
        }

        for (dir in listOf(filesDir, cacheDir, codeCacheDir, noBackupFilesDir, externalCacheDir, getExternalFilesDir(null))) {
            ok = (dir?.listFiles()?.all { it.deleteRecursively() } ?: true) && ok
        }

        return ok
    }

    private fun clearAllSharedPrefs(context: Context): Boolean {
        var ok = true
        val sharedPrefsDir = File(context.filesDir.parentFile, "shared_prefs")
        val prefNames = sharedPrefsDir.listFiles()
            ?.mapNotNull { file ->
                val name = file.name
                if (name.endsWith(".xml")) name.removeSuffix(".xml") else null
            }
            ?.toSet()
            ?: emptySet()

        for (name in prefNames) {
            try {
                if (!context.deleteSharedPreferences(name)) {
                    context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                }
            } catch (_: Exception) {
                ok = false
            }
        }
        return ok
    }
}
