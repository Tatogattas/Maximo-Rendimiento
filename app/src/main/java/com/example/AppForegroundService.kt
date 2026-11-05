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

class AppForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null

    // Math worker running on its own low-priority thread
    private var calculationThread: Thread? = null
    private val isCalculationRunning = AtomicBoolean(false)

    // Window interface 5-second update timer running on a separate background thread
    // to guarantee no input lag or touch control interference in video games
    private var windowUpdateThread: Thread? = null
    private val isWindowUpdateRunning = AtomicBoolean(false)

    // Floating overlay window views managed by the service
    private var windowManager: WindowManager? = null
    private var overlayContainer: LinearLayout? = null
    private var totalCpuTextView: TextView? = null
    private var totalProgressBarView: ProgressBar? = null
    private val coreTextViews = mutableListOf<TextView>()
    private val coreProgressBars = mutableListOf<ProgressBar>()
    private var isCompactMode = false
    private var coresContainerLayout: LinearLayout? = null

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
        startMathCalculationThread()
        startWindowUpdateTimerThread()

        val notification = buildNotification("Service Hub: Telemetría 5s y cálculo de baja prioridad activos")
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            while (_isServiceRunning.value) {
                delay(1000)
                _uptimeSeconds.value += 1
            }
        }
    }

    /**
     * Dedicated separate thread running every 5 seconds to update the window interface.
     * Running on a separate background thread with low priority ensures video game
     * controls, physics engines, and touch handling are 100% free from any hitch or lag.
     */
    private fun startWindowUpdateTimerThread() {
        if (isWindowUpdateRunning.compareAndSet(false, true)) {
            _isWindowUpdateTimerActive.value = true
            windowUpdateThread = Thread({
                try {
                    // Set Linux background thread priority so it never preempts game rendering or input
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                } catch (_: Exception) {}

                while (isWindowUpdateRunning.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        // 1. Perform /proc/stat consecutive diff telemetry computation on this separate thread
                        val snapshot = CpuMonitor.sampleCpuUsage()
                        _latestCpuSnapshot.value = snapshot
                        _lastWindowUpdateTime.value = System.currentTimeMillis()

                        // 2. Dispatch minimal UI update to floating overlay window on main looper
                        mainHandler.post {
                            updateOverlayUi(snapshot)
                            updateNotificationWithCpu(snapshot)
                        }

                        // 3. Sleep exactly 5 seconds (5000 ms) as requested
                        Thread.sleep(WINDOW_UPDATE_INTERVAL_MS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                _isWindowUpdateTimerActive.value = false
            }, "WindowUpdateSeparateThread").apply {
                // Minimum JVM thread priority
                priority = Thread.MIN_PRIORITY
                isDaemon = true
                start()
            }
        }
    }

    private fun stopWindowUpdateTimerThread() {
        isWindowUpdateRunning.set(false)
        windowUpdateThread?.interrupt()
        windowUpdateThread = null
        _isWindowUpdateTimerActive.value = false
    }

    /**
     * Math calculation thread with minimum priority doing simple math (from turn 5).
     */
    private fun startMathCalculationThread() {
        if (isCalculationRunning.compareAndSet(false, true)) {
            _isWorkerActive.value = true
            calculationThread = Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
                } catch (e: Exception) {
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    } catch (_: Exception) {}
                }

                var count = 0L
                var accumulator = 0.0

                while (isCalculationRunning.get() && !Thread.currentThread().isInterrupted) {
                    count++

                    val n = count % 1000
                    val term = ((n * 7) + 13) % 256
                    val sqrtVal = Math.sqrt(term.toDouble())
                    val sinVal = Math.sin(Math.toRadians(n.toDouble()))
                    val stepResult = (term * 1.5) + sqrtVal - sinVal
                    accumulator = (accumulator + stepResult) % 1_000_000.0

                    _mathCalculationCount.value = count
                    _lastCalculationDetail.value = "n=$n : (%d * 1.5 + √%d - sin(%d°)) = %.2f".format(term, term, n, stepResult)
                    _mathAccumulator.value = accumulator

                    try {
                        Thread.sleep(100L)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                _isWorkerActive.value = false
            }, "LowPriorityMathWorkerThread").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
                start()
            }
        }
    }

    private fun stopMathCalculationThread() {
        isCalculationRunning.set(false)
        calculationThread?.interrupt()
        calculationThread = null
        _isWorkerActive.value = false
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showFloatingOverlayInternal() {
        if (!Settings.canDrawOverlays(this)) {
            mainHandler.post {
                Toast.makeText(this, "Permiso de superposición no concedido", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (overlayContainer != null) return // Already showing

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // FLAG_NOT_FOCUSABLE and FLAG_NOT_TOUCH_MODAL ensure touch events outside this small window
        // pass directly to the underlying game/screen so video game controls are never blocked or delayed!
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
            x = 50
            y = 140
        }

        // 50% transparency: Color.argb(128, 15, 23, 42)
        val backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 22f
            setColor(AndroidColor.argb(128, 15, 23, 42))
            setStroke(2, AndroidColor.argb(140, 56, 189, 248))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = backgroundDrawable
            setPadding(24, 18, 24, 18)
            elevation = 16f
        }

        // Header Row: Title, 5s Tag, Minimize Toggle, and Close Button
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(this).apply {
            text = "CPU Monitor (5s)"
            setTextColor(AndroidColor.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val minimizeButton = TextView(this).apply {
            text = " ─ "
            setTextColor(AndroidColor.argb(230, 148, 163, 184))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(8, 2, 8, 2)
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
            setPadding(8, 2, 8, 2)
            setOnClickListener { hideFloatingOverlayInternal() }
        }

        headerLayout.addView(titleView)
        headerLayout.addView(minimizeButton)
        headerLayout.addView(closeButton)
        container.addView(headerLayout)

        val subtitleView = TextView(this).apply {
            text = "${CpuMonitor.getCoreCount()} Núcleos • Actualización 5s (Hilo Separado)"
            setTextColor(AndroidColor.argb(200, 148, 163, 184))
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setPadding(0, 2, 0, 4)
        }
        container.addView(subtitleView)

        val totalCpuText = TextView(this).apply {
            text = "Total CPU: 0.0% [5s]"
            setTextColor(AndroidColor.argb(240, 56, 189, 248))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 2, 0, 2)
        }
        val totalProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(200),
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
                textSize = 10f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(dpToPx(95), LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val coreBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                layoutParams = LinearLayout.LayoutParams(dpToPx(105), dpToPx(4))
            }

            coreTextViews.add(coreLabel)
            coreProgressBars.add(coreBar)

            coreRow.addView(coreLabel)
            coreRow.addView(coreBar)
            coresContainer.addView(coreRow)
        }

        // Touch listener allowing repositioning anywhere on screen away from game buttons
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

        // Immediate first refresh
        updateOverlayUi(_latestCpuSnapshot.value)

        Toast.makeText(this, "Ventana flotante activa (Actualización cada 5s en hilo separado)", Toast.LENGTH_SHORT).show()
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
            coresContainerLayout = null
            _isOverlayShowing.value = false
            mainHandler.post {
                Toast.makeText(this, "Ventana flotante oculta", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateOverlayUi(snapshot: CpuSnapshot) {
        if (overlayContainer == null) return

        val formattedTotal = String.format("%.1f", snapshot.totalUsagePercent)
        totalCpuTextView?.text = "Total CPU: $formattedTotal% (5s)"
        totalProgressBarView?.progress = snapshot.totalUsagePercent.toInt().coerceIn(0, 100)

        snapshot.cores.forEachIndexed { index, core ->
            if (index < coreTextViews.size) {
                val formattedCore = String.format("%.1f", core.usagePercent)
                coreTextViews[index].text = "Core $index: $formattedCore%"
                coreProgressBars[index].progress = core.usagePercent.toInt().coerceIn(0, 100)
            }
        }
    }

    private fun updateNotificationWithCpu(snapshot: CpuSnapshot) {
        if (!_isServiceRunning.value) return
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val updatedText = "CPU: %.1f%% (%d núcleos) • Actualizado cada 5s".format(
            snapshot.totalUsagePercent,
            snapshot.cores.size
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
                    acquire(10 * 60 * 1000L /* 10 minutes timeout safety */)
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
        stopWindowUpdateTimerThread()
        stopMathCalculationThread()
        releaseWakeLockInternal()
        _isServiceRunning.value = false
        _uptimeSeconds.value = 0L
        _serviceStartTime.value = 0L
        _mathCalculationCount.value = 0L
        _lastCalculationDetail.value = "Worker idle"
        _mathAccumulator.value = 0.0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideFloatingOverlayInternal()
        stopWindowUpdateTimerThread()
        stopMathCalculationThread()
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
                description = "Foreground service notification for persistent execution"
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
            .setContentTitle("Service Hub Activo")
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

        const val WINDOW_UPDATE_INTERVAL_MS = 5000L

        const val ACTION_START = "com.example.service.START"
        const val ACTION_STOP = "com.example.service.STOP"
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

        private val _isWorkerActive = MutableStateFlow(false)
        val isWorkerActive: StateFlow<Boolean> = _isWorkerActive.asStateFlow()

        private val _mathCalculationCount = MutableStateFlow(0L)
        val mathCalculationCount: StateFlow<Long> = _mathCalculationCount.asStateFlow()

        private val _lastCalculationDetail = MutableStateFlow("Worker idle")
        val lastCalculationDetail: StateFlow<String> = _lastCalculationDetail.asStateFlow()

        private val _mathAccumulator = MutableStateFlow(0.0)
        val mathAccumulator: StateFlow<Double> = _mathAccumulator.asStateFlow()

        // 5-second window update timer status & snapshot
        private val _isWindowUpdateTimerActive = MutableStateFlow(false)
        val isWindowUpdateTimerActive: StateFlow<Boolean> = _isWindowUpdateTimerActive.asStateFlow()

        private val _isOverlayShowing = MutableStateFlow(false)
        val isOverlayShowing: StateFlow<Boolean> = _isOverlayShowing.asStateFlow()

        private val _latestCpuSnapshot = MutableStateFlow(CpuMonitor.sampleCpuUsage())
        val latestCpuSnapshot: StateFlow<CpuSnapshot> = _latestCpuSnapshot.asStateFlow()

        private val _lastWindowUpdateTime = MutableStateFlow(System.currentTimeMillis())
        val lastWindowUpdateTime: StateFlow<Long> = _lastWindowUpdateTime.asStateFlow()

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
