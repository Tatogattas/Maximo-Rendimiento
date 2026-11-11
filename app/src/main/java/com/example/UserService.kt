package com.example

import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * Shizuku UserService implementation.
 * Runs in an independent process with system shell privileges (UID 2000).
 * Kept alive continuously with coroutines running mathematical loops and shell operations
 * even when the main Activity is closed.
 */
@Keep
class UserService : IUserService.Stub() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isLoopRunning = AtomicBoolean(false)
    private val benchmarkIterations = AtomicLong(0L)
    private var benchmarkJob: Job? = null

    init {
        Log.i(TAG, "UserService instantiated in process PID: ${Process.myPid()} with UID: ${Process.myUid()}")
        startBenchmarkLoop()
    }

    override fun destroy() {
        Log.i(TAG, "UserService destroy called, stopping coroutines and terminating process")
        stopBenchmarkLoop()
        serviceScope.cancel()
        exitProcess(0)
    }

    override fun startBenchmarkLoop() {
        if (isLoopRunning.compareAndSet(false, true)) {
            Log.i(TAG, "Starting persistent benchmark coroutine loop in Shizuku UserService (UID: ${Process.myUid()})")
            benchmarkJob = serviceScope.launch {
                var x = 0.12345
                var y = 0.67890
                val matrixA = DoubleArray(16) { i -> (i + 1) * 0.5 }
                val matrixB = DoubleArray(16) { i -> (16 - i) * 1.5 }
                val matrixC = DoubleArray(16)

                while (isActive && isLoopRunning.get()) {
                    try {
                        // 1. Matrix multiplication calculations (stress)
                        for (i in 0 until 4) {
                            for (j in 0 until 4) {
                                var sum = 0.0
                                for (k in 0 until 4) {
                                    sum += matrixA[i * 4 + k] * matrixB[k * 4 + j]
                                }
                                matrixC[i * 4 + j] = sum
                            }
                        }

                        // 2. Trigonometric recurrence
                        x = sin(x) * cos(y) + sqrt(abs(x + y)) - atan(y)
                        y = cos(y) * sin(x) + sqrt(abs(matrixC[0]))

                        val currentCount = benchmarkIterations.addAndGet(100L)
                        if (currentCount % 10000L == 0L) {
                            Log.d(TAG, "Shizuku Benchmark active: $currentCount ops (PID: ${Process.myPid()}, UID: ${Process.myUid()})")
                        }

                        // Small non-blocking yield to allow coroutine cooperation
                        delay(10L)
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception in benchmark coroutine loop", e)
                    }
                }
            }
        }
    }

    override fun stopBenchmarkLoop() {
        if (isLoopRunning.compareAndSet(true, false)) {
            Log.i(TAG, "Stopping persistent benchmark loop in Shizuku UserService")
            benchmarkJob?.cancel()
            benchmarkJob = null
        }
    }

    override fun isRunning(): Boolean {
        return isLoopRunning.get()
    }

    override fun executeCommand(command: String?): String {
        if (command.isNullOrBlank()) return ""
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
            }
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
            }
            process.waitFor()
            output.toString().trim()
        } catch (e: Exception) {
            "Error ejecutando comando: ${e.message}"
        }
    }

    companion object {
        private const val TAG = "ShizukuUserService"
    }
}
