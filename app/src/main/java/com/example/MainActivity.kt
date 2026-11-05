package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var isFullscreenLandscapeMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Configure window for true transparency so background is visible
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.setFormat(PixelFormat.TRANSLUCENT)

        setContent {
            MyApplicationTheme {
                MainContainerScreen(
                    isFullscreenLandscape = isFullscreenLandscapeMode,
                    onEnterFullscreenLandscape = {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                        insetsController.hide(WindowInsetsCompat.Type.systemBars())
                        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        isFullscreenLandscapeMode = true
                    },
                    onExitFullscreenLandscape = {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                        insetsController.show(WindowInsetsCompat.Type.systemBars())
                        isFullscreenLandscapeMode = false
                    },
                    onShowOverlay = {
                        if (!Settings.canDrawOverlays(this)) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        } else {
                            AppForegroundService.showOverlay(this)
                        }
                    },
                    onHideOverlay = {
                        AppForegroundService.hideOverlay(this)
                    }
                )
            }
        }
    }
}

@Composable
fun MainContainerScreen(
    isFullscreenLandscape: Boolean,
    onEnterFullscreenLandscape: () -> Unit,
    onExitFullscreenLandscape: () -> Unit,
    onShowOverlay: () -> Unit,
    onHideOverlay: () -> Unit
) {
    val isServiceRunning by AppForegroundService.isServiceRunning.collectAsState()
    val serviceCpuSnapshot by AppForegroundService.latestCpuSnapshot.collectAsState()
    var localCpuSnapshot by remember { mutableStateOf(CpuMonitor.sampleCpuUsage()) }

    // Continuous real-time telemetry polling without artificial timer delays
    LaunchedEffect(isServiceRunning) {
        if (!isServiceRunning) {
            while (isActive) {
                val snapshot = withContext(Dispatchers.Default) {
                    CpuMonitor.sampleCpuUsage()
                }
                localCpuSnapshot = snapshot
                delay(600L)
            }
        }
    }

    val currentCpuSnapshot = if (isServiceRunning) serviceCpuSnapshot else localCpuSnapshot

    BackHandler(enabled = isFullscreenLandscape) {
        onExitFullscreenLandscape()
    }

    // In normal portrait mode, display solid dark background; in fullscreen, background is transparent
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isFullscreenLandscape) Color.Transparent else Color(0xFF0F172A))
    ) {
        // Main Screen configured to show exclusively the Telemetry Dashboard
        if (!isFullscreenLandscape) {
            TelemetryMainScreen(
                cpuSnapshot = currentCpuSnapshot,
                onShowOverlay = onShowOverlay,
                onHideOverlay = onHideOverlay,
                onEnterFullscreenLandscape = onEnterFullscreenLandscape
            )
        }

        // Fullscreen Horizontal Telemetry View (Transparent Theme: 5% - 80% opacity)
        AnimatedVisibility(
            visible = isFullscreenLandscape,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            TransparentFullscreenTelemetryHUD(
                cpuSnapshot = currentCpuSnapshot,
                onExit = onExitFullscreenLandscape
            )
        }
    }
}

