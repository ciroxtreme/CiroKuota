package com.ciro.kuota

import android.app.Activity
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    PROTECTION("Proteksi", Icons.Default.Shield),
    HABIT_LOCK("Rem Khilaf", Icons.Default.HourglassTop),
    SETTINGS("Pengaturan", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CiroKuotaMainScreen(
    isDark: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(CiroTab.PROTECTION) }

    // State Pengaturan Umum
    var dailyBudgetMb by remember { mutableStateOf(QuotaManager.getDailyBudgetMb(context)) }
    var isKillswitch by remember { mutableStateOf(QuotaManager.isKillswitchActive(context)) }
    var isVpnFirewall by remember { mutableStateOf(QuotaManager.isVpnActive(context)) }
    var isFamilyDns by remember { mutableStateOf(QuotaManager.isFamilyDnsActive(context)) }
    var isAppLock by remember { mutableStateOf(QuotaManager.isAppLockActive(context)) }
    var isAutoCutoff by remember { mutableStateOf(QuotaManager.isAutoCutoffActive(context)) }
    var igLimitMin by remember { mutableStateOf(QuotaManager.getIgSessionLimitMinutes(context)) }
    var browserLimitMin by remember { mutableStateOf(QuotaManager.getBrowserSessionLimitMinutes(context)) }
    var manualLockUntil by remember { mutableStateOf(QuotaManager.getManualLockUntil(context)) }
    var selectedBlackholeApps by remember { mutableStateOf(QuotaManager.getBlackholePackages(context)) }

    // State Data Pemakaian
    var quotaStatus by remember { mutableStateOf(QuotaManager.getQuotaStatus(context)) }
    var topApps by remember { mutableStateOf(QuotaManager.getTopMonitoredAppsUsage(context)) }
    var candidateApps by remember { mutableStateOf(QuotaManager.getAllCandidateApps(context)) }
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

    // Auto Refresh setiap 3 detik
    LaunchedEffect(Unit) {
        while (true) {
            quotaStatus = QuotaManager.getQuotaStatus(context)
            topApps = QuotaManager.getTopMonitoredAppsUsage(context)
            candidateApps = QuotaManager.getAllCandidateApps(context)
            hasUsagePerm = QuotaManager.hasUsageStatsPermission(context)
            hasAccessPerm = QuotaManager.hasAccessibilityPermission(context)
            isBatteryIgnored = QuotaManager.isBatteryOptimizationIgnored(context)
            manualLockUntil = QuotaManager.getManualLockUntil(context)
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
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = if (isSelected) AccentGreen else textSecondary
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontFamily = PoppinsFontFamily,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 11.sp,
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
                CiroTab.PROTECTION -> {
                    TabProtectionScreen(
                        isDark = isDark,
                        status = quotaStatus,
                        dailyBudgetMb = dailyBudgetMb,
                        isKillswitch = isKillswitch,
                        isVpnFirewall = isVpnFirewall,
                        isFamilyDns = isFamilyDns,
                        isAutoCutoff = isAutoCutoff,
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
                        onToggleKillswitch = {
                            isKillswitch = !isKillswitch
                            QuotaManager.setKillswitchActive(context, isKillswitch)
                            if (isKillswitch) {
                                startVpnSafely()
                                Toast.makeText(context, "🚨 Killswitch Aktif! Internet target diblokir.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Killswitch Dimatikan.", Toast.LENGTH_SHORT).show()
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
                        onToggleDns = {
                            isFamilyDns = !isFamilyDns
                            QuotaManager.setFamilyDnsActive(context, isFamilyDns)
                            if (isVpnFirewall) startVpnSafely() // reload VPN with DNS
                            Toast.makeText(context, if (isFamilyDns) "Family DNS Aktif (1.1.1.3)!" else "Family DNS Mati", Toast.LENGTH_SHORT).show()
                        },
                        onToggleAutoCutoff = {
                            isAutoCutoff = !isAutoCutoff
                            QuotaManager.setAutoCutoffActive(context, isAutoCutoff)
                        }
                    )
                }

                CiroTab.HABIT_LOCK -> {
                    TabHabitLockScreen(
                        isDark = isDark,
                        igLimitMin = igLimitMin,
                        browserLimitMin = browserLimitMin,
                        manualLockUntil = manualLockUntil,
                        isAppLock = isAppLock,
                        hasAccessPerm = hasAccessPerm,
                        cardColor = cardColor,
                        cardBorder = cardBorder,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onIgLimitChange = { min ->
                            igLimitMin = min
                            QuotaManager.setIgSessionLimitMinutes(context, min)
                        },
                        onBrowserLimitChange = { min ->
                            browserLimitMin = min
                            QuotaManager.setBrowserSessionLimitMinutes(context, min)
                        },
                        onToggleAppLock = {
                            isAppLock = !isAppLock
                            QuotaManager.setAppLockActive(context, isAppLock)
                        },
                        onSetManualLock = { durationMillis ->
                            val until = System.currentTimeMillis() + durationMillis
                            manualLockUntil = until
                            QuotaManager.setManualLockUntil(context, until)
                            Toast.makeText(context, "Aplikasi target dikunci!", Toast.LENGTH_SHORT).show()
                        },
                        onUnlockSuccess = {
                            manualLockUntil = 0L
                            QuotaManager.setManualLockUntil(context, 0L)
                            Toast.makeText(context, "🎉 Selamat! Kunci berhasil dibuka!", Toast.LENGTH_LONG).show()
                        }
                    )
                }

                CiroTab.SETTINGS -> {
                    TabSettingsScreen(
                        isDark = isDark,
                        candidateApps = candidateApps,
                        hasAccessPerm = hasAccessPerm,
                        hasUsagePerm = hasUsagePerm,
                        isBatteryIgnored = isBatteryIgnored,
                        cardColor = cardColor,
                        cardBorder = cardBorder,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onToggleApp = { pkg ->
                            QuotaManager.toggleBlackholePackage(context, pkg)
                            candidateApps = QuotaManager.getAllCandidateApps(context)
                            if (isVpnFirewall) startVpnSafely() // Reload VPN with updated apps
                        }
                    )
                }
            }
        }
    }
}

// ================= TAB 1: PROTEKSI & KUOTA =================
@Composable
fun TabProtectionScreen(
    isDark: Boolean,
    status: QuotaStatus,
    dailyBudgetMb: Float,
    isKillswitch: Boolean,
    isVpnFirewall: Boolean,
    isFamilyDns: Boolean,
    isAutoCutoff: Boolean,
    topApps: List<AppDataUsage>,
    cardColor: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onBudgetChange: (Float) -> Unit,
    onToggleKillswitch: () -> Unit,
    onToggleVpn: () -> Unit,
    onToggleDns: () -> Unit,
    onToggleAutoCutoff: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Card
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
                // Killswitch
                QuickSwitchCard(
                    title = "Killswitch Darurat",
                    subtitle = "Putus seketika internet aplikasi target yang diblackhole",
                    icon = Icons.Default.PowerSettingsNew,
                    isActive = isKillswitch,
                    activeColor = AccentRed,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onToggle = onToggleKillswitch
                )

                // VPN Blackhole Firewall
                QuickSwitchCard(
                    title = "VPN Blackhole Firewall",
                    subtitle = "Pisahkan koneksi: Instagram & Chrome diblackhole, WA/Telegram lancar",
                    icon = Icons.Default.VpnKey,
                    isActive = isVpnFirewall,
                    activeColor = Color(0xFF38BDF8),
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onToggle = onToggleVpn
                )

                // DNS Family Shield
                QuickSwitchCard(
                    title = "DNS Family Shield (1.1.1.3)",
                    subtitle = "Blokir otomatis domain situs dewasa di level jaringan",
                    icon = Icons.Default.Shield,
                    isActive = isFamilyDns,
                    activeColor = AccentGreen,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onToggle = onToggleDns
                )

                // Auto Cutoff
                QuickSwitchCard(
                    title = "Auto-Cutoff saat Kuota Habis",
                    subtitle = "Otomatis hentikan kuota jika pemakaian tembus 100% budget",
                    icon = Icons.Default.DataSaverOn,
                    isActive = isAutoCutoff,
                    activeColor = AccentAmber,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onToggle = onToggleAutoCutoff
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

// ================= TAB 2: REM KHILAF & TANTANGAN NERAKA =================
@Composable
fun TabHabitLockScreen(
    isDark: Boolean,
    igLimitMin: Int,
    browserLimitMin: Int,
    manualLockUntil: Long,
    isAppLock: Boolean,
    hasAccessPerm: Boolean,
    cardColor: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onIgLimitChange: (Int) -> Unit,
    onBrowserLimitChange: (Int) -> Unit,
    onToggleAppLock: () -> Unit,
    onSetManualLock: (Long) -> Unit,
    onUnlockSuccess: () -> Unit
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val isLocked = manualLockUntil > now

    val secretKey = remember { TotpHelper.getOrCreateSecret(context) }
    var showChallengeDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Status Kunci Aktif
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
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = AccentRed, modifier = Modifier.size(36.dp))
                        Text(
                            text = "STATUS: TERKUNCI KETAT",
                            fontFamily = RighteousFontFamily,
                            fontSize = 18.sp,
                            color = AccentRed
                        )
                        Text(
                            text = "Instagram & Browser terkunci selama $remMinutes menit lagi.",
                            fontFamily = PoppinsFontFamily,
                            fontSize = 12.sp,
                            color = textPrimary,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = { showChallengeDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(imageVector = Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Buka Kunci (Tantangan Neraka)", fontFamily = PoppinsFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Kunci Khilaf Manual Sekarang:",
                                fontFamily = PoppinsFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = textPrimary
                            )
                        }
                        Text(
                            text = "Kunci langsung Instagram & Browser agar tangan tidak gatal streaming:",
                            fontFamily = PoppinsFontFamily,
                            fontSize = 11.sp,
                            color = textSecondary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onSetManualLock(60 * 60 * 1000L) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = cardBorder)
                            ) {
                                Text("1 Jam", fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Bold, color = textPrimary)
                            }

                            Button(
                                onClick = { onSetManualLock(2 * 60 * 60 * 1000L) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = cardBorder)
                            ) {
                                Text("2 Jam", fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Bold, color = textPrimary)
                            }

                            Button(
                                onClick = { onSetManualLock(4 * 60 * 60 * 1000L) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentRed.copy(alpha = 0.8f))
                            ) {
                                Text("4 Jam", fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // Pengaturan Jatah Sesi
        item {
            SectionHeader(title = "JATAH DURASI SESI", textSecondary = textSecondary)
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
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Instagram Sesi
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Batas Scroll Instagram per Sesi:", fontFamily = PoppinsFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = textPrimary)
                            Text(text = "$igLimitMin Menit", fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AccentAmber)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(10, 15, 20, 30).forEach { m ->
                                PillChoice(label = "${m}m", isSelected = igLimitMin == m, isDark = isDark, modifier = Modifier.weight(1f)) {
                                    onIgLimitChange(m)
                                }
                            }
                        }
                    }

                    Divider(color = cardBorder, thickness = 0.8.dp)

                    // Browser Sesi
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Batas Nonton Browser per Sesi:", fontFamily = PoppinsFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = textPrimary)
                            Text(text = "$browserLimitMin Menit", fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AccentAmber)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(10, 15, 20, 30).forEach { m ->
                                PillChoice(label = "${m}m", isSelected = browserLimitMin == m, isDark = isDark, modifier = Modifier.weight(1f)) {
                                    onBrowserLimitChange(m)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Setup Google Authenticator (TOTP)
        item {
            SectionHeader(title = "KUNCI GOOGLE AUTHENTICATOR (TOTP)", textSecondary = textSecondary)
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
                    Text(
                        text = "Kunci Rahasia Penyiapan (Setup Key):",
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = textPrimary
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = subtaskBgColor(isDark),
                        border = BorderStroke(1.dp, cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = secretKey,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = AccentGreen,
                                letterSpacing = 2.sp
                            )
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("CiroKuota Secret", secretKey))
                                    Toast.makeText(context, "Kunci rahasia disalin ke clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Salin Kunci", tint = textPrimary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Text(
                        text = "💡 Cara Pakai di Google Authenticator:\n1. Buka aplikasi Authenticator di HP Anda.\n2. Klik tanda '+' lalu pilih 'Masukkan Kunci Penyiapan'.\n3. Nama Akun: CiroKuota, Kunci: Tempel kunci di atas.\n4. Sekarang Authenticator Anda sudah terhubung!",
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

    // DIALOG TANTANGAN NERAKA
    if (showChallengeDialog) {
        DarkSoulsUnlockDialog(
            secretKey = secretKey,
            onDismiss = { showChallengeDialog = false },
            onSuccess = {
                showChallengeDialog = false
                onUnlockSuccess()
            }
        )
    }
}

// DIALOG INTERAKTIF TANTANGAN NERAKA (MATH + 15s COUNTDOWN + AUTHENTICATOR CODE)
@Composable
fun DarkSoulsUnlockDialog(
    secretKey: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    var num1 by remember { mutableStateOf(Random.nextInt(25, 75)) }
    var num2 by remember { mutableStateOf(Random.nextInt(15, 60)) }
    val correctMath = num1 + num2

    var mathAnswer by remember { mutableStateOf("") }
    var totpInput by remember { mutableStateOf("") }
    var isMathSolved by remember { mutableStateOf(false) }

    var difficultySeconds by remember { mutableStateOf(20) }
    var secondsLeft by remember { mutableStateOf(20) }
    var isFailed by remember { mutableStateOf(false) }

    // Live sync indicator of Authenticator 30s cycle
    var authCycleSec by remember { mutableStateOf(TotpHelper.getSecondsRemaining(30)) }
    LaunchedEffect(Unit) {
        while (true) {
            authCycleSec = TotpHelper.getSecondsRemaining(30)
            delay(500)
        }
    }

    LaunchedEffect(isMathSolved) {
        if (isMathSolved) {
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
                    text = "TANTANGAN NERAKA",
                    fontFamily = RighteousFontFamily,
                    fontSize = 18.sp,
                    color = AccentRed
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isFailed) {
                    Text(
                        text = "❌ GAGAL! Waktu Anda habis atau kode salah. Mode autopilot khilaf Anda berhasil dicegat. Silakan istirahat!",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 12.5.sp,
                        color = AccentRed,
                        lineHeight = 18.sp
                    )
                } else if (!isMathSolved) {
                    Text(
                        text = "Ronde 1: Selesaikan soal matematika untuk mengaktifkan otak rasional:",
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

                    Text(
                        text = "Pilih Tingkat Adrenalin Masuk Kode:",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 11.sp,
                        color = Color.LightGray
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(20 to "Normal (20s)", 10 to "Hard (10s)", 5 to "Neraka (5s)").forEach { (sec, label) ->
                            val isSel = difficultySeconds == sec
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) AccentRed else Color(0xFF2A2A2A),
                                border = BorderStroke(1.dp, if (isSel) AccentRed else Color.Gray),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { difficultySeconds = sec }
                            ) {
                                Text(
                                    text = label,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 10.sp,
                                    color = if (isSel) Color.White else Color.LightGray,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (mathAnswer.trim() == correctMath.toString()) {
                                isMathSolved = true
                                Toast.makeText(context, "Jawaban Benar! Waktu $difficultySeconds detik dimulai!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Salah hitung! Coba lagi.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber)
                    ) {
                        Text("Lanjut ke Ronde Authenticator", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                } else {
                    // RONDE PANIK: INPUT KODE TOTP DALAM WAKTU SANGAT SEMPIT
                    Text(
                        text = "🚨 Ronde 2: Buka Google Authenticator dan masukkan 6 digit kode sekarang!",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 12.sp,
                        color = AccentRed,
                        fontWeight = FontWeight.Bold
                    )

                    // Live Authenticator cycle indicator
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

                    // Panic Bar
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

// ================= TAB 3: PENGATURAN & KEKEBALAN HP =================
@Composable
fun TabSettingsScreen(
    isDark: Boolean,
    candidateApps: List<SelectableApp>,
    hasAccessPerm: Boolean,
    hasUsagePerm: Boolean,
    isBatteryIgnored: Boolean,
    cardColor: Color,
    cardBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onToggleApp: (String) -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Checklist Aplikasi Target VPN Blackhole
        item {
            SectionHeader(title = "PILIH APLIKASI TARGET VPN BLACKHOLE", textSecondary = textSecondary)
        }

        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cardColor,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Centang aplikasi yang ingin 'dibuang ke lubang hitam' saat kuota habis / killswitch aktif:",
                        fontFamily = PoppinsFontFamily,
                        fontSize = 11.5.sp,
                        color = textSecondary
                    )

                    candidateApps.forEach { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onToggleApp(app.packageName) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Checkbox(
                                    checked = app.isSelected,
                                    onCheckedChange = { onToggleApp(app.packageName) },
                                    colors = CheckboxDefaults.colors(checkedColor = AccentGreen)
                                )
                                Column {
                                    Text(
                                        text = app.appName,
                                        fontFamily = PoppinsFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.5.sp,
                                        color = textPrimary
                                    )
                                    Text(
                                        text = if (app.isInstalled) "Terpasang di ponsel" else "Belum terpasang",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 9.5.sp,
                                        color = if (app.isInstalled) AccentGreen else textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Status Izin & Anti-Lepas
        item {
            SectionHeader(title = "KEKEBALAN AKSEBILITAS (ANTI-LEPAS)", textSecondary = textSecondary)
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Izin Bebas Baterai (Unrestricted)
                PermissionStatusCard(
                    title = "Bebas dari Hemat Baterai (Anti-Kill)",
                    desc = "Wajib agar HP tidak mematikan Aksesibilitas secara diam-diam",
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

                // Izin Aksesibilitas
                PermissionStatusCard(
                    title = "Layanan Aksesibilitas CiroKuota",
                    desc = "Wajib aktif untuk mendeteksi pembukaan Instagram & Browser",
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

                // Izin Usage Stats
                PermissionStatusCard(
                    title = "Akses Penggunaan Kuota (Usage Stats)",
                    desc = "Diperlukan untuk membaca MB kuota per aplikasi",
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

        // Panduan Kunci di Recent Apps (Gembok)
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
}

fun subtaskBgColor(isDark: Boolean): Color = if (isDark) DarkSubtaskBg else LightSubtaskBg

// Reuse existing UI Components
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

    val progressColor = when {
        status.percentUsed >= 90f -> AccentRed
        status.percentUsed >= 70f -> AccentAmber
        else -> AccentGreen
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
                    text = "KUOTA WI-FI HARI INI",
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

            // Angka Utama Sisa Kuota
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

            // Progress Bar
            LinearProgressIndicator(
                progress = { (status.percentUsed / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = progressColor,
                trackColor = cardBorder.copy(alpha = 0.5f)
            )

            // Pilihan Cepat Budget Kuota
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Atur Jatah Budget Harian:",
                    fontFamily = PoppinsFontFamily,
                    fontSize = 11.sp,
                    color = textSecondary
                )
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
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val icon = when (app.iconCategory) {
                    "instagram" -> Icons.Default.CameraAlt
                    "browser" -> Icons.Default.Language
                    "youtube" -> Icons.Default.PlayCircle
                    "tiktok" -> Icons.Default.MusicNote
                    else -> Icons.Default.Apps
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (app.isMonitored) AccentAmber else textSecondary,
                    modifier = Modifier.size(20.dp)
                )

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.appName,
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp,
                            color = textPrimary
                        )
                        if (app.isMonitored) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = AccentAmber.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "Target Lock",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 9.sp,
                                    color = AccentAmber,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = app.packageName,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        color = textSecondary
                    )
                }
            }

            Text(
                text = QuotaManager.formatBytes(app.bytesUsed),
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.5.sp,
                color = if (app.bytesUsed > 500 * 1024 * 1024) AccentRed else textPrimary
            )
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
