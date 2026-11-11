package com.example

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/**
 * Shizuku UserService implementation.
 * Runs in an independent process with system shell privileges (UID 2000).
 * Kept alive continuously with a background worker thread even when Activity closes.
 */
@Keep
class UserService : IUserService.Stub() {

    private val isRunning = AtomicBoolean(false)
    private val executionCount = AtomicLong(0L)
    private var workerThread: Thread? = null

    init {
        Log.i(TAG, "UserService instantiated in process: ${Process.myPid()} with UID: ${Process.myUid()}")
        startPersistentTask()
    }

    override fun destroy() {
        Log.i(TAG, "UserService destroy called, terminating Shizuku user process")
        stopPersistentTask()
        exitProcess(0)
    }

    override fun startPersistentTask() {
        if (isRunning.compareAndSet(false, true)) {
            Log.i(TAG, "Starting persistent background task in Shizuku UserService")
            workerThread = Thread({
                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        val count = executionCount.incrementAndGet()
                        if (count % 10 == 0L) {
                            Log.d(TAG, "Shizuku UserService active iteration: $count (UID: ${Process.myUid()})")
                        }
                        // Continuous background heartbeat
                        Thread.sleep(1000L)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in persistent loop", e)
                    }
                }
            }, "ShizukuPersistentWorker").apply {
                priority = Thread.NORM_PRIORITY
                isDaemon = true
                start()
            }
        }
    }

    override fun stopPersistentTask() {
        isRunning.set(false)
        workerThread?.interrupt()
        workerThread = null
    }

    override fun isTaskRunning(): Boolean {
        return isRunning.get()
    }

    override fun getTaskStatus(): String {
        val uid = Process.myUid()
        val pid = Process.myPid()
        val state = if (isRunning.get()) "ACTIVO" else "DETENIDO"
        return "Shizuku Service [$state] • PID: $pid • UID: $uid (shell/system) • Ciclos: ${executionCount.get()}"
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

    override fun getExecutionCount(): Long {
        return executionCount.get()
    }

    companion object {
        private const val TAG = "ShizukuUserService"
    }
}
