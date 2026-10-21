package com.ciro.kuota

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import com.ciro.kuota.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            var isDark by remember { mutableStateOf(QuotaManager.isDarkMode(context)) }

            CiroKuotaTheme(darkTheme = isDark) {
                CiroKuotaMainScreen(
                    isDark = isDark,
                    onToggleTheme = {
                        isDark = !isDark
                        QuotaManager.setDarkMode(context, isDark)
                    }
                )
            }
        }
    }
}

enum class CiroTab(val title: String, val icon: ImageVector) {
    DASHBOARD("Beranda", Icons.Default.Shield),
    KUOTA_APP("Kuota", Icons.Default.DataSaverOn),
    HABITS("Habit", Icons.Default.Schedule),
    STRICT_MODE("Strict", Icons.Default.Lock),
    SETTINGS("Setting", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CiroKuotaMainScreen(
    isDark: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(CiroTab.DASHBOARD) }

    // State Tab 1: Dashboard
    var dailyBudgetMb by remember { mutableStateOf(QuotaManager.getDailyBudgetMb(context)) }
    var isStopData by remember { mutableStateOf(QuotaManager.isKillswitchActive(context)) }
    var isVpnFirewall by remember { mutableStateOf(QuotaManager.isVpnActive(context)) }
    var isStopByKuota by remember { mutableStateOf(QuotaManager.isAutoCutoffActive(context)) }
    var isAdultFilter by remember { mutableStateOf(QuotaManager.isAdultFilterActive(context)) }

    // State Target Aplikasi (Blackhole & Schedules/Rem Khilaf)
    var selectedBlackholeApps by remember { mutableStateOf(QuotaManager.getBlackholePackages(context)) }
    var appBlockPackages by remember { mutableStateOf(QuotaManager.getAppBlockPackages(context)) }

    // State Tab 2: Kuota App (Limit Kuota Kustom per Aplikasi)
    var isCondByAppQuota by remember { mutableStateOf(QuotaManager.isCondByAppQuotaActive(context)) }
    var appQuotaBudgetMb by remember { mutableStateOf(QuotaManager.getAppQuotaBudgetMb(context)) }
    var quotaRefreshTrigger by remember { mutableIntStateOf(0) }

    // State Tab 3: Habits (Dynamic AppBlock-style Profiles)
    var habitProfiles by remember { mutableStateOf(QuotaManager.getHabitProfiles(context)) }
    var editingHabitProfile by remember { mutableStateOf<HabitProfile?>(null) }
    var showHabitProfileDialog by remember { mutableStateOf(false) }

    // State Tab 2 Dialogs
    var showCustomAppQuotaDialog by remember { mutableStateOf(false) }
    var timeSchedule by remember { mutableStateOf(QuotaManager.getTimeSchedule(context)) }

    // State Tab 4: Strict Mode
    var manualLockUntil by remember { mutableStateOf(QuotaManager.getManualLockUntil(context)) }
    var isStrictPhrase by remember { mutableStateOf(QuotaManager.isStrictPhraseActive(context)) }
    var isStrictMath by remember { mutableStateOf(QuotaManager.isStrictMathActive(context)) }
    var isStrictTotp by remember { mutableStateOf(QuotaManager.isStrictTotpActive(context)) }
    var isStrictCharger by remember { mutableStateOf(QuotaManager.isStrictChargerActive(context)) }
    var isStrictTimer by remember { mutableStateOf(QuotaManager.isStrictTimerActive(context)) }
    var isCharging by remember { mutableStateOf(QuotaManager.isDeviceCharging(context)) }

    // Dialog Visibility State
    var showBlackholePickerDialog by remember { mutableStateOf(false) }
    var showAppBlockPickerDialog by remember { mutableStateOf(false) }
    var showTimePickerDialog by remember { mutableStateOf(false) }
    var showStrictUnlockDialog by remember { mutableStateOf(false) }

    var isBlockSettingsApp by remember { mutableStateOf(QuotaManager.isBlockSettingsActive(context)) }

    // State Verifikasi Komitmen (Anti-Khilaf)
    var pendingCommitmentAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var commitmentDialogTitle by remember { mutableStateOf("") }
    var commitmentDialogDesc by remember { mutableStateOf("") }

    // State Device Admin
    val dpm = remember { context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    val adminComponent = remember { ComponentName(context, CiroDeviceAdminReceiver::class.java) }
    var isAdminActive by remember { mutableStateOf(dpm.isAdminActive(adminComponent)) }

    // State Data Pemakaian & Permissions
    var quotaStatus by remember { mutableStateOf(QuotaManager.getQuotaStatus(context)) }
    var topApps by remember { mutableStateOf(QuotaManager.getTopMonitoredAppsUsage(context)) }
    var hasUsagePerm by remember { mutableStateOf(QuotaManager.hasUsageStatsPermission(context)) }
    var hasAccessPerm by remember { mutableStateOf(QuotaManager.hasAccessibilityPermission(context)) }
    var isBatteryIgnored by remember { mutableStateOf(QuotaManager.isBatteryOptimizationIgnored(context)) }

    // Launcher untuk VPN Service permission
    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            CiroVpnService.start(context)
            isVpnFirewall = true
            Toast.makeText(context, "VPN Blackhole Firewall Aktif!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Izin VPN diperlukan untuk memutus kuota per aplikasi", Toast.LENGTH_SHORT).show()
        }
    }

    fun startVpnSafely() {
        val intent = VpnService.prepare(context)
        if (intent != null) {
            vpnPrepareLauncher.launch(intent)
        } else {
            CiroVpnService.start(context)
            isVpnFirewall = true
        }
    }

    fun stopVpnSafely() {
        CiroVpnService.stop(context)
        isVpnFirewall = false
    }

    // Auto Refresh status & kuota setiap 3 detik
    LaunchedEffect(Unit) {
        while (true) {
            quotaStatus = QuotaManager.getQuotaStatus(context)
            topApps = QuotaManager.getTopMonitoredAppsUsage(context)
            hasUsagePerm = QuotaManager.hasUsageStatsPermission(context)
            hasAccessPerm = QuotaManager.hasAccessibilityPermission(context)
            isBatteryIgnored = QuotaManager.isBatteryOptimizationIgnored(context)
            manualLockUntil = QuotaManager.getManualLockUntil(context)
            isCharging = QuotaManager.isDeviceCharging(context)
            isAdminActive = dpm.isAdminActive(adminComponent)
            delay(3000)
        }
    }

    val bgColor = if (isDark) DarkBg else LightBg
    val cardColor = if (isDark) DarkCard else LightCard
    val cardBorder = if (isDark) DarkCardBorder else LightCardBorder
    val textPrimary = if (isDark) DarkTextPrimary else LightTextPrimary
    val textSecondary = if (isDark) DarkTextSecondary else LightTextSecondary

