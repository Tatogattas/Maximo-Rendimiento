package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var calculationThread: Thread? = null
    private val isCalculationRunning = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
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
            ACTION_START, null -> {
                startForegroundInternal()
            }
        }
        return START_STICKY
    }

    private fun startForegroundInternal() {
        _isServiceRunning.value = true
        _serviceStartTime.value = System.currentTimeMillis()

        acquireWakeLockInternal()
        startMathCalculationThread()

        val notification = buildNotification("Service Hub: Low priority math worker running")
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            while (_isServiceRunning.value) {
                delay(1000)
                _uptimeSeconds.value += 1
            }
        }
    }

    private fun startMathCalculationThread() {
        if (isCalculationRunning.compareAndSet(false, true)) {
            _isWorkerActive.value = true
            calculationThread = Thread({
                // Set Linux process priority to lowest / background
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

                    // Simple mathematical calculations:
                    // Progressions, modulo, square root and trigonometric functions
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
                        // Small sleep to maintain low CPU consumption and avoid battery drain
                        Thread.sleep(100L)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                _isWorkerActive.value = false
            }, "LowPriorityMathWorkerThread").apply {
                // Set minimum JVM thread priority (1 out of 10)
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // With android:stopWithTask="false", this callback is triggered when swiped from recents,
        // but the foreground service persists.
    }

    override fun onDestroy() {
        super.onDestroy()
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
            .setContentTitle("Service Hub Running")
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
        const val ACTION_TOGGLE_WAKELOCK = "com.example.service.TOGGLE_WAKELOCK"

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
    }
}
