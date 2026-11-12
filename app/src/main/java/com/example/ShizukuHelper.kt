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
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CommandStatus(
    val command: String,
    val exitCode: Int,
    val isSuccess: Boolean,
    val stdout: String,
    val stderr: String
)

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

    // Command-level execution states exposed directly to UI
    private val _startupCommandResults = MutableStateFlow<List<CommandStatus>>(emptyList())
    val startupCommandResults: StateFlow<List<CommandStatus>> = _startupCommandResults.asStateFlow()

    private val _shutdownCommandResults = MutableStateFlow<List<CommandStatus>>(emptyList())
    val shutdownCommandResults: StateFlow<List<CommandStatus>> = _shutdownCommandResults.asStateFlow()

    private val _isHighPerformanceActive = MutableStateFlow(false)
    val isHighPerformanceActive: StateFlow<Boolean> = _isHighPerformanceActive.asStateFlow()

    // Audit logs for tracking whether scripts/codes succeeded or failed
    private val _auditLogs = MutableStateFlow<List<AuditLogEntry>>(emptyList())
    val auditLogs: StateFlow<List<AuditLogEntry>> = _auditLogs.asStateFlow()

    private val _lastStartupResult = MutableStateFlow<AuditLogEntry?>(null)
    val lastStartupResult: StateFlow<AuditLogEntry?> = _lastStartupResult.asStateFlow()

    private val _lastShutdownResult = MutableStateFlow<AuditLogEntry?>(null)
    val lastShutdownResult: StateFlow<AuditLogEntry?> = _lastShutdownResult.asStateFlow()

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
        _isHighPerformanceActive.value = false
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

                // Execute native high performance commands
                try {
                    userServ.startBenchmarkLoop()
                    _isHighPerformanceActive.value = true
                    pullExecutionStatusFromService()
                } catch (e: Exception) {
                    Log.e(TAG, "Error iniciando modo alto rendimiento", e)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "UserService desconectado: $name")
            _isServiceConnected.value = false
            _userService.value = null
            _isHighPerformanceActive.value = false
            _serviceStatusMessage.value = "Servicio desconectado"
        }
    }

    fun init(context: Context? = null) {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
            val available = checkStatus()

            val startupLog = AuditLogEntry(
                action = "ENCENDER_APP",
                isSuccess = available,
                summary = if (available) "Inicio exitoso: Shizuku disponible en el sistema" else "Aviso: Shizuku no detectado",
                details = if (available) "Binder activo (UID 2000 listo para ejecutar comandos del sistema)" else "Abre la app de Shizuku para iniciar el servicio"
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
            restoreNativeSystemSettings()

            val shutdownLog = AuditLogEntry(
                action = "APAGAR_APP",
                isSuccess = true,
                summary = "Apagado: Valores del sistema restaurados y servicio desvinculado",
                details = "power_mode_type=0, high_performance_mode_on=0, thermalservice reset"
            )
            _lastShutdownResult.value = shutdownLog
            appendLog(shutdownLog)

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
            _isHighPerformanceActive.value = false
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
     * Executes native high performance mode commands:
     * 1) settings put system power_mode_type 2
     * 2) settings put secure high_performance_mode_on 1
     * 3) cmd thermalservice override-status 0
     * Captures exitCode, stdout, and stderr for each command.
     */
    fun applyNativeHighPerformance(): Boolean {
        val commands = listOf(
            "settings put system power_mode_type 2",
            "settings put secure high_performance_mode_on 1",
            "cmd thermalservice override-status 0"
        )

        val results = mutableListOf<CommandStatus>()
        var allSuccess = true

        for (cmd in commands) {
            val rawOutput = executeCommand(cmd)
            val isSuccess = !rawOutput.startsWith("ERROR")
            val exitCode = if (isSuccess) 0 else 1
            results.add(
                CommandStatus(
                    command = cmd,
                    exitCode = exitCode,
                    isSuccess = isSuccess,
                    stdout = if (isSuccess) rawOutput else "",
                    stderr = if (!isSuccess) rawOutput else ""
                )
            )
            if (!isSuccess) allSuccess = false
        }

        _startupCommandResults.value = results
        _isHighPerformanceActive.value = allSuccess

        logAudit(
            action = "MODO_ALTO_RENDIMIENTO",
            isSuccess = allSuccess,
            summary = if (allSuccess) "ÉXITO: Modo Alto Rendimiento y Thermal Bypass activados" else "FALLO parcial al aplicar ajustes de rendimiento",
            details = results.joinToString(" | ") { "${it.command} -> exitCode=${it.exitCode}" }
        )

        return allSuccess
    }

    /**
     * Executes restoration commands:
     * 1) settings put system power_mode_type 0
     * 2) settings put secure high_performance_mode_on 0
     * 3) cmd thermalservice reset
     * Captures exitCode, stdout, and stderr for each command.
     */
    fun restoreNativeSystemSettings(): Boolean {
        val commands = listOf(
            "settings put system power_mode_type 0",
            "settings put secure high_performance_mode_on 0",
            "cmd thermalservice reset"
        )

        val results = mutableListOf<CommandStatus>()
        var allSuccess = true

        for (cmd in commands) {
            val rawOutput = executeCommand(cmd)
            val isSuccess = !rawOutput.startsWith("ERROR")
            val exitCode = if (isSuccess) 0 else 1
            results.add(
                CommandStatus(
                    command = cmd,
                    exitCode = exitCode,
                    isSuccess = isSuccess,
                    stdout = if (isSuccess) rawOutput else "",
                    stderr = if (!isSuccess) rawOutput else ""
                )
            )
            if (!isSuccess) allSuccess = false
        }

        _shutdownCommandResults.value = results
        _isHighPerformanceActive.value = false

        logAudit(
            action = "RESTAURAR_SISTEMA",
            isSuccess = allSuccess,
            summary = if (allSuccess) "ÉXITO: Ajustes de energía y servicio térmico restaurados" else "FALLO al restaurar ajustes",
            details = results.joinToString(" | ") { "${it.command} -> exitCode=${it.exitCode}" }
        )

        return allSuccess
    }

    fun pullExecutionStatusFromService() {
        val service = _userService.value ?: return
        try {
            val raw = service.executionStatus
            if (raw.isNotEmpty()) {
                parseAndPublishCommandResults(raw)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching execution status from service", e)
        }
    }

    private fun parseAndPublishCommandResults(raw: String) {
        try {
            val sections = raw.split("###")
            val startupSection = sections.find { it.startsWith("STARTUP:") }?.removePrefix("STARTUP:")
            val shutdownSection = sections.find { it.startsWith("SHUTDOWN:") }?.removePrefix("SHUTDOWN:")

            if (!startupSection.isNullOrEmpty()) {
                val list = startupSection.split(";;").mapNotNull { parseCommandChunk(it) }
                if (list.isNotEmpty()) {
                    _startupCommandResults.value = list
                    _isHighPerformanceActive.value = list.all { it.isSuccess }
                }
            }

            if (!shutdownSection.isNullOrEmpty()) {
                val list = shutdownSection.split(";;").mapNotNull { parseCommandChunk(it) }
                if (list.isNotEmpty()) {
                    _shutdownCommandResults.value = list
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing command status chunks", e)
        }
    }

    private fun parseCommandChunk(chunk: String): CommandStatus? {
        if (chunk.isBlank()) return null
        val parts = chunk.split("|", limit = 5)
        return if (parts.size >= 5) {
            CommandStatus(
                command = parts[0],
                exitCode = parts[1].toIntOrNull() ?: 0,
                isSuccess = parts[2].toBoolean(),
                stdout = parts[3],
                stderr = parts[4]
            )
        } else null
    }

    fun executeCommand(command: String): String {
        val service = _userService.value
        return if (service != null && _isServiceConnected.value) {
            try {
                service.executeCommand(command)
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
        } else {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                val code = process.waitFor()
                if (code == 0) stdout.trim() else "ERROR (exitCode $code): ${stderr.trim()}"
            } catch (e: Exception) {
                "ERROR: ${e.message}"
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
        if (action.contains("ENCENDER", ignoreCase = true) || action.contains("MODO_ALTO", ignoreCase = true)) {
            _lastStartupResult.value = entry
        } else if (action.contains("APAGAR", ignoreCase = true) || action.contains("RESTAURAR", ignoreCase = true)) {
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
