package com.aerolink.phone

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
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
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var instruction: TextView
    private lateinit var telemetry: TextView
    private lateinit var trainButton: Button
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("aerolink_v31", MODE_PRIVATE) }

    private var landmarker: HandLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var role = ""
    private var gesture = "PINCH"
    private var training = false
    private var samples = 0
    private var selectedUri: Uri? = null
    private var transferStarted = false
    private var receiverPolling = false
    private var lastGestureAt = 0L
    private var relayUrl = ""
    private var recipientId = ""

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera()
    }

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedUri = uri
        transferStarted = false
        if (uri != null) {
            status.text = "FILE READY"
            instruction.text = "Perform your trained " + gesture + " gesture to send."
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
        trainButton = findViewById(R.id.trainGestures)
        gesture = prefs.getString("gesture", "PINCH") ?: "PINCH"
        relayUrl = prefs.getString("relayUrl", "") ?: ""

        findViewById<Button>(R.id.senderMode).setOnClickListener { chooseSender() }
        findViewById<Button>(R.id.receiverMode).setOnClickListener { chooseReceiver() }
        findViewById<Button>(R.id.selectFile).setOnClickListener { picker.launch("*/*") }
        findViewById<Button>(R.id.discover).setOnClickListener { startReceiver() }
        findViewById<Button>(R.id.cancel).setOnClickListener { reset() }
        trainButton.setOnClickListener { chooseGesture() }

        telemetry.text = "AeroLink v0.31 · gesture command + network file transport"
    }

    private fun configureNetwork(done: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val relay = EditText(this).apply {
            hint = "Relay URL (https://...)"
            setSingleLine(true)
            setText(relayUrl)
        }
        val receiver = EditText(this).apply {
            hint = "Receiver AeroLink ID"
            setSingleLine(true)
            setText(recipientId)
        }
        box.addView(relay)
        if (role == "sender") box.addView(receiver)

        AlertDialog.Builder(this)
            .setTitle("AeroLink network")
            .setMessage("The ESP32s no longer carry file data. The app sends the file through the AeroLink relay.")
            .setView(box)
            .setPositiveButton("SAVE") { _, _ ->
                relayUrl = relay.text.toString().trim().trimEnd('/')
                recipientId = receiver.text.toString().trim().uppercase()
                prefs.edit().putString("relayUrl", relayUrl).apply()
                if (relayUrl.isBlank()) {
                    status.text = "RELAY URL NEEDED"
                    instruction.text = "Enter the AeroLink relay URL."
                } else if (role == "sender" && recipientId.isBlank()) {
                    status.text = "RECIPIENT NEEDED"
                    instruction.text = "Enter the receiver's AeroLink ID."
                } else {
                    done()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun chooseGesture() {
        val items = arrayOf("Pinch", "Open palm", "Fist", "Two fingers", "Point")
        AlertDialog.Builder(this).setTitle("Choose your AeroLink gesture").setItems(items) { _, which ->
            gesture = listOf("PINCH", "OPEN_PALM", "FIST", "TWO_FINGERS", "POINT")[which]
            prefs.edit().putString("gesture", gesture).apply()
            training = true
            samples = 0
            instruction.text = "Perform " + items[which] + " repeatedly. Samples: 0/12"
            status.text = "TRAINING"
            startCamera()
        }.show()
    }

    private fun chooseSender() {
        role = "sender"
        transferStarted = false
        findViewById<Button>(R.id.selectFile).visibility = Button.VISIBLE
        findViewById<Button>(R.id.discover).visibility = Button.GONE
        status.text = "SENDER READY"
        instruction.text = "Set the receiver ID, choose a file, then perform your gesture."
        telemetry.text = "Gesture starts the transfer. No approval popup. No ESP32 file chunks."
        stopCamera()
        configureNetwork {
            instruction.text = "Choose a file, then perform your trained " + gesture + " gesture."
        }
    }

    private fun chooseReceiver() {
        role = "receiver"
        findViewById<Button>(R.id.selectFile).visibility = Button.GONE
        findViewById<Button>(R.id.discover).visibility = Button.VISIBLE
        status.text = "RECEIVER READY"
        instruction.text = "Arm the receiver, then perform your gesture."
        telemetry.text = "Your AeroLink ID is generated automatically."
        stopCamera()
        if (relayUrl.isBlank()) configureNetwork {
            instruction.text = "Tap ARM RECEIVER, then perform your gesture."
        }
    }

    private fun startReceiver() {
        if (role != "receiver") return
        if (relayUrl.isBlank()) {
            configureNetwork { startReceiver() }
            return
        }
        status.text = "READY TO RECEIVE"
        instruction.text = "Perform your trained " + gesture + " gesture to arm the receiver."
        telemetry.text = "Receive window active on the AeroLink relay."
        startCamera()
    }

    private fun reset() {
        role = ""
        training = false
        transferStarted = false
        receiverPolling = false
        selectedUri = null
        findViewById<Button>(R.id.selectFile).visibility = Button.GONE
        findViewById<Button>(R.id.discover).visibility = Button.GONE
        stopCamera()
        status.text = "READY"
        instruction.text = "Choose SEND or RECEIVE."
        telemetry.text = "AeroLink v0.31"
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
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { image ->
            val mp = BitmapImageBuilder(image.toBitmap()).build()
            landmarker?.detectAsync(mp, SystemClock.uptimeMillis())
            image.close()
        }
        provider.unbindAll()
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUse, analysis)
    }

    private fun onHand(result: HandLandmarkerResult) {
        val hand = result.landmarks().firstOrNull() ?: return
        val thumb = hand[4]
        val index = hand[8]
        val pinch = hypot((thumb.x() - index.x()).toDouble(), (thumb.y() - index.y()).toDouble()).toFloat()
        val extended = listOf(8, 12, 16, 20).count { hand[it].y() < hand[it - 2].y() }
        val detected = when (gesture) {
            "PINCH" -> pinch < 0.075f
            "OPEN_PALM" -> extended >= 4
            "FIST" -> extended <= 1
            "TWO_FINGERS" -> extended == 2
            else -> extended == 1
        }

        runOnUiThread {
            if (training && detected) {
                samples++
                instruction.text = "Perform " + gesture + " · Samples: " + samples.coerceAtMost(12) + "/12"
                if (samples >= 12) finishTraining()
            } else if (!training && detected && System.currentTimeMillis() - lastGestureAt > 2500) {
                lastGestureAt = System.currentTimeMillis()

                if (role == "receiver" && !receiverPolling) {
                    receiverPolling = true
                    status.text = "GESTURE ARMED"
                    instruction.text = "Waiting for the sender…"
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { armAndReceive() }.onFailure { e ->
                            withContext(Dispatchers.Main) {
                                receiverPolling = false
                                status.text = "RECEIVE FAILED"
                                instruction.text = e.message ?: "Relay transfer failed."
                            }
                        }
                    }
                } else if (role == "sender" && selectedUri != null && !transferStarted) {
                    transferStarted = true
                    status.text = "GESTURE DETECTED"
                    instruction.text = "Sending through the AeroLink network…"
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { sendFile() }.onFailure { e ->
                            withContext(Dispatchers.Main) {
                                transferStarted = false
                                status.text = "SEND FAILED"
                                instruction.text = e.message ?: "Relay transfer failed."
                            }
                        }
                    }
                }
            }
        }
    }

    private fun finishTraining() {
        training = false
        prefs.edit().putLong("trainedAt", System.currentTimeMillis()).apply()
        status.text = "GESTURE READY"
        instruction.text = gesture + " is trained and ready to use."
        telemetry.text = "12 successful calibration samples saved on this phone."
        stopCamera()
    }

    private suspend fun sendFile() {
        if (relayUrl.isBlank()) error("Relay URL is not configured.")
        if (recipientId.isBlank()) error("Receiver AeroLink ID is not configured.")
        val uri = selectedUri ?: error("Choose a file first.")
        val boundary = "AeroLink-" + UUID.randomUUID()
        val name = displayName(uri)
        val size = fileSize(uri)
        if (size < 0) error("Could not determine file size.")

        val c = (URL(relayUrl + "/v1/transfers").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 30000
            setRequestProperty("X-AeroLink-Sender", deviceId())
            setRequestProperty("X-AeroLink-Recipient", recipientId)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
        }

        try {
            DataOutputStream(c.outputStream).use { out ->
                val safeName = name.replace(""", "_")
                val head = "--" + boundary + "\r\nContent-Disposition: form-data; name="file"; filename="" +
                    safeName + ""\r\nContent-Type: application/octet-stream\r\n\r\n"
                out.write(head.toByteArray())

                contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    var n: Int
                    while (input.read(buffer).also { n = it } != -1) {
                        out.write(buffer, 0, n)
                        sent += n
                        val pct = ((sent * 100) / size).toInt().coerceIn(0, 100)
                        withContext(Dispatchers.Main) { status.text = "UPLOADING · " + pct + "%" }
                    }
                } ?: error("Could not open selected file.")

                out.write("\r\n--".plus(boundary).plus("--\r\n").toByteArray())
            }

            val body = readResponse(c)
            if (c.responseCode !in 200..299) error("Relay HTTP " + c.responseCode + ": " + body)

            withContext(Dispatchers.Main) {
                status.text = "SENT"
                instruction.text = "Transfer complete."
                telemetry.text = "Delivered through AeroLink network."
                transferStarted = false
            }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun armAndReceive() {
        if (relayUrl.isBlank()) error("Relay URL is not configured.")

        val ready = (URL(relayUrl + "/v1/ready/" + deviceId()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 10000
        }
        try {
            if (ready.responseCode !in 200..299) error("Relay HTTP " + ready.responseCode)
        } finally {
            ready.disconnect()
        }

        repeat(120) {
            delay(1500)
            val c = (URL(relayUrl + "/v1/inbox/" + deviceId()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
            }
            try {
                val body = readResponse(c)
                if (c.responseCode !in 200..299) error("Relay HTTP " + c.responseCode + ": " + body)
                val j = JSONObject(body)
                if (j.optBoolean("available")) {
                    downloadFile(j.getString("transferId"), j.optString("name", "aerolink-file.bin"))
                    return
                }
            } finally {
                c.disconnect()
            }
        }
        error("Receive window expired. Perform the gesture again.")
    }

    private fun downloadFile(id: String, name: String) {
        val c = (URL(relayUrl + "/v1/transfers/" + id + "/file").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 30000
        }
        try {
            if (c.responseCode !in 200..299) error("Download HTTP " + c.responseCode + ": " + readResponse(c))

            val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "aerolink-file" }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/AeroLink")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create destination file.")

            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    c.inputStream.use { input -> input.copyTo(out, 64 * 1024) }
                } ?: error("Could not open destination file.")

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)

                runOnUiThread {
                    status.text = "RECEIVED"
                    instruction.text = "Received " + safe
                    telemetry.text = "Saved in Downloads/AeroLink."
                    receiverPolling = false
                }
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        } finally {
            c.disconnect()
        }
    }

    private fun readResponse(c: HttpURLConnection): String {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun deviceId(): String {
        val old = prefs.getString("deviceId", null)
        if (old != null) return old
        val id = UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
        prefs.edit().putString("deviceId", id).apply()
        return id
    }

    private fun fileSize(uri: Uri): Long =
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1

    private fun displayName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.replace("/", "_") ?: "aerolink-file.bin"

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        landmarker?.close()
        landmarker = null
    }

    override fun onDestroy() {
        stopCamera()
        executor.shutdown()
        super.onDestroy()
    }
}
