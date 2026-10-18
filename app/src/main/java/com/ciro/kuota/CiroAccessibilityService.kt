package com.ciro.kuota

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*

class CiroAccessibilityService : AccessibilityService() {

    private var currentForegroundPackage: String? = null
    private var sessionStartTime: Long = 0L
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tickerJob: Job? = null
    private var lastBlockedTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            startTicker()
        } catch (_: Throwable) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            val pkg = event.packageName?.toString() ?: return

            // Skip our own app, system UI, launchers, keyboard
            if (pkg == packageName || pkg == "com.android.systemui" || pkg.contains("launcher") || pkg.contains("inputmethod")) {
                if (currentForegroundPackage != null && QuotaManager.isTargetMonitored(applicationContext, currentForegroundPackage!!)) {
                    recordSessionTime(currentForegroundPackage!!)
                }
                currentForegroundPackage = null
                return
            }

            if (pkg != currentForegroundPackage) {
                if (currentForegroundPackage != null && QuotaManager.isTargetMonitored(applicationContext, currentForegroundPackage!!)) {
                    recordSessionTime(currentForegroundPackage!!)
                }
                currentForegroundPackage = pkg
                sessionStartTime = System.currentTimeMillis()

                // Immediate check when entering target app
                if (QuotaManager.isTargetMonitored(applicationContext, pkg)) {
                    checkAndEnforceBlock(pkg)
                }
            }
        } catch (_: Throwable) {
            // Anti-crash: Layanan aksesibilitas tidak boleh crash agar tidak dicabut oleh OS Android
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                try {
                    delay(1000)
                    val pkg = currentForegroundPackage
                    if (pkg != null && QuotaManager.isTargetMonitored(applicationContext, pkg)) {
                        val now = System.currentTimeMillis()
                        val sessionSeconds = (now - sessionStartTime) / 1000
                        QuotaManager.addAppUsageSeconds(applicationContext, pkg, 1)

                        checkAndEnforceBlock(pkg, sessionSeconds)
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun recordSessionTime(pkg: String) {
        try {
            val elapsed = (System.currentTimeMillis() - sessionStartTime) / 1000
            if (elapsed > 0) {
                QuotaManager.addAppUsageSeconds(applicationContext, pkg, elapsed)
            }
        } catch (_: Throwable) {}
    }

    private fun checkAndEnforceBlock(pkg: String, currentSessionSeconds: Long = 0) {
        try {
            if (!QuotaManager.isAppLockActive(applicationContext)) return

            val now = System.currentTimeMillis()
            if (now - lastBlockedTime < 1500) return // Debounce

            val isKillswitch = QuotaManager.isKillswitchActive(applicationContext)
            val quotaStatus = QuotaManager.getQuotaStatus(applicationContext)
            val isAutoCutoff = QuotaManager.isAutoCutoffActive(applicationContext) && quotaStatus.isLimitReached
            val manualUntil = QuotaManager.getManualLockUntil(applicationContext)
            val isManualLocked = manualUntil > now

            val isInstagram = QuotaManager.isInstagram(pkg)
            val isBrowser = QuotaManager.isBrowser(pkg)

            val igLimitSec = QuotaManager.getIgSessionLimitMinutes(applicationContext) * 60L
            val browserLimitSec = QuotaManager.getBrowserSessionLimitMinutes(applicationContext) * 60L

            val appName = when {
                isInstagram -> "Instagram"
                isBrowser -> "Browser Web"
                else -> "Aplikasi Target"
            }

            var reason: String? = null

            when {
                isKillswitch -> {
                    reason = "🚨 Killswitch Darurat sedang aktif. Seluruh akses internet ke aplikasi hiburan dimatikan."
                }
                isManualLocked -> {
                    val remainingMin = ((manualUntil - now) / 60000) + 1
                    reason = "🔒 Kunci Sesi Khilaf aktif! Aplikasi terkunci selama $remainingMin menit lagi."
                }
                isAutoCutoff -> {
                    reason = "📊 Budget Kuota Wi-Fi Hari Ini (${quotaStatus.budgetMb.toInt()} MB) telah habis! Internet ke $appName dihentikan otomatis."
                }
                isInstagram && currentSessionSeconds >= igLimitSec -> {
                    reason = "⏳ Batas Waktu Scrolling Instagram (${igLimitSec / 60} menit) telah tercapai! Kuota Wi-Fi terselamatkan."
                }
                isBrowser && currentSessionSeconds >= browserLimitSec -> {
                    reason = "⏳ Batas Waktu Nonton Browser (${browserLimitSec / 60} menit) telah tercapai! Hindari streaming video boros kuota."
                }
            }

            if (reason != null) {
                lastBlockedTime = now
                triggerBlock(appName, reason)
            }
        } catch (_: Throwable) {}
    }

    private fun triggerBlock(appName: String, reason: String) {
        try {
            // Step 1: Throw back to Home
            performGlobalAction(GLOBAL_ACTION_HOME)

            // Step 2: Show stylish BlockedActivity screen
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val blockIntent = Intent(applicationContext, BlockedActivity::class.java).apply {
                        putExtra("EXTRA_APP_NAME", appName)
                        putExtra("EXTRA_REASON", reason)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(blockIntent)
                } catch (_: Throwable) {}
            }, 150)
        } catch (_: Throwable) {}
    }

    override fun onInterrupt() {
        tickerJob?.cancel()
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
