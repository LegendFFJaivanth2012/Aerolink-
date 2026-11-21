package com.aerolink.phone

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var instruction: TextView
    private lateinit var telemetry: TextView
    private lateinit var trainButton: Button
    private var landmarker: HandLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("aerolink_v30", MODE_PRIVATE) }
    private var role = ""
    private var gesture = "PINCH"
    private var training = false
    private var samples = 0
    private var selectedUri: Uri? = null
    private var transferStarted = false
    private var receiverPolling = false
    private var lastGestureAt = 0L

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startCamera() }
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> selectedUri = uri; transferStarted = false; if (uri != null) { status.text = "FILE READY"; instruction.text = "Perform your trained $gesture gesture to send."; if (role == "sender") startCamera() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview); status = findViewById(R.id.status); instruction = findViewById(R.id.instruction); telemetry = findViewById(R.id.telemetry); trainButton = findViewById(R.id.trainGestures)
        gesture = prefs.getString("gesture", "PINCH") ?: "PINCH"
        findViewById<Button>(R.id.senderMode).setOnClickListener { chooseSender() }
        findViewById<Button>(R.id.receiverMode).setOnClickListener { chooseReceiver() }
        findViewById<Button>(R.id.selectFile).setOnClickListener { picker.launch("*/*") }
        findViewById<Button>(R.id.discover).setOnClickListener { startReceiver() }
        findViewById<Button>(R.id.cancel).setOnClickListener { reset() }
        trainButton.setOnClickListener { chooseGesture() }
    }

    private fun chooseGesture() {
        val items = arrayOf("Pinch", "Open palm", "Fist", "Two fingers", "Point")
        AlertDialog.Builder(this).setTitle("Choose your AeroLink gesture").setItems(items) { _, which ->
            gesture = listOf("PINCH", "OPEN_PALM", "FIST", "TWO_FINGERS", "POINT")[which]
            prefs.edit().putString("gesture", gesture).apply(); training = true; samples = 0
            instruction.text = "Perform ${items[which]} repeatedly. Samples: 0/12"; status.text = "TRAINING"; startCamera()
        }.show()
    }

    private fun chooseSender() {
        role = "sender"
        transferStarted = false
        receiverPolling = false
        findViewById<Button>(R.id.selectFile).visibility = Button.VISIBLE
        findViewById<Button>(R.id.discover).visibility = Button.GONE
        status.text = "SENDER READY"
        instruction.text = "Choose a file, then perform your trained $gesture gesture to send."
        telemetry.text = "Gesture recognition starts the transfer. No approval popup is used."
        stopCamera()
    }

    private fun chooseReceiver() {
        role = "receiver"; findViewById<Button>(R.id.selectFile).visibility = Button.GONE; findViewById<Button>(R.id.discover).visibility = Button.VISIBLE
        status.text = "RECEIVER READY"; instruction.text = "Turn on discoverable mode."; telemetry.text = "Gesture: $gesture"; stopCamera()
    }

    private fun startReceiver() { if (role != "receiver") return; status.text = "DISCOVERABLE"; instruction.text = "Perform your trained $gesture gesture."; telemetry.text = "Gesture detection active."; startCamera() }
    private fun reset() { role = ""; training = false; transferStarted = false; receiverPolling = false; selectedUri = null; stopCamera(); status.text = "READY"; instruction.text = "Choose SEND or RECEIVE."; telemetry.text = "AeroLink v0.30" }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { cameraPermission.launch(Manifest.permission.CAMERA); return }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ cameraProvider = future.get(); bindCamera(); createLandmarker() }, ContextCompat.getMainExecutor(this))
    }

    private fun createLandmarker() {
        landmarker?.close()
        val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
        val options = HandLandmarker.HandLandmarkerOptions.builder().setBaseOptions(base).setRunningMode(RunningMode.LIVE_STREAM).setNumHands(1).setMinHandDetectionConfidence(0.55f).setMinHandPresenceConfidence(0.55f).setMinTrackingConfidence(0.55f).setResultListener { result, _ -> onHand(result) }.build()
        landmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val previewUse = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { image -> val mp = BitmapImageBuilder(image.toBitmap()).build(); landmarker?.detectAsync(mp, SystemClock.uptimeMillis()); image.close() }
        provider.unbindAll(); provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUse, analysis)
    }

    private fun onHand(result: HandLandmarkerResult) {
        val hand = result.landmarks().firstOrNull() ?: return
        val thumb = hand[4]; val index = hand[8]
        val pinch = hypot((thumb.x() - index.x()).toDouble(), (thumb.y() - index.y()).toDouble()).toFloat()
        val extended = listOf(8,12,16,20).count { hand[it].y() < hand[it-2].y() }
        val detected = when (gesture) { "PINCH" -> pinch < 0.075f; "OPEN_PALM" -> extended >= 4; "FIST" -> extended <= 1; "TWO_FINGERS" -> extended == 2; else -> extended == 1 }
        runOnUiThread {
            if (training && detected) {
                samples++
                instruction.text = "Perform $gesture · Samples: ${samples.coerceAtMost(12)}/12"
                if (samples >= 12) finishTraining()
            } else if (!training && detected && System.currentTimeMillis() - lastGestureAt > 2500) {
                lastGestureAt = System.currentTimeMillis()
                if (role == "receiver" && !receiverPolling) {
                    receiverPolling = true
                    status.text = "CATCH DETECTED"
                    instruction.text = "Gesture recognized. Waiting for incoming file…"
                    lifecycleScope.launch(Dispatchers.IO) {
                        waitForFile()
                        withContext(Dispatchers.Main) {
                            receiverPolling = false
                        }
                    }
                } else if (role == "sender" && selectedUri != null && !transferStarted) {
                    transferStarted = true
                    status.text = "GESTURE DETECTED"
                    instruction.text = "Gesture recognized. Sending file…"
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { sendFile() }.onFailure { error ->
                            withContext(Dispatchers.Main) {
                                transferStarted = false
                                status.text = "SEND FAILED"
                                instruction.text = error.message ?: "Could not send file."
                            }
                        }
                    }
                }
            }
        }
    }

    private fun finishTraining() { training = false; prefs.edit().putLong("trainedAt", System.currentTimeMillis()).apply(); status.text = "GESTURE READY"; instruction.text = "$gesture is trained and ready to use."; telemetry.text = "12 successful calibration samples saved on this phone."; stopCamera() }

    private suspend fun sendFile() {
        val uri = selectedUri ?: return
        withContext(Dispatchers.Main) { status.text = "SENDING"; instruction.text = "Transferring your selected file…" }
        val name = displayName(uri); val size = fileSize(uri); if (size < 0L) error("Could not determine file size.")
        val chunk = 1024; val total = maxOf(1, ((size + chunk - 1) / chunk).toInt()); val id = UUID.randomUUID().toString().replace("-", "").take(16)
        contentResolver.openInputStream(uri)?.use { input -> for (seq in 0 until total) { val wanted = minOf(chunk.toLong(), size - seq.toLong()*chunk).toInt().coerceAtLeast(0); val data = ByteArray(wanted); var off=0; while(off<wanted){ val n=input.read(data,off,wanted-off); if(n<0) error("Unexpected end of file"); off+=n }; postChunk(id,seq,total,name,data); withContext(Dispatchers.Main){ status.text="SENDING · ${((seq+1)*100/total)}%" } } } ?: error("Could not open selected file.")
        withContext(Dispatchers.Main) { status.text="SENT"; instruction.text="Transfer complete."; transferStarted = false }
    }

    private suspend fun waitForFile() { repeat(180) { delay(900); val body=get("/api/status") ?: return@repeat; val j=JSONObject(body); if(j.optBoolean("complete")){ withContext(Dispatchers.Main){ status.text="FILE READY"; instruction.text="Received ${j.optString("name","file")}" }; return } } }
    private fun post(path: String, method: String) { val c=URL("http://192.168.4.1$path").openConnection() as HttpURLConnection; c.requestMethod=method; c.connectTimeout=2500; c.readTimeout=2500; c.responseCode; c.disconnect() }
    private fun get(path:String):String?=runCatching{ val c=URL("http://192.168.4.1$path").openConnection() as HttpURLConnection; c.connectTimeout=2500;c.readTimeout=2500;val s=c.inputStream.bufferedReader().use{it.readText()};c.disconnect();s }.getOrNull()
    private fun postChunk(id:String,seq:Int,total:Int,name:String,payload:ByteArray){ val b="AeroLink"+UUID.randomUUID().toString().replace("-",""); val p="--$b\r\nContent-Disposition: form-data; name=\"chunk\"; filename=\"part.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n"; val s="\r\n--$b--\r\n"; val q="id=${enc(id)}&seq=$seq&total=$total&name=${enc(name)}"; val c=URL("http://192.168.4.1/api/chunk?$q").openConnection() as HttpURLConnection;c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","multipart/form-data; boundary=$b");DataOutputStream(c.outputStream).use{it.write(p.toByteArray());it.write(payload);it.write(s.toByteArray())};val r=c.responseCode;c.disconnect();if(r !in 200..299)error("ESP32 HTTP $r") }
    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8")
    private fun fileSize(uri:Uri):Long=contentResolver.openAssetFileDescriptor(uri,"r")?.use{it.length}?:-1L
    private fun displayName(uri:Uri):String=uri.lastPathSegment?.substringAfterLast('/')?.replace("/","_")?:"aerolink-file.bin"
    private fun stopCamera(){cameraProvider?.unbindAll();cameraProvider=null;landmarker?.close();landmarker=null}
    override fun onDestroy(){stopCamera();executor.shutdown();super.onDestroy()}
}
