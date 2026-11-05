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
    private val connectionMutex = Mutex()
    private val cameraReads = CameraReadQueue(scope)
    private val _connectionState = MutableStateFlow<UsbPtpConnectionState>(UsbPtpConnectionState.Idle)

    private var currentInitiator: BaselineInitiator? = null
    private var currentDevice: UsbDevice? = null
    private var currentDeviceId: UsbPtpDeviceId? = null
    private var pendingPermissionDeviceId: UsbPtpDeviceId? = null
    private var transferListener: FileTransferListener? = null
    private var downloadedListener: FileDownloadedListener? = null
    private var fileDownloadPath: String? = null
    private var uiOwner: Any? = null

    val connectionState: StateFlow<UsbPtpConnectionState> = _connectionState.asStateFlow()

    /** Shares the image priority queue with read-only mounted-camera file operations. */
    internal suspend fun <T> withFileRead(
        priority: CameraReadPriority, key: String? = null, read: () -> T
    ): T = cameraReads.submit(priority, key) { withContext(ioDispatcher) { read() } }

    /** Runs a camera read on IO while preventing the connection from closing during the operation. */
    internal suspend fun <T> withConnectedCamera(
        priority: CameraReadPriority = CameraReadPriority.BACKGROUND, key: String? = null,
        read: (BaselineInitiator) -> T
    ): T = cameraReads.submit(priority, key) {
        withContext(ioDispatcher) {
            stateMutex.withLock {
                val initiator = currentInitiator ?: throw PTPException("USB camera is not connected")
                check(initiator.isSessionActive()) { "USB session is not active" }
                read(initiator)
            }
        }
    }

    fun scanAndConnectIfPossible(requestPermission: Boolean = true) {
        scope.launch {
            val devices = usbManager.deviceList.values.toList()
            Timber.tag("USB_COMPAT").i("USB_SCAN devices=%d", devices.size)
            if (devices.isEmpty()) {
                val current = stateMutex.withLock { currentDeviceId }
                connectionMutex.withLock {
                    if (current != null) disconnectInternal("no_device", current)
                    else _connectionState.value = UsbPtpConnectionState.Disconnected(null, "no_device")
                }
                return@launch
            }
            devices.forEach { device ->
                val connected = tryStartConnection(device, requestPermission)
                if (connected) {
                    return@launch
                }
            }
        }
    }

    fun onUsbAttached(device: UsbDevice?) {
        if (device == null) return
        scope.launch {
            // Let the system's default-app routing grant access first; do not race it with a second dialog.
            tryStartConnection(device, requestPermission = false)
        }
    }

    fun onUsbDetached(device: UsbDevice?) {
        scope.launch {
            connectionMutex.withLock {
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
    }


    fun onUsbPermissionResult(device: UsbDevice?, granted: Boolean) {
        Timber.tag("USB_COMPAT").i("USB_PERMISSION granted=%b hasDevice=%b", granted, device != null)
        scope.launch {
            connectionMutex.withLock {
                val pendingId = stateMutex.withLock { pendingPermissionDeviceId }
                if (pendingId == null && device == null) {
                    Timber.tag("USB_COMPAT").i("USB_PERMISSION_IGNORED noPendingRequest=true")
                    return@withLock
                }
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
                    return@withLock
                }
                if (permissionDevice == null) {
                    pendingPermissionDeviceId = null
                    _connectionState.value = UsbPtpConnectionState.Error(null, "permission_missing_device")
                    return@withLock
                }
                if (_connectionState.value !is UsbPtpConnectionState.Connected || currentDeviceId != deviceId) {
                    connectInternal(permissionDevice)
                }
            }
        }
    }


    fun disconnect(reason: String = "manual_disconnect") {
        scope.launch {
            connectionMutex.withLock { disconnectInternal(reason, null) }
        }
    }

    /** Replaces UI callbacks atomically without closing the Application-owned USB session. */
    fun bindUi(owner: Any, path: String?, transfer: FileTransferListener, downloaded: FileDownloadedListener) {
        scope.launch { stateMutex.withLock {
            uiOwner = owner
            fileDownloadPath = path
            transferListener = transfer
            downloadedListener = downloaded
            currentInitiator?.apply {
                path?.let(::setFileDownloadPath)
                resetFileTransferListener(); setFileTransferListener(transfer)
                resetFileDownloadedListener(); setFileDownloadedListener(downloaded)
            }
            Timber.tag("USB_COMPAT").i("USB_UI_BOUND")
        } }
    }

    /** A late-destroyed screen must not remove the replacement screen's callbacks. */
    fun releaseUi(owner: Any) {
        scope.launch { stateMutex.withLock {
            if (uiOwner !== owner) return@withLock
            uiOwner = null
            transferListener = null
            downloadedListener = null
            currentInitiator?.resetFileTransferListener()
            currentInitiator?.resetFileDownloadedListener()
            Timber.tag("USB_COMPAT").i("USB_UI_RELEASED connectionRetained=%b", currentInitiator != null)
        } }
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

    private suspend fun tryStartConnection(device: UsbDevice, requestPermission: Boolean): Boolean = connectionMutex.withLock {
        // Mounted mass-storage volumes belong to Android's filesystem driver, not this PTP initiator.
        if ((0 until device.interfaceCount).none { device.getInterface(it).interfaceClass == 6 }) return@withLock false
        Timber.tag("USB_COMPAT").i(
            "USB_CANDIDATE vid=0x%04x pid=0x%04x permission=%b",
            device.vendorId, device.productId, usbManager.hasPermission(device)
        )
        val candidateId = device.toUsbPtpDeviceId()
        val shouldSkip = stateMutex.withLock {
            val trackedDevice = currentDeviceId ?: pendingPermissionDeviceId
            shouldSkipConnect(_connectionState.value, trackedDevice, candidateId)
        }
        val permission = usbManager.hasPermission(device)
        if (shouldSkip && (permission || _connectionState.value !is UsbPtpConnectionState.Connected)) {
            Timber.i("USB device already managed, skipping reconnect: %s", candidateId.deviceName)
            return@withLock true
        }
        if (!permission) {
            if (currentInitiator != null) disconnectInternal("permission_lost", candidateId)
            if (requestPermission) this@UsbPtpConnectionController.requestPermission(device, candidateId)
            else _connectionState.value = UsbPtpConnectionState.Error(candidateId, "permission_required")
            return@withLock false
        }
        connectInternal(device)
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
