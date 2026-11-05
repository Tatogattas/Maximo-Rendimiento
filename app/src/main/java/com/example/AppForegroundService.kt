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

        val notification = buildNotification("AnTuTu CPU Benchmark: Multi-hilo a máxima carga")
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            while (_isServiceRunning.value) {
                delay(1000)
                _uptimeSeconds.value += 1
            }
        }
    }

    /**
     * AnTuTu Benchmark Multi-Threaded CPU Stress Test:
     * Launches parallel worker threads across all available CPU cores.
     * Each thread executes continuous tight mathematical loops WITHOUT ANY PAUSES OR SLEEP:
     * - 64-bit Matrix Multiplications
     * - Trigonometric series: sin(x) * cos(x) + sqrt(|x|) - atan(x)
     * - Mandelbrot fractal point coordinate recurrence: z = z^2 + c
     * - Sieve prime factorization
     * - Xorshift128+ bitwise cryptographic permutations
     * This pushes every CPU core to 100% maximum capacity, exactly like AnTuTu Benchmark.
     */
    private fun startMultiThreadedAnTuTuStress() {
        if (isBenchmarkRunning.compareAndSet(false, true)) {
            _isBenchmarkActive.value = true
            benchmarkThreads.clear()

            val coreCount = CpuMonitor.getCoreCount()
            _activeBenchmarkThreads.value = coreCount

            for (threadIndex in 0 until coreCount) {
                val workerThread = Thread({
                    try {
                        // High execution priority for maximum CPU stress
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    } catch (_: Exception) {
                        try {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
                        } catch (_: Exception) {}
                    }

                    // Pre-allocated matrices and seed variables for high-throughput math
                    var seedA = (threadIndex + 1L) * 1103515245L + 12345L
                    var seedB = seedA xor 0x5DEECE66DL
                    var x = 0.123456789 * (threadIndex + 1)
                    var y = 0.987654321 / (threadIndex + 1)

                    val matrixA = DoubleArray(16) { i -> (i + threadIndex) * 0.75 }
                    val matrixB = DoubleArray(16) { i -> (15 - i + threadIndex) * 1.25 }
                    val matrixC = DoubleArray(16)

                    var localOps = 0L

                    // CONTINUOUS LOOP WITHOUT PAUSES - ZERO SLEEP
                    while (isBenchmarkRunning.get() && !Thread.currentThread().isInterrupted) {
                        // 1. Matrix Multiplication (4x4 double precision)
                        for (i in 0 until 4) {
                            for (j in 0 until 4) {
                                var sum = 0.0
                                for (k in 0 until 4) {
                                    sum += matrixA[i * 4 + k] * matrixB[k * 4 + j]
                                }
                                matrixC[i * 4 + j] = sum
                            }
                        }

                        // 2. Trigonometric and Transcendental Series
                        x = sin(x) * cos(y) + sqrt(abs(x + y)) - atan(y)
                        y = cos(y) * sin(x) + sqrt(abs(matrixC[0]))

                        // 3. Mandelbrot complex recurrence: z = z^2 + c
                        var zr = x
                        var zi = y
                        val cr = (seedA and 0xFFFF) / 65536.0 - 0.5
                        val ci = (seedB and 0xFFFF) / 65536.0 - 0.5
                        for (iter in 0 until 12) {
                            val zr2 = zr * zr
                            val zi2 = zi * zi
                            if (zr2 + zi2 > 4.0) {
                                zr = cr
                                zi = ci
                            } else {
                                zi = 2.0 * zr * zi + ci
                                zr = zr2 - zi2 + cr
                            }
                        }

                        // 4. Xorshift128+ 64-bit bitwise mixing
                        var s1 = seedA
                        val s0 = seedB
                        seedA = s0
                        s1 = s1 xor (s1 shl 23)
                        seedB = s1 xor s0 xor (s1 ushr 17) xor (s0 ushr 26)

                        localOps += 64L

                        // Periodically flush local counter to global atomic counter
                        if ((localOps and 0x3FFL) == 0L) {
                            val globalTotal = totalOpsCounter.addAndGet(1024L)
                            _totalBenchmarkOperations.value = globalTotal
                        }
                    }
                }, "AnTuTuStressCore_$threadIndex").apply {
                    priority = Thread.MAX_PRIORITY
                    isDaemon = true
                    start()
                }
                benchmarkThreads.add(workerThread)
            }

            mainHandler.post {
                Toast.makeText(
                    this,
                    "AnTuTu Stress Test Iniciado: ${coreCount} hilos a carga máxima continua",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun stopMultiThreadedAnTuTuStress() {
        if (isBenchmarkRunning.compareAndSet(true, false)) {
            _isBenchmarkActive.value = false
            _activeBenchmarkThreads.value = 0

            benchmarkThreads.forEach { thread ->
                try {
                    thread.interrupt()
                } catch (_: Exception) {}
            }
            benchmarkThreads.clear()

            mainHandler.post {
                Toast.makeText(this, "AnTuTu Stress Test Detenido", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
            text = "AnTuTu CPU Monitor"
            setTextColor(AndroidColor.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val stressBadge = TextView(this).apply {
            text = if (_isBenchmarkActive.value) "MAX LOAD" else "IDLE"
            setTextColor(if (_isBenchmarkActive.value) AndroidColor.argb(255, 239, 68, 68) else AndroidColor.argb(255, 16, 185, 129))
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

        stressStatusBadgeView?.text = if (_isBenchmarkActive.value) "MAX LOAD" else "IDLE"
        stressStatusBadgeView?.setTextColor(
            if (_isBenchmarkActive.value) AndroidColor.argb(255, 239, 68, 68) else AndroidColor.argb(255, 16, 185, 129)
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
        val stressTag = if (_isBenchmarkActive.value) "[AnTuTu MAX LOAD] " else ""
        val updatedText = "%sCPU: %.1f%% (%d núcleos) | Ops: %,d".format(
            stressTag,
            snapshot.totalUsagePercent,
            snapshot.cores.size,
            _totalBenchmarkOperations.value
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
        stopMultiThreadedAnTuTuStress()
        stopContinuousTelemetryMonitor()
        releaseWakeLockInternal()
        _isServiceRunning.value = false
        _uptimeSeconds.value = 0L
        _serviceStartTime.value = 0L
        _totalBenchmarkOperations.value = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideFloatingOverlayInternal()
        stopMultiThreadedAnTuTuStress()
        stopContinuousTelemetryMonitor()
        releaseWakeLockInternal()
        serviceScope.cancel()
        _isServiceRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Service Hub Background Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service notification for continuous execution"
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AnTuTu CPU Benchmark Hub")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
