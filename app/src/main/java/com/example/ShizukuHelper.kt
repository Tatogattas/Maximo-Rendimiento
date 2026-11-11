package com.example

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Singleton manager for Shizuku connection, permission, and UserService lifecycle.
 * Manages binding with daemon(true) and processNameSuffix("shizuku_service").
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 8123

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

    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            _isPermissionGranted.value = granted
            Log.i(TAG, "Shizuku permission result: granted=$granted")
            if (granted) {
                _serviceStatusMessage.value = "Permiso concedido"
            } else {
                _serviceStatusMessage.value = "Permiso Shizuku denegado"
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        checkStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        _isShizukuAvailable.value = false
        _isServiceConnected.value = false
        _userService.value = null
        _serviceStatusMessage.value = "Servicio Shizuku desconectado"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Shizuku UserService connected: $name")
            if (service != null && service.pingBinder()) {
                val userServ = IUserService.Stub.asInterface(service)
                _userService.value = userServ
                _isServiceConnected.value = true
                try {
                    userServ.startBenchmarkLoop()
                    _serviceStatusMessage.value = "Servicio Shizuku Conectado (UID 2000)"
                } catch (e: Exception) {
                    _serviceStatusMessage.value = "Conectado (UID 2000)"
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Shizuku UserService disconnected: $name")
            _isServiceConnected.value = false
            _userService.value = null
            _serviceStatusMessage.value = "Servicio Shizuku desconectado"
        }
    }

    fun init(context: Context? = null) {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
            checkStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Shizuku listeners", e)
        }
    }

    fun destroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing Shizuku listeners", e)
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
            _serviceStatusMessage.value = "Shizuku no disponible"
            false
        }
    }

    fun requestPermission(activity: Activity) {
        if (!checkStatus()) {
            if (_isShizukuAvailable.value && !_isPermissionGranted.value) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    Log.i(TAG, "Shizuku shouldShowRequestPermissionRationale")
                }
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        }
    }

    /**
     * Binds the UserService in an independent process with UID 2000 (shell).
     * Uses daemon(true) and processNameSuffix("shizuku_service") as requested.
     */
    fun bindService(context: Context) {
        if (!checkStatus()) {
            if (context is Activity && _isShizukuAvailable.value && !_isPermissionGranted.value) {
                requestPermission(context)
            }
            return
        }

        if (_isServiceConnected.value) {
            Log.i(TAG, "UserService already connected")
            return
        }

        try {
            val component = ComponentName(context.packageName, UserService::class.java.name)
            val args = Shizuku.UserServiceArgs(component)
                .daemon(true)
                .processNameSuffix("shizuku_service")
                .debuggable(BuildConfig.DEBUG)
                .version(1)

            userServiceArgs = args
            _serviceStatusMessage.value = "Vinculando UserService (daemon=true)..."
            Shizuku.bindUserService(args, serviceConnection)
            Log.i(TAG, "Shizuku.bindUserService invoked for component: $component")
        } catch (e: Exception) {
            Log.e(TAG, "Error binding Shizuku UserService", e)
            _serviceStatusMessage.value = "Error al vincular: ${e.message}"
        }
    }

    // Alias for Spanish convention
    fun iniciarServicio(context: Context) = bindService(context)

    fun unbindService() {
        val args = userServiceArgs ?: return
        try {
            _userService.value?.stopBenchmarkLoop()
            Shizuku.unbindUserService(args, serviceConnection, true)
            _isServiceConnected.value = false
            _userService.value = null
            _serviceStatusMessage.value = "UserService desvinculado"
            Log.i(TAG, "Shizuku.unbindUserService invoked")
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding Shizuku UserService", e)
        }
    }

    // Alias for Spanish convention
    fun detenerServicio() = unbindService()

    fun isRunning(): Boolean {
        return try {
            _userService.value?.isRunning ?: false
        } catch (e: Exception) {
            false
        }
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
            "UserService no está conectado con Shizuku"
        }
    }

    fun ejecutarComando(command: String): String = executeCommand(command)
}
