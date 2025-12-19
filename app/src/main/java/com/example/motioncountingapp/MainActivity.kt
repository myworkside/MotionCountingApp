package com.example.motioncountingapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var counterText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnReset: Button

    private var count = 0
    private var isCounting = false

    private var lastX = -1f
    private var lastY = -1f
    private var lastCountTime = 0L

    private lateinit var cameraExecutor: ExecutorService

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 101
        private const val MOVE_THRESHOLD = 40f   // movement sensitivity
        private const val COOLDOWN_MS = 800L     // anti double count
    }

    // ML Kit Object Detector
    private val detector by lazy {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build()
        ObjectDetection.getClient(options)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.livecamera)
        counterText = findViewById(R.id.countingtext)
        btnStart = findViewById(R.id.btnstart)
        btnStop = findViewById(R.id.btnstop)
        btnReset = findViewById(R.id.btnreset)

        cameraExecutor = Executors.newSingleThreadExecutor()

        btnStart.setOnClickListener { isCounting = true }
        btnStop.setOnClickListener { isCounting = false }
        btnReset.setOnClickListener {
            isCounting = false
            count = 0
            lastX = -1f
            lastY = -1f
            counterText.text = "0"
        }

        if (hasCameraPermission()) startCamera()
        else requestCameraPermission()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImage(imageProxy)
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        if (!isCounting) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        detector.process(image)
            .addOnSuccessListener { objects ->
                if (objects.isNotEmpty()) {
                    trackAndCount(objects[0])
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun trackAndCount(obj: DetectedObject) {
        val box = obj.boundingBox
        val centerX = box.centerX().toFloat()
        val centerY = box.centerY().toFloat()

        if (lastX >= 0 && lastY >= 0) {
            val distance = calculateDistance(lastX, lastY, centerX, centerY)

            val now = System.currentTimeMillis()
            if (distance > MOVE_THRESHOLD && now - lastCountTime > COOLDOWN_MS) {
                count++
                lastCountTime = now
                runOnUiThread {
                    counterText.text = count.toString()
                }
            }
        }

        lastX = centerX
        lastY = centerY
    }

    private fun calculateDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }
}
