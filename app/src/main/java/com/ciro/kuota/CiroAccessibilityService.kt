package com.ciro.kuota

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

            val pkg = event.packageName?.toString() ?: return

            // Skip our own app, system UI, launchers, keyboard
            if (pkg == packageName || pkg == "com.android.systemui" || pkg.contains("launcher") || pkg.contains("inputmethod")) {
                if (currentForegroundPackage != null && QuotaManager.isAppBlockMonitored(applicationContext, currentForegroundPackage!!)) {
                    recordSessionTime(currentForegroundPackage!!)
                }
                currentForegroundPackage = null
                return
            }

            // 1. Deteksi Cepat Domain Dewasa & Pornografi (missav.ai, pornhub, dll)
            if (QuotaManager.isAdultFilterActive(applicationContext)) {
                // Cek teks langsung pada event
                for (t in event.text) {
                    val str = t?.toString() ?: continue
                    if (QuotaManager.isAdultDomain(str)) {
                        triggerBlock("Browser (Situs Terlarang)", "🚨 Akses ke situs dewasa ($str) diblokir otomatis demi menjaga fokus & privasi Tuan Ciro.")
                        return
                    }
                }

                // Jika sedang membuka browser, pindai URL bar dan elemen jendela
                if (QuotaManager.isBrowserPackage(pkg)) {
                    val root = try { rootInActiveWindow ?: event.source } catch (_: Throwable) { null }
                    val detectedAdult = scanForAdultContent(root)
                    if (detectedAdult != null) {
                        triggerBlock("Browser (Situs Dewasa)", "🚨 Akses ke domain dewasa ($detectedAdult) diblokir otomatis oleh CiroKuota!")
                        return
                    }
                }
            }

            // 2. Monitoring Perubahan Jendela & Sesi Aplikasi
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (pkg != currentForegroundPackage) {
                    if (currentForegroundPackage != null && QuotaManager.isAppBlockMonitored(applicationContext, currentForegroundPackage!!)) {
                        recordSessionTime(currentForegroundPackage!!)
                    }
                    currentForegroundPackage = pkg
                    sessionStartTime = System.currentTimeMillis()

                    // Immediate check when entering target app
                    if (QuotaManager.isAppBlockMonitored(applicationContext, pkg)) {
                        QuotaManager.incrementLaunchCount(applicationContext, pkg)
                        checkAndEnforceBlock(pkg)
                    }
                }
            }
        } catch (_: Throwable) {
            // Anti-crash: Layanan aksesibilitas tidak boleh crash agar tidak dicabut oleh OS Android
        }
    }

    private fun scanForAdultContent(node: AccessibilityNodeInfo?, depth: Int = 0): String? {
        if (node == null || depth > 8) return null
        try {
            val text = node.text?.toString()
            if (!text.isNullOrBlank() && QuotaManager.isAdultDomain(text)) {
                return text
            }
            val desc = node.contentDescription?.toString()
            if (!desc.isNullOrBlank() && QuotaManager.isAdultDomain(desc)) {
                return desc
            }

            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
                val found = scanForAdultContent(child, depth + 1)
                try { child.recycle() } catch (_: Throwable) {}
                if (found != null) return found
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                try {
                    delay(1000)
                    val pkg = currentForegroundPackage
                    if (pkg != null && QuotaManager.isAppBlockMonitored(applicationContext, pkg)) {
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
            if (QuotaManager.isTempPassActive(applicationContext)) return

            val now = System.currentTimeMillis()
            if (now - lastBlockedTime < 1500) return // Debounce

            val isKillswitch = QuotaManager.isKillswitchActive(applicationContext)
            val quotaStatus = QuotaManager.getQuotaStatus(applicationContext)
            val isCondByKuota = QuotaManager.isCondByKuotaActive(applicationContext) && (QuotaManager.isAutoCutoffActive(applicationContext) && quotaStatus.isLimitReached)
            val manualUntil = QuotaManager.getManualLockUntil(applicationContext)
            val isManualLocked = manualUntil > now

            val isMonitored = QuotaManager.isAppBlockMonitored(applicationContext, pkg)
            if (!isMonitored && !isKillswitch && !isCondByKuota) return

            val appName = QuotaManager.getAppDisplayName(applicationContext, pkg)
            var reason: String? = null

            // Periksa kondisi satu per satu
            val isCondByTime = QuotaManager.isCurrentTimeInSchedule(applicationContext)
            val isCondByLaunch = QuotaManager.isCondByLaunchActive(applicationContext)
            val maxLaunches = QuotaManager.getMaxLaunchesPerDay(applicationContext)
            val launchCount = QuotaManager.getLaunchCountToday(applicationContext, pkg)

            val isCondByDuration = QuotaManager.isCondByDurationActive(applicationContext)
            val sessionLimitSec = QuotaManager.getAppBlockSessionLimitMinutes(applicationContext) * 60L

            when {
                isKillswitch -> {
                    reason = "🚨 Stop Data Aktif! Akses ke $appName sementara dihentikan total."
                }
                isManualLocked -> {
                    val remainingMin = ((manualUntil - now) / 60000) + 1
                    reason = "🔒 Kunci Ketat (Strict Mode) aktif! $appName terkunci selama $remainingMin menit lagi."
                }
                isCondByKuota -> {
                    reason = "📊 By Kuota: Budget Kuota Wi-Fi Hari Ini (${quotaStatus.budgetMb.toInt()} MB) telah habis! Akses ke $appName dihentikan otomatis."
                }
                isCondByTime -> {
                    reason = "🕒 By Time: $appName sedang berada di dalam jadwal jam gembok / tidur."
                }
                isCondByLaunch && launchCount > maxLaunches -> {
                    reason = "🔢 By Launch: Batas membuka $appName hari ini (maks $maxLaunches kali) telah habis!"
                }
                isCondByDuration && currentSessionSeconds >= sessionLimitSec -> {
                    reason = "⏳ By Duration: Batas Waktu Sesi $appName (${sessionLimitSec / 60} menit) telah tercapai!"
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