    Scaffold(
        containerColor = bgColor,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "CiroKuota",
                                fontFamily = RighteousFontFamily,
                                fontSize = 24.sp,
                                color = textPrimary,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = AccentGreen.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "v1.0",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentGreen,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Minimalist Wi-Fi Saver & Habit Blocker",
                            fontFamily = PoppinsFontFamily,
                            fontSize = 11.sp,
                            color = textSecondary
                        )
                    }

                    IconButton(
                        onClick = onToggleTheme,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(cardColor)
                            .border(1.dp, cardBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Ganti Tema",
                            tint = textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = cardColor,
                tonalElevation = 6.dp
            ) {
                CiroTab.values().forEach { tab ->
                    val isSelected = currentTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentTab = tab },
                        alwaysShowLabel = true,
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = if (isSelected) AccentGreen else textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontFamily = PoppinsFontFamily,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 10.sp,
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                color = if (isSelected) textPrimary else textSecondary
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = AccentGreen.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp)
        ) {
            when (currentTab) {
                CiroTab.DASHBOARD -> {
                    TabDashboardScreen(
                        isDark = isDark,
                        status = quotaStatus,
                        dailyBudgetMb = dailyBudgetMb,
                        isStopData = isStopData,
                        isVpnFirewall = isVpnFirewall,
                        isStopByKuota = isStopByKuota,
                        isAdultFilter = isAdultFilter,
                        selectedBlackholeCount = selectedBlackholeApps.size,
                        topApps = topApps,
                        cardColor = cardColor,
                        cardBorder = cardBorder,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onBudgetChange = { mb ->
                            dailyBudgetMb = mb
                            QuotaManager.setDailyBudgetMb(context, mb)
                            quotaStatus = QuotaManager.getQuotaStatus(context)
                        },
                        onToggleStopData = {
                            isStopData = !isStopData
                            QuotaManager.setKillswitchActive(context, isStopData)
                            if (isStopData) {
                                startVpnSafely()
                                Toast.makeText(context, "🚨 Stop Data Aktif! Internet target diblokir.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Stop Data Dimatikan.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onToggleVpn = {
                            if (isVpnFirewall) {
                                stopVpnSafely()
                                Toast.makeText(context, "VPN Blackhole Dimatikan", Toast.LENGTH_SHORT).show()
                            } else {
                                startVpnSafely()
                            }
                        },
                        onToggleStopByKuota = {
                            isStopByKuota = !isStopByKuota
                            QuotaManager.setAutoCutoffActive(context, isStopByKuota)
                            Toast.makeText(context, if (isStopByKuota) "Stop by Kuota Usage Aktif!" else "Stop by Kuota Usage Mati", Toast.LENGTH_SHORT).show()
                        },
                        onToggleAdultFilter = {
                            if (isAdultFilter) {
                                // Mau mematikan filter: WAJIB verifikasi kalimat komitmen!
                                commitmentDialogTitle = "KONFIRMASI MATIKAN FILTER DEWASA"
                                commitmentDialogDesc = "Anda sedang mencoba mematikan pemblokir situs dewasa. Apakah Anda yakin ingin mematikan perlindungan ini?"
                                pendingCommitmentAction = {
                                    isAdultFilter = false
                                    QuotaManager.setAdultFilterActive(context, false)
                                    if (isVpnFirewall) startVpnSafely()
                                    Toast.makeText(context, "Pemblokir Situs Dewasa Dimatikan", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                isAdultFilter = true
                                QuotaManager.setAdultFilterActive(context, true)
                                if (isVpnFirewall) startVpnSafely()
                                Toast.makeText(context, "🛡️ Pemblokir Situs Dewasa Aktif!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onManageBlackholeApps = {
                            showBlackholePickerDialog = true
                        }
                    )
                }

                CiroTab.KUOTA_APP -> {
                    TabQuotaAppScreen(
                        isDark = isDark,
                        appBlockPackages = appBlockPackages,
                        isCondByAppQuota = isCondByAppQuota,
                        cardColor = cardColor,
                        cardBorder = cardBorder,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        quotaRefreshTrigger = quotaRefreshTrigger,
                        onManageAppBlockApps = { showAppBlockPickerDialog = true },
                        onOpenCustomAppQuotaDialog = {
                            commitmentDialogTitle = "ATUR LIMIT KUOTA APLIKASI"
                            commitmentDialogDesc = "Anda akan mengubah batasan kuota untuk aplikasi yang diproteksi. Pastikan ini bukan bentuk kelonggaran yang impulsif."
                            pendingCommitmentAction = {
                                showCustomAppQuotaDialog = true
                            }
                        },
                        onToggleCondByAppQuota = {
                            if (isCondByAppQuota) {
                                commitmentDialogTitle = "MATIKAN PENGAMAN KUOTA APLIKASI"
                                commitmentDialogDesc = "Anda akan mematikan perlindungan pembatasan kuota per aplikasi."
                                pendingCommitmentAction = {
                                    isCondByAppQuota = false
                                    QuotaManager.setCondByAppQuotaActive(context, false)
                                    Toast.makeText(context, "Pengaman kuota aplikasi dimatikan.", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                isCondByAppQuota = true
                                QuotaManager.setCondByAppQuotaActive(context, true)
                                Toast.makeText(context, "🛡️ Pengaman kuota aplikasi aktif!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                CiroTab.HABITS -> {
                    TabHabitsScreen(
                        isDark = isDark,
                        habitProfiles = habitProfiles,
                        cardColor = cardColor,
                        cardBorder = cardBorder,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onCreateNewProfile = {
                            editingHabitProfile = HabitProfile(
                                id = java.util.UUID.randomUUID().toString(),
                                name = "Profil Baru",
                                isEnabled = true,
                                packages = emptySet(),
                                isByDuration = true,
                                durationMinutes = 20,
                                isByTime = false,
                                startHour = 22,
                                startMinute = 0,
                                endHour = 5,
                                endMinute = 0,
                                isByLaunch = false,
                                maxLaunches = 5
                            )
                            showHabitProfileDialog = true
                        },
                        onEditProfile = { profile ->
                            editingHabitProfile = profile
                            showHabitProfileDialog = true
                        },
                        onToggleProfile = { profile ->
                            val updated = profile.copy(isEnabled = !profile.isEnabled)
                            QuotaManager.saveOrUpdateHabitProfile(context, updated)
                            habitProfiles = QuotaManager.getHabitProfiles(context)
                        },
                        onDeleteProfile = { profile ->
                            commitmentDialogTitle = "HAPUS PROFIL HABIT"
                            commitmentDialogDesc = "Anda akan menghapus profil habit \"${profile.name}\". Apakah Anda yakin?"
                            pendingCommitmentAction = {
                                QuotaManager.deleteHabitProfile(context, profile.id)
                                habitProfiles = QuotaManager.getHabitProfiles(context)
                                Toast.makeText(context, "Profil \"${profile.name}\" dihapus", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                CiroTab.STRICT_MODE -> {
                    TabStrictModeScreen(
                        isDark = isDark,
                        manualLockUntil = manualLockUntil,
                        appCount = appBlockPackages.size,
                        isStrictPhrase = isStrictPhrase,
                        isStrictMath = isStrictMath,
                        isStrictTotp = isStrictTotp,
                        isStrictCharger = isStrictCharger,
                        isStrictTimer = isStrictTimer,
                        isCharging = isCharging,
                        cardColor = cardColor,
                        cardBorder = cardBorder,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onSetManualLock = { durationMillis ->
                            val until = System.currentTimeMillis() + durationMillis
                            manualLockUntil = until
                            QuotaManager.setManualLockUntil(context, until)
                            Toast.makeText(context, "Aplikasi target dikunci ketat!", Toast.LENGTH_SHORT).show()
                        },
                        onToggleStrictPhrase = {
                            val targetState = !isStrictPhrase
                            commitmentDialogTitle = if (targetState) "AKTIFKAN TANTANGAN KATA-KATA IKRAR" else "NONAKTIFKAN TANTANGAN KATA-KATA IKRAR"
                            commitmentDialogDesc = if (targetState) {
                                "Anda akan mewajibkan pengetikan kalimat ikrar setiap kali membuka gembok ketat."
                            } else {
                                "Anda akan mematikan syarat mengetik kalimat ikrar pada gembok ketat."
                            }
                            pendingCommitmentAction = {
                                isStrictPhrase = targetState
                                QuotaManager.setStrictPhraseActive(context, targetState)
                            }
                        },
                        onToggleStrictMath = {
                            val targetState = !isStrictMath
                            commitmentDialogTitle = if (targetState) "AKTIFKAN TANTANGAN MATEMATIKA" else "NONAKTIFKAN TANTANGAN MATEMATIKA"
                            commitmentDialogDesc = if (targetState) {
                                "Anda akan mewajibkan tantangan matematika acak setiap kali membuka gembok ketat."
                            } else {
                                "Anda akan mematikan tantangan matematika pada gembok ketat."
                            }
                            pendingCommitmentAction = {
                                isStrictMath = targetState
                                QuotaManager.setStrictMathActive(context, targetState)
                            }
                        },
                        onToggleStrictTotp = {
                            val targetState = !isStrictTotp
                            commitmentDialogTitle = if (targetState) "AKTIFKAN GOOGLE AUTHENTICATOR (TOTP)" else "NONAKTIFKAN GOOGLE AUTHENTICATOR (TOTP)"
                            commitmentDialogDesc = if (targetState) {
                                "Anda akan mewajibkan verifikasi kode 2FA Authenticator setiap kali membuka gembok ketat."
                            } else {
                                "Anda akan mematikan verifikasi 2FA Authenticator pada gembok ketat."
                            }
                            pendingCommitmentAction = {
                                isStrictTotp = targetState
                                QuotaManager.setStrictTotpActive(context, targetState)
                            }
                        },
                        onToggleStrictCharger = {
                            val targetState = !isStrictCharger
                            commitmentDialogTitle = if (targetState) "AKTIFKAN WAJIB COLOK CHARGER" else "NONAKTIFKAN WAJIB COLOK CHARGER"
                            commitmentDialogDesc = if (targetState) {
                                "Anda akan mengunci gembok ketat sampai HP benar-benar dicolok ke casan/charger."
                            } else {
                                "Anda akan mematikan syarat colok charger pada gembok ketat."
                            }
                            pendingCommitmentAction = {
                                isStrictCharger = targetState
                                QuotaManager.setStrictChargerActive(context, targetState)
                            }
                        },
                        onToggleStrictTimer = {
                            val targetState = !isStrictTimer
                            commitmentDialogTitle = if (targetState) "AKTIFKAN GEMBOK TIMER TANPA KOMPROMI" else "NONAKTIFKAN GEMBOK TIMER TANPA KOMPROMI"
                            commitmentDialogDesc = if (targetState) {
                                "PERINGATAN: Bypass unlock akan dimatikan mutlak sampai countdown selesai!"
                            } else {
                                "Anda akan mematikan gembok timer tanpa kompromi."
                            }
                            pendingCommitmentAction = {
                                isStrictTimer = targetState
                                QuotaManager.setStrictTimerActive(context, targetState)
                            }
                        },
                        onTryUnlock = {
                            if (isStrictTimer) {
                                Toast.makeText(context, "⏳ Gembok Timer Tanpa Kompromi Aktif! Tunggu waktu habis.", Toast.LENGTH_LONG).show()
                                return@TabStrictModeScreen
                            }
                            if (isStrictCharger && !QuotaManager.isDeviceCharging(context)) {
                                Toast.makeText(context, "🔌 Wajib tancapkan HP ke charger/casan terlebih dahulu!", Toast.LENGTH_LONG).show()
                                return@TabStrictModeScreen
                            }
                            showStrictUnlockDialog = true
                        }
                    )
                }

                CiroTab.SETTINGS -> {
                    TabSettingsScreen(
                        isDark = isDark,
                        hasAccessPerm = hasAccessPerm,
                        hasUsagePerm = hasUsagePerm,
                        isBatteryIgnored = isBatteryIgnored,
                        isAdminActive = isAdminActive,
                        isBlockSettingsApp = isBlockSettingsApp,
                        cardColor = cardColor,
                        cardBorder = cardBorder,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onActivateAdmin = {
                            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                putExtra(
                                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "Aktifkan Device Admin agar aplikasi CiroKuota kebal dari uninstall mendadak saat fase relaps/khilaf."
                                )
                            }
                            context.startActivity(intent)
                        },
                        onToggleBlockSettings = {
                            if (isBlockSettingsApp) {
                                commitmentDialogTitle = "MATIKAN BLOKIR APP SETTINGS"
                                commitmentDialogDesc = "Anda akan mematikan pemblokir otomatis aplikasi Pengaturan HP. Pastikan Anda tidak tergoda untuk mematikan Aksesibilitas atau mencabut Device Admin secara diam-diam!"
                                pendingCommitmentAction = {
                                    isBlockSettingsApp = false
                                    QuotaManager.setBlockSettingsActive(context, false)
                                    Toast.makeText(context, "Blokir App Settings Dimatikan.", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                isBlockSettingsApp = true
                                QuotaManager.setBlockSettingsActive(context, true)
                                Toast.makeText(context, "🛡️ Blokir Total App Settings Aktif!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onEmergencyExit = {
                            commitmentDialogTitle = "EMERGENCY SAFE EXIT"
                            commitmentDialogDesc = "Anda akan mencabut Device Admin dan membuka proteksi Settings selama 10 menit untuk keperluan maintenance/uninstall aman."
                            pendingCommitmentAction = {
                                try {
                                    dpm.removeActiveAdmin(adminComponent)
                                    isAdminActive = false
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                QuotaManager.grantSettingsGuardBypass(context, 10 * 60 * 1000L)
                                Toast.makeText(
                                    context,
                                    "Safe Exit Aktif! Device Admin dicabut & Proteksi Settings dibuka selama 10 menit.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
                }
            }
        }
    }

    // Modal Dialog: Kelola Aplikasi Blackhole
    if (showBlackholePickerDialog) {
        BlackholePickerDialog(
            isDark = isDark,
            selectedApps = selectedBlackholeApps,
            cardColor = cardColor,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            onToggleApp = { pkg ->
                QuotaManager.toggleBlackholePackage(context, pkg)
                selectedBlackholeApps = QuotaManager.getBlackholePackages(context)
                if (isVpnFirewall) startVpnSafely()
            },
            onResetDefault = {
                QuotaManager.resetToDefaultApps(context)
                selectedBlackholeApps = QuotaManager.getBlackholePackages(context)
                if (isVpnFirewall) startVpnSafely()
                Toast.makeText(context, "Aplikasi blackhole direset ke bawaan!", Toast.LENGTH_SHORT).show()
            },
            onClearAll = {
                QuotaManager.unselectAllApps(context)
                selectedBlackholeApps = QuotaManager.getBlackholePackages(context)
                if (isVpnFirewall) startVpnSafely()
                Toast.makeText(context, "Semua aplikasi dibatalkan pilihannya!", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showBlackholePickerDialog = false }
        )
    }

    // Modal Dialog: Kelola Aplikasi Target Pengaman Kuota (Dengan Anti-Bentrok)
    if (showAppBlockPickerDialog) {
        val conflictMap = remember(habitProfiles) {
            QuotaManager.getAppAssignmentMap(context, excludeQuota = true)
        }
        AppBlockPickerDialog(
            isDark = isDark,
            appBlockPackages = appBlockPackages,
            conflictMap = conflictMap,
            cardColor = cardColor,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            onTogglePackage = { pkg ->
                val current = QuotaManager.getAppBlockPackages(context)
                if (current.contains(pkg)) {
                    // Mau meng-unlist/menghapus aplikasi target: WAJIB komitmen!
                    commitmentDialogTitle = "UNLIST APLIKASI PENGAMAN KUOTA"
                    commitmentDialogDesc = "Anda akan melepas aplikasi ini dari Pengaman Kuota. Apakah Anda yakin?"
                    pendingCommitmentAction = {
                        QuotaManager.toggleAppBlockPackage(context, pkg)
                        appBlockPackages = QuotaManager.getAppBlockPackages(context)
                        quotaRefreshTrigger++
                        Toast.makeText(context, "Aplikasi dilepas dari pengaman kuota", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Menambah aplikasi baru: diperbolehkan langsung
                    QuotaManager.toggleAppBlockPackage(context, pkg)
                    appBlockPackages = QuotaManager.getAppBlockPackages(context)
                    quotaRefreshTrigger++
                }
            },
            onDismiss = { showAppBlockPickerDialog = false }
        )
    }

    // Modal Dialog: Atur Limit Kuota Masing-Masing Aplikasi
    if (showCustomAppQuotaDialog) {
        CustomAppQuotaDialog(
            isDark = isDark,
            appBlockPackages = appBlockPackages,
            cardColor = cardColor,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            onSave = { updatedBudgets ->
                commitmentDialogTitle = "KONFIRMASI SIMPAN LIMIT KUOTA"
                commitmentDialogDesc = "Anda akan menerapkan perubahan limit kuota aplikasi ini. Apakah Anda yakin dengan komitmen Anda?"
                pendingCommitmentAction = {
                    for ((pkg, mb) in updatedBudgets) {
                        QuotaManager.setAppSpecificBudgetMb(context, pkg, mb)
                    }
                    quotaRefreshTrigger++
                    showCustomAppQuotaDialog = false
                    Toast.makeText(context, "Limit kuota per aplikasi berhasil disimpan!", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showCustomAppQuotaDialog = false }
        )
    }

    // Modal Dialog: Edit / Tambah Profil Habit (Dengan Anti-Bentrok)
    if (showHabitProfileDialog && editingHabitProfile != null) {
        val profile = editingHabitProfile!!
        val conflictMap = remember(profile.id, habitProfiles, appBlockPackages) {
            QuotaManager.getAppAssignmentMap(context, excludeHabitProfileId = profile.id)
        }
        HabitProfileEditDialog(
            isDark = isDark,
            initialProfile = profile,
            conflictMap = conflictMap,
            cardColor = cardColor,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            onDismiss = {
                showHabitProfileDialog = false
                editingHabitProfile = null
            },
            onSave = { updatedProfile ->
                QuotaManager.saveOrUpdateHabitProfile(context, updatedProfile)
                habitProfiles = QuotaManager.getHabitProfiles(context)
                showHabitProfileDialog = false
                editingHabitProfile = null
                Toast.makeText(context, "Profil \"${updatedProfile.name}\" berhasil disimpan!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Modal Dialog: Verifikasi Kalimat Komitmen (Anti-Khilaf)
    if (pendingCommitmentAction != null) {
        CommitmentVerificationDialog(
            title = commitmentDialogTitle,
            actionDesc = commitmentDialogDesc,
            onDismiss = {
                pendingCommitmentAction = null
            },
            onConfirmed = {
                val action = pendingCommitmentAction
                pendingCommitmentAction = null
                action?.invoke()
            }
        )
    }

    // Modal Dialog: Custom Time Schedule
    if (showTimePickerDialog) {
        TimeRangePickerDialog(
            currentSchedule = timeSchedule,
            cardColor = cardColor,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            onDismiss = { showTimePickerDialog = false },
            onSave = { sH, sM, eH, eM ->
                timeSchedule = Pair(Pair(sH, sM), Pair(eH, eM))
                QuotaManager.setTimeSchedule(context, sH, sM, eH, eM)
                showTimePickerDialog = false
                Toast.makeText(context, "Jadwal jam disimpan!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Modal Dialog: Buka Strict Mode via Tantangan
    if (showStrictUnlockDialog) {
        val secretKey = remember { TotpHelper.getOrCreateSecret(context) }
        DarkSoulsUnlockDialog(
            secretKey = secretKey,
            onDismiss = { showStrictUnlockDialog = false },
            onSuccess = {
                showStrictUnlockDialog = false
                manualLockUntil = 0L
                QuotaManager.setManualLockUntil(context, 0L)
                Toast.makeText(context, "🎉 Selamat! Kunci gembok berhasil dibuka!", Toast.LENGTH_LONG).show()
            }
        )
    }
}

// ================= TAB 1: DASHBOARD =================
@Composable
fun TabDashboardScreen(
    isDark: Boolean,
    status: QuotaStatus,
    dailyBudgetMb: Float,
    isStopData: Boolean,
    isVpnFirewall: Boolean,
    isStopByKuota: Boolean,
    isAdultFilter: Boolean,
    selectedBlackholeCount: Int,
    topApps: List<AppDataUsage>,
    cardColor: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onBudgetChange: (Float) -> Unit,
    onToggleStopData: () -> Unit,
    onToggleVpn: () -> Unit,
    onToggleStopByKuota: () -> Unit,
    onToggleAdultFilter: () -> Unit,
    onManageBlackholeApps: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Quota Card
        item {
            HeroQuotaCard(
                status = status,
                cardBg = cardColor,
                cardBorder = cardBorder,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                onSelectBudget = onBudgetChange
            )
        }

        // Saklar Proteksi Cepat
        item {
            SectionHeader(title = "SAKLAR PROTEKSI CEPAT", textSecondary = textSecondary)
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 1. Stop Data (Ganti nama dari Killswitch)
                QuickSwitchCard(
                    title = "Stop Data",
                    subtitle = "Putus seketika koneksi internet aplikasi target yang diblackhole",
                    icon = Icons.Default.PowerSettingsNew,
                    isActive = isStopData,
                    activeColor = AccentRed,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onToggle = onToggleStopData
                )

                // 2. VPN Blackhole Firewall (Reactive subtext fixing Bug 3)
                val vpnSubtitle = if (selectedBlackholeCount == 0) {
                    "0 aplikasi dipilih (Proteksi siap)"
                } else {
                    "$selectedBlackholeCount aplikasi diblokir internetnya, aplikasi lain lancar"
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cardColor,
                    border = BorderStroke(1.dp, if (isVpnFirewall) Color(0xFF38BDF8).copy(alpha = 0.5f) else cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(if (isVpnFirewall) Color(0xFF38BDF8).copy(alpha = 0.15f) else cardBorder.copy(alpha = 0.3f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VpnKey,
                                        contentDescription = null,
                                        tint = if (isVpnFirewall) Color(0xFF38BDF8) else textSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Column {
                                    Text(
                                        text = "VPN Blackhole Firewall",
                                        fontFamily = PoppinsFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = textPrimary
                                    )
                                    Text(
                                        text = vpnSubtitle,
                                        fontFamily = PoppinsFontFamily,
                                        fontSize = 10.5.sp,
                                        color = textSecondary,
                                        lineHeight = 14.sp
                                    )
                                }
                            }

                            Switch(
                                checked = isVpnFirewall,
                                onCheckedChange = { onToggleVpn() },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF38BDF8),
                                    uncheckedThumbColor = textSecondary,
                                    uncheckedTrackColor = cardBorder
                                )
                            )
                        }

                        OutlinedButton(
                            onClick = onManageBlackholeApps,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp),
                            border = BorderStroke(1.dp, cardBorder)
                        ) {
                            Icon(Icons.Default.FilterAlt, contentDescription = null, tint = textPrimary, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Kelola Aplikasi Blackhole ($selectedBlackholeCount)",
                                fontFamily = PoppinsFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.5.sp,
                                color = textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 3. Stop by Kuota Usage (Ganti nama dari Auto Cutoff)
                QuickSwitchCard(
                    title = "Stop by Kuota Usage",
                    subtitle = "Hentikan akses internet jika pemakaian kuota tembus 100% budget",
                    icon = Icons.Default.DataSaverOn,
                    isActive = isStopByKuota,
                    activeColor = AccentAmber,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onToggle = onToggleStopByKuota
                )

                // 4. Blokir Situs Dewasa (MissAV & Porn Guard)
                QuickSwitchCard(
                    title = "Blokir Situs Dewasa (MissAV & Porn)",
                    subtitle = "Cegat missav.ai & domain dewasa via DNS NXDOMAIN & Aksesibilitas",
                    icon = Icons.Default.Block,
                    isActive = isAdultFilter,
                    activeColor = AccentRed,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onToggle = onToggleAdultFilter
                )
            }
        }

        // Ranking Aplikasi Penyedot Kuota Hari Ini
        item {
            SectionHeader(title = "APLIKASI PENYEDOT WI-FI HARI INI", textSecondary = textSecondary)
        }

        if (topApps.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = cardColor,
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Belum ada pemakaian Wi-Fi hari ini (atau izin Usage Stats belum aktif di Tab Pengaturan).",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 11.5.sp,
                        color = textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            }
        } else {
            items(topApps.size) { idx ->
                val app = topApps[idx]
                AppUsageRow(
                    app = app,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary
                )
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

// ================= TAB 2: KUOTA APP (PENGAMAN KUOTA KHUSUS) =================
@Composable
fun TabQuotaAppScreen(
    isDark: Boolean,
    appBlockPackages: Set<String>,
    isCondByAppQuota: Boolean,
    cardColor: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    quotaRefreshTrigger: Int,
    onManageAppBlockApps: () -> Unit,
    onOpenCustomAppQuotaDialog: () -> Unit,
    onToggleCondByAppQuota: () -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section Header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionHeader(title = "PENGAMAN KUOTA APLIKASI", textSecondary = textSecondary)
                Text(
                    text = "Pantau dan kunci aplikasi boros secara otomatis jika pemakaian data internet melebihi limit masing-masing.",
                    fontFamily = PoppinsFontFamily,
                    fontSize = 11.sp,
                    color = textSecondary
                )
            }
        }

        // Card 1: Target Aplikasi
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cardColor,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(AccentAmber.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Apps, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text(
                                text = "Aplikasi Terpantau",
                                fontFamily = PoppinsFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = textPrimary
                            )
                            Text(
                                text = "${appBlockPackages.size} aplikasi dilindungi oleh Pengaman Kuota",
                                fontFamily = PoppinsFontFamily,
                                fontSize = 10.5.sp,
                                color = textSecondary
                            )
                        }
                    }

                    Button(
                        onClick = onManageAppBlockApps,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "✎ Pilih / Kelola Aplikasi Terpantau",
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Saklar Utama & Tombol Atur Limit Custom
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickSwitchCard(
                    title = "🛡️ Aktifkan Pengaman Kuota",
                    subtitle = "Blokir aplikasi target seketika saat kuotanya melampaui limit MB yang Anda atur",
                    icon = Icons.Default.DataSaverOn,
                    isActive = isCondByAppQuota,
                    activeColor = AccentAmber,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onToggle = onToggleCondByAppQuota
                )

                if (isCondByAppQuota) {
                    // Tombol Custom Atur Limit Masing-Masing Aplikasi
                    Button(
                        onClick = onOpenCustomAppQuotaDialog,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Text(
                                text = "⚙️ Atur Limit Kuota Masing-Masing Aplikasi",
                                fontFamily = PoppinsFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // DAFTAR STATUS SISA KUOTA APLIKASI
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = subtaskBgColor(isDark),
                        border = BorderStroke(0.8.dp, cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "STATUS SISA KUOTA APLIKASI",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = AccentAmber
                            )

                            if (appBlockPackages.isEmpty()) {
                                Text(
                                    text = "Belum ada aplikasi yang dipilih untuk dipantau.",
                                    fontFamily = PoppinsFontFamily,
                                    fontSize = 11.sp,
                                    color = textSecondary
                                )
                            } else {
                                val pm = context.packageManager
                                appBlockPackages.forEach { pkg ->
                                    val appLabel = remember(pkg) {
                                        try {
                                            val info = pm.getApplicationInfo(pkg, 0)
                                            pm.getApplicationLabel(info).toString()
                                        } catch (_: Exception) {
                                            pkg
                                        }
                                    }
                                    val customBudgetMb = remember(pkg, quotaRefreshTrigger) {
                                        QuotaManager.getAppSpecificBudgetMb(context, pkg)
                                    }
                                    val usedMb = QuotaManager.getAppMonitoredUsageMb(context, pkg)
                                    val remainingMb = (customBudgetMb - usedMb).coerceAtLeast(0f)
                                    val percentUsed = ((usedMb / customBudgetMb) * 100f).coerceIn(0f, 100f)
                                    val isExceeded = usedMb >= customBudgetMb

                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = appLabel,
                                                fontFamily = PoppinsFontFamily,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp,
                                                color = textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = if (isExceeded) "HABIS (0 MB)" else "Sisa ${String.format(Locale.US, "%.1f", remainingMb)} MB",
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.5.sp,
                                                color = if (isExceeded) AccentRed else AccentGreen
                                            )
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Terpakai: ${String.format(Locale.US, "%.1f", usedMb)} / ${customBudgetMb.toInt()} MB (${String.format(Locale.US, "%.0f", percentUsed)}%)",
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontSize = 9.5.sp,
                                                color = textSecondary
                                            )
                                            if (isExceeded) {
                                                Text(
                                                    text = "🔒 DIBLOKIR",
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 9.sp,
                                                    color = AccentRed
                                                )
                                            }
                                        }

                                        LinearProgressIndicator(
                                            progress = { (percentUsed / 100f).coerceIn(0f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(5.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = if (isExceeded) AccentRed else if (percentUsed > 75f) AccentAmber else AccentGreen,
                                            trackColor = cardBorder.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

// ================= TAB 3: HABITS (DYNAMIC APPBLOCK-STYLE PROFILES) =================
@Composable
fun TabHabitsScreen(
    isDark: Boolean,
    habitProfiles: List<HabitProfile>,
    cardColor: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onCreateNewProfile: () -> Unit,
    onEditProfile: (HabitProfile) -> Unit,
    onToggleProfile: (HabitProfile) -> Unit,
    onDeleteProfile: (HabitProfile) -> Unit
) {
    val context = LocalContext.current
    val pm = remember { context.packageManager }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section Header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionHeader(title = "HABIT BLOCKER PROFILES", textSecondary = textSecondary)
                Text(
                    text = "Bebas buat banyak profil disiplin ala AppBlock (durasi sesi, jadwal jam gembok, atau batas buka harian).",
                    fontFamily = PoppinsFontFamily,
                    fontSize = 11.sp,
                    color = textSecondary
                )
            }
        }

        // Tombol Buat Profil Baru
        item {
            Button(
                onClick = onCreateNewProfile,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Text(
                        text = "➕ Buat Profil Habit Baru",
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }
        }

        // Daftar Profil Dinamis
        if (habitProfiles.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cardColor,
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = textSecondary, modifier = Modifier.size(36.dp))
                        Text(
                            text = "Belum Ada Profil Habit",
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = textPrimary
                        )
                        Text(
                            text = "Klik tombol di atas untuk membuat jadwal blokir kustom baru.",
                            fontFamily = PoppinsFontFamily,
                            fontSize = 11.sp,
                            color = textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(habitProfiles.size) { idx ->
                val profile = habitProfiles[idx]
                HabitProfileCard(
                    profile = profile,
                    pm = pm,
                    cardColor = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onToggle = { onToggleProfile(profile) },
                    onEdit = { onEditProfile(profile) },
                    onDelete = { onDeleteProfile(profile) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun HabitProfileCard(
    profile: HabitProfile,
    pm: PackageManager,
    cardColor: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val activeBorder = if (profile.isEnabled) AccentGreen.copy(alpha = 0.5f) else cardBorder

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardColor,
        border = BorderStroke(1.dp, activeBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Card: Title + Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (profile.isEnabled) AccentGreen.copy(alpha = 0.15f) else cardBorder.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = if (profile.isEnabled) AccentGreen else textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column {
                        Text(
                            text = profile.name,
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${profile.packages.size} aplikasi terpantau",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            color = textSecondary
                        )
                    }
                }

                Switch(
                    checked = profile.isEnabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AccentGreen,
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = cardBorder
                    )
                )
            }

            Divider(color = cardBorder, thickness = 0.8.dp)

            // Pemicu yang aktif dalam profil ini
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (profile.isByDuration) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.HourglassTop, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(14.dp))
                        Text(
                            text = "By Duration: Maks ${profile.durationMinutes} menit sesi pemakaian",
                            fontFamily = PoppinsFontFamily,
                            fontSize = 11.sp,
                            color = textPrimary
                        )
                    }
                }
                if (profile.isByTime) {
                    val timeStr = String.format(Locale.US, "%02d:%02d - %02d:%02d", profile.startHour, profile.startMinute, profile.endHour, profile.endMinute)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.AccessTime, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                        Text(
                            text = "By Time: Gembok pada jam $timeStr",
                            fontFamily = PoppinsFontFamily,
                            fontSize = 11.sp,
                            color = textPrimary
                        )
                    }
                }
                if (profile.isByLaunch) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.TouchApp, contentDescription = null, tint = AccentRed, modifier = Modifier.size(14.dp))
                        Text(
                            text = "By Launch: Maks ${profile.maxLaunches} kali buka per hari",
                            fontFamily = PoppinsFontFamily,
                            fontSize = 11.sp,
                            color = textPrimary
                        )
                    }
                }
                if (!profile.isByDuration && !profile.isByTime && !profile.isByLaunch) {
                    Text(
                        text = "Belum ada kondisi pemicu yang dicentang.",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 10.5.sp,
                        color = textSecondary
                    )
                }
            }

            // Preview Nama Aplikasi
            if (profile.packages.isNotEmpty()) {
                val appNames = profile.packages.take(3).map { pkg ->
                    try {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(ai).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                }
                val previewStr = if (profile.packages.size > 3) {
                    "${appNames.joinToString(", ")} +${profile.packages.size - 3} lainnya"
                } else {
                    appNames.joinToString(", ")
                }
                Text(
                    text = "Aplikasi: $previewStr",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Action Buttons: Edit & Hapus
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    border = BorderStroke(1.dp, cardBorder),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("✎ Edit Profil", fontSize = 11.sp, color = textPrimary)
                }

                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("🗑️ Hapus", fontSize = 11.sp, color = AccentRed)
                }
            }
        }
    }
}

// ================= TAB 4: STRICT MODE (GEMBOK KETAT & OPSI TANTANGAN) =================
@Composable
fun TabStrictModeScreen(
    isDark: Boolean,
    manualLockUntil: Long,
    appCount: Int,
    isStrictPhrase: Boolean,
    isStrictMath: Boolean,
    isStrictTotp: Boolean,
    isStrictCharger: Boolean,
    isStrictTimer: Boolean,
    isCharging: Boolean,
    cardColor: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onSetManualLock: (Long) -> Unit,
    onToggleStrictPhrase: () -> Unit,
    onToggleStrictMath: () -> Unit,
    onToggleStrictTotp: () -> Unit,
    onToggleStrictCharger: () -> Unit,
    onToggleStrictTimer: () -> Unit,
    onTryUnlock: () -> Unit
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val isLocked = manualLockUntil > now

    val secretKey = remember { TotpHelper.getOrCreateSecret(context) }
    var isTotpConfigured by remember { mutableStateOf(TotpHelper.isConfigured(context)) }
    var showTestDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section Header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionHeader(title = "STRICT MODE (GEMBOK KETAT)", textSecondary = textSecondary)
                Text(
                    text = "Cegah impulsivitas dengan kombinasi gembok ketat ala AppBlock & Dark Souls.",
                    fontFamily = PoppinsFontFamily,
                    fontSize = 11.sp,
                    color = textSecondary
                )
            }
        }

        // Status Kunci Ketat / Instant Lock Card
        item {
            if (isLocked) {
                val remMinutes = ((manualLockUntil - now) / 60000) + 1
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = AccentRed.copy(alpha = 0.15f),
                    border = BorderStroke(1.2.dp, AccentRed),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = AccentRed, modifier = Modifier.size(36.dp))
                        Text(
                            text = "STATUS: TERKUNCI KETAT",
                            fontFamily = RighteousFontFamily,
                            fontSize = 18.sp,
                            color = AccentRed
                        )
                        Text(
                            text = "$appCount aplikasi target terkunci selama $remMinutes menit lagi.",
                            fontFamily = PoppinsFontFamily,
                            fontSize = 12.sp,
                            color = textPrimary,
                            textAlign = TextAlign.Center
                        )

                        if (isStrictTimer) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkSubtaskBg,
                                border = BorderStroke(1.dp, cardBorder)
                            ) {
                                Text(
                                    text = "⏳ Mode Timer Tanpa Kompromi aktif! Bypass ditiadakan sampai waktu selesai.",
                                    fontFamily = PoppinsFontFamily,
                                    fontSize = 11.sp,
                                    color = textSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        } else {
                            Button(
                                onClick = onTryUnlock,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                            ) {
                                Text(
                                    text = "🔓 Buka Kunci (Taklukkan Tantangan)",
                                    fontFamily = PoppinsFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = cardColor,
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(AccentGreen.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.LockOpen, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                            }
                            Column {
                                Text(
                                    text = "Status: Bebas / Tidak Terkunci",
                                    fontFamily = PoppinsFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = AccentGreen
                                )
                                Text(
                                    text = "Aplikasi mengikuti jadwal otomatis di Tab Schedules",
                                    fontFamily = PoppinsFontFamily,
                                    fontSize = 10.5.sp,
                                    color = textSecondary
                                )
                            }
                        }

                        Text(
                            text = "Kunci Instan Sekarang (Manual Strict Lock):",
                            fontFamily = PoppinsFontFamily,
                            fontSize = 11.sp,
                            color = textSecondary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "1 Jam" to (60 * 60 * 1000L),
                                "2 Jam" to (120 * 60 * 1000L),
                                "4 Jam" to (240 * 60 * 1000L),
                                "8 Jam" to (480 * 60 * 1000L)
                            ).forEach { (label, ms) ->
                                Button(
                                    onClick = { onSetManualLock(ms) },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        text = label,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = Color.White,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Opsi Pilihan Tantangan Anti-Bypass
        item {
            SectionHeader(title = "PILIHAN TANTANGAN UNLOCK (KOMBINASI)", textSecondary = textSecondary)
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 1. Tantangan Kata-kata / Kalimat Ikrar
                QuickSwitchCard(
                    title = "📜 Tantangan Kata-Kata / Teks Ikrar",
                    subtitle = "Wajib mengetik kalimat komitmen penuh tanpa typo untuk rem kesadaran",
                    icon = Icons.Default.Description,
                    isActive = isStrictPhrase,
                    activeColor = AccentOrange,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onToggle = onToggleStrictPhrase
                )

                // 2. Tantangan Matematika
                QuickSwitchCard(
                    title = "🧩 Tantangan Matematika (Dark Souls)",
                    subtitle = "Soal penjumlahan acak mengaktifkan otak rasional",
                    icon = Icons.Default.Calculate,
                    isActive = isStrictMath,
                    activeColor = AccentAmber,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onToggle = onToggleStrictMath
                )

                // 3. Google Authenticator Reseh (TOTP)
                QuickSwitchCard(
                    title = "🚨 Google Authenticator Reseh (TOTP)",
                    subtitle = "Wajib masukkan 6 digit token dari Authenticator dalam 15 detik",
                    icon = Icons.Default.Security,
                    isActive = isStrictTotp,
                    activeColor = AccentRed,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onToggle = onToggleStrictTotp
                )

                // 3. Wajib Colok Charger / Cas HP
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    QuickSwitchCard(
                        title = "🔌 Wajib Colok Charger / Cas HP",
                        subtitle = "Hanya bisa buka blokir jika HP sedang dicolok ke casan kabel/wireless",
                        icon = Icons.Default.ElectricBolt,
                        isActive = isStrictCharger,
                        activeColor = Color(0xFF38BDF8),
                        cardBg = cardColor,
                        cardBorder = cardBorder,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onToggle = onToggleStrictCharger
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCharging) AccentGreen.copy(alpha = 0.15f) else AccentAmber.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, if (isCharging) AccentGreen else AccentAmber),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isCharging) "⚡ Status: HP Sedang Dicas (Siap Buka Gembok)" else "🔌 Status: Belum Dicolok Casan (Gembok Terkunci)",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp,
                            color = if (isCharging) AccentGreen else AccentAmber,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 5.dp)
                        )
                    }
                }

                // 4. Gembok Timer Tanpa Kompromi
                QuickSwitchCard(
                    title = "⏳ Gembok Timer Tanpa Kompromi",
                    subtitle = "Tombol bypass dimatikan mutlak sampai waktu countdown berakhir!",
                    icon = Icons.Default.HourglassTop,
                    isActive = isStrictTimer,
                    activeColor = AccentRed,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onToggle = onToggleStrictTimer
                )
            }
        }

        // Section: Setup Google Authenticator 2FA Card
        item {
            SectionHeader(title = "SETUP GOOGLE AUTHENTICATOR (TOTP)", textSecondary = textSecondary)
        }

        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cardColor,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                            Text(
                                text = "Kunci Rahasia 2FA",
                                fontFamily = PoppinsFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = textPrimary
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isTotpConfigured) AccentGreen.copy(alpha = 0.15f) else AccentAmber.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (isTotpConfigured) "Tersambung" else "Belum Dites",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isTotpConfigured) AccentGreen else AccentAmber,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Key Display & Copy
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = subtaskBgColor(isDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = secretKey,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = textPrimary,
                                letterSpacing = 1.sp
                            )
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("TOTP Secret", secretKey))
                                    Toast.makeText(context, "Secret key disalin ke clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Salin", tint = textSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showTestDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text("🔑 Test Kode OTP", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        OutlinedButton(
                            onClick = {
                                TotpHelper.resetSecret(context)
                                isTotpConfigured = false
                                Toast.makeText(context, "Secret key baru di-generate!", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            border = BorderStroke(1.dp, cardBorder)
                        ) {
                            Text("🔄 Reset Key", fontSize = 11.sp, color = textPrimary)
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }

    if (showTestDialog) {
        var testInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTestDialog = false },
            title = { Text("Test Kode Authenticator", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Buka Google Authenticator dan masukkan 6 digit token untuk memverifikasi sambungan:", fontSize = 12.sp)
                    OutlinedTextField(
                        value = testInput,
                        onValueChange = { if (it.length <= 6) testInput = it },
                        label = { Text("6 Digit Kode OTP") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ok = TotpHelper.verifyCode(secretKey, testInput)
                        if (ok) {
                            TotpHelper.setConfigured(context, true)
                            isTotpConfigured = true
                            showTestDialog = false
                            Toast.makeText(context, "✅ Berhasil! Authenticator terverifikasi.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "❌ Kode salah! Pastikan jam HP akurat.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text("Verifikasi", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTestDialog = false }) {
                    Text("Tutup")
                }
            }
        )
    }
}

// ================= TAB 4: PENGATURAN & KEKEBALAN SISTEM HP =================
@Composable
fun TabSettingsScreen(
    isDark: Boolean,
    hasAccessPerm: Boolean,
    hasUsagePerm: Boolean,
    isBatteryIgnored: Boolean,
    isAdminActive: Boolean,
    isBlockSettingsApp: Boolean,
    cardColor: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onActivateAdmin: () -> Unit,
    onToggleBlockSettings: () -> Unit,
    onEmergencyExit: () -> Unit
) {
    val context = LocalContext.current
    var commitmentPhrase by remember { mutableStateOf(QuotaManager.getCommitmentPhrase(context)) }
    var pendingPhrase by remember { mutableStateOf(QuotaManager.getPendingCommitmentPhrase(context)) }
    var pendingApplyTime by remember { mutableStateOf(QuotaManager.getPendingCommitmentApplyTime(context)) }
    var showEditPhraseDialog by remember { mutableStateOf(false) }
    var editInputText by remember { mutableStateOf("") }

    // Update state pending timer
    LaunchedEffect(Unit) {
        while (true) {
            commitmentPhrase = QuotaManager.getCommitmentPhrase(context)
            pendingPhrase = QuotaManager.getPendingCommitmentPhrase(context)
            pendingApplyTime = QuotaManager.getPendingCommitmentApplyTime(context)
            delay(1000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // --- 1. KEKEBALAN SISTEM & DEVICE ADMIN ---
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionHeader(title = "KEKEBALAN SISTEM & ANTI-UNINSTALL", textSecondary = textSecondary)
                Text(
                    text = "Konfigurasi Device Admin dan izin sistem agar CiroKuota kebal dari uninstall mendadak serta tidak dimatikan paksa di Pengaturan.",
                    fontFamily = PoppinsFontFamily,
                    fontSize = 11.sp,
                    color = textSecondary
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Device Admin Status Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cardColor,
                    border = BorderStroke(1.dp, if (isAdminActive) AccentGreen.copy(alpha = 0.5f) else AccentRed.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (isAdminActive) AccentGreen.copy(alpha = 0.15f) else AccentRed.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isAdminActive) Icons.Default.Security else Icons.Default.GppMaybe,
                                    contentDescription = null,
                                    tint = if (isAdminActive) AccentGreen else AccentRed,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Device Admin (Anti-Uninstall)",
                                    fontFamily = PoppinsFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = textPrimary
                                )
                                Text(
                                    text = if (isAdminActive) "Aktif • Aplikasi dilindungi dari pencopotan pemasangan" else "Nonaktif • Sangat rentan di-uninstall saat relaps!",
                                    fontFamily = PoppinsFontFamily,
                                    fontSize = 10.sp,
                                    color = if (isAdminActive) AccentGreen else AccentRed
                                )
                            }
                        }

                        if (!isAdminActive) {
                            Button(
                                onClick = onActivateAdmin,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Aktifkan", fontFamily = PoppinsFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Saklar Blokir App Pengaturan HP (Settings App)
                QuickSwitchCard(
                    title = "🔒 Kunci Total Aplikasi Pengaturan (Settings)",
                    subtitle = "Mencegah akses ke Pengaturan HP agar Aksesibilitas tidak bisa diutak-atik (Buka via Emergency Exit)",
                    icon = Icons.Default.Lock,
                    isActive = isBlockSettingsApp,
                    activeColor = AccentRed,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onToggle = onToggleBlockSettings
                )

                // Izin Aksesibilitas
                PermissionStatusCard(
                    title = "Layanan Aksesibilitas CiroKuota",
                    desc = "Wajib aktif untuk mendeteksi pembukaan aplikasi target & blokir situs terlarang",
                    isGranted = hasAccessPerm,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onClickAction = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                )

                // Izin Bebas Baterai (Unrestricted)
                PermissionStatusCard(
                    title = "Bebas dari Hemat Baterai (Anti-Kill)",
                    desc = "Wajib aktif agar HP tidak mematikan Aksesibilitas secara diam-diam",
                    isGranted = isBatteryIgnored,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onClickAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    }
                )

                // Izin Usage Stats
                PermissionStatusCard(
                    title = "Akses Penggunaan Kuota (Usage Stats)",
                    desc = "Diperlukan untuk membaca MB kuota per aplikasi secara akurat",
                    isGranted = hasUsagePerm,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onClickAction = {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        context.startActivity(intent)
                    }
                )
            }
        }

        // --- 2. BENTENG KOMITMEN & 24 JAM COOLDOWN ---
        item {
            SectionHeader(title = "KALIMAT IKRAR & COOLDOWN 24 JAM", textSecondary = textSecondary)
        }

        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cardColor,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📜 Kalimat Ikrar Saat Ini:",
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = textPrimary,
                            modifier = Modifier.weight(1f)
                        )

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AccentBlue.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, AccentBlue.copy(alpha = 0.35f)),
                            modifier = Modifier.clickable {
                                editInputText = commitmentPhrase
                                showEditPhraseDialog = true
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = AccentBlue
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Ubah (24 Jam)",
                                    fontFamily = PoppinsFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AccentBlue,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // Kalimat Aktif
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = (if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)),
                        border = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "\"$commitmentPhrase\"",
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            color = AccentOrange,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    // Status Cooldown Pending jika ada
                    if (pendingPhrase != null) {
                        val diffMs = pendingApplyTime - System.currentTimeMillis()
                        val hours = if (diffMs > 0) diffMs / (1000 * 60 * 60) else 0
                        val minutes = if (diffMs > 0) (diffMs % (1000 * 60 * 60)) / (1000 * 60) else 0

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AccentPurple.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, AccentPurple.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.HourglassTop, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Sedang Masa Cooldown (${hours}j ${minutes}m lagi)",
                                        fontFamily = PoppinsFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = AccentPurple
                                    )
                                }
                                Text(
                                    text = "Permintaan ikrar baru: \"$pendingPhrase\"",
                                    fontFamily = PoppinsFontFamily,
                                    fontSize = 10.sp,
                                    color = textSecondary
                                )
                                OutlinedButton(
                                    onClick = {
                                        QuotaManager.cancelPendingCommitment(context)
                                        pendingPhrase = null
                                    },
                                    modifier = Modifier.align(Alignment.End),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Batalkan Perubahan", fontFamily = PoppinsFontFamily, fontSize = 10.sp, color = AccentRed)
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "💡 Perubahan kalimat ikrar memerlukan masa tunggu (cooldown) 24 jam sebelum berlaku, agar Anda tidak bisa mempermudah kalimatnya saat sedang impulsif.",
                            fontFamily = PoppinsFontFamily,
                            fontSize = 10.sp,
                            color = textSecondary,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }

        // --- 3. EMERGENCY SAFE EXIT (PELINDUNG SEWAKTU-WAKTU JIKA NGEBUG) ---
        item {
            SectionHeader(title = "EMERGENCY SAFE EXIT (JIKA ADA BUG)", textSecondary = textSecondary)
        }

        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cardColor,
                border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WarningAmber, contentDescription = null, tint = AccentRed, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Keluar Darurat & Buka Proteksi Settings",
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = AccentRed
                        )
                    }

                    Text(
                        text = "Jika suatu saat aplikasi mengalami bug fatal atau Anda benar-benar butuh mencopot aplikasi: tombol ini akan mencabut Device Admin dan menonaktifkan proteksi anti-tamper Settings selama 10 menit.\n\nTetap diwajibkan mengetik kalimat ikrar penuh sebagai rem kesadaran.",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 11.sp,
                        color = textSecondary,
                        lineHeight = 15.sp
                    )

                    Button(
                        onClick = onEmergencyExit,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Buka Emergency Safe Exit", fontFamily = PoppinsFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // --- 4. Panduan Kunci di Recent Apps (Gembok) ---
        item {
            SectionHeader(title = "PANDUAN GEMBOK RECENT APPS", textSecondary = textSecondary)
        }

        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cardColor,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "🔒 Panduan Kunci Aplikasi di Recent Apps:",
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = textPrimary
                    )
                    Text(
                        text = "1. Buka layar Recent Apps (aplikasi yang baru dibuka).\n2. Tekan dan tahan kartu aplikasi CiroKuota.\n3. Ketuk ikon 'Gembok' (Lock).\nDengan cara ini, saat Tuan Ciro membersihkan RAM HP, CiroKuota dan layanan Aksesibilitasnya tidak akan pernah terlepas!",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 11.sp,
                        color = textSecondary,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }

    // Dialog Pengajuan Perubahan Kalimat Ikrar (24h Cooldown)
    if (showEditPhraseDialog) {
        AlertDialog(
            onDismissRequest = { showEditPhraseDialog = false },
            title = {
                Text(
                    text = "Ajukan Kalimat Ikrar Baru",
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Kalimat ini baru akan aktif setelah masa cooldown 24 jam berjalan tanpa dibatalkan.",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 11.sp,
                        color = textSecondary
                    )
                    OutlinedTextField(
                        value = editInputText,
                        onValueChange = { editInputText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Tulis kalimat ikrar baru...") },
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editInputText.isNotBlank()) {
                            QuotaManager.requestChangeCommitmentPhrase(context, editInputText)
                            pendingPhrase = QuotaManager.getPendingCommitmentPhrase(context)
                            pendingApplyTime = QuotaManager.getPendingCommitmentApplyTime(context)
                            showEditPhraseDialog = false
                            Toast.makeText(context, "Permintaan ikrar disimpan! Aktif dalam 24 jam.", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text("Ajukan (Cooldown 24 Jam)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditPhraseDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

// ================= MODAL DIALOGS =================

// 1. DIALOG PEMILIH APLIKASI BLACKHOLE
@Composable
fun BlackholePickerDialog(
    isDark: Boolean,
    selectedApps: Set<String>,
    cardColor: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onToggleApp: (String) -> Unit,
    onResetDefault: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf("ALL") }

    val candidateApps = remember(selectedApps) {
        QuotaManager.getAllCandidateApps(context, selectedApps)
    }

    val selectedCount = remember(candidateApps) { candidateApps.count { it.isSelected } }

    val filtered = remember(candidateApps, query, filterMode) {
        candidateApps.filter { app ->
            val matchesQuery = query.isBlank() ||
                    app.appName.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)

            val matchesFilter = when (filterMode) {
                "USER" -> !app.isSystemApp
                "SYSTEM" -> app.isSystemApp
                "SELECTED" -> app.isSelected
                else -> true
            }

            matchesQuery && matchesFilter
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Aplikasi Blackhole Firewall",
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = textPrimary
                )
                Text(
                    text = "$selectedCount dari ${candidateApps.size} aplikasi dipilih",
                    fontFamily = PoppinsFontFamily,
                    fontSize = 11.sp,
                    color = textSecondary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Cari aplikasi...", fontSize = 11.5.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("ALL" to "Semua", "USER" to "User", "SYSTEM" to "Sistem", "SELECTED" to "Dipilih").forEach { (mode, label) ->
                        val isSel = filterMode == mode
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSel) Color(0xFF38BDF8) else cardColor,
                            border = BorderStroke(1.dp, if (isSel) Color(0xFF38BDF8) else cardBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { filterMode = mode }
                        ) {
                            Text(
                                text = label,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                color = if (isSel) Color.Black else textPrimary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = onResetDefault,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text("Reset Bawaan", fontSize = 10.sp, color = textPrimary, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = onClearAll,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text("Batal Semua", fontSize = 10.sp, color = AccentRed, maxLines = 1)
                    }
                }

                Divider(color = cardBorder, thickness = 0.8.dp)

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filtered.size) { idx ->
                        val app = filtered[idx]
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (app.isSelected) Color(0xFF38BDF8).copy(alpha = 0.1f) else cardColor,
                            border = BorderStroke(1.dp, if (app.isSelected) Color(0xFF38BDF8).copy(alpha = 0.4f) else cardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleApp(app.packageName) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = app.isSelected,
                                    onCheckedChange = { onToggleApp(app.packageName) },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF38BDF8))
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.appName,
                                        fontFamily = PoppinsFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.5.sp,
                                        color = textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.packageName,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 8.5.sp,
                                        color = textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
            ) {
                Text("Selesai", fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    )
}

// 2. DIALOG PEMILIH APLIKASI TARGET (DENGAN ANTI-BENTROK)
@Composable
fun AppBlockPickerDialog(
    isDark: Boolean,
    appBlockPackages: Set<String>,
    conflictMap: Map<String, String> = emptyMap(),
    cardColor: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onTogglePackage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf("ALL") }

    val candidateApps = remember(appBlockPackages, conflictMap) {
        QuotaManager.getAllCandidateApps(context, appBlockPackages, conflictMap)
    }

    val selectedCount = remember(candidateApps) { candidateApps.count { it.isSelected } }

    val filtered = remember(candidateApps, query, filterMode) {
        candidateApps.filter { app ->
            val matchesQuery = query.isBlank() ||
                    app.appName.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)

            val matchesFilter = when (filterMode) {
                "USER" -> !app.isSystemApp
                "SYSTEM" -> app.isSystemApp
                "SELECTED" -> app.isSelected
                else -> true
            }

            matchesQuery && matchesFilter
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Pilih Aplikasi Pengaman Kuota",
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = textPrimary
                )
                Text(
                    text = "$selectedCount dari ${candidateApps.size} aplikasi dipilih untuk dipantau kuotanya",
                    fontFamily = PoppinsFontFamily,
                    fontSize = 11.sp,
                    color = textSecondary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Cari aplikasi...", fontSize = 11.5.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("ALL" to "Semua", "USER" to "User", "SYSTEM" to "Sistem", "SELECTED" to "Dipilih").forEach { (mode, label) ->
                        val isSel = filterMode == mode
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSel) AccentAmber else cardColor,
                            border = BorderStroke(1.dp, if (isSel) AccentAmber else cardBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { filterMode = mode }
                        ) {
                            Text(
                                text = label,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                color = if (isSel) Color.Black else textPrimary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val availableDefaults = QuotaManager.DEFAULT_APPBLOCK_APPS.filter { !conflictMap.containsKey(it) }.toSet()
                            QuotaManager.setAppBlockPackages(context, availableDefaults)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text("Sosmed & Video", fontSize = 10.sp, color = textPrimary, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = {
                            QuotaManager.setAppBlockPackages(context, emptySet())
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text("Batal Semua", fontSize = 10.sp, color = AccentRed, maxLines = 1)
                    }
                }

                Divider(color = cardBorder, thickness = 0.8.dp)

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filtered.size) { idx ->
                        val app = filtered[idx]
                        val isBlockedByConflict = app.isAssignedElsewhere && !app.isSelected

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when {
                                app.isSelected -> AccentAmber.copy(alpha = 0.1f)
                                isBlockedByConflict -> cardBorder.copy(alpha = 0.15f)
                                else -> cardColor
                            },
                            border = BorderStroke(
                                1.dp,
                                when {
                                    app.isSelected -> AccentAmber.copy(alpha = 0.4f)
                                    isBlockedByConflict -> cardBorder.copy(alpha = 0.3f)
                                    else -> cardBorder
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isBlockedByConflict) { onTogglePackage(app.packageName) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = app.isSelected,
                                    enabled = !isBlockedByConflict,
                                    onCheckedChange = { if (!isBlockedByConflict) onTogglePackage(app.packageName) },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = AccentAmber,
                                        disabledCheckedColor = Color.Gray,
                                        disabledUncheckedColor = Color.DarkGray
                                    )
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.appName,
                                        fontFamily = PoppinsFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.5.sp,
                                        color = if (isBlockedByConflict) textSecondary.copy(alpha = 0.5f) else textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.packageName,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 8.5.sp,
                                        color = textSecondary.copy(alpha = if (isBlockedByConflict) 0.4f else 0.8f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isBlockedByConflict) {
                                        Text(
                                            text = "🔒 Digunakan di: ${app.assignedLabel}",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 8.5.sp,
                                            color = AccentAmber,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AccentAmber)
            ) {
                Text("Selesai", fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    )
}

// 3. DIALOG ATUR LIMIT KUOTA MASING-MASING APLIKASI SECARA MANUAL
@Composable
fun CustomAppQuotaDialog(
    isDark: Boolean,
    appBlockPackages: Set<String>,
    cardColor: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onSave: (Map<String, Float>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val pm = remember { context.packageManager }

    // Map package -> String input text
    val inputValues = remember(appBlockPackages) {
        val map = mutableStateMapOf<String, String>()
        for (pkg in appBlockPackages) {
            val budget = QuotaManager.getAppSpecificBudgetMb(context, pkg)
            map[pkg] = budget.toInt().toString()
        }
        map
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Atur Limit Kuota Masing-Masing Aplikasi",
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = textPrimary
                )
                Text(
                    text = "Tentukan jatah data (MB) khusus untuk tiap aplikasi yang dipantau.",
                    fontFamily = PoppinsFontFamily,
                    fontSize = 11.sp,
                    color = textSecondary
                )
            }
        },
        text = {
            if (appBlockPackages.isEmpty()) {
                Text(
                    text = "Belum ada aplikasi yang dimasukkan ke Pengaman Kuota. Pilih aplikasi terlebih dahulu.",
                    fontFamily = PoppinsFontFamily,
                    fontSize = 12.sp,
                    color = textSecondary
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(appBlockPackages.toList().size) { idx ->
                        val pkg = appBlockPackages.toList()[idx]
                        val appLabel = remember(pkg) {
                            try {
                                val ai = pm.getApplicationInfo(pkg, 0)
                                pm.getApplicationLabel(ai).toString()
                            } catch (_: Exception) {
                                pkg
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = subtaskBgColor(isDark),
                            border = BorderStroke(0.8.dp, cardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = appLabel,
                                        fontFamily = PoppinsFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        color = textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = pkg,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 8.5.sp,
                                        color = textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    OutlinedTextField(
                                        value = inputValues[pkg] ?: "100",
                                        onValueChange = { newVal ->
                                            val digits = newVal.filter { it.isDigit() }
                                            inputValues[pkg] = digits
                                        },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.width(90.dp),
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    )
                                    Text(
                                        text = "MB",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val result = mutableMapOf<String, Float>()
                    for ((pkg, valueStr) in inputValues) {
                        val mb = valueStr.toFloatOrNull() ?: 100f
                        result[pkg] = mb
                    }
                    onSave(result)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                Text("Simpan Limit", fontWeight = FontWeight.Bold, color = Color.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = textSecondary)
            }
        }
    )
}

// 4. DIALOG EDIT / BUAT PROFIL HABIT (DENGAN PEMILIH APP ANTI-BENTROK)
@Composable
fun HabitProfileEditDialog(
    isDark: Boolean,
    initialProfile: HabitProfile,
    conflictMap: Map<String, String>,
    cardColor: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onDismiss: () -> Unit,
    onSave: (HabitProfile) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initialProfile.name) }
    var selectedPkgs by remember { mutableStateOf(initialProfile.packages) }

    var isByDuration by remember { mutableStateOf(initialProfile.isByDuration) }
    var durationMinutes by remember { mutableIntStateOf(initialProfile.durationMinutes) }

    var isByTime by remember { mutableStateOf(initialProfile.isByTime) }
    var sH by remember { mutableIntStateOf(initialProfile.startHour) }
    var sM by remember { mutableIntStateOf(initialProfile.startMinute) }
    var eH by remember { mutableIntStateOf(initialProfile.endHour) }
    var eM by remember { mutableIntStateOf(initialProfile.endMinute) }

    var sHText by remember { mutableStateOf(initialProfile.startHour.toString()) }
    var sMText by remember { mutableStateOf(String.format(Locale.US, "%02d", initialProfile.startMinute)) }
    var eHText by remember { mutableStateOf(initialProfile.endHour.toString()) }
    var eMText by remember { mutableStateOf(String.format(Locale.US, "%02d", initialProfile.endMinute)) }

    var isByLaunch by remember { mutableStateOf(initialProfile.isByLaunch) }
    var maxLaunches by remember { mutableIntStateOf(initialProfile.maxLaunches) }
    var launchText by remember { mutableStateOf(initialProfile.maxLaunches.toString()) }

    var durationText by remember { mutableStateOf(initialProfile.durationMinutes.toString()) }

    var showAppPickerSubDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialProfile.name.isNotBlank()) "Edit Profil Habit" else "Buat Profil Habit Baru",
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = textPrimary
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Nama Profil
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Nama Profil:", fontFamily = PoppinsFontFamily, fontSize = 11.sp, color = textSecondary)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = { Text("Contoh: Jam Tidur / Belajar") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 2. Aplikasi Terpilih & Tombol Kelola
                item {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = subtaskBgColor(isDark),
                        border = BorderStroke(0.8.dp, cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Aplikasi Target (${selectedPkgs.size})",
                                    fontFamily = PoppinsFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.5.sp,
                                    color = textPrimary
                                )
                                TextButton(
                                    onClick = { showAppPickerSubDialog = true },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("✎ Pilih Aplikasi", fontSize = 11.sp, color = AccentGreen)
                                }
                            }
                            if (selectedPkgs.isEmpty()) {
                                Text(
                                    text = "⚠️ Belum ada aplikasi yang dipilih untuk profil ini.",
                                    fontFamily = PoppinsFontFamily,
                                    fontSize = 10.5.sp,
                                    color = AccentAmber
                                )
                            } else {
                                Text(
                                    text = "${selectedPkgs.size} aplikasi dipilih khusus untuk profil ini.",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 10.sp,
                                    color = textSecondary
                                )
                            }
                        }
                    }
                }

                // 3. Pemicu 1: By Duration
                item {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = cardColor,
                        border = BorderStroke(1.dp, if (isByDuration) AccentAmber.copy(alpha = 0.5f) else cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.HourglassTop, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(16.dp))
                                    Text("By Duration (Batas Sesi)", fontFamily = PoppinsFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, color = textPrimary)
                                }
                                Switch(
                                    checked = isByDuration,
                                    onCheckedChange = { isByDuration = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentAmber)
                                )
                            }
                            if (isByDuration) {
                                // Preset Cepat
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(10, 15, 20, 30, 45, 60).forEach { m ->
                                        PillChoice(
                                            label = "${m}m",
                                            isSelected = durationMinutes == m,
                                            isDark = isDark,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            durationMinutes = m
                                            durationText = m.toString()
                                        }
                                    }
                                }

                                // Custom Menit Input Manual
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Atur Menit Kustom:",
                                        fontFamily = PoppinsFontFamily,
                                        fontSize = 11.sp,
                                        color = textSecondary
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = durationText,
                                            onValueChange = { newVal ->
                                                val digits = newVal.filter { it.isDigit() }
                                                durationText = digits
                                                if (digits.isNotBlank()) {
                                                    val m = digits.toInt().coerceIn(1, 1440)
                                                    durationMinutes = m
                                                }
                                            },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier.width(76.dp),
                                            textStyle = androidx.compose.ui.text.TextStyle(
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        )
                                        Text(
                                            text = "menit",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 11.sp,
                                            color = textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Pemicu 2: By Time (Jadwal Jam)
                item {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = cardColor,
                        border = BorderStroke(1.dp, if (isByTime) Color(0xFF38BDF8).copy(alpha = 0.5f) else cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.AccessTime, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                                    Text("By Time (Jadwal Jam)", fontFamily = PoppinsFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, color = textPrimary)
                                }
                                Switch(
                                    checked = isByTime,
                                    onCheckedChange = { isByTime = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF38BDF8))
                                )
                            }
                            if (isByTime) {
                                // Preset cepat jadwal jam
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            sH = 22; sM = 0; eH = 5; eM = 0
                                            sHText = "22"; sMText = "00"; eHText = "5"; eMText = "00"
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f).height(34.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("Tidur (22:00-05:00)", fontSize = 10.sp, color = textPrimary)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            sH = 8; sM = 0; eH = 17; eM = 0
                                            sHText = "8"; sMText = "00"; eHText = "17"; eMText = "00"
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f).height(34.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("Kerja (08:00-17:00)", fontSize = 10.sp, color = textPrimary)
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Jam & Menit Mulai
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Mulai (Jam : Menit)", fontSize = 10.sp, color = textSecondary)
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = sHText,
                                                onValueChange = { newVal ->
                                                    val digits = newVal.filter { it.isDigit() }
                                                    sHText = digits
                                                    if (digits.isNotBlank()) sH = digits.toInt().coerceIn(0, 23)
                                                },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                singleLine = true,
                                                placeholder = { Text("00") },
                                                modifier = Modifier.weight(1f),
                                                textStyle = androidx.compose.ui.text.TextStyle(
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    textAlign = TextAlign.Center
                                                )
                                            )
                                            Text(":", fontWeight = FontWeight.Bold, color = textSecondary)
                                            OutlinedTextField(
                                                value = sMText,
                                                onValueChange = { newVal ->
                                                    val digits = newVal.filter { it.isDigit() }
                                                    sMText = digits
                                                    if (digits.isNotBlank()) sM = digits.toInt().coerceIn(0, 59)
                                                },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                singleLine = true,
                                                placeholder = { Text("00") },
                                                modifier = Modifier.weight(1f),
                                                textStyle = androidx.compose.ui.text.TextStyle(
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    textAlign = TextAlign.Center
                                                )
                                            )
                                        }
                                    }

                                    Text("s/d", fontSize = 11.sp, color = textSecondary, modifier = Modifier.padding(top = 14.dp))

                                    // Jam & Menit Selesai
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Selesai (Jam : Menit)", fontSize = 10.sp, color = textSecondary)
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = eHText,
                                                onValueChange = { newVal ->
                                                    val digits = newVal.filter { it.isDigit() }
                                                    eHText = digits
                                                    if (digits.isNotBlank()) eH = digits.toInt().coerceIn(0, 23)
                                                },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                singleLine = true,
                                                placeholder = { Text("00") },
                                                modifier = Modifier.weight(1f),
                                                textStyle = androidx.compose.ui.text.TextStyle(
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    textAlign = TextAlign.Center
                                                )
                                            )
                                            Text(":", fontWeight = FontWeight.Bold, color = textSecondary)
                                            OutlinedTextField(
                                                value = eMText,
                                                onValueChange = { newVal ->
                                                    val digits = newVal.filter { it.isDigit() }
                                                    eMText = digits
                                                    if (digits.isNotBlank()) eM = digits.toInt().coerceIn(0, 59)
                                                },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                singleLine = true,
                                                placeholder = { Text("00") },
                                                modifier = Modifier.weight(1f),
                                                textStyle = androidx.compose.ui.text.TextStyle(
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    textAlign = TextAlign.Center
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Pemicu 3: By Launch Count
                item {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = cardColor,
                        border = BorderStroke(1.dp, if (isByLaunch) AccentRed.copy(alpha = 0.5f) else cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.TouchApp, contentDescription = null, tint = AccentRed, modifier = Modifier.size(16.dp))
                                    Text("By Launch / Click (Batas Buka)", fontFamily = PoppinsFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, color = textPrimary)
                                }
                                Switch(
                                    checked = isByLaunch,
                                    onCheckedChange = { isByLaunch = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentRed)
                                )
                            }
                            if (isByLaunch) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(3, 5, 10, 15).forEach { cnt ->
                                        PillChoice(
                                            label = "${cnt}x",
                                            isSelected = maxLaunches == cnt,
                                            isDark = isDark,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            maxLaunches = cnt
                                            launchText = cnt.toString()
                                        }
                                    }
                                }

                                // Custom Jumlah Buka Manual
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Batas Kustom (Kali):",
                                        fontFamily = PoppinsFontFamily,
                                        fontSize = 11.sp,
                                        color = textSecondary
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = launchText,
                                            onValueChange = { newVal ->
                                                val digits = newVal.filter { it.isDigit() }
                                                launchText = digits
                                                if (digits.isNotBlank()) {
                                                    val cnt = digits.toInt().coerceIn(1, 999)
                                                    maxLaunches = cnt
                                                }
                                            },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier.width(76.dp),
                                            textStyle = androidx.compose.ui.text.TextStyle(
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        )
                                        Text(
                                            text = "kali",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 11.sp,
                                            color = textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = if (name.isBlank()) "Profil Habit" else name.trim()
                    val updated = initialProfile.copy(
                        name = finalName,
                        packages = selectedPkgs,
                        isByDuration = isByDuration,
                        durationMinutes = durationMinutes,
                        isByTime = isByTime,
                        startHour = sH,
                        startMinute = sM,
                        endHour = eH,
                        endMinute = eM,
                        isByLaunch = isByLaunch,
                        maxLaunches = maxLaunches
                    )
                    onSave(updated)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                Text("Simpan Profil", fontWeight = FontWeight.Bold, color = Color.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = textSecondary)
            }
        }
    )

    // Sub-dialog Pemilih Aplikasi Khusus Profil Habit Ini (Dengan Anti-Bentrok)
    if (showAppPickerSubDialog) {
        AppBlockPickerDialog(
            isDark = isDark,
            appBlockPackages = selectedPkgs,
            conflictMap = conflictMap,
            cardColor = cardColor,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            onTogglePackage = { pkg ->
                val current = selectedPkgs.toMutableSet()
                if (current.contains(pkg)) {
                    current.remove(pkg)
                } else {
                    current.add(pkg)
                }
                selectedPkgs = current
            },
            onDismiss = { showAppPickerSubDialog = false }
        )
    }
}

// 5. DIALOG PILIH RENTANG JAM (BY TIME SCHEDULE)
@Composable
fun TimeRangePickerDialog(
    currentSchedule: Pair<Pair<Int, Int>, Pair<Int, Int>>,
    cardColor: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onDismiss: () -> Unit,
    onSave: (Int, Int, Int, Int) -> Unit
) {
    val (start, end) = currentSchedule
    var sH by remember { mutableStateOf(start.first) }
    var sM by remember { mutableStateOf(start.second) }
    var eH by remember { mutableStateOf(end.first) }
    var eM by remember { mutableStateOf(end.second) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Atur Jam Gembok (By Time)", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Aplikasi target akan terkunci otomatis saat waktu berada di antara jam mulai dan jam selesai.",
                    fontFamily = PoppinsFontFamily,
                    fontSize = 11.sp,
                    color = textSecondary
                )

                // Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { sH = 22; sM = 0; eH = 5; eM = 0 },
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("22:00-05:00", fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = { sH = 8; sM = 0; eH = 17; eM = 0 },
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("08:00-17:00", fontSize = 10.sp)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E1E1E),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Jam Aktif: ${String.format(Locale.US, "%02d:%02d", sH, sM)} s/d ${String.format(Locale.US, "%02d:%02d", eH, eM)}",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = AccentGreen
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Jam Mulai (0-23):", fontSize = 10.sp)
                        OutlinedTextField(
                            value = sH.toString(),
                            onValueChange = { sH = (it.toIntOrNull() ?: 0).coerceIn(0, 23) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Jam Selesai (0-23):", fontSize = 10.sp)
                        OutlinedTextField(
                            value = eH.toString(),
                            onValueChange = { eH = (it.toIntOrNull() ?: 0).coerceIn(0, 23) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(sH, sM, eH, eM) },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                Text("Simpan", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

// 4. DIALOG INTERAKTIF TANTANGAN NERAKA (MULTI-CHALLENGE ANTI-BYPASS)
@Composable
fun DarkSoulsUnlockDialog(
    secretKey: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val isPhraseRequired = QuotaManager.isStrictPhraseActive(context)
    val isMathRequired = QuotaManager.isStrictMathActive(context)
    val isTotpRequired = QuotaManager.isStrictTotpActive(context)
    val isChargerRequired = QuotaManager.isStrictChargerActive(context)
    val isPlugged = QuotaManager.isDeviceCharging(context)

    val commitmentPhrase = remember { QuotaManager.getCommitmentPhrase(context) }
    var phraseInput by remember { mutableStateOf("") }
    var isPhraseSolved by remember { mutableStateOf(!isPhraseRequired) }

    var num1 by remember { mutableStateOf(Random.nextInt(25, 75)) }
    var num2 by remember { mutableStateOf(Random.nextInt(15, 60)) }
    val correctMath = num1 + num2

    var mathAnswer by remember { mutableStateOf("") }
    var totpInput by remember { mutableStateOf("") }
    var isMathSolved by remember { mutableStateOf(!isMathRequired) }

    var difficultySeconds by remember { mutableStateOf(15) }
    var secondsLeft by remember { mutableStateOf(15) }
    var isFailed by remember { mutableStateOf(false) }

    // Live sync indicator of Authenticator 30s cycle
    var authCycleSec by remember { mutableStateOf(TotpHelper.getSecondsRemaining(30)) }
    LaunchedEffect(Unit) {
        while (true) {
            authCycleSec = TotpHelper.getSecondsRemaining(30)
            delay(500)
        }
    }

    LaunchedEffect(isPhraseSolved, isMathSolved) {
        if (isPhraseSolved && isMathSolved && isTotpRequired) {
            secondsLeft = difficultySeconds
            while (secondsLeft > 0 && !isFailed) {
                delay(1000)
                secondsLeft--
            }
            if (secondsLeft <= 0) {
                isFailed = true
                Toast.makeText(context, "Waktu Habis! Anda gagal menaklukkan tantangan.", Toast.LENGTH_LONG).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Whatshot, contentDescription = null, tint = AccentRed, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "TANTANGAN STRICT UNLOCK",
                    fontFamily = RighteousFontFamily,
                    fontSize = 17.sp,
                    color = AccentRed
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isChargerRequired && !isPlugged) {
                    Text(
                        text = "🔌 HP WAJIB DICOLOK CASAN! Silakan hubungkan kabel charger HP Anda sekarang untuk membuka tantangan.",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 12.5.sp,
                        color = AccentAmber,
                        lineHeight = 18.sp
                    )
                } else if (isFailed) {
                    Text(
                        text = "❌ GAGAL! Waktu Anda habis atau kode salah. Mode autopilot khilaf Anda berhasil dicegat. Silakan istirahat!",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 12.5.sp,
                        color = AccentRed,
                        lineHeight = 18.sp
                    )
                } else if (isPhraseRequired && !isPhraseSolved) {
                    Text(
                        text = "📜 Ronde 1: Ketik kalimat ikrar komitmen dengan presisi (tanpa copy-paste):",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 12.sp,
                        color = Color.White
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E1E1E),
                        border = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "\"$commitmentPhrase\"",
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            color = AccentOrange,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    OutlinedTextField(
                        value = phraseInput,
                        onValueChange = { phraseInput = it },
                        placeholder = { Text("Ketik ulang kalimat di atas...", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )

                    val isPhraseMatch = phraseInput.trim() == commitmentPhrase.trim()

                    Button(
                        onClick = {
                            if (isPhraseMatch) {
                                isPhraseSolved = true
                                if (!isMathRequired && !isTotpRequired) {
                                    onSuccess()
                                } else {
                                    Toast.makeText(context, "Kalimat ikrar cocok! Melanjutkan...", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Kalimat belum persis sama!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = isPhraseMatch,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentOrange,
                            disabledContainerColor = Color.DarkGray
                        )
                    ) {
                        Text(
                            text = when {
                                isMathRequired -> "Lanjut ke Soal Matematika"
                                isTotpRequired -> "Lanjut ke Authenticator"
                                else -> "Buka Kunci Sekarang"
                            },
                            fontWeight = FontWeight.Bold,
                            color = if (isPhraseMatch) Color.White else Color.Gray
                        )
                    }
                } else if (isMathRequired && !isMathSolved) {
                    Text(
                        text = if (isPhraseRequired) "🧩 Ronde 2: Selesaikan soal matematika otak rasional:" else "Ronde 1: Selesaikan soal matematika untuk mengaktifkan otak rasional:",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 12.sp,
                        color = Color.White
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E1E1E),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "$num1 + $num2 = ?",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = AccentAmber,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    OutlinedTextField(
                        value = mathAnswer,
                        onValueChange = { mathAnswer = it },
                        label = { Text("Jawaban Angka") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            if (mathAnswer.trim() == correctMath.toString()) {
                                isMathSolved = true
                                if (!isTotpRequired) {
                                    onSuccess()
                                } else {
                                    Toast.makeText(context, "Jawaban Benar! Waktu $difficultySeconds detik dimulai!", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Salah hitung! Coba lagi.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber)
                    ) {
                        Text(
                            text = if (isTotpRequired) "Lanjut ke Authenticator" else "Buka Kunci Sekarang",
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                } else if (isTotpRequired) {
                    Text(
                        text = "🚨 Masukkan 6 digit kode dari Google Authenticator:",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 12.sp,
                        color = AccentRed,
                        fontWeight = FontWeight.Bold
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF222222),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Siklus Authenticator:", fontFamily = PoppinsFontFamily, fontSize = 11.sp, color = Color.Gray)
                            Text("$authCycleSec detik lagi", fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AccentGreen)
                        }
                    }

                    LinearProgressIndicator(
                        progress = { (secondsLeft / difficultySeconds.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = AccentRed,
                        trackColor = Color.DarkGray
                    )

                    Text(
                        text = "⏳ Sisa Waktu Anda: $secondsLeft Detik lagi!",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = AccentRed,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = totpInput,
                        onValueChange = { if (it.length <= 6) totpInput = it },
                        label = { Text("6 Digit Kode Authenticator") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            val isValid = TotpHelper.verifyCode(secretKey, totpInput)
                            if (isValid) {
                                onSuccess()
                            } else {
                                isFailed = true
                                Toast.makeText(context, "Kode Authenticator Salah!", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text("Validasi & Buka Kunci", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                } else {
                    Text(
                        text = "Konfirmasi membuka gembok proteksi:",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 12.5.sp,
                        color = Color.White
                    )
                    Button(
                        onClick = onSuccess,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text("Buka Kunci Sekarang", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup", color = Color.LightGray)
            }
        }
    )
}

// ================= DIALOG VERIFIKASI KALIMAT KOMITMEN (ANTI-KHILAF) =================
@Composable
fun CommitmentVerificationDialog(
    title: String,
    actionDesc: String,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit
) {
    val context = LocalContext.current
    val requiredPhrase = remember { QuotaManager.getCommitmentPhrase(context) }
    var typedInput by remember { mutableStateOf("") }
    val isMatch = typedInput.trim() == requiredPhrase.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.GppMaybe,
                    contentDescription = null,
                    tint = AccentRed,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontFamily = RighteousFontFamily,
                    fontSize = 16.sp,
                    color = AccentRed
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = actionDesc,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 11.5.sp,
                    color = Color.LightGray,
                    lineHeight = 16.sp
                )

                Text(
                    text = "Ketik ulang kalimat komitmen berikut persis (tanpa copy-paste):",
                    fontFamily = PoppinsFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentAmber
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E1E1E),
                    border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "\"$requiredPhrase\"",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        color = AccentAmber,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                OutlinedTextField(
                    value = typedInput,
                    onValueChange = { typedInput = it },
                    placeholder = { Text("Ketik kalimat di atas...", fontSize = 11.5.sp) },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                if (typedInput.isNotEmpty() && !isMatch) {
                    Text(
                        text = "⚠️ Kalimat belum cocok persis (${typedInput.length}/${requiredPhrase.length} karakter)",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        color = AccentRed
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isMatch) {
                        onConfirmed()
                    } else {
                        Toast.makeText(context, "Kalimat komitmen belum cocok!", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = isMatch,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentRed,
                    disabledContainerColor = Color.DarkGray
                )
            ) {
                Text(
                    text = "Konfirmasi Tindakan",
                    fontWeight = FontWeight.Bold,
                    color = if (isMatch) Color.White else Color.Gray,
                    fontSize = 11.sp
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = Color.LightGray)
            }
        }
    )
}

// Dialog Input Custom Budget
@Composable
fun SetBudgetCustomDialog(
    initialBudgetMb: Float,
    title: String,
    cardBg: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    var textInput by remember { mutableStateOf(if (initialBudgetMb > 0) initialBudgetMb.toInt().toString() else "1024") }
    var selectedUnit by remember { mutableStateOf(if (initialBudgetMb >= 1024f && initialBudgetMb % 1024f == 0f) "GB" else "MB") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = textPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Tentukan batas jatah kuota secara fleksibel sesuai paket atau kebutuhan Anda:",
                    fontFamily = PoppinsFontFamily,
                    fontSize = 12.sp,
                    color = textSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = {
                            textInput = it.filter { char -> char.isDigit() }
                            errorMessage = null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        placeholder = { Text("Contoh: 500 atau 2") },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = cardBorder
                        )
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("MB", "GB").forEach { unit ->
                            val isSelected = selectedUnit == unit
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) AccentBlue else Color.Transparent)
                                    .border(1.dp, if (isSelected) AccentBlue else cardBorder, RoundedCornerShape(8.dp))
                                    .clickable { selectedUnit = unit }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = unit,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isSelected) PureWhite else textPrimary
                                )
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        fontFamily = PoppinsFontFamily,
                        fontSize = 11.sp,
                        color = AccentRed
                    )
                }

                // Preset Cepat
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val presets = if (selectedUnit == "GB") listOf("1", "2", "3", "5") else listOf("250", "500", "750", "1500")
                    presets.forEach { preset ->
                        OutlinedButton(
                            onClick = { textInput = preset },
                            modifier = Modifier.weight(1f).height(32.dp),
                            contentPadding = PaddingValues(0.dp),
                            border = BorderStroke(1.dp, cardBorder)
                        ) {
                            Text(
                                text = "$preset$selectedUnit",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 10.sp,
                                color = textSecondary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = textInput.toFloatOrNull()
                    if (parsed == null || parsed <= 0f) {
                        errorMessage = "Masukkan angka budget yang valid (> 0)!"
                        return@Button
                    }
                    val mbVal = if (selectedUnit == "GB") parsed * 1024f else parsed
                    onConfirm(mbVal)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                Text("Simpan", fontFamily = PoppinsFontFamily, fontWeight = FontWeight.Bold, color = PureWhite)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", fontFamily = PoppinsFontFamily, color = textSecondary)
            }
        }
    )
}

// ================= REUSABLE UI COMPONENTS =================
@Composable
fun HeroQuotaCard(
    status: QuotaStatus,
    cardBg: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onSelectBudget: (Float) -> Unit
) {
    val usedBytes = status.totalWifiBytesToday
    val budgetBytes = (status.budgetMb * 1024 * 1024).toLong()
    val remainingBytes = (budgetBytes - usedBytes).coerceAtLeast(0L)
    var showCustomBudgetDialog by remember { mutableStateOf(false) }

    val progressColor = when {
        status.percentUsed >= 90f -> AccentRed
        status.percentUsed >= 70f -> AccentAmber
        else -> AccentGreen
    }

    if (showCustomBudgetDialog) {
        SetBudgetCustomDialog(
            initialBudgetMb = status.budgetMb,
            title = "Atur Budget Fleksibel (Total HP)",
            cardBg = cardBg,
            cardBorder = cardBorder,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            onDismiss = { showCustomBudgetDialog = false },
            onConfirm = { mb ->
                onSelectBudget(mb)
                showCustomBudgetDialog = false
            }
        )
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "KUOTA TOTAL HARI INI",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp,
                    color = textSecondary
                )
                Text(
                    text = "${String.format(Locale.US, "%.1f", status.percentUsed)}% Terpakai",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp,
                    color = progressColor
                )
            }

            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = QuotaManager.formatBytes(remainingBytes),
                        fontFamily = RighteousFontFamily,
                        fontSize = 32.sp,
                        color = textPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "tersisa",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 13.sp,
                        color = textSecondary,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }
                Text(
                    text = "Terpakai: ${QuotaManager.formatBytes(usedBytes)} dari total budget ${status.budgetMb.toInt()} MB",
                    fontFamily = PoppinsFontFamily,
                    fontSize = 12.sp,
                    color = textSecondary
                )
            }

            LinearProgressIndicator(
                progress = { (status.percentUsed / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = progressColor,
                trackColor = cardBorder.copy(alpha = 0.5f)
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Atur Jatah Budget Harian:",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 11.sp,
                        color = textSecondary
                    )
                    Text(
                        text = "Custom",
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = AccentBlue,
                        modifier = Modifier.clickable { showCustomBudgetDialog = true }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(1024f, 1536f, 2048f, 3072f).forEach { mb ->
                        val isSelected = status.budgetMb == mb
                        val label = when (mb) {
                            1024f -> "1 GB"
                            1536f -> "1.5 GB"
                            2048f -> "2 GB"
                            3072f -> "3 GB"
                            else -> "${mb.toInt()}M"
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) textPrimary else Color.Transparent)
                                .border(1.dp, if (isSelected) textPrimary else cardBorder, RoundedCornerShape(8.dp))
                                .clickable { onSelectBudget(mb) }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (isSelected) cardBg else textPrimary
                            )
                        }
                    }
                    // Tombol Edit Custom
                    Box(
                        modifier = Modifier
                            .weight(0.8f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (status.budgetMb !in listOf(1024f, 1536f, 2048f, 3072f)) AccentBlue.copy(alpha = 0.2f) else Color.Transparent)
                            .border(1.dp, if (status.budgetMb !in listOf(1024f, 1536f, 2048f, 3072f)) AccentBlue else cardBorder, RoundedCornerShape(8.dp))
                            .clickable { showCustomBudgetDialog = true }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Custom Budget",
                            tint = if (status.budgetMb !in listOf(1024f, 1536f, 2048f, 3072f)) AccentBlue else textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QuickSwitchCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isActive: Boolean,
    activeColor: Color,
    cardBg: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        border = BorderStroke(1.dp, if (isActive) activeColor.copy(alpha = 0.5f) else cardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (isActive) activeColor.copy(alpha = 0.15f) else cardBorder.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isActive) activeColor else textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = title,
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = textPrimary
                    )
                    Text(
                        text = subtitle,
                        fontFamily = PoppinsFontFamily,
                        fontSize = 10.5.sp,
                        color = textSecondary,
                        lineHeight = 14.sp
                    )
                }
            }

            Switch(
                checked = isActive,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = activeColor,
                    uncheckedThumbColor = textSecondary,
                    uncheckedTrackColor = cardBorder
                )
            )
        }
    }
}

@Composable
fun AppUsageRow(
    app: AppDataUsage,
    cardBg: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val appIcon: ImageBitmap? = remember(app.packageName) {
        try {
            val dr = pm.getApplicationIcon(app.packageName)
            val w = if (dr.intrinsicWidth > 0) dr.intrinsicWidth else 96
            val h = if (dr.intrinsicHeight > 0) dr.intrinsicHeight else 96
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            dr.setBounds(0, 0, canvas.width, canvas.height)
            dr.draw(canvas)
            bmp.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (appIcon != null) {
                        Image(
                            bitmap = appIcon,
                            contentDescription = app.appName,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        val fallbackIcon = when (app.iconCategory) {
                            "instagram" -> Icons.Default.CameraAlt
                            "browser" -> Icons.Default.Language
                            "youtube" -> Icons.Default.PlayCircle
                            "tiktok" -> Icons.Default.MusicNote
                            else -> Icons.Default.Apps
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(cardBorder.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = fallbackIcon,
                                contentDescription = null,
                                tint = if (app.isMonitored) AccentAmber else textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = app.appName,
                                fontFamily = PoppinsFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (app.isMonitored) Modifier.weight(1f, fill = false) else Modifier
                            )
                            if (app.isMonitored) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = AccentAmber.copy(alpha = 0.15f)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = AccentAmber,
                                            modifier = Modifier.size(9.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = "Target Lock",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentAmber,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = app.packageName,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 9.5.sp,
                            color = textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = QuotaManager.formatBytes(app.bytesUsed),
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (app.bytesUsed > 500 * 1024 * 1024) AccentRed else textPrimary
                    )
                    Text(
                        text = String.format(Locale.US, "%.2f%%", app.percentOfTotal),
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        color = textSecondary
                    )
                }
            }

            // Dual Progress Bar: Biru (Download/Rx) & Hijau (Upload/Tx) ala aplikasi pemantau data
            val total = (app.rxBytes + app.txBytes).coerceAtLeast(1L).toFloat()
            val rxRatio = (app.rxBytes.toFloat() / total).coerceIn(0f, 1f)
            val txRatio = (app.txBytes.toFloat() / total).coerceIn(0f, 1f)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(cardBorder.copy(alpha = 0.35f))
            ) {
                if (rxRatio > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(rxRatio.coerceAtLeast(0.01f))
                            .fillMaxHeight()
                            .background(Color(0xFF00A2FF)) // Biru terang (Download)
                    )
                }
                if (txRatio > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(txRatio.coerceAtLeast(0.01f))
                            .fillMaxHeight()
                            .background(Color(0xFF00E676)) // Hijau terang (Upload)
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionStatusCard(
    title: String,
    desc: String,
    isGranted: Boolean,
    cardBg: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onClickAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cardBg,
        border = BorderStroke(1.dp, if (isGranted) AccentGreen.copy(alpha = 0.4f) else cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = if (isGranted) AccentGreen else AccentAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = textPrimary
                    )
                }
                Text(
                    text = desc,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 10.5.sp,
                    color = textSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (!isGranted) {
                TextButton(onClick = onClickAction) {
                    Text(
                        text = "Aktifkan",
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        color = AccentAmber
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = AccentGreen.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "Aktif",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        color = AccentGreen,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, textSecondary: Color) {
    Text(
        text = title,
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        color = textSecondary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
fun PillChoice(
    label: String,
    isSelected: Boolean,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = if (isSelected) (if (isDark) PureWhite else PureBlack) else Color.Transparent
    val textCol = if (isSelected) (if (isDark) PureBlack else PureWhite) else (if (isDark) DarkTextPrimary else LightTextPrimary)
    val borderCol = if (isSelected) bg else (if (isDark) DarkCardBorder else LightCardBorder)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, borderCol, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = textCol
        )
    }
}

fun subtaskBgColor(isDark: Boolean): Color = if (isDark) DarkSubtaskBg else LightSubtaskBg
