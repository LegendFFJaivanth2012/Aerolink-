package com.aerolink.phone

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {
    companion object {
        private const val SERVICE_TYPE = "_aerolink._tcp."
        private const val PROTOCOL_MAGIC = "AEROLINK32"
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L * 1024L
        private const val RECEIVE_WINDOW_MS = 10 * 60 * 1000
        private const val CHUNK_SIZE = 128 * 1024
    }

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var instruction: TextView
    private lateinit var telemetry: TextView
    private lateinit var sensitivityLabel: TextView
    private lateinit var sensitivityBar: SeekBar

    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("aerolink_v32", MODE_PRIVATE) }
    private val mainHandler by lazy { android.os.Handler(mainLooper) }

    private var landmarker: HandLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var nsdManager: NsdManager? = null
    private var wifiManager: WifiManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var role = ""
    private var gesture = "PINCH"
    private var training = false
    private var samples = 0
    private var sensitivity = 65
    private var selectedUri: Uri? = null
    private var transferStarted = false
    private var receiverPolling = false
    private var lastGestureAt = 0L
    private var lastTrainingSampleAt = 0L

    private var receiverJob: Job? = null
    private var receiverSocket: ServerSocket? = null
    private var receiverToken = ""
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryTimeoutJob: Job? = null
    private var resolvingReceiver = false
    private var receiverResolved = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            status.text = "CAMERA PERMISSION NEEDED"
            instruction.text = "Allow camera access so AeroLink can recognise your gesture."
        }
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) armLocalReceiver() else {
            receiverPolling = false
            status.text = "STORAGE PERMISSION NEEDED"
            instruction.text = "Allow storage access to save received files on this Android version."
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedUri = uri
        transferStarted = false
        if (uri != null) {
            status.text = "FILE READY"
            instruction.text = "Perform your trained " + gesture + " gesture to find the receiver and send."
            telemetry.text = displayName(uri) + " · " + readableSize(fileSize(uri))
            if (role == "sender") startCamera()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        instruction = findViewById(R.id.instruction)
        telemetry = findViewById(R.id.telemetry)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)
        sensitivityBar = findViewById(R.id.sensitivity)

        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        gesture = prefs.getString("gesture", "PINCH") ?: "PINCH"
        sensitivity = prefs.getInt("sensitivity", 65).coerceIn(10, 100)
        sensitivityBar.progress = sensitivity
        updateSensitivityLabel()

        findViewById<Button>(R.id.senderMode).setOnClickListener { chooseSender() }
        findViewById<Button>(R.id.receiverMode).setOnClickListener { chooseReceiver() }
        findViewById<Button>(R.id.selectFile).setOnClickListener { picker.launch("*/*") }
        findViewById<Button>(R.id.discover).setOnClickListener { startReceiver() }
        findViewById<Button>(R.id.cancel).setOnClickListener { reset() }
        findViewById<Button>(R.id.trainGestures).setOnClickListener { chooseGesture() }

        sensitivityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivity = progress.coerceIn(10, 100)
                updateSensitivityLabel()
                if (fromUser) prefs.edit().putInt("sensitivity", sensitivity).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        findViewById<TextView>(R.id.mode).text = "DIRECT WI-FI · NO ESP32 · NO CLOUD"
        telemetry.text = "Direct local Wi-Fi transfer · internet not required"
    }

    private fun updateSensitivityLabel() {
        sensitivityLabel.text = "PINCH SENSITIVITY · " + sensitivity + "%"
    }

    private fun chooseGesture() {
        val items = arrayOf("Pinch", "Open palm", "Fist", "Two fingers", "Point")
        AlertDialog.Builder(this)
            .setTitle("Choose your AeroLink gesture")
            .setItems(items) { _, which ->
                gesture = listOf("PINCH", "OPEN_PALM", "FIST", "TWO_FINGERS", "POINT")[which]
                prefs.edit().putString("gesture", gesture).apply()
                training = true
                samples = 0
                lastTrainingSampleAt = 0L
                instruction.text = "Show " + items[which] + " clearly. Calibration: 0/12"
                status.text = "CALIBRATING"
                startCamera()
            }
            .show()
    }

    private fun chooseSender() {
        stopLocalReceiver()
        stopDiscovery()
        role = "sender"
        transferStarted = false
        receiverPolling = false
        findViewById<TextView>(R.id.mode).text = "SENDER · LOCAL WI-FI"
        findViewById<Button>(R.id.selectFile).visibility = Button.VISIBLE
        findViewById<Button>(R.id.discover).visibility = Button.GONE
        findViewById<Button>(R.id.cancel).visibility = Button.VISIBLE
        status.text = "SENDER MODE"
        instruction.text = "Choose a file. When the receiver is armed, perform your gesture to send it."
        telemetry.text = "Both phones must be on the same Wi-Fi network or hotspot."
        stopCamera()
    }

    private fun chooseReceiver() {
        stopLocalReceiver()
        stopDiscovery()
        role = "receiver"
        selectedUri = null
        transferStarted = false
        receiverPolling = false
        findViewById<TextView>(R.id.mode).text = "RECEIVER · LOCAL WI-FI"
        findViewById<Button>(R.id.selectFile).visibility = Button.GONE
        findViewById<Button>(R.id.discover).visibility = Button.VISIBLE
        findViewById<Button>(R.id.cancel).visibility = Button.VISIBLE
        status.text = "RECEIVER MODE"
        instruction.text = "Tap ARM RECEIVER, then perform your trained gesture to become discoverable."
        telemetry.text = "The file is received directly over your local Wi-Fi."
        stopCamera()
    }

    private fun startReceiver() {
        if (role != "receiver") return
        status.text = "READY TO ARM"
        instruction.text = "Perform your " + gesture + " gesture to open a 10-minute receive window."
        telemetry.text = "Keep both phones connected to the same Wi-Fi or hotspot."
        startCamera()
    }

    private fun reset() {
        role = ""
        training = false
        transferStarted = false
        receiverPolling = false
        selectedUri = null
        stopLocalReceiver()
        stopDiscovery()
        findViewById<Button>(R.id.selectFile).visibility = Button.GONE
        findViewById<Button>(R.id.discover).visibility = Button.GONE
        findViewById<Button>(R.id.cancel).visibility = Button.GONE
        findViewById<TextView>(R.id.mode).text = "DIRECT WI-FI · NO ESP32 · NO CLOUD"
        stopCamera()
        status.text = "READY"
        instruction.text = "Choose SEND or RECEIVE."
        telemetry.text = "AeroLink · gesture-controlled local file transfer"
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
            createLandmarker()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createLandmarker() {
        landmarker?.close()
        val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.55f)
            .setMinHandPresenceConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .setResultListener { result, _ -> onHand(result) }
            .build()
        landmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val previewUse = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(executor) { image ->
            try {
                val mp = BitmapImageBuilder(image.toBitmap()).build()
                landmarker?.detectAsync(mp, SystemClock.uptimeMillis())
            } catch (_: Exception) {
                // Skip a failed frame; the following camera frame will be analysed.
            } finally {
                image.close()
            }
        }

        provider.unbindAll()
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUse, analysis)
    }

    private fun onHand(result: HandLandmarkerResult) {
        val hand = result.landmarks().firstOrNull() ?: return
        val thumb = hand[4]
        val index = hand[8]
        val pinchDistance = hypot(
            (thumb.x() - index.x()).toDouble(),
            (thumb.y() - index.y()).toDouble()
        ).toFloat()

        val extended = listOf(8, 12, 16, 20).count { hand[it].y() < hand[it - 2].y() }
        val pinchThreshold = 0.035f + (sensitivity / 100f) * 0.065f
        val detected = when (gesture) {
            "PINCH" -> pinchDistance < pinchThreshold
            "OPEN_PALM" -> extended >= 4
            "FIST" -> extended <= 1
            "TWO_FINGERS" -> extended == 2
            else -> extended == 1
        }

        runOnUiThread {
            val now = SystemClock.elapsedRealtime()
            if (training) {
                if (detected && now - lastTrainingSampleAt > 350L) {
                    lastTrainingSampleAt = now
                    samples++
                    instruction.text = "Hold " + gesture + " clearly · Calibration: " + samples.coerceAtMost(12) + "/12"
                    if (samples >= 12) finishTraining()
                }
                return@runOnUiThread
            }

            if (detected && now - lastGestureAt > 2200L) {
                lastGestureAt = now
                if (role == "receiver" && !receiverPolling) {
                    receiverPolling = true
                    status.text = "GESTURE ACCEPTED"
                    instruction.text = "Opening your direct Wi-Fi receive session…"
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    ) {
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        armLocalReceiver()
                    }
                } else if (role == "sender" && selectedUri != null && !transferStarted) {
                    transferStarted = true
                    status.text = "GESTURE ACCEPTED"
                    instruction.text = "Searching for the armed AeroLink receiver…"
                    discoverReceiver()
                }
            }
        }
    }

    private fun finishTraining() {
        training = false
        prefs.edit().putLong("calibratedAt", System.currentTimeMillis()).apply()
        status.text = "GESTURE CALIBRATED"
        instruction.text = gesture + " calibration is complete and ready to use."
        telemetry.text = "12 stable gesture detections recorded on this phone."
        stopCamera()
    }

    private fun armLocalReceiver() {
        if (role != "receiver") return
        val token = UUID.randomUUID().toString().replace("-", "")
        receiverToken = token

        receiverJob?.cancel()
        receiverJob = lifecycleScope.launch {
            var server: ServerSocket? = null
            try {
                val activeServer = withContext(Dispatchers.IO) {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(0))
                        soTimeout = RECEIVE_WINDOW_MS
                    }
                }
                server = activeServer
                receiverSocket = activeServer

                registerReceiverService(activeServer.localPort, token)
                delay(900)

                status.text = "WI-FI RECEIVER ACTIVE"
                instruction.text = "Receiver armed. Keep this screen open while the sender performs its gesture."
                telemetry.text = "Discoverable on local Wi-Fi · " + localWifiAddress()

                val client = withContext(Dispatchers.IO) { activeServer.accept() }
                client.use { socket ->
                    socket.soTimeout = 120000
                    withContext(Dispatchers.IO) { handleIncomingUpload(socket, token) }
                }

                status.text = "FILE RECEIVED"
                instruction.text = "Transfer complete. Your file is saved in Downloads/AeroLink."
                telemetry.text = "Direct Wi-Fi transfer completed."
            } catch (e: SocketTimeoutException) {
                if (role == "receiver") {
                    status.text = "RECEIVE WINDOW EXPIRED"
                    instruction.text = "No sender connected. Tap ARM RECEIVER and perform your gesture again."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (role == "receiver") {
                    status.text = "RECEIVE FAILED"
                    instruction.text = e.message ?: "Could not receive the file over Wi-Fi."
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { server?.close() }
                }
                if (receiverSocket === server) receiverSocket = null
                withContext(NonCancellable + Dispatchers.Main) {
                    unregisterReceiverService()
                    receiverPolling = false
                }
            }
        }
    }

    private fun registerReceiverService(port: Int, token: String) {
        val manager = nsdManager ?: error("Android local network discovery is unavailable.")
        unregisterReceiverService()

        val service = NsdServiceInfo().apply {
            serviceName = "AeroLink-" + token.take(6).uppercase()
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("token", token)
            setAttribute("version", "32")
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                runOnUiThread {
                    if (role == "receiver" && receiverPolling) {
                        status.text = "WI-FI RECEIVER ACTIVE"
                        telemetry.text = "Discoverable on local Wi-Fi · " + localWifiAddress()
                    }
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                runOnUiThread {
                    if (role == "receiver") {
                        status.text = "DISCOVERY FAILED"
                        instruction.text = "Android could not publish the receiver on this Wi-Fi network. Check the Wi-Fi connection and try again."
                    }
                }
                registrationListener = null
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }

        registrationListener = listener
        manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun unregisterReceiverService() {
        val listener = registrationListener ?: return
        runCatching { nsdManager?.unregisterService(listener) }
        registrationListener = null
    }

    private fun discoverReceiver() {
        val manager = nsdManager
        if (manager == null) {
            sendFailed("Android Wi-Fi discovery is unavailable.")
            return
        }

        resolvingReceiver = false
        receiverResolved = false
        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (receiverResolved || resolvingReceiver) return
                if (!serviceInfo.serviceName.startsWith("AeroLink", ignoreCase = true)) return
                resolvingReceiver = true

                val resolver = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        resolvingReceiver = false
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        if (receiverResolved) return
                        receiverResolved = true
                        discoveryTimeoutJob?.cancel()
                        stopDiscovery()

                        val tokenBytes = info.attributes["token"]
                        val token = tokenBytes?.let { String(it, Charsets.UTF_8) }
                        val host = info.host
                        if (token.isNullOrBlank() || host == null || info.port <= 0) {
                            sendFailed("The receiver did not publish a valid local transfer session.")
                            return
                        }

                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                sendFileDirectly(host, info.port, token)
                            } catch (e: Exception) {
                                sendFailed(e.message ?: "Could not send the file over Wi-Fi.")
                            }
                        }
                    }
                }

                runCatching { manager.resolveService(serviceInfo, resolver) }
                    .onFailure {
                        resolvingReceiver = false
                    }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
                sendFailed("Wi-Fi discovery could not start. Make sure both phones are on the same Wi-Fi network.")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { manager.stopServiceDiscovery(this) }
            }
        }

        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            discoveryTimeoutJob?.cancel()
            discoveryTimeoutJob = lifecycleScope.launch {
                delay(30000)
                if (!receiverResolved && transferStarted) {
                    stopDiscovery()
                    sendFailed("No armed AeroLink receiver found. Check that both phones are on the same Wi-Fi or hotspot.")
                }
            }
        } catch (e: Exception) {
            stopDiscovery()
            sendFailed(e.message ?: "Could not start local Wi-Fi discovery.")
        }
    }

    private fun sendFileDirectly(host: java.net.InetAddress, port: Int, token: String) {
        val uri = selectedUri ?: error("Choose a file first.")
        val size = fileSize(uri)
        if (size > MAX_FILE_BYTES) error("This file is larger than AeroLink's 2 GB transfer limit.")

        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 256 * 1024
            socket.connect(InetSocketAddress(host, port), 12000)
            socket.soTimeout = 120000

            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 256 * 1024))
            output.writeUTF(PROTOCOL_MAGIC)
            output.writeUTF(token)
            output.writeUTF(displayName(uri).take(240))
            output.writeUTF(contentResolver.getType(uri) ?: "application/octet-stream")

            val input = contentResolver.openInputStream(uri) ?: error("Could not open the selected file.")
            input.use { stream ->
                val buffer = ByteArray(CHUNK_SIZE)
                var sent = 0L
                var nextUiUpdate = 0L
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    sent += count
                    if (sent > MAX_FILE_BYTES) error("This file is larger than AeroLink's 2 GB transfer limit.")
                    output.writeInt(count)
                    output.write(buffer, 0, count)
                    if (sent - nextUiUpdate >= 512L * 1024L) {
                        nextUiUpdate = sent
                        val pct = if (size > 0) ((sent * 100L) / size).coerceIn(0, 100) else -1
                        mainHandler.post {
                            status.text = if (pct >= 0) "SENDING · " + pct + "%" else "SENDING · " + readableSize(sent)
                        }
                    }
                }
            }

            output.writeInt(0)
            output.flush()

            val response = DataInputStream(BufferedInputStream(socket.getInputStream())).readUTF()
            if (response != "OK") error("The receiver did not confirm the completed transfer.")

            runOnUiThread {
                status.text = "SENT"
                instruction.text = "Transfer complete. The receiver saved the file."
                telemetry.text = "Sent directly over local Wi-Fi · " + readableSize(size)
                transferStarted = false
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleIncomingUpload(socket: Socket, expectedToken: String) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
        val magic = input.readUTF()
        val token = input.readUTF()
        if (magic != PROTOCOL_MAGIC || token != expectedToken) error("The sender could not be authenticated for this receive session.")

        val name = input.readUTF().take(240)
        val mime = input.readUTF().take(120).ifBlank { "application/octet-stream" }
        val safeName = sanitizeFileName(name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AeroLink")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val folder = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "AeroLink"
                )
                if (!folder.exists()) folder.mkdirs()
                put(MediaStore.MediaColumns.DATA, File(folder, safeName).absolutePath)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val destination = contentResolver.insert(collection, values)
            ?: error("Android could not create the destination file.")

        try {
            var total = 0L
            contentResolver.openOutputStream(destination, "w")?.use { output ->
                val buffer = ByteArray(CHUNK_SIZE)
                while (true) {
                    val count = input.readInt()
                    if (count == 0) break
                    if (count < 0 || count > CHUNK_SIZE) error("The sender sent an invalid data block.")
                    total += count
                    if (total > MAX_FILE_BYTES) error("The incoming file exceeds the 2 GB limit.")
                    input.readFully(buffer, 0, count)
                    output.write(buffer, 0, count)
                    if (total % (2L * 1024L * 1024L) < CHUNK_SIZE) {
                        val progress = readableSize(total)
                        mainHandler.post { status.text = "RECEIVING · " + progress }
                    }
                }
                output.flush()
            } ?: error("Android could not open the destination file.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completed = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(destination, completed, null, null)
            }

            val response = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            response.writeUTF("OK")
            response.flush()
        } catch (e: Exception) {
            contentResolver.delete(destination, null, null)
            throw e
        }
    }

    private fun stopLocalReceiver() {
        receiverJob?.cancel()
        receiverJob = null
        runCatching { receiverSocket?.close() }
        receiverSocket = null
        unregisterReceiverService()
        receiverPolling = false
    }

    private fun stopDiscovery() {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        val listener = discoveryListener
        if (listener != null) {
            runCatching { nsdManager?.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val lock = wifiManager?.createMulticastLock("AeroLink-Local-Discovery") ?: return
        lock.setReferenceCounted(false)
        runCatching { lock.acquire() }
        multicastLock = lock
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        if (runCatching { lock.isHeld }.getOrDefault(false)) {
            runCatching { lock.release() }
        }
        multicastLock = null
    }

    private fun sendFailed(message: String) {
        runOnUiThread {
            transferStarted = false
            status.text = "SEND FAILED"
            instruction.text = message
            telemetry.text = "No cloud relay used · local Wi-Fi only"
        }
    }

    private fun localWifiAddress(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val wifi = interfaces.filter {
                it.isUp && !it.isLoopback && (
                    it.name.startsWith("wlan", true) ||
                    it.name.startsWith("ap", true) ||
                    it.name.startsWith("swlan", true)
                )
            }
            val candidates = (wifi + interfaces.filter { it.isUp && !it.isLoopback }).distinct()
            candidates.asSequence()
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress ?: "Wi-Fi address unavailable"
        } catch (_: Exception) {
            "Wi-Fi address unavailable"
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: "aerolink-file.bin"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "aerolink-file.bin"
    }

    private fun fileSize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .take(180)
            .ifBlank { "aerolink-file.bin" }
    }

    private fun readableSize(bytes: Long): String {
        if (bytes < 0) return "size unknown"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb < 1.0) {
            (bytes / 1024.0).let { String.format(java.util.Locale.US, "%.1f KB", it) }
        } else {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        }
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        landmarker?.close()
        landmarker = null
    }

    override fun onDestroy() {
        stopLocalReceiver()
        stopDiscovery()
        stopCamera()
        executor.shutdown()
        super.onDestroy()
    }
}
