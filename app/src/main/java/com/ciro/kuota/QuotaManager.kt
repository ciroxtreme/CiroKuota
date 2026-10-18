package com.ciro.kuota

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.TrafficStats
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
    val isInstalled: Boolean,
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
    private const val KEY_APP_LOCK = "app_lock_active"
    private const val KEY_AUTO_CUTOFF = "auto_cutoff_active"
    private const val KEY_IG_LIMIT_MIN = "ig_session_limit_minutes"
    private const val KEY_BROWSER_LIMIT_MIN = "browser_session_limit_minutes"
    private const val KEY_MANUAL_LOCK_UNTIL = "manual_lock_until"
    private const val KEY_CHALLENGE_LOCK_UNTIL = "challenge_lock_until"
    private const val KEY_DARK_MODE = "is_dark_mode"
    private const val KEY_LAST_DATE = "last_tracked_date"
    private const val KEY_BLACKHOLE_APPS = "blackhole_packages"

    val DEFAULT_BLACKHOLE_APPS = setOf(
        "com.instagram.android",
        "com.instagram.lite",
        "com.android.chrome",
        "com.google.android.apps.chrome",
        "org.mozilla.firefox",
        "com.brave.browser",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.microsoft.emmx",
        "com.duckduckgo.mobile.android"
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

    fun isInstagram(packageName: String): Boolean = packageName.contains("instagram")
    fun isBrowser(packageName: String): Boolean = packageName.contains("chrome") ||
            packageName.contains("firefox") ||
            packageName.contains("browser") ||
            packageName.contains("opera") ||
            packageName.contains("duckduckgo") ||
            packageName.contains("emmx")

    fun getAllCandidateApps(context: Context): List<SelectableApp> {
        val pm = context.packageManager
        val selected = getBlackholePackages(context)
        val candidateMap = linkedMapOf(
            "com.instagram.android" to Pair("Instagram", "social"),
            "com.instagram.lite" to Pair("Instagram Lite", "social"),
            "com.android.chrome" to Pair("Google Chrome", "browser"),
            "com.google.android.apps.chrome" to Pair("Chrome Dev", "browser"),
            "org.mozilla.firefox" to Pair("Mozilla Firefox", "browser"),
            "com.brave.browser" to Pair("Brave Browser", "browser"),
            "com.sec.android.app.sbrowser" to Pair("Samsung Internet", "browser"),
            "com.opera.browser" to Pair("Opera Browser", "browser"),
            "com.opera.mini.native" to Pair("Opera Mini", "browser"),
            "com.microsoft.emmx" to Pair("Microsoft Edge", "browser"),
            "com.duckduckgo.mobile.android" to Pair("DuckDuckGo", "browser"),
            "com.google.android.youtube" to Pair("YouTube", "video"),
            "com.zhiliaoapp.musically" to Pair("TikTok", "social"),
            "com.twitter.android" to Pair("X (Twitter)", "social")
        )

        val result = mutableListOf<SelectableApp>()
        for ((pkg, info) in candidateMap) {
            val isInstalled = try {
                pm.getApplicationInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
            result.add(
                SelectableApp(
                    packageName = pkg,
                    appName = info.first,
                    category = info.second,
                    isInstalled = isInstalled,
                    isSelected = selected.contains(pkg)
                )
            )
        }
        return result
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
