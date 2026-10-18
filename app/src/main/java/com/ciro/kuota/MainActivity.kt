package com.ciro.kuota

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ciro.kuota.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            var isDark by remember { mutableStateOf(QuotaManager.isDarkMode(context)) }

            CiroKuotaTheme(darkTheme = isDark) {
                CiroKuotaDashboard(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CiroKuotaDashboard(
    isDark: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current

    // State Pengaturan
    var dailyBudgetMb by remember { mutableStateOf(QuotaManager.getDailyBudgetMb(context)) }
    var isKillswitch by remember { mutableStateOf(QuotaManager.isKillswitchActive(context)) }
    var isFamilyDns by remember { mutableStateOf(QuotaManager.isFamilyDnsActive(context)) }
    var isAppLock by remember { mutableStateOf(QuotaManager.isAppLockActive(context)) }
    var isAutoCutoff by remember { mutableStateOf(QuotaManager.isAutoCutoffActive(context)) }
    var igLimitMin by remember { mutableStateOf(QuotaManager.getIgSessionLimitMinutes(context)) }
    var browserLimitMin by remember { mutableStateOf(QuotaManager.getBrowserSessionLimitMinutes(context)) }
    var manualLockUntil by remember { mutableStateOf(QuotaManager.getManualLockUntil(context)) }

    // State Data Pemakaian
    var quotaStatus by remember { mutableStateOf(QuotaManager.getQuotaStatus(context)) }
    var topApps by remember { mutableStateOf(QuotaManager.getTopMonitoredAppsUsage(context)) }
    var hasUsagePerm by remember { mutableStateOf(QuotaManager.hasUsageStatsPermission(context)) }
    var hasAccessPerm by remember { mutableStateOf(QuotaManager.hasAccessibilityPermission(context)) }

    // Launcher untuk VPN Service permission
    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            CiroVpnService.start(context)
            Toast.makeText(context, "Firewall & Pelindung Kuota Aktif!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Izin VPN diperlukan untuk memutus kuota/filter DNS", Toast.LENGTH_SHORT).show()
        }
    }

    fun startVpnSafely() {
        val intent = VpnService.prepare(context)
        if (intent != null) {
            vpnPrepareLauncher.launch(intent)
        } else {
            CiroVpnService.start(context)
        }
    }

    // Auto Refresh setiap 4 detik
    LaunchedEffect(Unit) {
        while (true) {
            quotaStatus = QuotaManager.getQuotaStatus(context)
            topApps = QuotaManager.getTopMonitoredAppsUsage(context)
            hasUsagePerm = QuotaManager.hasUsageStatsPermission(context)
            hasAccessPerm = QuotaManager.hasAccessibilityPermission(context)
            manualLockUntil = QuotaManager.getManualLockUntil(context)
            delay(4000)
        }
    }

    val bgColor = if (isDark) DarkBg else LightBg
    val cardColor = if (isDark) DarkCard else LightCard
    val cardBorder = if (isDark) DarkCardBorder else LightCardBorder
    val textPrimary = if (isDark) DarkTextPrimary else LightTextPrimary
    val textSecondary = if (isDark) DarkTextSecondary else LightTextSecondary
    val subtaskBg = if (isDark) DarkSubtaskBg else LightSubtaskBg

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

                    // Theme toggle button
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
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. HERO CARD: Sisa Kuota Wi-Fi Hari Ini
            item {
                HeroQuotaCard(
                    status = quotaStatus,
                    cardBg = cardColor,
                    cardBorder = cardBorder,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onSelectBudget = { mb ->
                        dailyBudgetMb = mb
                        QuotaManager.setDailyBudgetMb(context, mb)
                        quotaStatus = QuotaManager.getQuotaStatus(context)
                    }
                )
            }

            // 2. KARTU SAKLAR PROTEKSI CEPAT
            item {
                SectionHeader(title = "SAKLAR PROTEKSI CEPAT", textSecondary = textSecondary)
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Saklar A: Killswitch Darurat (Putus Total Internet)
                    QuickSwitchCard(
                        title = "Killswitch Darurat",
                        subtitle = "Putus 100% internet seketika untuk cegah bocor kuota",
                        icon = Icons.Default.PowerSettingsNew,
                        isActive = isKillswitch,
                        activeColor = AccentRed,
                        cardBg = cardColor,
                        cardBorder = cardBorder,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onToggle = {
                            isKillswitch = !isKillswitch
                            QuotaManager.setKillswitchActive(context, isKillswitch)
                            if (isKillswitch) {
                                startVpnSafely()
                                Toast.makeText(context, "🚨 Killswitch Aktif! Internet diputus total.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Killswitch Dimatikan.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )

                    // Saklar B: DNS Family Shield (Cloudflare 1.1.1.3 Anti Bokep)
                    QuickSwitchCard(
                        title = "DNS Family Shield",
                        subtitle = "Blokir otomatis situs dewasa & malware di level DNS",
                        icon = Icons.Default.Shield,
                        isActive = isFamilyDns,
                        activeColor = AccentGreen,
                        cardBg = cardColor,
                        cardBorder = cardBorder,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onToggle = {
                            isFamilyDns = !isFamilyDns
                            QuotaManager.setFamilyDnsActive(context, isFamilyDns)
                            startVpnSafely()
                            Toast.makeText(context, if (isFamilyDns) "Family DNS Aktif!" else "Family DNS Mati", Toast.LENGTH_SHORT).show()
                        }
                    )

                    // Saklar C: AppLock Instagram & Browser
                    QuickSwitchCard(
                        title = "AppLock Instagram & Browser",
                        subtitle = "Kunci instan jika melewati batas sesi scrolling / nonton",
                        icon = Icons.Default.Lock,
                        isActive = isAppLock,
                        activeColor = AccentAmber,
                        cardBg = cardColor,
                        cardBorder = cardBorder,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onToggle = {
                            isAppLock = !isAppLock
                            QuotaManager.setAppLockActive(context, isAppLock)
                            if (isAppLock && !hasAccessPerm) {
                                Toast.makeText(context, "Harap aktifkan Layanan Aksesibilitas di bawah!", Toast.LENGTH_LONG).show()
                            }
                        }
                    )

                    // Saklar D: Auto-Cutoff saat Kuota Harian Habis
                    QuickSwitchCard(
                        title = "Auto-Cutoff Kuota Habis",
                        subtitle = "Otomatis hentikan kuota jika pemakaian tembus 100%",
                        icon = Icons.Default.DataSaverOn,
                        isActive = isAutoCutoff,
                        activeColor = Color(0xFF38BDF8),
                        cardBg = cardColor,
                        cardBorder = cardBorder,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onToggle = {
                            isAutoCutoff = !isAutoCutoff
                            QuotaManager.setAutoCutoffActive(context, isAutoCutoff)
                        }
                    )
                }
            }

            // 3. PENGATURAN BATAS SESI (INSTAGRAM & BROWSER)
            item {
                SectionHeader(title = "BATAS SESI & REM KHILAF", textSecondary = textSecondary)
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Maksimal Scroll Instagram / Sesi:",
                                    fontFamily = PoppinsFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.5.sp,
                                    color = textPrimary
                                )
                                Text(
                                    text = "$igLimitMin Menit",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = AccentAmber
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(10, 15, 20, 30).forEach { m ->
                                    val isSelected = igLimitMin == m
                                    PillChoice(
                                        label = "${m}m",
                                        isSelected = isSelected,
                                        isDark = isDark,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        igLimitMin = m
                                        QuotaManager.setIgSessionLimitMinutes(context, m)
                                    }
                                }
                            }
                        }

                        Divider(color = cardBorder, thickness = 0.8.dp)

                        // Browser Sesi
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Maksimal Nonton Browser / Sesi:",
                                    fontFamily = PoppinsFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.5.sp,
                                    color = textPrimary
                                )
                                Text(
                                    text = "$browserLimitMin Menit",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = AccentAmber
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(10, 15, 20, 30).forEach { m ->
                                    val isSelected = browserLimitMin == m
                                    PillChoice(
                                        label = "${m}m",
                                        isSelected = isSelected,
                                        isDark = isDark,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        browserLimitMin = m
                                        QuotaManager.setBrowserSessionLimitMinutes(context, m)
                                    }
                                }
                            }
                        }

                        Divider(color = cardBorder, thickness = 0.8.dp)

                        // Kunci Darurat Sekarang (1 Jam / 2 Jam)
                        val now = System.currentTimeMillis()
                        val isManualLocked = manualLockUntil > now
                        if (isManualLocked) {
                            val remMinutes = ((manualLockUntil - now) / 60000) + 1
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = AccentRed.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "🔒 Kunci Sesi Sedang Berjalan",
                                            fontFamily = PoppinsFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = AccentRed
                                        )
                                        Text(
                                            text = "Tersisa $remMinutes menit lagi",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 11.sp,
                                            color = textSecondary
                                        )
                                    }
                                    TextButton(onClick = {
                                        manualLockUntil = 0L
                                        QuotaManager.setManualLockUntil(context, 0L)
                                        Toast.makeText(context, "Kunci manual dilepas", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Text("Lepas", color = textPrimary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val until = System.currentTimeMillis() + (60 * 60 * 1000)
                                        manualLockUntil = until
                                        QuotaManager.setManualLockUntil(context, until)
                                        Toast.makeText(context, "IG & Browser dikunci selama 1 Jam!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, cardBorder)
                                ) {
                                    Text("Kunci 1 Jam", fontFamily = PoppinsFontFamily, fontSize = 11.5.sp, color = textPrimary)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val until = System.currentTimeMillis() + (2 * 60 * 60 * 1000)
                                        manualLockUntil = until
                                        QuotaManager.setManualLockUntil(context, until)
                                        Toast.makeText(context, "IG & Browser dikunci selama 2 Jam!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, cardBorder)
                                ) {
                                    Text("Kunci 2 Jam", fontFamily = PoppinsFontFamily, fontSize = 11.5.sp, color = textPrimary)
                                }
                            }
                        }
                    }
                }
            }

            // 4. DAFTAR PEMAKAIAN WI-FI PER APLIKASI
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
                            text = if (!hasUsagePerm) "⚠️ Izin Akses Penggunaan belum aktif. Ketuk kartu izin di bawah agar data per aplikasi terbaca." else "Belum ada pemakaian Wi-Fi hari ini.",
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

            // 5. PANDUAN & STATUS IZIN SISTEM
            item {
                SectionHeader(title = "STATUS IZIN SISTEM", textSecondary = textSecondary)
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Izin 1: Layanan Aksesibilitas (AppLock)
                    PermissionStatusCard(
                        title = "Layanan Aksesibilitas (AppLock)",
                        desc = "Wajib untuk mendeteksi pembukaan Instagram & Browser",
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

                    // Izin 2: Akses Penggunaan (Usage Stats)
                    PermissionStatusCard(
                        title = "Akses Penggunaan Kuota (Usage Stats)",
                        desc = "Diperlukan untuk membaca riwayat MB data per aplikasi",
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

            // Spacer bawah
            item {
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}

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
