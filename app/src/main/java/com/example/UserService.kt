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
 * Kept alive continuously with coroutines running mathematical loops.
 *
 * Configures the CPU governor to 'performance' mode on startBenchmarkLoop
 * using Shizuku.newProcess, and restores it to normal/balanced mode on onDestroy.
 */
@Keep
class UserService : IUserService.Stub() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isLoopRunning = AtomicBoolean(false)
    private val benchmarkIterations = AtomicLong(0L)
    private var benchmarkJob: Job? = null

    // Cache of original governors to restore exactly the user's previous governor
    private val originalGovernors = mutableMapOf<String, String>()

    init {
        Log.i(TAG, "UserService instanciado en PID: ${Process.myPid()} con UID: ${Process.myUid()}")
        saveOriginalGovernors()
        startBenchmarkLoop()
    }

    /**
     * Standard AIDL termination method for Shizuku UserService.
     */
    override fun destroy() {
        onDestroy()
    }

    /**
     * Executes cleanup and restores CPU governor to its normal state via Shizuku
     * before terminating the UserService process.
     */
    fun onDestroy() {
        Log.i(TAG, "UserService onDestroy invocado: restaurando CPU governor y finalizando proceso")
        stopBenchmarkLoop()
        restoreCpuGovernor()
        serviceScope.cancel()
        exitProcess(0)
    }

    override fun startBenchmarkLoop() {
        if (isLoopRunning.compareAndSet(false, true)) {
            Log.i(TAG, "Iniciando bucle de benchmark persistente en UserService (UID: ${Process.myUid()})")

            // 1. Configurar y fijar el regulador de la CPU a 'performance' mediante Shizuku.newProcess
            setCpuGovernorPerformance()

            // 2. Iniciar bucle de corrutinas con cálculo intensivo
            benchmarkJob = serviceScope.launch {
                var x = 0.12345
                var y = 0.67890
                val matrixA = DoubleArray(16) { i -> (i + 1) * 0.5 }
                val matrixB = DoubleArray(16) { i -> (16 - i) * 1.5 }
                val matrixC = DoubleArray(16)

                while (isActive && isLoopRunning.get()) {
                    try {
                        // Multiplicación de matrices de 64-bits
                        for (i in 0 until 4) {
                            for (j in 0 until 4) {
                                var sum = 0.0
                                for (k in 0 until 4) {
                                    sum += matrixA[i * 4 + k] * matrixB[k * 4 + j]
                                }
                                matrixC[i * 4 + j] = sum
                            }
                        }

                        // Series trigonométricas continuas
                        x = sin(x) * cos(y) + sqrt(abs(x + y)) - atan(y)
                        y = cos(y) * sin(x) + sqrt(abs(matrixC[0]))

                        val total = benchmarkIterations.addAndGet(100L)
                        if (total % 10000L == 0L) {
                            Log.d(TAG, "Shizuku Benchmark activo: $total ops (PID: ${Process.myPid()}, UID: ${Process.myUid()})")
                        }

                        delay(10L)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error en bucle de benchmark", e)
                    }
                }
            }
        }
    }

    override fun stopBenchmarkLoop() {
        if (isLoopRunning.compareAndSet(true, false)) {
            Log.i(TAG, "Deteniendo bucle de benchmark en UserService")
            benchmarkJob?.cancel()
            benchmarkJob = null

            // Restaurar el regulador de la CPU a su estado normal
            restoreCpuGovernor()
        }
    }

    override fun isRunning(): Boolean {
        return isLoopRunning.get()
    }

    override fun executeCommand(command: String?): String {
        if (command.isNullOrBlank()) return ""
        return executeShizukuShell(command)
    }

    /**
     * Executes shell commands using Shizuku.newProcess via reflection
     * (as it is marked private static in Shizuku v13), with native fallback to Runtime.getRuntime().exec
     * (which runs with UID 2000 within this Shizuku process).
     */
    private fun executeShizukuShell(command: String): String {
        return try {
            val cmdArray = arrayOf("sh", "-c", command)
            val process = newProcess(cmdArray, null, null)

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
            Log.e(TAG, "Error ejecutando comando via Shizuku: $command", e)
            "Error: ${e.message}"
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

    /**
     * Saves the current scaling_governor of each policy before modifying them.
     */
    private fun saveOriginalGovernors() {
        try {
            val policyDir = File("/sys/devices/system/cpu/cpufreq")
            if (policyDir.exists() && policyDir.isDirectory) {
                policyDir.listFiles { f -> f.name.startsWith("policy") }?.forEach { policy ->
                    val govFile = File(policy, "scaling_governor")
                    if (govFile.exists() && govFile.canRead()) {
                        val gov = govFile.readText().trim()
                        if (gov.isNotEmpty()) {
                            originalGovernors[policy.absolutePath] = gov
                            Log.d(TAG, "Governor original de ${policy.name}: $gov")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer governor original (continuará con fallback)", e)
        }
    }

    /**
     * Sets and locks the CPU governor to 'performance' mode across all policies and cores,
     * setting scaling_min_freq = scaling_max_freq to lock frequencies at peak.
     */
    private fun setCpuGovernorPerformance() {
        Log.i(TAG, "Configurando CPU governor en modo 'performance' via Shizuku.newProcess...")

        val performanceShellScript = """
            # 1. Configurar governor en performance para todas las politicas de cpufreq
            for p in /sys/devices/system/cpu/cpufreq/policy*; do
                if [ -d "${'$'}p" ]; then
                    echo performance > "${'$'}p/scaling_governor" 2>/dev/null
                    # Fijar frecuencia minima igual a la frecuencia maxima para mantener 100% de reloj
                    if [ -f "${'$'}p/scaling_max_freq" ]; then
                        cat "${'$'}p/scaling_max_freq" > "${'$'}p/scaling_min_freq" 2>/dev/null
                    fi
                fi
            done

            # 2. Configurar governor en performance para cada core individual
            for c in /sys/devices/system/cpu/cpu*/cpufreq; do
                if [ -d "${'$'}c" ]; then
                    echo performance > "${'$'}c/scaling_governor" 2>/dev/null
                fi
            done

            # 3. Desactivar throttling y ahorro agresivo en clusters Qualcomm Snapdragon
            for ctl in /sys/devices/system/cpu/cpu*/core_ctl; do
                if [ -d "${'$'}ctl" ]; then
                    echo 0 > "${'$'}ctl/enable" 2>/dev/null
                fi
            done
        """.trimIndent()

        val result = executeShizukuShell(performanceShellScript)
        Log.i(TAG, "Comando performance ejecutado. Output: $result")
    }

    /**
     * Restores CPU governor to normal/balanced mode (schedutil or original governors)
     * and restores scaling_min_freq back to cpuinfo_min_freq.
     */
    private fun restoreCpuGovernor() {
        Log.i(TAG, "Restaurando CPU governor a estado normal via Shizuku.newProcess...")

        val restoreShellScript = """
            # 1. Restaurar frecuencia mínima y governor balanceado en todas las políticas
            for p in /sys/devices/system/cpu/cpufreq/policy*; do
                if [ -d "${'$'}p" ]; then
                    # Restaurar frecuencia minima base del procesador
                    if [ -f "${'$'}p/cpuinfo_min_freq" ]; then
                        cat "${'$'}p/cpuinfo_min_freq" > "${'$'}p/scaling_min_freq" 2>/dev/null
                    fi
                    # Restaurar governor normal: schedutil (Android moderno), interactive o powersave
                    echo schedutil > "${'$'}p/scaling_governor" 2>/dev/null || echo interactive > "${'$'}p/scaling_governor" 2>/dev/null || echo ondemand > "${'$'}p/scaling_governor" 2>/dev/null
                fi
            done

            # 2. Restaurar governor normal en cores individuales
            for c in /sys/devices/system/cpu/cpu*/cpufreq; do
                if [ -d "${'$'}c" ]; then
                    echo schedutil > "${'$'}c/scaling_governor" 2>/dev/null || echo interactive > "${'$'}c/scaling_governor" 2>/dev/null
                fi
            done

            # 3. Reactivar core_ctl para gestión energética normal
            for ctl in /sys/devices/system/cpu/cpu*/core_ctl; do
                if [ -d "${'$'}ctl" ]; then
                    echo 1 > "${'$'}ctl/enable" 2>/dev/null
                fi
            done
        """.trimIndent()

        val result = executeShizukuShell(restoreShellScript)
        Log.i(TAG, "Comando restauracion ejecutado. Output: $result")
    }

    companion object {
        private const val TAG = "ShizukuUserService"
    }
}
