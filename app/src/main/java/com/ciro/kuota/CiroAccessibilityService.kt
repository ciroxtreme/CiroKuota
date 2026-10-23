package com.ciro.kuota

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
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

            // 0. BENTENG PENGATURAN SISTEM (SETTINGS GUARD)
            // Melindungi CiroKuota agar pengaturan HP (Settings/Aksesibilitas/Device Admin) tidak diotak-atik
            if (pkg == "com.android.settings") {
                if (!QuotaManager.isSettingsGuardBypassActive(applicationContext)) {
                    val isFullBlockActive = QuotaManager.isBlockSettingsActive(applicationContext)
                    val root = try { rootInActiveWindow ?: event.source } catch (_: Throwable) { null }
                    val isTampering = scanForSettingsTampering(root)

                    if (isFullBlockActive || isTampering) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        val blockMsg = if (isFullBlockActive) {
                            "🔒 Aplikasi Pengaturan (Settings) dikunci demi menjaga komitmen! Gunakan Emergency Safe Exit jika ada keperluan mendesak."
                        } else {
                            "🔒 Halaman Pengaturan CiroKuota dikunci demi keamanan! Gunakan Emergency Safe Exit jika diperlukan."
                        }
                        triggerBlock("Pengaturan Sistem (Settings)", blockMsg)
                        return
                    }
                }
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

    private fun scanForSettingsTampering(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (node == null || depth > 8) return false
        try {
            val text = node.text?.toString()?.lowercase(Locale.US) ?: ""
            val desc = node.contentDescription?.toString()?.lowercase(Locale.US) ?: ""

            // Deteksi jika halaman Settings menampilkan opsi terkait CiroKuota atau Device Admin CiroKuota
            val isTargetMentioned = text.contains("cirokuota") || desc.contains("cirokuota") ||
                    text.contains("ciro kuota") || desc.contains("ciro kuota")

            if (isTargetMentioned) {
                return true
            }

            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
                val found = scanForSettingsTampering(child, depth + 1)
                try { child.recycle() } catch (_: Throwable) {}
                if (found) return true
            }
        } catch (_: Throwable) {}
        return false
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
            val isCondByKuota = QuotaManager.isCondByKuotaActive(applicationContext) && quotaStatus.isLimitReached
            val manualUntil = QuotaManager.getManualLockUntil(applicationContext)
            val isManualLocked = manualUntil > now

            val isMonitored = QuotaManager.isAppBlockMonitored(applicationContext, pkg)
            val isTargetQuotaApp = QuotaManager.getAppBlockPackages(applicationContext).contains(pkg)
            if (!isMonitored && !isKillswitch && !isCondByKuota) return

            val appName = QuotaManager.getAppDisplayName(applicationContext, pkg)
            var reason: String? = null

            var isHardBlock = false

            // 1. Kondisi Global / Strict Mode / Killswitch
            if (isKillswitch) {
                reason = "🚨 Stop Data Aktif! Akses ke $appName sementara dihentikan total."
            } else if (isManualLocked) {
                val remainingMin = ((manualUntil - now) / 60000) + 1
                reason = "🔒 Kunci Ketat (Strict Mode) aktif! $appName terkunci selama $remainingMin menit lagi."
            } else if (isCondByKuota) {
                reason = "📊 By Kuota Utama: Budget Total Kuota Internet HP (${quotaStatus.budgetMb.toInt()} MB) telah habis! Akses ke $appName diblokir otomatis."
            }

            // 2. Kondisi Tab Pengaman Kuota (By Custom Quota per App) - KATEGORI HITAM
            if (reason == null && isTargetQuotaApp) {
                val isCondByAppQuota = QuotaManager.isCondByAppQuotaActive(applicationContext)
                val appSpecificBudgetMb = QuotaManager.getAppSpecificBudgetMb(applicationContext, pkg)
                val appUsageMb = if (isCondByAppQuota) QuotaManager.getAppMonitoredUsageMb(applicationContext, pkg) else 0f
                if (isCondByAppQuota && appUsageMb >= appSpecificBudgetMb) {
                    reason = "📊 Kuota Aplikasi Habis: Pemakaian $appName (${String.format(Locale.US, "%.1f", appUsageMb)} MB) telah melampaui limit (${appSpecificBudgetMb.toInt()} MB)!"
                    isHardBlock = true
                }
            }

            // 3. Kondisi Tab Habit Blocker (Profil Dinamis: By Duration, By Time, By Launch)
            if (reason == null) {
                val habitProfiles = QuotaManager.getHabitProfiles(applicationContext)
                for (profile in habitProfiles) {
                    if (!profile.isEnabled || !profile.packages.contains(pkg)) continue

                    val launchCount = QuotaManager.getLaunchCountToday(applicationContext, pkg)
                    val isProfileTime = QuotaManager.isProfileTimeActive(profile)
                    val profileDurationSec = profile.durationMinutes * 60L

                    when {
                        profile.isByTime && isProfileTime -> {
                            val timeStr = String.format(Locale.US, "%02d:%02d - %02d:%02d", profile.startHour, profile.startMinute, profile.endHour, profile.endMinute)
                            reason = "🕒 Profil Habit \"${profile.name}\" (By Time): $appName sedang dalam jadwal jam gembok ($timeStr)."
                        }
                        profile.isByLaunch && launchCount > profile.maxLaunches -> {
                            reason = "🔢 Profil Habit \"${profile.name}\" (By Launch): Batas membuka $appName hari ini (maks ${profile.maxLaunches} kali) telah habis!"
                        }
                        profile.isByDuration && currentSessionSeconds >= profileDurationSec -> {
                            reason = "⏳ Profil Habit \"${profile.name}\" (By Duration): Batas waktu sesi $appName (${profile.durationMinutes} menit) telah tercapai!"
                        }
                    }

                    if (reason != null) break
                }
            }

            // Fallback backward compatibility jika masih ada pemicu lama yang aktif
            if (reason == null && isTargetQuotaApp) {
                val isCondByTime = QuotaManager.isCurrentTimeInSchedule(applicationContext)
                val isCondByLaunch = QuotaManager.isCondByLaunchActive(applicationContext)
                val maxLaunches = QuotaManager.getMaxLaunchesPerDay(applicationContext)
                val launchCount = QuotaManager.getLaunchCountToday(applicationContext, pkg)
                val isCondByDuration = QuotaManager.isCondByDurationActive(applicationContext)
                val sessionLimitSec = QuotaManager.getAppBlockSessionLimitMinutes(applicationContext) * 60L

                when {
                    isCondByTime -> reason = "🕒 By Time: $appName sedang berada di dalam jadwal jam gembok / tidur."
                    isCondByLaunch && launchCount > maxLaunches -> reason = "🔢 By Launch: Batas membuka $appName hari ini (maks $maxLaunches kali) telah habis!"
                    isCondByDuration && currentSessionSeconds >= sessionLimitSec -> reason = "⏳ By Duration: Batas Waktu Sesi $appName (${sessionLimitSec / 60} menit) telah tercapai!"
                }
            }

            if (reason != null) {
                lastBlockedTime = now
                triggerBlock(appName, reason, isHardBlock)
            }
        } catch (_: Throwable) {}
    }

    private fun triggerBlock(appName: String, reason: String, isHardBlock: Boolean = false) {
        try {
            // Step 1: Throw back to Home
            performGlobalAction(GLOBAL_ACTION_HOME)

            // Step 2: Show stylish BlockedActivity screen
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val blockIntent = Intent(applicationContext, BlockedActivity::class.java).apply {
                        putExtra("EXTRA_APP_NAME", appName)
                        putExtra("EXTRA_REASON", reason)
                        putExtra("EXTRA_IS_HARD_BLOCK", isHardBlock)
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
