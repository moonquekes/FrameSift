package cn.devcxl.photosync.ptp.manager

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cn.devcxl.photosync.App
import cn.devcxl.photosync.ptp.interfaces.FileDownloadedListener
import cn.devcxl.photosync.ptp.interfaces.FileTransferListener
import cn.devcxl.photosync.ptp.params.SyncParams
import cn.devcxl.photosync.ptp.usbcamera.BaselineInitiator
import cn.devcxl.photosync.ptp.usbcamera.Command
import cn.devcxl.photosync.ptp.usbcamera.InitiatorFactory
import cn.devcxl.photosync.ptp.usbcamera.PTPException
import cn.devcxl.photosync.ptp.usbcamera.sony.SonyInitiator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Manages the lifecycle of a USB PTP connection: scanning, connecting, disconnecting,
 * and routing file-transfer events. Exposes a [StateFlow] of [UsbPtpConnectionState].
 */
class UsbPtpConnectionController(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val stateMutex = Mutex()
    private val _connectionState = MutableStateFlow<UsbPtpConnectionState>(UsbPtpConnectionState.Idle)

    private var currentInitiator: BaselineInitiator? = null
    private var currentDevice: UsbDevice? = null
    private var currentDeviceId: UsbPtpDeviceId? = null
    private var pendingPermissionDeviceId: UsbPtpDeviceId? = null
    private var transferListener: FileTransferListener? = null
    private var downloadedListener: FileDownloadedListener? = null
    private var fileDownloadPath: String? = null

    val connectionState: StateFlow<UsbPtpConnectionState> = _connectionState.asStateFlow()

    /** Runs a camera read on IO while preventing the connection from closing during the operation. */
    suspend fun <T> withConnectedCamera(read: (BaselineInitiator) -> T): T = withContext(ioDispatcher) {
        stateMutex.withLock {
            val initiator = currentInitiator ?: throw PTPException("USB camera is not connected")
            check(initiator.isSessionActive()) { "USB session is not active" }
            read(initiator)
        }
    }

    fun scanAndConnectIfPossible() {
        scope.launch {
            val devices = usbManager.deviceList.values.toList()
            Timber.tag("USB_COMPAT").i("USB_SCAN devices=%d", devices.size)
            if (devices.isEmpty()) {
                val current = stateMutex.withLock { currentDeviceId }
                if (current == null) {
                    _connectionState.value = UsbPtpConnectionState.Disconnected(null, "no_device")
                }
                return@launch
            }
            devices.forEach { device ->
                val connected = tryStartConnection(device)
                if (connected) {
                    return@launch
                }
            }
        }
    }

    fun onUsbAttached(device: UsbDevice?) {
        if (device == null) return
        scope.launch {
            tryStartConnection(device)
        }
    }

    fun onUsbDetached(device: UsbDevice?) {
        scope.launch {
            val detachedId = device?.toUsbPtpDeviceId()
            val shouldDisconnect = stateMutex.withLock {
                if (detachedId == null) {
                    currentDeviceId ?: pendingPermissionDeviceId
                } else {
                    resolveDetachedDisconnectTarget(currentDeviceId, pendingPermissionDeviceId, detachedId)
                }
            }
            if (shouldDisconnect != null) {
                disconnectInternal("usb_detached", shouldDisconnect)
            }
        }
    }

    fun onUsbPermissionResult(device: UsbDevice?, granted: Boolean) {
        Timber.tag("USB_COMPAT").i("USB_PERMISSION granted=%b hasDevice=%b", granted, device != null)
        scope.launch {
            val pendingId = stateMutex.withLock { pendingPermissionDeviceId }
            val permissionDevice = device ?: usbManager.deviceList.values.firstOrNull {
                it.toUsbPtpDeviceId() == pendingId
            }
            val permissionGranted = permissionDevice?.let(usbManager::hasPermission) == true
            Timber.tag("USB_COMPAT").i(
                "USB_PERMISSION_VERIFIED reportedGranted=%b resolvedDevice=%b actualGranted=%b",
                granted, permissionDevice != null, permissionGranted
            )
            val deviceId = permissionDevice?.toUsbPtpDeviceId()
            if (!permissionGranted) {
                stateMutex.withLock {
                    if (deviceId == null || pendingPermissionDeviceId == deviceId) {
                        pendingPermissionDeviceId = null
                    }
                }
                _connectionState.value = UsbPtpConnectionState.Error(deviceId, "permission_denied")
                return@launch
            }
            if (permissionDevice == null) {
                pendingPermissionDeviceId = null
                _connectionState.value = UsbPtpConnectionState.Error(null, "permission_missing_device")
                return@launch
            }
            connectInternal(permissionDevice)
        }
    }

    fun disconnect(reason: String = "manual_disconnect") {
        scope.launch {
            disconnectInternal(reason, null)
        }
    }

    fun setFileDownloadPath(path: String?) {
        fileDownloadPath = path
        scope.launch {
            stateMutex.withLock {
                currentInitiator?.setFileDownloadPath(path)
            }
        }
    }

    fun setFileTransferListener(listener: FileTransferListener?) {
        transferListener = listener
        scope.launch {
            stateMutex.withLock {
                currentInitiator?.resetFileTransferListener()
                if (listener != null) {
                    currentInitiator?.setFileTransferListener(listener)
                }
            }
        }
    }

    fun setFileDownloadedListener(listener: FileDownloadedListener?) {
        downloadedListener = listener
        scope.launch {
            stateMutex.withLock {
                currentInitiator?.resetFileDownloadedListener()
                if (listener != null) {
                    currentInitiator?.setFileDownloadedListener(listener)
                }
            }
        }
    }

    private suspend fun tryStartConnection(device: UsbDevice): Boolean {
        Timber.tag("USB_COMPAT").i(
            "USB_CANDIDATE vid=0x%04x pid=0x%04x permission=%b",
            device.vendorId, device.productId, usbManager.hasPermission(device)
        )
        val candidateId = device.toUsbPtpDeviceId()
        val shouldSkip = stateMutex.withLock {
            val trackedDevice = currentDeviceId ?: pendingPermissionDeviceId
            shouldSkipConnect(_connectionState.value, trackedDevice, candidateId)
        }
        if (shouldSkip) {
            Timber.i("USB device already managed, skipping reconnect: %s", candidateId.deviceName)
            return true
        }
        if (!usbManager.hasPermission(device)) {
            requestPermission(device, candidateId)
            return false
        }
        return connectInternal(device)
    }

    private suspend fun requestPermission(device: UsbDevice, deviceId: UsbPtpDeviceId) {
        stateMutex.withLock {
            pendingPermissionDeviceId = deviceId
            _connectionState.value = UsbPtpConnectionState.PermissionRequested(deviceId)
        }
        val permissionIntent = PendingIntent.getBroadcast(
            appContext,
            device.deviceId,
            Intent(App.ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbManager.requestPermission(device, permissionIntent)
        Timber.tag("USB_COMPAT").i("USB_PERMISSION_REQUESTED")
        Timber.d("请求设备权限 %s", device.deviceName)
    }

    private suspend fun connectInternal(device: UsbDevice): Boolean {
        val deviceId = device.toUsbPtpDeviceId()
        disconnectInternal("reconnect_before_connect", deviceId, resetStateAfterDisconnect = false)
        stateMutex.withLock {
            currentDeviceId = deviceId
            pendingPermissionDeviceId = null
        }
        _connectionState.value = UsbPtpConnectionState.Connecting(deviceId)

        val initiator = try {
            val produced = InitiatorFactory.produceInitiator(device, usbManager)
            produced.getClearStatus()
            produced.setSyncTriggerMode(SyncParams.SYNC_TRIGGER_MODE_POLL_LIST)
            if (produced is SonyInitiator) {
                val supportsRemote = produced.getDeviceInfo().supportsOperation(Command.SONY_SDIOCOMMAND)
                produced.setSyncTriggerMode(
                    if (supportsRemote) SyncParams.SYNC_TRIGGER_MODE_EVENT else SyncParams.SYNC_TRIGGER_MODE_MANUAL
                )
                Timber.tag("USB_COMPAT").i("SONY_MODE remoteSupported=%b manualAlbum=%b", supportsRemote, !supportsRemote)
            }
            fileDownloadPath?.let(produced::setFileDownloadPath)
            transferListener?.let(produced::setFileTransferListener)
            downloadedListener?.let(produced::setFileDownloadedListener)
            produced.openSession()
            produced
        } catch (e: Exception) {
            Timber.tag("USB_COMPAT").e(e, "USB_CONNECT_FAILED")
            stateMutex.withLock {
                currentDeviceId = null
            }
            _connectionState.value = UsbPtpConnectionState.Error(deviceId, "connect_failed", e.message)
            Timber.e(e, "connectInternal failed")
            return false
        }

        stateMutex.withLock {
            currentInitiator = initiator
            currentDevice = device
        }
        _connectionState.value = UsbPtpConnectionState.Connected(deviceId)
        Timber.tag("USB_COMPAT").i("USB_CONNECTED triggerMode=%d", initiator.syncTriggerMode)
        return true
    }

    private suspend fun disconnectInternal(
        reason: String,
        targetDeviceId: UsbPtpDeviceId?,
        resetStateAfterDisconnect: Boolean = true
    ) {
        Timber.tag("USB_COMPAT").i("USB_DISCONNECT reason=%s", reason)
        stateMutex.withLock {
            if (currentInitiator == null && currentDeviceId == null && pendingPermissionDeviceId == null) {
                if (resetStateAfterDisconnect && _connectionState.value !is UsbPtpConnectionState.Idle) {
                    _connectionState.value = UsbPtpConnectionState.Disconnected(targetDeviceId, reason)
                }
                return
            }
        }
        val initiatorToClose: BaselineInitiator?
        val deviceToDisconnect: UsbPtpDeviceId?
        stateMutex.withLock {
            deviceToDisconnect = targetDeviceId ?: currentDeviceId ?: pendingPermissionDeviceId
            initiatorToClose = currentInitiator
            if (deviceToDisconnect != null) {
                _connectionState.value = UsbPtpConnectionState.Disconnecting(deviceToDisconnect, reason)
            }
            currentInitiator = null
            currentDevice = null
            currentDeviceId = null
            pendingPermissionDeviceId = null
        }

        try {
            initiatorToClose?.close()
        } catch (e: PTPException) {
            Timber.w(e, "disconnectInternal close failed")
        } catch (e: Exception) {
            Timber.w(e, "disconnectInternal unexpected close failure")
        }

        if (resetStateAfterDisconnect) {
            _connectionState.value = UsbPtpConnectionState.Disconnected(deviceToDisconnect, reason)
        }
    }
}
