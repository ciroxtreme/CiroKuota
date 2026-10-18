package com.ciro.kuota

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.*

data class AppDataUsage(
    val packageName: String,
    val appName: String,
    val bytesUsed: Long,
    val isMonitored: Boolean,
    val iconCategory: String = "general"
)

data class SelectableApp(
    val packageName: String,
    val appName: String,
    val category: String,
    val isInstalled: Boolean = true,
    val isSystemApp: Boolean = false,
    val isSelected: Boolean
)

data class QuotaStatus(
    val totalWifiBytesToday: Long,
    val budgetMb: Float,
    val percentUsed: Float,
    val isLimitReached: Boolean
)

object QuotaManager {
    private const val PREFS_NAME = "cirokuota_prefs"

    private const val KEY_BUDGET_MB = "daily_budget_mb"
    private const val KEY_KILLSWITCH = "killswitch_active"
    private const val KEY_VPN_ACTIVE = "vpn_firewall_active"
    private const val KEY_FAMILY_DNS = "family_dns_active"
    private const val KEY_ADULT_FILTER = "adult_filter_active"
    private const val KEY_APP_LOCK = "app_lock_active"
    private const val KEY_AUTO_CUTOFF = "auto_cutoff_active"
    private const val KEY_IG_LIMIT_MIN = "ig_session_limit_minutes"
    private const val KEY_BROWSER_LIMIT_MIN = "browser_session_limit_minutes"
    private const val KEY_MANUAL_LOCK_UNTIL = "manual_lock_until"
    private const val KEY_CHALLENGE_LOCK_UNTIL = "challenge_lock_until"
    private const val KEY_DARK_MODE = "is_dark_mode"
    private const val KEY_LAST_DATE = "last_tracked_date"
    private const val KEY_BLACKHOLE_APPS = "blackhole_packages"

    // Daftar Domain Dewasa / Pornografi (Termasuk missav.ai dan mirror/variannya)
    val ADULT_DOMAINS = setOf(
        "missav.ai", "missav.com", "missav.ws", "missav.net", "missav.org", "missav.live", "missav.tv",
        "pornhub.com", "xvideos.com", "xnxx.com", "rule34.xxx", "onlyfans.com",
        "javlibrary.com", "javdb.com", "javbus.com", "spankbang.com", "redtube.com",
        "youporn.com", "eporner.com", "hqporner.com", "beeg.com", "tube8.com",
        "chaturbate.com", "stripchat.com", "livejasmin.com", "bonga.com", "cam4.com",
        "camsoda.com", "xhamster.com", "brazzers.com", "hentaihaven.xxx", "nhentai.net",
        "hanime.tv", "fapello.com", "ero-video.net", "jable.tv", "tktube.com", "subjav.com", "supjav.com"
    )

    // Kata Kunci Situs Dewasa untuk Deteksi Cepat
    val ADULT_KEYWORDS = listOf(
        "missav",
        "pornhub",
        "xvideos",
        "xnxx",
        "rule34",
        "xhamster",
        "spankbang",
        "hentai",
        "javdb",
        "javbus",
        "javlibrary",
        "chaturbate",
        "stripchat"
    )

