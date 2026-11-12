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
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Shizuku UserService implementation.
 * Runs in an independent process with system shell privileges (UID 2000).
 *
 * Manages device native performance mode without artificial heavy math stress loops:
 * - On startBenchmarkLoop: activates system native high performance mode and disables thermal limits:
 *   1) settings put system power_mode_type 2
 *   2) settings put secure high_performance_mode_on 1
 *   3) cmd thermalservice override-status 0
 * - On onDestroy / stopBenchmarkLoop: restores normal system settings and resets thermal service:
 *   1) settings put system power_mode_type 0
 *   2) settings put secure high_performance_mode_on 0
 *   3) cmd thermalservice reset
 *
 * Captures exitCode, stdout, and stderr for each command and logs detailed results in Logcat.
 */
@Keep
class UserService : IUserService.Stub() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isLoopRunning = AtomicBoolean(false)
    private var heartbeatJob: Job? = null

    // Holds command execution results for startup and shutdown
    private val startupResults = mutableListOf<String>()
    private val shutdownResults = mutableListOf<String>()

    init {
        Log.i(TAG, "UserService instanciado con éxito en PID: ${Process.myPid()} con UID: ${Process.myUid()}")
        startBenchmarkLoop()
    }

    override fun destroy() {
        onDestroy()
    }

    /**
     * Executes cleanup and restores system power & thermal settings via Shizuku
     * before terminating the UserService process.
     */
    fun onDestroy() {
        Log.i(TAG, "UserService onDestroy invocado: ejecutando comandos de restauración del sistema")
        stopBenchmarkLoop()
        restoreSystemSettings()
        serviceScope.cancel()
        exitProcess(0)
    }

    override fun startBenchmarkLoop() {
        if (isLoopRunning.compareAndSet(false, true)) {
            Log.i(TAG, "Iniciando modo de alto rendimiento nativo mediante Shizuku (UID: ${Process.myUid()})")

            val startupCommands = listOf(
                "settings put system power_mode_type 2",
                "settings put secure high_performance_mode_on 1",
                "cmd thermalservice override-status 0"
            )

            synchronized(startupResults) {
                startupResults.clear()
                for (cmd in startupCommands) {
                    val result = runShellCommand(cmd)
                    val statusFormatted = "${result.command}|${result.exitCode}|${result.isSuccess}|${result.stdout}|${result.stderr}"
                    startupResults.add(statusFormatted)

                    if (result.isSuccess) {
                        Log.i(TAG, "STARTUP CMD [EXIT 0 OK]: ${result.command} | Stdout: '${result.stdout}'")
                    } else {
                        Log.e(TAG, "STARTUP CMD [EXIT ${result.exitCode} ERROR]: ${result.command} | Stderr: '${result.stderr}'")
                    }
                }
            }

            // Lightweight non-stress heartbeat coroutine to keep process state alive
            heartbeatJob = serviceScope.launch {
                while (isActive && isLoopRunning.get()) {
                    delay(1000L)
                }
            }
        }
    }

    override fun stopBenchmarkLoop() {
        if (isLoopRunning.compareAndSet(true, false)) {
            Log.i(TAG, "Deteniendo modo alto rendimiento y restaurando valores del sistema")
            heartbeatJob?.cancel()
            heartbeatJob = null
            restoreSystemSettings()
        }
    }

    private fun restoreSystemSettings() {
        val restoreCommands = listOf(
            "settings put system power_mode_type 0",
            "settings put secure high_performance_mode_on 0",
            "cmd thermalservice reset"
        )

        synchronized(shutdownResults) {
            shutdownResults.clear()
            for (cmd in restoreCommands) {
                val result = runShellCommand(cmd)
                val statusFormatted = "${result.command}|${result.exitCode}|${result.isSuccess}|${result.stdout}|${result.stderr}"
                shutdownResults.add(statusFormatted)

                if (result.isSuccess) {
                    Log.i(TAG, "SHUTDOWN CMD [EXIT 0 OK]: ${result.command} | Stdout: '${result.stdout}'")
                } else {
                    Log.e(TAG, "SHUTDOWN CMD [EXIT ${result.exitCode} ERROR]: ${result.command} | Stderr: '${result.stderr}'")
                }
            }
        }
    }

    override fun isRunning(): Boolean {
        return isLoopRunning.get()
    }

    override fun executeCommand(command: String?): String {
        if (command.isNullOrBlank()) return ""
        val result = runShellCommand(command)
        return if (result.isSuccess) {
            if (result.stdout.isNotEmpty()) result.stdout else "OK (exitCode: 0)"
        } else {
            "ERROR (exitCode: ${result.exitCode}): ${result.stderr}"
        }
    }

    /**
     * Exposes detailed command execution status to the main UI.
     * Encodes startup and shutdown command results separated by section.
     */
    override fun getExecutionStatus(): String {
        val sb = StringBuilder()
        sb.append("STARTUP:")
        synchronized(startupResults) {
            sb.append(startupResults.joinToString(";;"))
        }
        sb.append("###SHUTDOWN:")
        synchronized(shutdownResults) {
            sb.append(shutdownResults.joinToString(";;"))
        }
        return sb.toString()
    }

    data class ShellExecutionResult(
        val command: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val isSuccess: Boolean
    )

    /**
     * Executes shell commands using Shizuku.newProcess via reflection
     * (as it is marked private static in Shizuku v13), with native fallback to Runtime.getRuntime().exec.
     * Captures exitCode, stdout, and stderr.
     */
    private fun runShellCommand(command: String): ShellExecutionResult {
        return try {
            val cmdArray = arrayOf("sh", "-c", command)
            val process = newProcess(cmdArray, null, null)

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (stdoutBuilder.isNotEmpty()) stdoutBuilder.append("\n")
                            stdoutBuilder.append(line)
                        }
                    }
                } catch (_: Exception) {}
            }

            val stderrThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (stderrBuilder.isNotEmpty()) stderrBuilder.append("\n")
                            stderrBuilder.append(line)
                        }
                    }
                } catch (_: Exception) {}
            }

            stdoutThread.start()
            stderrThread.start()

            val exitCode = process.waitFor()
            stdoutThread.join(1000)
            stderrThread.join(1000)

            val stdout = stdoutBuilder.toString().trim()
            val stderr = stderrBuilder.toString().trim()
            val isSuccess = (exitCode == 0)

            ShellExecutionResult(
                command = command,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                isSuccess = isSuccess
            )
        } catch (e: Exception) {
            Log.e(TAG, "Excepción ejecutando comando: $command", e)
            ShellExecutionResult(
                command = command,
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Exception",
                isSuccess = false
            )
        }
    }

    /**
     * Invokes Shizuku.newProcess using reflection, falling back to Runtime.getRuntime().exec.
     */
    private fun newProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): java.lang.Process {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, env, dir) as java.lang.Process
        } catch (_: Exception) {
            Runtime.getRuntime().exec(cmd, env, dir?.let { File(it) })
        }
    }

    companion object {
        private const val TAG = "ShizukuUserService"
    }
}