/**
 * Main Layout: Exclusively dedicated to the CPU Telemetry Dashboard
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetryMainScreen(
    cpuSnapshot: CpuSnapshot,
    onShowOverlay: () -> Unit,
    onHideOverlay: () -> Unit,
    onEnterFullscreenLandscape: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isServiceRunning by AppForegroundService.isServiceRunning.collectAsState()
    val isBenchmarkActive by AppForegroundService.isBenchmarkActive.collectAsState()
    val activeBenchmarkThreads by AppForegroundService.activeBenchmarkThreads.collectAsState()
    val totalBenchmarkOperations by AppForegroundService.totalBenchmarkOperations.collectAsState()

    val isOverlayShowing by AppForegroundService.isOverlayShowing.collectAsState()
    var canDrawOverlays by remember { mutableStateOf(false) }

    fun refreshSystemStatus() {
        canDrawOverlays = Settings.canDrawOverlays(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshSystemStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            AppForegroundService.startService(context)
        } else {
            Toast.makeText(context, "Notification permission needed for Foreground Service", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "CPU Telemetry Monitor",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                            color = Color.White
                        )
                        Text(
                            text = "/proc/stat • Consecutive Delta Analysis",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF38BDF8)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { refreshSystemStatus() },
                        modifier = Modifier.testTag("refresh_status_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh status", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF1E293B).copy(alpha = 0.9f)
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(6.dp))

                // Primary Action Button: Enter Fullscreen Horizontal Mode with Transparent Background
                Button(
                    onClick = onEnterFullscreenLandscape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("fullscreen_landscape_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Pantalla Completa Horizontal (Fondo Transparente)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Real Hardware Information Card
            item {
                val hw = cpuSnapshot.hardwareDetails ?: CpuMonitor.getHardwareDetails()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E293B).copy(alpha = 0.95f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Memory, contentDescription = null, tint = Color(0xFF38BDF8))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "HARDWARE Y PROCESADOR REAL",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF94A3B8),
                                    letterSpacing = 0.5.sp
                                )
                            }
                            Surface(
                                color = Color(0xFF38BDF8).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "${hw.coreCount} NÚCLEOS DETECTADOS",
                                    color = Color(0xFF38BDF8),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = hw.processorName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Text(
                            text = "${hw.hardwareModel} • Arch: ${hw.architecture} • Governor: ${hw.governor}",
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1)
                        )
                        if (hw.bogoMips != "N/A" || hw.cacheSize != "N/A") {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "BogoMIPS: ${hw.bogoMips} | Caché L2/L3: ${hw.cacheSize}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF38BDF8)
                            )
                        }
                    }
                }
            }

            // AnTuTu Benchmark CPU Stress Test Card (Continuous parallel loops on all cores, Max Load)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isBenchmarkActive) Color(0xFF450A0A).copy(alpha = 0.95f) else Color(0xFF1E293B).copy(alpha = 0.95f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        if (isBenchmarkActive) Color(0xFFEF4444) else Color(0xFFF59E0B).copy(alpha = 0.6f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Calculate,
                                    contentDescription = null,
                                    tint = if (isBenchmarkActive) Color(0xFFEF4444) else Color(0xFFF59E0B)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "ANTUTU CPU STRESS TEST",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isBenchmarkActive) Color(0xFFFCA5A5) else Color(0xFFFCD34D),
                                    letterSpacing = 0.5.sp
                                )
                            }
                            Surface(
                                color = if (isBenchmarkActive) Color(0xFFEF4444).copy(alpha = 0.25f) else Color(0xFF64748B).copy(alpha = 0.25f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (isBenchmarkActive) "100% CARGA MÁXIMA" else "EN ESPERA",
                                    color = if (isBenchmarkActive) Color(0xFFEF4444) else Color(0xFF94A3B8),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Operaciones matemáticas complejas en bucles continuos sin pausas en todos los núcleos (${CpuMonitor.getCoreCount()} hilos en paralelo): matrices 4x4, series trigonométricas, fractales Mandelbrot y permutaciones SHA-256.",
                            fontSize = 10.sp,
                            color = Color(0xFFCBD5E1)
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Operaciones: %,d".format(totalBenchmarkOperations),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                            Text(
                                text = "${if (isBenchmarkActive) activeBenchmarkThreads else CpuMonitor.getCoreCount()} Hilos Paralelos",
                                fontSize = 10.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (isBenchmarkActive) {
                                    AppForegroundService.stopStress(context)
                                } else {
                                    AppForegroundService.startStress(context)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isBenchmarkActive) Color(0xFFDC2626) else Color(0xFFD97706)
                            )
                        ) {
                            Icon(
                                if (isBenchmarkActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBenchmarkActive) "DETENER STRESS TEST" else "INICIAR STRESS TEST (100% CARGA)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Global Processor Load Meter
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E293B).copy(alpha = 0.95f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Memory,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "GLOBAL CPU LOAD",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF94A3B8),
                                    letterSpacing = 0.5.sp
                                )
                            }

                            Surface(
                                color = Color(0xFF38BDF8).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (cpuSnapshot.isFromProcStat) "/proc/stat (REAL)" else "Δ diffs",
                                    color = Color(0xFF38BDF8),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "%.1f".format(cpuSnapshot.totalUsagePercent),
                                fontSize = 44.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = when {
                                    cpuSnapshot.totalUsagePercent > 75f -> Color(0xFFEF4444)
                                    cpuSnapshot.totalUsagePercent > 40f -> Color(0xFFF59E0B)
                                    else -> Color(0xFF10B981)
                                }
                            )
                            Text(
                                text = "%",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "${cpuSnapshot.cores.size} PROCESSOR CORES",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        LinearProgressIndicator(
                            progress = { (cpuSnapshot.totalUsagePercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = when {
                                cpuSnapshot.totalUsagePercent > 75f -> Color(0xFFEF4444)
                                cpuSnapshot.totalUsagePercent > 40f -> Color(0xFFF59E0B)
                                else -> Color(0xFF10B981)
                            }
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Consecutive readings delta: ΔActive = ${cpuSnapshot.deltaActive} ticks • ΔTotal = ${cpuSnapshot.deltaTotal} ticks",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }

            // Cores Section Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PER-CORE TELEMETRY",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8),
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Real-time updates",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }

            // Individual Cores Cards
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cpuSnapshot.cores.forEach { core ->
                        val coreColor = when {
                            core.usagePercent > 75f -> Color(0xFFEF4444)
                            core.usagePercent > 40f -> Color(0xFFF59E0B)
                            else -> Color(0xFF10B981)
                        }

                        Surface(
                            color = Color(0xFF1E293B).copy(alpha = 0.8f),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, coreColor.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Core #${core.coreIndex}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Frecuencia real: ${core.freqFormatted}",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF38BDF8)
                                        )
                                    }
                                    Text(
                                        text = "%.1f%%".format(core.usagePercent),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        color = coreColor
                                    )
                                }

                                Spacer(modifier = Modifier.height(5.dp))
                                LinearProgressIndicator(
                                    progress = { (core.usagePercent / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = coreColor,
                                    trackColor = Color.White.copy(alpha = 0.1f)
                                )

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Δact: ${core.deltaActive} / Δtot: ${core.deltaTotal}",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }
            }

            // Quick Background Service & Floating Overlay Controls
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E293B).copy(alpha = 0.7f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "CONTROLES ADICIONALES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Foreground Service Toggle Button
                            Button(
                                onClick = {
                                    if (isServiceRunning) {
                                        AppForegroundService.stopService(context)
                                    } else {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            AppForegroundService.startService(context)
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isServiceRunning) Color(0xFFEF4444) else Color(0xFF10B981)
                                )
                            ) {
                                Icon(
                                    if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isServiceRunning) "Stop Service" else "Start Service", fontSize = 11.sp)
                            }

                            // Floating Window (Overlay) Toggle Button
                            OutlinedButton(
                                onClick = {
                                    if (!canDrawOverlays) {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    } else {
                                        if (isOverlayShowing) {
                                            onHideOverlay()
                                        } else {
                                            onShowOverlay()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (isOverlayShowing) Color(0xFF38BDF8) else Color.White
                                )
                            ) {
                                Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    if (!canDrawOverlays) "Permiso Overlay"
                                    else if (isOverlayShowing) "Ocultar Flotante"
                                    else "Ventana Flotante",
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Real-Time Engine & AnTuTu Stress Status (Timer Eliminated)
                        Surface(
                            color = Color(0xFF0F172A).copy(alpha = 0.8f),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "MONITOREO HARDWARE & TEST ANTUTU",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF38BDF8)
                                    )
                                    Text(
                                        text = if (isBenchmarkActive) "100% CARGA" else "TIEMPO REAL",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isBenchmarkActive) Color(0xFFEF4444) else Color(0xFF10B981)
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "Temporizador eliminado: telemetría continua en tiempo real. Los bucles de estrés de AnTuTu se ejecutan en segundo plano sin pausas con múltiples hilos en paralelo manteniendo los núcleos al 100%.",
                                    fontSize = 9.sp,
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Transparent Fullscreen Horizontal HUD:
 * Transparent background ensuring that whatever is behind the app is completely visible!
 */
