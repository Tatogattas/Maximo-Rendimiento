package com.example

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AppForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null

    // Continuous Multi-threaded AnTuTu CPU Stress Test (No pauses / continuous background loops)
    private val benchmarkThreads = mutableListOf<Thread>()
    private val isBenchmarkRunning = AtomicBoolean(false)
    private val totalOpsCounter = AtomicLong(0L)

    // Continuous Telemetry Monitor Thread (Live real-time refresh, timer delay eliminated)
    private var telemetryMonitorThread: Thread? = null
    private val isTelemetryRunning = AtomicBoolean(false)

    // Floating overlay window views managed by the service
    private var windowManager: WindowManager? = null
    private var overlayContainer: LinearLayout? = null
    private var totalCpuTextView: TextView? = null
    private var totalProgressBarView: ProgressBar? = null
    private val coreTextViews = mutableListOf<TextView>()
    private val coreProgressBars = mutableListOf<ProgressBar>()
    private val coreFreqTextViews = mutableListOf<TextView>()
    private var isCompactMode = false
    private var coresContainerLayout: LinearLayout? = null
    private var stressStatusBadgeView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServiceInternal()
                return START_NOT_STICKY
            }
            ACTION_START_STRESS -> {
                startForegroundInternal()
                startMultiThreadedAnTuTuStress()
            }
            ACTION_STOP_STRESS -> {
                stopMultiThreadedAnTuTuStress()
            }
            ACTION_TOGGLE_STRESS -> {
                if (_isBenchmarkActive.value) {
                    stopMultiThreadedAnTuTuStress()
                } else {
                    startForegroundInternal()
                    startMultiThreadedAnTuTuStress()
                }
            }
            ACTION_TOGGLE_WAKELOCK -> {
                toggleWakeLockInternal()
            }
            ACTION_SHOW_OVERLAY -> {
                startForegroundInternal()
                showFloatingOverlayInternal()
            }
            ACTION_HIDE_OVERLAY -> {
                hideFloatingOverlayInternal()
            }
            ACTION_TOGGLE_OVERLAY -> {
                if (_isOverlayShowing.value) {
                    hideFloatingOverlayInternal()
                } else {
                    startForegroundInternal()
                    showFloatingOverlayInternal()
                }
            }
            ACTION_START, null -> {
                startForegroundInternal()
            }
        }
        return START_STICKY
    }

    private fun startForegroundInternal() {
        if (_isServiceRunning.value) return

        _isServiceRunning.value = true
        _serviceStartTime.value = System.currentTimeMillis()

        acquireWakeLockInternal()
        startMultiThreadedAnTuTuStress()
        startContinuousTelemetryMonitor()

        val notification = buildNotification("Game Mode: Alto Rendimiento y Telemetría Activa")
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            while (_isServiceRunning.value) {
                delay(1000)
                _uptimeSeconds.value += 1
            }
        }
    }

    /**
     * Native High Performance Mode management via Shizuku:
     * Eliminates artificial math loops and directly enables device native high performance mode
     * and thermal bypass (settings put system power_mode_type 2, high_performance_mode_on 1, thermalservice override-status 0).
     */
    private fun startHighPerformanceMode() {
        _isBenchmarkActive.value = true
        serviceScope.launch {
            ShizukuHelper.applyNativeHighPerformance()
            mainHandler.post {
                Toast.makeText(
                    this@AppForegroundService,
                    "Modo Alto Rendimiento del Sistema Activado (Shizuku UID 2000)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun stopHighPerformanceMode() {
        _isBenchmarkActive.value = false
        serviceScope.launch {
            ShizukuHelper.restoreNativeSystemSettings()
            mainHandler.post {
                Toast.makeText(this@AppForegroundService, "Modo Normal Restaurado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Aliases for compatibility
    private fun startMultiThreadedAnTuTuStress() = startHighPerformanceMode()
    private fun stopMultiThreadedAnTuTuStress() = stopHighPerformanceMode()

    /**
     * Continuous Telemetry Monitor:
     * The 5-second timer has been eliminated.
     * Reads /proc/stat and sysfs hardware clock speeds continuously in real time.
     */
    private fun startContinuousTelemetryMonitor() {
        if (isTelemetryRunning.compareAndSet(false, true)) {
            telemetryMonitorThread = Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                } catch (_: Exception) {}

                while (isTelemetryRunning.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        val snapshot = CpuMonitor.sampleCpuUsage()
                        _latestCpuSnapshot.value = snapshot
                        _lastTelemetryUpdateTime.value = System.currentTimeMillis()

                        mainHandler.post {
                            updateOverlayUi(snapshot)
                            updateNotificationWithCpu(snapshot)
                        }

                        // Continuous smooth real-time sampling (no 5-second pause)
                        Thread.sleep(600L)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }, "ContinuousTelemetryThread").apply {
                priority = Thread.NORM_PRIORITY
                isDaemon = true
                start()
            }
        }
    }

    private fun stopContinuousTelemetryMonitor() {
        isTelemetryRunning.set(false)
        telemetryMonitorThread?.interrupt()
        telemetryMonitorThread = null
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showFloatingOverlayInternal() {
        if (!Settings.canDrawOverlays(this)) {
            mainHandler.post {
                Toast.makeText(this, "Permiso de superposición no concedido", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (overlayContainer != null) return

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 120
        }

        // 50% transparency: Color.argb(128, 15, 23, 42)
        val backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(AndroidColor.argb(128, 15, 23, 42))
            setStroke(2, AndroidColor.argb(160, 56, 189, 248))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = backgroundDrawable
            setPadding(22, 16, 22, 16)
            elevation = 16f
        }

        // Header Row: Title, Stress Badge, Minimize, Close
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(this).apply {
            text = "CPU & Performance Monitor"
            setTextColor(AndroidColor.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val stressBadge = TextView(this).apply {
            val isPerf = _isBenchmarkActive.value || ShizukuHelper.isHighPerformanceActive.value
            text = if (isPerf) "HIGH PERF" else "NORMAL"
            setTextColor(if (isPerf) AndroidColor.argb(255, 239, 68, 68) else AndroidColor.argb(255, 16, 185, 129))
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(8, 2, 8, 2)
        }
        stressStatusBadgeView = stressBadge

        val minimizeButton = TextView(this).apply {
            text = " ─ "
            setTextColor(AndroidColor.argb(230, 148, 163, 184))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(6, 2, 6, 2)
            setOnClickListener {
                isCompactMode = !isCompactMode
                coresContainerLayout?.visibility = if (isCompactMode) View.GONE else View.VISIBLE
                text = if (isCompactMode) " ＋ " else " ─ "
            }
        }

        val closeButton = TextView(this).apply {
            text = " ✕ "
            setTextColor(AndroidColor.argb(230, 248, 113, 113))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(6, 2, 6, 2)
            setOnClickListener { hideFloatingOverlayInternal() }
        }

        headerLayout.addView(titleView)
        headerLayout.addView(stressBadge)
        headerLayout.addView(minimizeButton)
        headerLayout.addView(closeButton)
        container.addView(headerLayout)

        val hw = CpuMonitor.getHardwareDetails()
        val subtitleView = TextView(this).apply {
            text = "${hw.coreCount} Cores • ${hw.architecture} • Real-Time"
            setTextColor(AndroidColor.argb(200, 148, 163, 184))
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setPadding(0, 2, 0, 4)
        }
        container.addView(subtitleView)

        val totalCpuText = TextView(this).apply {
            text = "Total CPU: 0.0%"
            setTextColor(AndroidColor.argb(240, 56, 189, 248))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 2, 0, 2)
        }
        val totalProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(210),
                dpToPx(5)
            ).apply {
                bottomMargin = dpToPx(6)
            }
        }

        container.addView(totalCpuText)
        container.addView(totalProgressBar)

        totalCpuTextView = totalCpuText
        totalProgressBarView = totalProgressBar

        val coresContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(coresContainer)
        coresContainerLayout = coresContainer

        coreTextViews.clear()
        coreProgressBars.clear()
        coreFreqTextViews.clear()

        val coreCount = CpuMonitor.getCoreCount()
        for (i in 0 until coreCount) {
            val coreRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(3)
                }
            }

            val coreLabel = TextView(this).apply {
                text = "Core $i: 0.0%"
                setTextColor(AndroidColor.argb(220, 241, 245, 249))
                textSize = 9f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(dpToPx(85), LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val coreBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                layoutParams = LinearLayout.LayoutParams(dpToPx(65), dpToPx(4))
            }

            val coreFreq = TextView(this).apply {
                text = "..."
                setTextColor(AndroidColor.argb(230, 56, 189, 248))
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setPadding(dpToPx(4), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(dpToPx(65), LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            coreTextViews.add(coreLabel)
            coreProgressBars.add(coreBar)
            coreFreqTextViews.add(coreFreq)

            coreRow.addView(coreLabel)
            coreRow.addView(coreBar)
            coreRow.addView(coreFreq)
            coresContainer.addView(coreRow)
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(container, params)
                    true
                }
                else -> false
            }
        }

        overlayContainer = container
        windowManager?.addView(container, params)
        _isOverlayShowing.value = true

        updateOverlayUi(_latestCpuSnapshot.value)
    }

    private fun hideFloatingOverlayInternal() {
        if (overlayContainer != null) {
            try {
                windowManager?.removeView(overlayContainer)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayContainer = null
            totalCpuTextView = null
            totalProgressBarView = null
            coreTextViews.clear()
            coreProgressBars.clear()
            coreFreqTextViews.clear()
            coresContainerLayout = null
            stressStatusBadgeView = null
            _isOverlayShowing.value = false
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateOverlayUi(snapshot: CpuSnapshot) {
        if (overlayContainer == null) return

        val formattedTotal = String.format("%.1f", snapshot.totalUsagePercent)
        totalCpuTextView?.text = "Total CPU: $formattedTotal%"
        totalProgressBarView?.progress = snapshot.totalUsagePercent.toInt().coerceIn(0, 100)

        val isPerf = _isBenchmarkActive.value || ShizukuHelper.isHighPerformanceActive.value
        stressStatusBadgeView?.text = if (isPerf) "HIGH PERF" else "NORMAL"
        stressStatusBadgeView?.setTextColor(
            if (isPerf) AndroidColor.argb(255, 239, 68, 68) else AndroidColor.argb(255, 16, 185, 129)
        )

        snapshot.cores.forEachIndexed { index, core ->
            if (index < coreTextViews.size) {
                val formattedCore = String.format("%.1f", core.usagePercent)
                coreTextViews[index].text = "Core $index: $formattedCore%"
                coreProgressBars[index].progress = core.usagePercent.toInt().coerceIn(0, 100)
                coreFreqTextViews[index].text = core.freqFormatted
            }
        }
    }

    private fun updateNotificationWithCpu(snapshot: CpuSnapshot) {
        if (!_isServiceRunning.value) return
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val perfTag = if (_isBenchmarkActive.value || ShizukuHelper.isHighPerformanceActive.value) "[ALTO RENDIMIENTO] " else ""
        val hw = snapshot.hardwareDetails ?: CpuMonitor.getHardwareDetails()
        val procName = hw.processorName.take(24)
        val mode = if (ShizukuHelper.isHighPerformanceActive.value) "PERF (UID 2000)" else "NORMAL"
        val updatedText = "%sCPU: %.1f%% (%d cores) | %s | Modo: %s".format(
            perfTag,
            snapshot.totalUsagePercent,
            snapshot.cores.size,
            procName,
            mode
        )
        val notification = buildNotification(updatedText)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun acquireWakeLockInternal() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ServiceHub::ForegroundServiceWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(30 * 60 * 1000L /* 30 minutes */)
                }
                _isWakeLockAcquired.value = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _isWakeLockAcquired.value = false
        }
    }

    private fun toggleWakeLockInternal() {
        if (_isWakeLockAcquired.value) {
            releaseWakeLockInternal()
        } else {
            acquireWakeLockInternal()
        }
    }

    private fun releaseWakeLockInternal() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            wakeLock = null
            _isWakeLockAcquired.value = false
        }
    }

    private fun stopServiceInternal() {
        hideFloatingOverlayInternal()
        stopHighPerformanceMode()
        stopContinuousTelemetryMonitor()
        releaseWakeLockInternal()
        _isServiceRunning.value = false
        _uptimeSeconds.value = 0L
        _serviceStartTime.value = 0L
        _totalBenchmarkOperations.value = 0L
        ShizukuHelper.restoreNativeSystemSettings()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideFloatingOverlayInternal()
        stopHighPerformanceMode()
        stopContinuousTelemetryMonitor()
        releaseWakeLockInternal()
        ShizukuHelper.restoreNativeSystemSettings()
        serviceScope.cancel()
        _isServiceRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Game Mode Priority Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Game Mode Booster High-Priority Foreground Service (v1.3)"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(false)
                setSound(null, null)
                setShowBadge(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isPerf = ShizukuHelper.isHighPerformanceActive.value
        val shizukuConnected = ShizukuHelper.isServiceConnected.value
        val modeBadge = if (isPerf) "Modo: ALTO RENDIMIENTO (ON)" else "Modo: NORMAL (Balanceado)"
        val shizukuBadge = if (shizukuConnected) "UID 2000 [OK]" else "Shizuku: Offline"
        val dynamicSubText = "$modeBadge • $shizukuBadge"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Game Mode Booster v1.3")
            .setSubText(dynamicSubText)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "service_hub_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.service.START"
        const val ACTION_STOP = "com.example.service.STOP"
        const val ACTION_START_STRESS = "com.example.service.START_STRESS"
        const val ACTION_STOP_STRESS = "com.example.service.STOP_STRESS"
        const val ACTION_TOGGLE_STRESS = "com.example.service.TOGGLE_STRESS"
        const val ACTION_TOGGLE_WAKELOCK = "com.example.service.TOGGLE_WAKELOCK"
        const val ACTION_SHOW_OVERLAY = "com.example.service.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.example.service.HIDE_OVERLAY"
        const val ACTION_TOGGLE_OVERLAY = "com.example.service.TOGGLE_OVERLAY"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _isWakeLockAcquired = MutableStateFlow(false)
        val isWakeLockAcquired: StateFlow<Boolean> = _isWakeLockAcquired.asStateFlow()

        private val _uptimeSeconds = MutableStateFlow(0L)
        val uptimeSeconds: StateFlow<Long> = _uptimeSeconds.asStateFlow()

        private val _serviceStartTime = MutableStateFlow(0L)
        val serviceStartTime: StateFlow<Long> = _serviceStartTime.asStateFlow()

        // Multi-threaded AnTuTu Stress Engine States
        private val _isBenchmarkActive = MutableStateFlow(false)
        val isBenchmarkActive: StateFlow<Boolean> = _isBenchmarkActive.asStateFlow()

        // Alias for compatibility
        val isWorkerActive: StateFlow<Boolean> = _isBenchmarkActive

        private val _activeBenchmarkThreads = MutableStateFlow(0)
        val activeBenchmarkThreads: StateFlow<Int> = _activeBenchmarkThreads.asStateFlow()

        private val _totalBenchmarkOperations = MutableStateFlow(0L)
        val totalBenchmarkOperations: StateFlow<Long> = _totalBenchmarkOperations.asStateFlow()

        val mathCalculationCount: StateFlow<Long> = _totalBenchmarkOperations

        private val _isOverlayShowing = MutableStateFlow(false)
        val isOverlayShowing: StateFlow<Boolean> = _isOverlayShowing.asStateFlow()

        private val _latestCpuSnapshot = MutableStateFlow(CpuMonitor.sampleCpuUsage())
        val latestCpuSnapshot: StateFlow<CpuSnapshot> = _latestCpuSnapshot.asStateFlow()

        private val _lastTelemetryUpdateTime = MutableStateFlow(System.currentTimeMillis())
        val lastTelemetryUpdateTime: StateFlow<Long> = _lastTelemetryUpdateTime.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, AppForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AppForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun startStress(context: Context) {
            val intent = Intent(context, AppForegroundService::class.java).apply {
                action = ACTION_START_STRESS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopStress(context: Context) {
            val intent = Intent(context, AppForegroundService::class.java).apply {
                action = ACTION_STOP_STRESS
            }
            context.startService(intent)
        }

        fun toggleStress(context: Context) {
            val intent = Intent(context, AppForegroundService::class.java).apply {
                action = ACTION_TOGGLE_STRESS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun toggleWakeLock(context: Context) {
            val intent = Intent(context, AppForegroundService::class.java).apply {
                action = ACTION_TOGGLE_WAKELOCK
            }
            context.startService(intent)
        }

        fun showOverlay(context: Context) {
            val intent = Intent(context, AppForegroundService::class.java).apply {
                action = ACTION_SHOW_OVERLAY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hideOverlay(context: Context) {
            val intent = Intent(context, AppForegroundService::class.java).apply {
                action = ACTION_HIDE_OVERLAY
            }
            context.startService(intent)
        }

        fun toggleOverlay(context: Context) {
            val intent = Intent(context, AppForegroundService::class.java).apply {
                action = ACTION_TOGGLE_OVERLAY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
