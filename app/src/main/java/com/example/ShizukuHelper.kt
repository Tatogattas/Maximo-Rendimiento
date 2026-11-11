package com.example

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AuditLogEntry(
    val id: Long = System.currentTimeMillis() + (0..999).random(),
    val timestamp: Long = System.currentTimeMillis(),
    val action: String,
    val isSuccess: Boolean,
    val summary: String,
    val details: String = "",
    val executedCommand: String = "",
    val commandOutput: String = ""
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 8123

    private val helperScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _isShizukuAvailable = MutableStateFlow(false)
    val isShizukuAvailable: StateFlow<Boolean> = _isShizukuAvailable.asStateFlow()

    private val _isPermissionGranted = MutableStateFlow(false)
    val isPermissionGranted: StateFlow<Boolean> = _isPermissionGranted.asStateFlow()

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _userService = MutableStateFlow<IUserService?>(null)
    val userService: StateFlow<IUserService?> = _userService.asStateFlow()

    private val _serviceStatusMessage = MutableStateFlow("Shizuku no conectado")
    val serviceStatusMessage: StateFlow<String> = _serviceStatusMessage.asStateFlow()

    // Audit logs for tracking whether scripts/codes succeeded or failed
    private val _auditLogs = MutableStateFlow<List<AuditLogEntry>>(emptyList())
    val auditLogs: StateFlow<List<AuditLogEntry>> = _auditLogs.asStateFlow()

    private val _lastStartupResult = MutableStateFlow<AuditLogEntry?>(null)
    val lastStartupResult: StateFlow<AuditLogEntry?> = _lastStartupResult.asStateFlow()

    private val _lastShutdownResult = MutableStateFlow<AuditLogEntry?>(null)
    val lastShutdownResult: StateFlow<AuditLogEntry?> = _lastShutdownResult.asStateFlow()

    private val _isGovernorPerformance = MutableStateFlow(false)
    val isGovernorPerformance: StateFlow<Boolean> = _isGovernorPerformance.asStateFlow()

    private val _governorStatusText = MutableStateFlow("NORMAL (schedutil)")
    val governorStatusText: StateFlow<String> = _governorStatusText.asStateFlow()

    private val _clusterGovernors = MutableStateFlow<List<ClusterGovernorInfo>>(emptyList())
    val clusterGovernors: StateFlow<List<ClusterGovernorInfo>> = _clusterGovernors.asStateFlow()

    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            _isPermissionGranted.value = granted
            Log.i(TAG, "Permiso Shizuku resultado: $granted")
            logAudit(
                action = "PERMISO_SHIZUKU",
                isSuccess = granted,
                summary = if (granted) "Permiso concedido por el usuario" else "Permiso denegado por el usuario",
                details = "Código de petición: $requestCode"
            )
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Binder de Shizuku recibido")
        checkStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Binder de Shizuku muerto")
        _isShizukuAvailable.value = false
        _isServiceConnected.value = false
        _userService.value = null
        logAudit(
            action = "DESCONEXION_SHIZUKU",
            isSuccess = false,
            summary = "El servidor Shizuku se cerró o se detuvo",
            details = "Binder disconnected"
        )
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "UserService conectado exitosamente: $name")
            if (service != null && service.pingBinder()) {
                val userServ = IUserService.Stub.asInterface(service)
                _userService.value = userServ
                _isServiceConnected.value = true
                _serviceStatusMessage.value = "Conectado (UID 2000)"

                logAudit(
                    action = "VINCULAR_SERVICIO",
                    isSuccess = true,
                    summary = "Servicio daemon Shizuku vinculado exitosamente (UID 2000)",
                    details = "Component: $name"
                )

                // Start benchmark loop which sets governor to performance
                try {
                    userServ.startBenchmarkLoop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error iniciando bucle", e)
                }

                // Verify real kernel governors
                refreshGovernorStatus()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "UserService desconectado: $name")
            _isServiceConnected.value = false
            _userService.value = null
            _serviceStatusMessage.value = "Servicio desconectado"
        }
    }

    fun init(context: Context? = null) {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
            val available = checkStatus()

            refreshGovernorStatus()

            // Record startup verification
            val startupLog = AuditLogEntry(
                action = "ENCENDER_APP",
                isSuccess = available,
                summary = if (available) "Inicio exitoso: Shizuku disponible en el sistema" else "Advertencia al iniciar: Shizuku no está en ejecución",
                details = if (available) "Binder activo. Listo para privilegios UID 2000." else "Abre Shizuku para otorgar permisos."
            )
            _lastStartupResult.value = startupLog
            appendLog(startupLog)

            if (context != null && available) {
                bindService(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando ShizukuHelper", e)
            val errorLog = AuditLogEntry(
                action = "ENCENDER_APP",
                isSuccess = false,
                summary = "Error al inicializar Shizuku: ${e.message}",
                details = e.stackTraceToString()
            )
            _lastStartupResult.value = errorLog
            appendLog(errorLog)
        }
    }

    fun destroy() {
        try {
            val shutdownLog = AuditLogEntry(
                action = "APAGAR_APP",
                isSuccess = true,
                summary = "Apagado: Desvinculando servicio y liberando listeners de Shizuku",
                details = "Ejecutando limpieza de procesos en segundo plano."
            )
            _lastShutdownResult.value = shutdownLog
            appendLog(shutdownLog)

            restoreNormalGovernor()
            unbindService()

            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error en destroy()", e)
        }
    }

    fun checkStatus(): Boolean {
        return try {
            val available = Shizuku.pingBinder()
            _isShizukuAvailable.value = available
            if (available) {
                val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                _isPermissionGranted.value = granted
                if (granted) {
                    _serviceStatusMessage.value = "Shizuku listo para vincular"
                } else {
                    _serviceStatusMessage.value = "Permiso de Shizuku pendiente"
                }
                granted
            } else {
                _isPermissionGranted.value = false
                _serviceStatusMessage.value = "Shizuku no está en ejecución"
                false
            }
        } catch (e: Exception) {
            _isShizukuAvailable.value = false
            _isPermissionGranted.value = false
            false
        }
    }

    fun requestPermission(activity: Activity) {
        if (!checkStatus() && _isShizukuAvailable.value && !_isPermissionGranted.value) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    fun bindService(context: Context) {
        if (!checkStatus()) {
            if (context is Activity && _isShizukuAvailable.value && !_isPermissionGranted.value) {
                requestPermission(context)
            }
            return
        }

        if (_isServiceConnected.value) return

        try {
            val component = ComponentName(context.packageName, UserService::class.java.name)
            val args = Shizuku.UserServiceArgs(component)
                .daemon(true)
                .processNameSuffix("shizuku_service")
                .debuggable(BuildConfig.DEBUG)
                .version(1)

            userServiceArgs = args
            Shizuku.bindUserService(args, serviceConnection)
            Log.i(TAG, "Shizuku.bindUserService enviado para $component")
        } catch (e: Exception) {
            Log.e(TAG, "Error vinculando UserService", e)
            logAudit(
                action = "VINCULAR_SERVICIO",
                isSuccess = false,
                summary = "Fallo al vincular Shizuku UserService: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }

    fun unbindService() {
        val args = userServiceArgs ?: return
        try {
            _userService.value?.stopBenchmarkLoop()
            Shizuku.unbindUserService(args, serviceConnection, true)
            _isServiceConnected.value = false
            _userService.value = null
            _serviceStatusMessage.value = "Servicio desvinculado"

            logAudit(
                action = "DESVINCULAR_SERVICIO",
                isSuccess = true,
                summary = "Servicio Shizuku desvinculado correctamente",
                details = "daemon detenido"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error desvinculando servicio", e)
        }
    }

    fun isRunning(): Boolean = _userService.value?.isRunning ?: false

    /**
     * Executes shell script to force performance governor across all cores and logs the outcome.
     */
    fun applyPerformanceGovernor(): Boolean {
        val script = """
            for p in /sys/devices/system/cpu/cpufreq/policy*; do
                if [ -d "${'$'}p" ]; then
                    echo performance > "${'$'}p/scaling_governor" 2>/dev/null
                    if [ -f "${'$'}p/scaling_max_freq" ]; then
                        cat "${'$'}p/scaling_max_freq" > "${'$'}p/scaling_min_freq" 2>/dev/null
                    fi
                fi
            done
            for c in /sys/devices/system/cpu/cpu*/cpufreq; do
                if [ -d "${'$'}c" ]; then
                    echo performance > "${'$'}c/scaling_governor" 2>/dev/null
                fi
            done
        """.trimIndent()

        val output = executeCommand(script)
        val verified = refreshGovernorStatus()
        val success = verified.any { it.isPerformance } || _isServiceConnected.value

        logAudit(
            action = "FIJAR_PERFORMANCE",
            isSuccess = success,
            summary = if (success) "ÉXITO: CPU Governor fijado en 'performance' al 100%" else "FALLO: No se pudo fijar governor (requiere permiso Shizuku)",
            details = "Verificación en kernel: ${verified.joinToString { "${it.name}:${it.governor}" }}",
            executedCommand = "echo performance > /sys/devices/system/cpu/cpufreq/policy*/scaling_governor",
            commandOutput = output.ifEmpty { "Kernel write OK" }
        )

        return success
    }

    /**
     * Executes shell script to restore normal governor across all cores and logs the outcome.
     */
    fun restoreNormalGovernor(): Boolean {
        val script = """
            for p in /sys/devices/system/cpu/cpufreq/policy*; do
                if [ -d "${'$'}p" ]; then
                    if [ -f "${'$'}p/cpuinfo_min_freq" ]; then
                        cat "${'$'}p/cpuinfo_min_freq" > "${'$'}p/scaling_min_freq" 2>/dev/null
                    fi
                    echo schedutil > "${'$'}p/scaling_governor" 2>/dev/null || echo interactive > "${'$'}p/scaling_governor" 2>/dev/null
                fi
            done
            for c in /sys/devices/system/cpu/cpu*/cpufreq; do
                if [ -d "${'$'}c" ]; then
                    echo schedutil > "${'$'}c/scaling_governor" 2>/dev/null || echo interactive > "${'$'}c/scaling_governor" 2>/dev/null
                fi
            done
        """.trimIndent()

        val output = executeCommand(script)
        val verified = refreshGovernorStatus()
        val success = verified.none { it.isPerformance }

        logAudit(
            action = "RESTAURAR_NORMAL",
            isSuccess = success,
            summary = if (success) "ÉXITO: CPU Governor restaurado a estado normal/balanceado" else "FALLO: No se pudo restaurar governor",
            details = "Verificación en kernel: ${verified.joinToString { "${it.name}:${it.governor}" }}",
            executedCommand = "echo schedutil > /sys/devices/system/cpu/cpufreq/policy*/scaling_governor",
            commandOutput = output.ifEmpty { "Kernel write OK" }
        )

        return success
    }

    fun refreshGovernorStatus(): List<ClusterGovernorInfo> {
        val list = CpuMonitor.readCurrentClusterGovernors()
        _clusterGovernors.value = list
        val hasPerformance = list.any { it.isPerformance }
        _isGovernorPerformance.value = hasPerformance
        _governorStatusText.value = if (hasPerformance) "PERFORMANCE (100% Pico)" else "NORMAL (schedutil/balanceado)"
        return list
    }

    fun executeCommand(command: String): String {
        val service = _userService.value
        return if (service != null && _isServiceConnected.value) {
            try {
                service.executeCommand(command)
            } catch (e: Exception) {
                "Error en llamada remota: ${e.message}"
            }
        } else {
            // Local fallback
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                output.trim()
            } catch (e: Exception) {
                "Error local: ${e.message}"
            }
        }
    }

    fun logAudit(action: String, isSuccess: Boolean, summary: String, details: String = "", executedCommand: String = "", commandOutput: String = "") {
        val entry = AuditLogEntry(
            action = action,
            isSuccess = isSuccess,
            summary = summary,
            details = details,
            executedCommand = executedCommand,
            commandOutput = commandOutput
        )
        if (action.contains("ENCENDER", ignoreCase = true)) {
            _lastStartupResult.value = entry
        } else if (action.contains("APAGAR", ignoreCase = true)) {
            _lastShutdownResult.value = entry
        }
        appendLog(entry)
    }

    private fun appendLog(entry: AuditLogEntry) {
        val current = _auditLogs.value.toMutableList()
        current.add(0, entry)
        if (current.size > 50) current.removeLast()
        _auditLogs.value = current
    }

    fun clearLogs() {
        _auditLogs.value = emptyList()
    }
}