@Composable
fun TransparentFullscreenTelemetryHUD(
    cpuSnapshot: CpuSnapshot,
    onExit: () -> Unit
) {
    // Default 10% dark tint -> 90% visibility of whatever is behind the app!
    var overlayAlpha by remember { mutableFloatStateOf(0.10f) }

    // Dynamic pane and card transparency based on chosen overlayAlpha
    val panelBgAlpha = (overlayAlpha * 1.8f).coerceIn(0.04f, 0.70f)
    val cardBgAlpha = (overlayAlpha * 1.5f).coerceIn(0.04f, 0.60f)

    val isBenchmarkActive by AppForegroundService.isBenchmarkActive.collectAsState()
    val activeBenchmarkThreads by AppForegroundService.activeBenchmarkThreads.collectAsState()
    val totalBenchmarkOperations by AppForegroundService.totalBenchmarkOperations.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Transparent background allowing the real system background / app underneath to be visible
            .background(Color(0xFF000000).copy(alpha = overlayAlpha))
            .padding(10.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onExit,
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color.White.copy(alpha = 0.25f), CircleShape)
                            .testTag("exit_fullscreen_button")
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Exit Fullscreen",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "CPU TELEMETRY HUD (PANTALLA COMPLETA)",
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp,
                                color = Color.White,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = Color(0xFF38BDF8).copy(alpha = 0.35f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (cpuSnapshot.isFromProcStat) "/proc/stat" else "Δ diffs",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Fondo visible: ${((1f - overlayAlpha) * 100).toInt()}% • Opacidad: ${(overlayAlpha * 100).toInt()}%",
                            fontSize = 10.sp,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }

                // Opacity / Visibility Selector (5%, 10%, 20%, 50%, 80%)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Fondo:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE2E8F0)
                    )

                    FilterChip(
                        selected = overlayAlpha == 0.05f,
                        onClick = { overlayAlpha = 0.05f },
                        label = { Text("5%", fontSize = 9.sp) },
                        modifier = Modifier.height(26.dp)
                    )

                    FilterChip(
                        selected = overlayAlpha == 0.10f,
                        onClick = { overlayAlpha = 0.10f },
                        label = { Text("10%", fontSize = 9.sp) },
                        modifier = Modifier.height(26.dp)
                    )

                    FilterChip(
                        selected = overlayAlpha == 0.20f,
                        onClick = { overlayAlpha = 0.20f },
                        label = { Text("20%", fontSize = 9.sp) },
                        modifier = Modifier.height(26.dp)
                    )

                    FilterChip(
                        selected = overlayAlpha == 0.50f,
                        onClick = { overlayAlpha = 0.50f },
                        label = { Text("50%", fontSize = 9.sp) },
                        modifier = Modifier.height(26.dp)
                    )

                    FilterChip(
                        selected = overlayAlpha == 0.80f,
                        onClick = { overlayAlpha = 0.80f },
                        label = { Text("80%", fontSize = 9.sp) },
                        modifier = Modifier.height(26.dp)
                    )

                    FilledTonalButton(
                        onClick = onExit,
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("exit_fullscreen_chip")
                    ) {
                        Icon(
                            Icons.Default.FullscreenExit,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Salir", fontSize = 10.sp)
                    }
                }
            }

            // Main Landscape 2-Pane Content with Semi-Transparent Glassmorphic Cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Left Pane: Global CPU & Background Worker
                Surface(
                    color = Color(0xFF0F172A).copy(alpha = panelBgAlpha),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8).copy(alpha = 0.6f)),
                    modifier = Modifier
                        .weight(0.38f)
                        .fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Total CPU Gauge Box
                        Column {
                            Text(
                                text = "GLOBAL PROCESSOR LOAD",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFCBD5E1),
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "%.1f".format(cpuSnapshot.totalUsagePercent),
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    color = when {
                                        cpuSnapshot.totalUsagePercent > 75f -> Color(0xFFEF4444)
                                        cpuSnapshot.totalUsagePercent > 40f -> Color(0xFFF59E0B)
                                        else -> Color(0xFF10B981)
                                    }
                                )
                                Text(
                                    text = "%",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFCBD5E1),
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = "${cpuSnapshot.cores.size} CORES",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF38BDF8),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }

                            LinearProgressIndicator(
                                progress = { (cpuSnapshot.totalUsagePercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = when {
                                    cpuSnapshot.totalUsagePercent > 75f -> Color(0xFFEF4444)
                                    cpuSnapshot.totalUsagePercent > 40f -> Color(0xFFF59E0B)
                                    else -> Color(0xFF10B981)
                                }
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "ΔActive: ${cpuSnapshot.deltaActive} ticks • ΔTotal: ${cpuSnapshot.deltaTotal} ticks",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFCBD5E1)
                            )
                        }

                        // AnTuTu Benchmark Stress Test Telemetry in HUD
                        Surface(
                            color = Color(0xFF1E293B).copy(alpha = cardBgAlpha),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isBenchmarkActive) Color(0xFFEF4444) else Color(0xFF38BDF8).copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "AnTuTu CPU Stress Test",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isBenchmarkActive) Color(0xFFFCA5A5) else Color.White
                                    )
                                    Text(
                                        text = if (isBenchmarkActive) "100% CARGA" else "INACTIVO",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isBenchmarkActive) Color(0xFFEF4444) else Color(0xFF94A3B8)
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Ops: %,d | %d Hilos sin pausas".format(
                                        totalBenchmarkOperations,
                                        if (isBenchmarkActive) activeBenchmarkThreads else cpuSnapshot.cores.size
                                    ),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF38BDF8)
                                )
                                Text(
                                    text = "Bucles continuos: Matrices, Trigonometría, Mandelbrot, SHA",
                                    fontSize = 8.sp,
                                    color = Color(0xFFCBD5E1),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Right Pane: Transparent Grid of all CPU Cores with Real Frequencies
                Surface(
                    color = Color(0xFF0F172A).copy(alpha = panelBgAlpha),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .weight(0.62f)
                        .fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "INDIVIDUAL CPU CORES (/proc/stat CONSECUTIVE DIFFS)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFCBD5E1),
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(cpuSnapshot.cores) { core ->
                                CoreGridCardTransparent(core = core, cardAlpha = cardBgAlpha)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CoreGridCardTransparent(core: CoreUsage, cardAlpha: Float = 0.55f) {
    val coreColor = when {
        core.usagePercent > 75f -> Color(0xFFEF4444)
        core.usagePercent > 40f -> Color(0xFFF59E0B)
        else -> Color(0xFF10B981)
    }

    Surface(
        color = Color(0xFF1E293B).copy(alpha = cardAlpha),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, coreColor.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Core #${core.coreIndex}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                    Text(
                        text = core.freqFormatted,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF38BDF8)
                    )
                }

                Text(
                    text = "%.1f%%".format(core.usagePercent),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = coreColor
                )
            }

            Spacer(modifier = Modifier.height(3.dp))
            LinearProgressIndicator(
                progress = { (core.usagePercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = coreColor,
                trackColor = Color.White.copy(alpha = 0.15f)
            )

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Δact: ${core.deltaActive} / Δtot: ${core.deltaTotal}",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFCBD5E1)
            )
        }
    }
}