    // Paket-paket browser populer
    val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "com.sec.android.app.sbrowser",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.brave.browser",
        "com.duckduckgo.mobile.android",
        "com.kiwibrowser.browser",
        "com.UCMobile.intl",
        "com.mi.globalbrowser"
    )

    fun isBrowserPackage(pkg: String): Boolean {
        return BROWSER_PACKAGES.contains(pkg) || pkg.contains("browser")
    }

    fun isAdultDomain(text: String): Boolean {
        val lower = text.lowercase(Locale.US).trim()
        if (lower.isEmpty()) return false

        // Check if any known adult domain is part of or equal to text
        for (domain in ADULT_DOMAINS) {
            if (lower == domain || lower.endsWith(".$domain") || lower.contains("/$domain") || lower.contains(domain)) {
                return true
            }
        }

        // TLD match
        if (lower.endsWith(".xxx") || lower.endsWith(".porn") || lower.endsWith(".adult")) {
            return true
        }

        // Keyword match
        for (kw in ADULT_KEYWORDS) {
            if (lower.contains(kw)) {
                return true
            }
        }

        return false
    }

    val DEFAULT_BLACKHOLE_APPS = setOf(
        "com.instagram.android",
        "com.instagram.lite"
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDailyBudgetMb(context: Context): Float = getPrefs(context).getFloat(KEY_BUDGET_MB, 2048f) // Default 2 GB
    fun setDailyBudgetMb(context: Context, mb: Float) = getPrefs(context).edit().putFloat(KEY_BUDGET_MB, mb).apply()

    fun isKillswitchActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_KILLSWITCH, false)
    fun setKillswitchActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_KILLSWITCH, active).apply()

    fun isVpnActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_VPN_ACTIVE, false)
    fun setVpnActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_VPN_ACTIVE, active).apply()

    fun isFamilyDnsActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_FAMILY_DNS, true)
    fun setFamilyDnsActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_FAMILY_DNS, active).apply()

    fun isAdultFilterActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_ADULT_FILTER, true)
    fun setAdultFilterActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_ADULT_FILTER, active).apply()

    fun isAppLockActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_APP_LOCK, true)
    fun setAppLockActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_APP_LOCK, active).apply()

    fun isAutoCutoffActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_AUTO_CUTOFF, true)
    fun setAutoCutoffActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_AUTO_CUTOFF, active).apply()

    fun getIgSessionLimitMinutes(context: Context): Int = getPrefs(context).getInt(KEY_IG_LIMIT_MIN, 20)
    fun setIgSessionLimitMinutes(context: Context, min: Int) = getPrefs(context).edit().putInt(KEY_IG_LIMIT_MIN, min).apply()

    fun getBrowserSessionLimitMinutes(context: Context): Int = getPrefs(context).getInt(KEY_BROWSER_LIMIT_MIN, 15)
    fun setBrowserSessionLimitMinutes(context: Context, min: Int) = getPrefs(context).edit().putInt(KEY_BROWSER_LIMIT_MIN, min).apply()

    fun getManualLockUntil(context: Context): Long = getPrefs(context).getLong(KEY_MANUAL_LOCK_UNTIL, 0L)
    fun setManualLockUntil(context: Context, untilTimestamp: Long) = getPrefs(context).edit().putLong(KEY_MANUAL_LOCK_UNTIL, untilTimestamp).apply()

    fun getChallengeLockUntil(context: Context): Long = getPrefs(context).getLong(KEY_CHALLENGE_LOCK_UNTIL, 0L)
    fun setChallengeLockUntil(context: Context, untilTimestamp: Long) = getPrefs(context).edit().putLong(KEY_CHALLENGE_LOCK_UNTIL, untilTimestamp).apply()

    private const val KEY_TEMP_PASS_UNTIL = "temp_pass_until"
    fun getTempPassUntil(context: Context): Long = getPrefs(context).getLong(KEY_TEMP_PASS_UNTIL, 0L)
    fun grantTemporaryPass(context: Context, durationMillis: Long = 15 * 60 * 1000L) {
        val until = System.currentTimeMillis() + durationMillis
        getPrefs(context).edit().putLong(KEY_TEMP_PASS_UNTIL, until).apply()
    }
    fun isTempPassActive(context: Context): Boolean {
        return getTempPassUntil(context) > System.currentTimeMillis()
    }
    fun clearTempPass(context: Context) {
        getPrefs(context).edit().putLong(KEY_TEMP_PASS_UNTIL, 0L).apply()
    }

    fun isDarkMode(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DARK_MODE, true)
    fun setDarkMode(context: Context, isDark: Boolean) = getPrefs(context).edit().putBoolean(KEY_DARK_MODE, isDark).apply()

    // Blackhole Apps Selection
    fun getBlackholePackages(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_BLACKHOLE_APPS, DEFAULT_BLACKHOLE_APPS) ?: DEFAULT_BLACKHOLE_APPS
    }

    fun setBlackholePackages(context: Context, pkgs: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_BLACKHOLE_APPS, pkgs).apply()
    }

    fun toggleBlackholePackage(context: Context, pkg: String): Boolean {
        val current = getBlackholePackages(context).toMutableSet()
        val isNowSelected = if (current.contains(pkg)) {
            current.remove(pkg)
            false
        } else {
            current.add(pkg)
            true
        }
        setBlackholePackages(context, current)
        return isNowSelected
    }

    fun isTargetMonitored(context: Context, packageName: String): Boolean {
        return getBlackholePackages(context).contains(packageName)
    }

    // AppBlock (Rem Khilaf) Settings & Targets
    private const val KEY_APPBLOCK_APPS = "appblock_packages"
    private const val KEY_APPBLOCK_LIMIT_MIN = "appblock_session_limit_minutes"

    val DEFAULT_APPBLOCK_APPS = setOf(
        "com.instagram.android",
        "com.instagram.lite",
        "com.google.android.youtube",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.android.chrome"
    )

    fun getAppBlockPackages(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_APPBLOCK_APPS, DEFAULT_APPBLOCK_APPS) ?: DEFAULT_APPBLOCK_APPS
    }

    fun setAppBlockPackages(context: Context, pkgs: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_APPBLOCK_APPS, pkgs).apply()
    }

    fun toggleAppBlockPackage(context: Context, pkg: String): Boolean {
        val current = getAppBlockPackages(context).toMutableSet()
        val isNowSelected = if (current.contains(pkg)) {
            current.remove(pkg)
            false
        } else {
            current.add(pkg)
            true
        }
        setAppBlockPackages(context, current)
        return isNowSelected
    }

    fun getAppBlockSessionLimitMinutes(context: Context): Int = getPrefs(context).getInt(KEY_APPBLOCK_LIMIT_MIN, 20)
    fun setAppBlockSessionLimitMinutes(context: Context, min: Int) = getPrefs(context).edit().putInt(KEY_APPBLOCK_LIMIT_MIN, min).apply()

    fun isAppBlockMonitored(context: Context, packageName: String): Boolean {
        return getAppBlockPackages(context).contains(packageName) || isInstagram(packageName) || isBrowser(packageName)
    }

    fun getAppDisplayName(context: Context, pkg: String): String {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            when {
                isInstagram(pkg) -> "Instagram"
                isBrowser(pkg) -> "Browser Web"
                pkg.contains("youtube") -> "YouTube"
                pkg.contains("tiktok") || pkg.contains("musically") -> "TikTok"
                else -> pkg
            }
        }
    }

    fun isInstagram(packageName: String): Boolean = packageName.contains("instagram")
    fun isBrowser(packageName: String): Boolean = packageName.contains("chrome") ||
            packageName.contains("firefox") ||
            packageName.contains("browser") ||
            packageName.contains("opera") ||
            packageName.contains("duckduckgo") ||
            packageName.contains("emmx")

    // Profile Conditions (Kondisi Pemicu Blokir)
    private const val KEY_COND_KUOTA = "cond_by_kuota"
    private const val KEY_COND_DURATION = "cond_by_duration"
    private const val KEY_COND_TIME = "cond_by_time"
    private const val KEY_COND_LAUNCH = "cond_by_launch"
    private const val KEY_MAX_LAUNCHES = "max_launches_per_day"
    private const val KEY_TIME_START_HOUR = "time_start_hour"
    private const val KEY_TIME_START_MIN = "time_start_min"
    private const val KEY_TIME_END_HOUR = "time_end_hour"
    private const val KEY_TIME_END_MIN = "time_end_min"

    fun isCondByKuotaActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_COND_KUOTA, true)
    fun setCondByKuotaActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_COND_KUOTA, active).apply()
    // Kuota per App (Budget per individual target app)
    private const val KEY_COND_APP_KUOTA = "cond_by_app_kuota"
    private const val KEY_APP_KUOTA_BUDGET_MB = "app_quota_budget_mb"

    fun isCondByAppQuotaActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_COND_APP_KUOTA, true)
    fun setCondByAppQuotaActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_COND_APP_KUOTA, active).apply()

    fun getAppQuotaBudgetMb(context: Context): Float = getPrefs(context).getFloat(KEY_APP_KUOTA_BUDGET_MB, 500f)
    fun setAppQuotaBudgetMb(context: Context, mb: Float) = getPrefs(context).edit().putFloat(KEY_APP_KUOTA_BUDGET_MB, mb).apply()

    fun getAppUsageTodayBytes(context: Context, packageName: String): Long {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            val hasPerm = hasUsageStatsPermission(context)
            if (hasPerm && nsm != null) {
                val startTime = getMidnightTimestamp()
                val endTime = System.currentTimeMillis()
                val stats: NetworkStats = nsm.queryDetailsForUid(
                    ConnectivityManager.TYPE_WIFI,
                    null,
                    startTime,
                    endTime,
                    appInfo.uid
                )
                var bytes = 0L
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    bytes += bucket.rxBytes + bucket.txBytes
                }
                stats.close()
                bytes
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun getAppUsageTodayMb(context: Context, packageName: String): Float {
        return getAppUsageTodayBytes(context, packageName).toFloat() / (1024f * 1024f)
    }

    fun isCondByDurationActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_COND_DURATION, true)
    fun setCondByDurationActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_COND_DURATION, active).apply()

    fun isCondByTimeActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_COND_TIME, false)
    fun setCondByTimeActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_COND_TIME, active).apply()

    fun isCondByLaunchActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_COND_LAUNCH, false)
    fun setCondByLaunchActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_COND_LAUNCH, active).apply()

    fun getMaxLaunchesPerDay(context: Context): Int = getPrefs(context).getInt(KEY_MAX_LAUNCHES, 5)
    fun setMaxLaunchesPerDay(context: Context, max: Int) = getPrefs(context).edit().putInt(KEY_MAX_LAUNCHES, max).apply()

    fun getTimeSchedule(context: Context): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val prefs = getPrefs(context)
        val sH = prefs.getInt(KEY_TIME_START_HOUR, 22)
        val sM = prefs.getInt(KEY_TIME_START_MIN, 0)
        val eH = prefs.getInt(KEY_TIME_END_HOUR, 5)
        val eM = prefs.getInt(KEY_TIME_END_MIN, 0)
        return Pair(Pair(sH, sM), Pair(eH, eM))
    }

    fun setTimeSchedule(context: Context, sH: Int, sM: Int, eH: Int, eM: Int) {
        getPrefs(context).edit()
            .putInt(KEY_TIME_START_HOUR, sH)
            .putInt(KEY_TIME_START_MIN, sM)
            .putInt(KEY_TIME_END_HOUR, eH)
            .putInt(KEY_TIME_END_MIN, eM)
            .apply()
    }

    fun isCurrentTimeInSchedule(context: Context): Boolean {
        if (!isCondByTimeActive(context)) return false
        val (start, end) = getTimeSchedule(context)
        val (sH, sM) = start
        val (eH, eM) = end
        val startMins = sH * 60 + sM
        val endMins = eH * 60 + eM

        val now = Calendar.getInstance()
        val currentMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        return if (startMins <= endMins) {
            currentMins in startMins..endMins
        } else {
            // Over midnight (e.g. 22:00 to 05:00)
            currentMins >= startMins || currentMins <= endMins
        }
    }

    // Launch Count Tracking per App per Day
    fun getLaunchCountToday(context: Context, pkg: String): Int {
        val todayStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return getPrefs(context).getInt("launch_${todayStr}_$pkg", 0)
    }

    fun incrementLaunchCount(context: Context, pkg: String): Int {
        val todayStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val key = "launch_${todayStr}_$pkg"
        val count = getPrefs(context).getInt(key, 0) + 1
        getPrefs(context).edit().putInt(key, count).apply()
        return count
    }

    // STRICT MODE OPTIONS (Gembok Ketat & Pilihan Tantangan)
    private const val KEY_STRICT_MATH = "strict_math_active"
    private const val KEY_STRICT_TOTP = "strict_totp_active"
    private const val KEY_STRICT_CHARGER = "strict_charger_active"
    private const val KEY_STRICT_TIMER = "strict_timer_active"

    fun isStrictMathActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_STRICT_MATH, true)
    fun setStrictMathActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_STRICT_MATH, active).apply()

    fun isStrictTotpActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_STRICT_TOTP, true)
    fun setStrictTotpActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_STRICT_TOTP, active).apply()

    fun isStrictChargerActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_STRICT_CHARGER, false)
    fun setStrictChargerActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_STRICT_CHARGER, active).apply()

    fun isStrictTimerActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_STRICT_TIMER, false)
    fun setStrictTimerActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_STRICT_TIMER, active).apply()

    fun isDeviceCharging(context: Context): Boolean {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
            val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            plugged == BatteryManager.BATTERY_PLUGGED_AC ||
            plugged == BatteryManager.BATTERY_PLUGGED_USB ||
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
        } catch (_: Exception) {
            false
        }
    }

    fun getAllCandidateApps(context: Context, selectedPackages: Set<String> = getBlackholePackages(context)): List<SelectableApp> {
        val pm = context.packageManager
        val installedApps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            emptyList()
        }

        val result = mutableListOf<SelectableApp>()
        for (appInfo in installedApps) {
            val pkg = appInfo.packageName
            if (pkg == context.packageName) continue // Jangan masukkan CiroKuota sendiri

            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val label = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                pkg
            }

            val category = when {
                isInstagram(pkg) -> "instagram"
                isBrowser(pkg) -> "browser"
                pkg.contains("youtube") -> "youtube"
                pkg.contains("tiktok") || pkg.contains("musically") -> "tiktok"
                isSystem -> "system"
                else -> "user"
            }

            result.add(
                SelectableApp(
                    packageName = pkg,
                    appName = if (label.isNotBlank()) label else pkg,
                    category = category,
                    isInstalled = true,
                    isSystemApp = isSystem,
                    isSelected = selectedPackages.contains(pkg)
                )
            )
        }

        // Sort: Aplikasi terpilih di atas, lalu user apps, lalu system apps
        return result.sortedWith(
            compareByDescending<SelectableApp> { it.isSelected }
                .thenBy { it.isSystemApp }
                .thenBy { it.appName.lowercase(Locale.getDefault()) }
        )
    }

    fun unselectAllApps(context: Context) {
        setBlackholePackages(context, emptySet())
    }

    fun resetToDefaultApps(context: Context) {
        setBlackholePackages(context, DEFAULT_BLACKHOLE_APPS)
    }

    fun resetAppBlockToDefault(context: Context) {
        setAppBlockPackages(context, DEFAULT_APPBLOCK_APPS)
    }

    fun clearAllAppBlock(context: Context) {
        setAppBlockPackages(context, emptySet())
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else {
            true
        }
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasAccessibilityPermission(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        val expected = "${context.packageName}/${CiroAccessibilityService::class.java.canonicalName}"
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expected, ignoreCase = true) || componentName.contains(context.packageName)) {
                return true
            }
        }
        return false
    }

    private fun getMidnightTimestamp(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun getWifiUsageToday(context: Context): Long {
        if (!hasUsageStatsPermission(context)) {
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            return if (rx > 0 && tx > 0) (rx + tx) else 0L
        }

        return try {
            val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            if (nsm != null) {
                val startTime = getMidnightTimestamp()
                val endTime = System.currentTimeMillis()
                val bucket = nsm.querySummaryForDevice(
                    ConnectivityManager.TYPE_WIFI,
                    null,
                    startTime,
                    endTime
                )
                bucket.rxBytes + bucket.txBytes
            } else {
                0L
            }
        } catch (_: Exception) {
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            if (rx > 0 && tx > 0) (rx + tx) else 0L
        }
    }

    fun getQuotaStatus(context: Context): QuotaStatus {
        val wifiBytes = getWifiUsageToday(context)
        val budgetMb = getDailyBudgetMb(context)
        val budgetBytes = (budgetMb * 1024f * 1024f).toLong()
        val percent = if (budgetBytes > 0) {
            ((wifiBytes.toFloat() / budgetBytes.toFloat()) * 100f).coerceIn(0f, 100f)
        } else {
            0f
        }
        val isLimit = budgetBytes > 0 && wifiBytes >= budgetBytes
        return QuotaStatus(
            totalWifiBytesToday = wifiBytes,
            budgetMb = budgetMb,
            percentUsed = percent,
            isLimitReached = isLimit
        )
    }

    fun getTopMonitoredAppsUsage(context: Context): List<AppDataUsage> {
        val pm = context.packageManager
        val list = mutableListOf<AppDataUsage>()

        val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
        val hasPerm = hasUsageStatsPermission(context)
        val startTime = getMidnightTimestamp()
        val endTime = System.currentTimeMillis()

        val candidatePackages = getBlackholePackages(context) + listOf(
            "com.google.android.youtube",
            "com.zhiliaoapp.musically",
            "com.whatsapp",
            "org.telegram.messenger"
        )

        for (pkg in candidatePackages) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                var bytes = 0L

                if (hasPerm && nsm != null) {
                    try {
                        val stats: NetworkStats = nsm.queryDetailsForUid(
                            ConnectivityManager.TYPE_WIFI,
                            null,
                            startTime,
                            endTime,
                            appInfo.uid
                        )
                        val bucket = NetworkStats.Bucket()
                        while (stats.hasNextBucket()) {
                            stats.getNextBucket(bucket)
                            bytes += bucket.rxBytes + bucket.txBytes
                        }
                        stats.close()
                    } catch (_: Exception) {}
                }

                val category = when {
                    isInstagram(pkg) -> "instagram"
                    isBrowser(pkg) -> "browser"
                    pkg.contains("youtube") -> "youtube"
                    pkg.contains("tiktok") || pkg.contains("musically") -> "tiktok"
                    else -> "social"
                }

                list.add(
                    AppDataUsage(
                        packageName = pkg,
                        appName = label,
                        bytesUsed = bytes,
                        isMonitored = isTargetMonitored(context, pkg),
                        iconCategory = category
                    )
                )
            } catch (_: PackageManager.NameNotFoundException) {
                // App not installed
            }
        }

        return list.sortedByDescending { it.bytesUsed }
    }

    fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    fun getAppUsageSecondsToday(context: Context, packageName: String): Long {
        checkAndResetDailyUsage(context)
        val key = "app_sec_${packageName}_${getTodayDateString()}"
        return getPrefs(context).getLong(key, 0L)
    }

    fun addAppUsageSeconds(context: Context, packageName: String, seconds: Long) {
        checkAndResetDailyUsage(context)
        val key = "app_sec_${packageName}_${getTodayDateString()}"
        val current = getPrefs(context).getLong(key, 0L)
        getPrefs(context).edit().putLong(key, current + seconds).apply()
    }

    private fun checkAndResetDailyUsage(context: Context) {
        val today = getTodayDateString()
        val lastDate = getPrefs(context).getString(KEY_LAST_DATE, "")
        if (lastDate != today) {
            getPrefs(context).edit().putString(KEY_LAST_DATE, today).apply()
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }
}
