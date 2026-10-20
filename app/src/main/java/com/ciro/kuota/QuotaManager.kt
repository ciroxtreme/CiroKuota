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
import org.json.JSONArray
import org.json.JSONObject

data class AppDataUsage(
    val packageName: String,
    val appName: String,
    val bytesUsed: Long,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val percentOfTotal: Float = 0f,
    val isMonitored: Boolean = false,
    val iconCategory: String = "general"
)

data class SelectableApp(
    val packageName: String,
    val appName: String,
    val category: String,
    val isInstalled: Boolean = true,
    val isSystemApp: Boolean = false,
    val isSelected: Boolean,
    val isAssignedElsewhere: Boolean = false,
    val assignedLabel: String = ""
)

data class HabitProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Profil Habit",
    val isEnabled: Boolean = true,
    val packages: Set<String> = emptySet(),
    val isByDuration: Boolean = true,
    val durationMinutes: Int = 20,
    val isByTime: Boolean = false,
    val startHour: Int = 22,
    val startMinute: Int = 0,
    val endHour: Int = 5,
    val endMinute: Int = 0,
    val isByLaunch: Boolean = false,
    val maxLaunches: Int = 5
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
        if (getAppBlockPackages(context).contains(packageName) || isInstagram(packageName) || isBrowser(packageName)) {
            return true
        }
        val habitProfiles = getHabitProfiles(context)
        for (profile in habitProfiles) {
            if (profile.isEnabled && profile.packages.contains(packageName)) {
                return true
            }
        }
        return false
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

    fun isCondByKuotaActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_COND_KUOTA, false)
    fun setCondByKuotaActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_COND_KUOTA, active).apply()

    // Kuota per App (Budget per individual target app)
    private const val KEY_COND_APP_KUOTA = "cond_by_app_kuota"
    private const val KEY_APP_KUOTA_BUDGET_MB = "app_quota_budget_mb"
    private const val KEY_APP_KUOTA_BASE_PREFIX = "app_kuota_base_"
    private const val KEY_APP_KUOTA_BASE_DATE = "app_kuota_base_date"

    fun isCondByAppQuotaActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_COND_APP_KUOTA, true)
    fun setCondByAppQuotaActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_COND_APP_KUOTA, active).apply()

    fun getAppQuotaBudgetMb(context: Context): Float = getPrefs(context).getFloat(KEY_APP_KUOTA_BUDGET_MB, 500f)
    fun setAppQuotaBudgetMb(context: Context, mb: Float) = getPrefs(context).edit().putFloat(KEY_APP_KUOTA_BUDGET_MB, mb).apply()

    // Jatah Kuota Kustom Per Aplikasi (Contoh: YouTube 500 MB, TikTok 300 MB)
    private const val KEY_APP_SPECIFIC_BUDGET_PREFIX = "app_specific_budget_mb_"

    fun getAppSpecificBudgetMb(context: Context, pkg: String): Float {
        val key = "$KEY_APP_SPECIFIC_BUDGET_PREFIX$pkg"
        val prefs = getPrefs(context)
        return if (prefs.contains(key)) {
            prefs.getFloat(key, 100f)
        } else {
            getAppQuotaBudgetMb(context) // Fallback default app quota budget
        }
    }

    fun setAppSpecificBudgetMb(context: Context, pkg: String, mb: Float) {
        val key = "$KEY_APP_SPECIFIC_BUDGET_PREFIX$pkg"
        getPrefs(context).edit().putFloat(key, mb.coerceAtLeast(1f)).apply()
    }

    // Mengambil pemakaian data (Rx + Tx) per UID menggunakan NetworkStatsManager.querySummary
    private fun getUidUsageSummaryMap(context: Context): Map<Int, Pair<Long, Long>> {
        val map = mutableMapOf<Int, Pair<Long, Long>>() // uid -> (rx, tx)
        val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return map
        if (!hasUsageStatsPermission(context)) return map

        val startTime = getMidnightTimestamp()
        val endTime = System.currentTimeMillis()

        // 1. Query Wi-Fi
        try {
            val wifiStats = nsm.querySummary(ConnectivityManager.TYPE_WIFI, null, startTime, endTime)
            val bucket = NetworkStats.Bucket()
            while (wifiStats.hasNextBucket()) {
                wifiStats.getNextBucket(bucket)
                val uid = bucket.uid
                val current = map[uid] ?: Pair(0L, 0L)
                map[uid] = Pair(current.first + bucket.rxBytes, current.second + bucket.txBytes)
            }
            wifiStats.close()
        } catch (_: Exception) {}

        // 2. Query Mobile Data
        try {
            val mobileStats = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, null, startTime, endTime)
            val bucket = NetworkStats.Bucket()
            while (mobileStats.hasNextBucket()) {
                mobileStats.getNextBucket(bucket)
                val uid = bucket.uid
                val current = map[uid] ?: Pair(0L, 0L)
                map[uid] = Pair(current.first + bucket.rxBytes, current.second + bucket.txBytes)
            }
            mobileStats.close()
        } catch (_: Exception) {}

        return map
    }

    // Mengambil akumulasi trafik raw aplikasi hari ini (Wi-Fi + Seluler/Mobile Data)
    fun getRawAppUsageBytes(context: Context, packageName: String): Long {
        val detail = getRawAppUsageDetail(context, packageName)
        return detail.first + detail.second
    }

    fun getRawAppUsageDetail(context: Context, packageName: String): Pair<Long, Long> {
        var rxTotal = 0L
        var txTotal = 0L
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val uid = appInfo.uid

            // Metode 1: querySummary system-wide (sangat akurat ala aplikasi pemantau data sebelah)
            val summaryMap = getUidUsageSummaryMap(context)
            if (summaryMap.containsKey(uid)) {
                val pair = summaryMap[uid] ?: Pair(0L, 0L)
                rxTotal += pair.first
                txTotal += pair.second
            }

            // Metode 2: Jika querySummary belum mencakup atau bernilai 0, coba queryDetailsForUid
            if (rxTotal + txTotal <= 0L) {
                val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
                if (hasUsageStatsPermission(context) && nsm != null) {
                    val startTime = getMidnightTimestamp()
                    val endTime = System.currentTimeMillis()

                    // Wi-Fi
                    try {
                        val wifiStats = nsm.queryDetailsForUid(ConnectivityManager.TYPE_WIFI, null, startTime, endTime, uid)
                        val bucket = NetworkStats.Bucket()
                        while (wifiStats.hasNextBucket()) {
                            wifiStats.getNextBucket(bucket)
                            rxTotal += bucket.rxBytes
                            txTotal += bucket.txBytes
                        }
                        wifiStats.close()
                    } catch (_: Exception) {}

                    // Mobile
                    try {
                        val mobileStats = nsm.queryDetailsForUid(ConnectivityManager.TYPE_MOBILE, null, startTime, endTime, uid)
                        val bucket = NetworkStats.Bucket()
                        while (mobileStats.hasNextBucket()) {
                            mobileStats.getNextBucket(bucket)
                            rxTotal += bucket.rxBytes
                            txTotal += bucket.txBytes
                        }
                        mobileStats.close()
                    } catch (_: Exception) {}
                }
            }

            // Metode 3: Fallback ke TrafficStats jika tetap 0
            if (rxTotal + txTotal <= 0L) {
                val rx = TrafficStats.getUidRxBytes(uid)
                val tx = TrafficStats.getUidTxBytes(uid)
                if (rx > 0 || tx > 0) {
                    rxTotal = rx.coerceAtLeast(0L)
                    txTotal = tx.coerceAtLeast(0L)
                }
            }
        } catch (_: Exception) {}

        return Pair(rxTotal, txTotal)
    }

    // Menghitung pemakaian kuota aplikasi yang dimonitor (Langsung mengikuti hitungan nyata hari ini, tanpa reset baseline semu)
    fun getAppMonitoredUsageBytes(context: Context, packageName: String): Long {
        return getRawAppUsageBytes(context, packageName)
    }

    fun getAppMonitoredUsageMb(context: Context, packageName: String): Float {
        return getAppMonitoredUsageBytes(context, packageName).toFloat() / (1024f * 1024f)
    }

    fun getAppUsageTodayBytes(context: Context, packageName: String): Long {
        return getAppMonitoredUsageBytes(context, packageName)
    }

    fun getAppUsageTodayMb(context: Context, packageName: String): Float {
        return getAppMonitoredUsageMb(context, packageName)
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

    // ========================================================
    // HABIT PROFILES (AppBlock-style Dynamic Profiles)
    // ========================================================
    private const val KEY_HABIT_PROFILES_JSON = "habit_profiles_json"

    fun getHabitProfiles(context: Context): List<HabitProfile> {
        val jsonStr = getPrefs(context).getString(KEY_HABIT_PROFILES_JSON, null)
        if (jsonStr.isNullOrBlank()) {
            return getDefaultHabitProfiles()
        }
        return try {
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<HabitProfile>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val pkgsArray = obj.optJSONArray("packages") ?: JSONArray()
                val pkgSet = mutableSetOf<String>()
                for (j in 0 until pkgsArray.length()) {
                    pkgSet.add(pkgsArray.getString(j))
                }
                list.add(
                    HabitProfile(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", "Profil Habit"),
                        isEnabled = obj.optBoolean("isEnabled", true),
                        packages = pkgSet,
                        isByDuration = obj.optBoolean("isByDuration", true),
                        durationMinutes = obj.optInt("durationMinutes", 20),
                        isByTime = obj.optBoolean("isByTime", false),
                        startHour = obj.optInt("startHour", 22),
                        startMinute = obj.optInt("startMinute", 0),
                        endHour = obj.optInt("endHour", 5),
                        endMinute = obj.optInt("endMinute", 0),
                        isByLaunch = obj.optBoolean("isByLaunch", false),
                        maxLaunches = obj.optInt("maxLaunches", 5)
                    )
                )
            }
            list
        } catch (_: Exception) {
            getDefaultHabitProfiles()
        }
    }

    fun saveHabitProfiles(context: Context, profiles: List<HabitProfile>) {
        val jsonArray = JSONArray()
        for (p in profiles) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("isEnabled", p.isEnabled)
                val pkgs = JSONArray()
                p.packages.forEach { pkgs.put(it) }
                put("packages", pkgs)
                put("isByDuration", p.isByDuration)
                put("durationMinutes", p.durationMinutes)
                put("isByTime", p.isByTime)
                put("startHour", p.startHour)
                put("startMinute", p.startMinute)
                put("endHour", p.endHour)
                put("endMinute", p.endMinute)
                put("isByLaunch", p.isByLaunch)
                put("maxLaunches", p.maxLaunches)
            }
            jsonArray.put(obj)
        }
        getPrefs(context).edit().putString(KEY_HABIT_PROFILES_JSON, jsonArray.toString()).apply()
    }

    private fun getDefaultHabitProfiles(): List<HabitProfile> {
        return listOf(
            HabitProfile(
                id = "default_sleep_profile",
                name = "Jadwal Tidur Malam",
                isEnabled = false,
                packages = emptySet(),
                isByDuration = false,
                durationMinutes = 20,
                isByTime = true,
                startHour = 22,
                startMinute = 0,
                endHour = 5,
                endMinute = 0,
                isByLaunch = false,
                maxLaunches = 5
            )
        )
    }

    fun saveOrUpdateHabitProfile(context: Context, profile: HabitProfile) {
        val list = getHabitProfiles(context).toMutableList()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            list[index] = profile
        } else {
            list.add(profile)
        }
        saveHabitProfiles(context, list)
    }

    fun deleteHabitProfile(context: Context, profileId: String) {
        val list = getHabitProfiles(context).filter { it.id != profileId }
        saveHabitProfiles(context, list)
    }

    // Pemetaan Kepemilikan Aplikasi untuk Validasi Anti-Bentrok
    // Setiap aplikasi hanya boleh berada di 1 tempat aktif (Pengaman Kuota atau salah satu Profil Habit)
    fun getAppAssignmentMap(context: Context, excludeHabitProfileId: String? = null, excludeQuota: Boolean = false): Map<String, String> {
        val map = mutableMapOf<String, String>()

        // 1. Pengaman Kuota (AppBlock Packages)
        if (!excludeQuota) {
            val quotaApps = getAppBlockPackages(context)
            for (pkg in quotaApps) {
                map[pkg] = "Pengaman Kuota"
            }
        }

        // 2. Dynamic Habit Profiles
        val profiles = getHabitProfiles(context)
        for (profile in profiles) {
            if (excludeHabitProfileId != null && profile.id == excludeHabitProfileId) {
                continue
            }
            for (pkg in profile.packages) {
                map[pkg] = "Profil \"${profile.name}\""
            }
        }

        return map
    }

    fun isProfileTimeActive(profile: HabitProfile): Boolean {
        if (!profile.isEnabled || !profile.isByTime) return false
        val sH = profile.startHour
        val sM = profile.startMinute
        val eH = profile.endHour
        val eM = profile.endMinute
        val startMins = sH * 60 + sM
        val endMins = eH * 60 + eM

        val now = Calendar.getInstance()
        val currentMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        return if (startMins <= endMins) {
            currentMins in startMins..endMins
        } else {
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
    private const val KEY_STRICT_PHRASE = "strict_phrase_active"

    fun isStrictPhraseActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_STRICT_PHRASE, true)
    fun setStrictPhraseActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_STRICT_PHRASE, active).apply()

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

    fun getAllCandidateApps(
        context: Context,
        selectedPackages: Set<String> = getBlackholePackages(context),
        conflictMap: Map<String, String> = emptyMap()
    ): List<SelectableApp> {
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

            val isAssignedElsewhere = conflictMap.containsKey(pkg)
            val assignedLabel = conflictMap[pkg] ?: ""

            result.add(
                SelectableApp(
                    packageName = pkg,
                    appName = if (label.isNotBlank()) label else pkg,
                    category = category,
                    isInstalled = true,
                    isSystemApp = isSystem,
                    isSelected = selectedPackages.contains(pkg),
                    isAssignedElsewhere = isAssignedElsewhere,
                    assignedLabel = assignedLabel
                )
            )
        }

        // Sort: Terpilih -> Belum digunakan (bukan assigned elsewhere) -> User apps -> Nama
        return result.sortedWith(
            compareByDescending<SelectableApp> { it.isSelected }
                .thenBy { it.isAssignedElsewhere }
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
        var totalBytes = 0L
        if (hasUsageStatsPermission(context)) {
            try {
                val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
                if (nsm != null) {
                    val startTime = getMidnightTimestamp()
                    val endTime = System.currentTimeMillis()

                    // 1. Wi-Fi
                    try {
                        val wifiBucket = nsm.querySummaryForDevice(
                            ConnectivityManager.TYPE_WIFI,
                            null,
                            startTime,
                            endTime
                        )
                        totalBytes += (wifiBucket.rxBytes + wifiBucket.txBytes)
                    } catch (_: Exception) {}

                    // 2. Mobile Data
                    try {
                        val mobileBucket = nsm.querySummaryForDevice(
                            ConnectivityManager.TYPE_MOBILE,
                            null,
                            startTime,
                            endTime
                        )
                        totalBytes += (mobileBucket.rxBytes + mobileBucket.txBytes)
                    } catch (_: Exception) {}

                    if (totalBytes > 0L) {
                        return totalBytes
                    }
                }
            } catch (_: Exception) {}
        }

        // Fallback TrafficStats dengan baseline harian agar tidak mengambil hitungan GB sejak HP menyala (boot)
        val currentTotal = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
        if (currentTotal <= 0) return 0L

        val prefs = getPrefs(context)
        val todayStr = getTodayDateString()
        val lastDate = prefs.getString("total_wifi_traffic_date", "")
        val key = "total_wifi_traffic_base"

        if (lastDate != todayStr || !prefs.contains(key)) {
            prefs.edit().putString("total_wifi_traffic_date", todayStr).putLong(key, currentTotal).apply()
            return 0L
        }

        val base = prefs.getLong(key, currentTotal)
        if (currentTotal < base) {
            prefs.edit().putLong(key, currentTotal).apply()
            return 0L
        }

        return currentTotal - base
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
        val summaryMap = getUidUsageSummaryMap(context)

        // Himpun semua aplikasi yang memiliki aktivitas / terinstal
        val seenPackages = mutableSetOf<String>()
        val installedApps = pm.getInstalledApplications(0)
        val uidToPackage = mutableMapOf<Int, String>()
        for (app in installedApps) {
            uidToPackage[app.uid] = app.packageName
        }

        // 1. Masukkan aplikasi yang tercatat aktif di NetworkStatsManager summary hari ini
        for ((uid, pair) in summaryMap) {
            val totalBytes = pair.first + pair.second
            if (totalBytes <= 0) continue

            val pkg = uidToPackage[uid] ?: pm.getNameForUid(uid) ?: continue
            // Lewati paket sistem internal murni jika bukan aplikasi user atau launcher
            if (pkg == "android" || pkg.startsWith("com.android.providers.telephony")) continue
            if (seenPackages.contains(pkg)) continue
            seenPackages.add(pkg)

            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
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
                        bytesUsed = totalBytes,
                        rxBytes = pair.first,
                        txBytes = pair.second,
                        isMonitored = isTargetMonitored(context, pkg) || isAppBlockMonitored(context, pkg),
                        iconCategory = category
                    )
                )
            } catch (_: Exception) {}
        }

        // 2. Tambahkan kandidat paket prioritas jika belum masuk daftar
        val candidatePackages = (getBlackholePackages(context) + getAppBlockPackages(context) + listOf(
            "com.google.android.youtube",
            "com.zhiliaoapp.musically.go",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.aweme",
            "com.whatsapp",
            "org.telegram.messenger",
            "com.android.chrome"
        )).distinct()

        for (pkg in candidatePackages) {
            if (seenPackages.contains(pkg)) continue
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                val detail = getRawAppUsageDetail(context, pkg)
                val totalBytes = detail.first + detail.second

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
                        bytesUsed = totalBytes,
                        rxBytes = detail.first,
                        txBytes = detail.second,
                        isMonitored = isTargetMonitored(context, pkg) || isAppBlockMonitored(context, pkg),
                        iconCategory = category
                    )
                )
                seenPackages.add(pkg)
            } catch (_: PackageManager.NameNotFoundException) {}
        }

        // Hitung total penggunaan keseluruhan untuk persentase kontribusi per aplikasi
        val grandTotal = list.sumOf { it.bytesUsed }.coerceAtLeast(1L)
        val sorted = list.sortedByDescending { it.bytesUsed }
        return sorted.map {
            it.copy(percentOfTotal = (it.bytesUsed.toFloat() / grandTotal.toFloat()) * 100f)
        }
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

    // ========================================================
    // BENTENG BAJA: KALIMAT KOMITMEN & 24H EDIT COOLDOWN
    // ========================================================
    const val DEFAULT_COMMITMENT_PHRASE = "Aku tidak menyesali kehilangan kuota 1gb dan siap bertanggung jawab"
    private const val KEY_COMMITMENT_PHRASE = "commitment_phrase_text"
    private const val KEY_PENDING_COMMITMENT = "pending_commitment_phrase"
    private const val KEY_PENDING_COMMITMENT_TIME = "pending_commitment_time"
    private const val KEY_SETTINGS_GUARD_BYPASS_UNTIL = "settings_guard_bypass_until"

    fun getCommitmentPhrase(context: Context): String {
        applyPendingCommitmentIfReady(context)
        return getPrefs(context).getString(KEY_COMMITMENT_PHRASE, DEFAULT_COMMITMENT_PHRASE) ?: DEFAULT_COMMITMENT_PHRASE
    }

    fun requestChangeCommitmentPhrase(context: Context, newPhrase: String) {
        val trimmed = newPhrase.trim()
        if (trimmed.isEmpty()) return
        val now = System.currentTimeMillis()
        getPrefs(context).edit()
            .putString(KEY_PENDING_COMMITMENT, trimmed)
            .putLong(KEY_PENDING_COMMITMENT_TIME, now)
            .apply()
    }

    fun getPendingCommitmentPhrase(context: Context): String? {
        applyPendingCommitmentIfReady(context)
        return getPrefs(context).getString(KEY_PENDING_COMMITMENT, null)
    }

    fun getPendingCommitmentApplyTime(context: Context): Long {
        val reqTime = getPrefs(context).getLong(KEY_PENDING_COMMITMENT_TIME, 0L)
        if (reqTime <= 0L) return 0L
        return reqTime + (24L * 60L * 60L * 1000L) // 24 Jam
    }

    fun applyPendingCommitmentIfReady(context: Context) {
        val pending = getPrefs(context).getString(KEY_PENDING_COMMITMENT, null) ?: return
        val reqTime = getPrefs(context).getLong(KEY_PENDING_COMMITMENT_TIME, 0L)
        if (reqTime <= 0L) return
        val cooldownMs = 24L * 60L * 60L * 1000L
        if (System.currentTimeMillis() - reqTime >= cooldownMs) {
            getPrefs(context).edit()
                .putString(KEY_COMMITMENT_PHRASE, pending)
                .remove(KEY_PENDING_COMMITMENT)
                .remove(KEY_PENDING_COMMITMENT_TIME)
                .apply()
        }
    }

    fun cancelPendingCommitment(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_PENDING_COMMITMENT)
            .remove(KEY_PENDING_COMMITMENT_TIME)
            .apply()
    }

    // Temporary Bypass untuk Settings Guard (Safe Exit / Maintenance)
    fun grantSettingsGuardBypass(context: Context, durationMillis: Long = 10 * 60 * 1000L) {
        val until = System.currentTimeMillis() + durationMillis
        getPrefs(context).edit().putLong(KEY_SETTINGS_GUARD_BYPASS_UNTIL, until).apply()
    }

    fun isSettingsGuardBypassActive(context: Context): Boolean {
        return getPrefs(context).getLong(KEY_SETTINGS_GUARD_BYPASS_UNTIL, 0L) > System.currentTimeMillis()
    }

    // Blokir Akses ke Pengaturan HP (Settings App Lock)
    private const val KEY_BLOCK_SETTINGS_APP = "block_settings_app_active"
    fun isBlockSettingsActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_BLOCK_SETTINGS_APP, true)
    fun setBlockSettingsActive(context: Context, active: Boolean) = getPrefs(context).edit().putBoolean(KEY_BLOCK_SETTINGS_APP, active).apply()
}

