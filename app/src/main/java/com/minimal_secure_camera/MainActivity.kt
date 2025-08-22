package com.minimal_secure_camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.content.Intent
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import android.hardware.camera2.CaptureRequest
import java.util.concurrent.TimeUnit
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null
    private lateinit var previewView: PreviewView

    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var aspectRatio = AspectRatio.RATIO_4_3
    private var isoValue: Int? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)

        findViewById<ImageButton>(R.id.capture_button).setOnClickListener {
            takePhoto()
        }

        findViewById<ImageButton>(R.id.switch_camera_button).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            startCamera()
        }

        findViewById<ImageButton>(R.id.gallery_button).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        findViewById<ImageButton>(R.id.settings_button).setOnClickListener { view ->
            val menu = PopupMenu(this, view)
            menu.menu.add("Aspect 4:3")
            menu.menu.add("Aspect 16:9")
            menu.menu.add("ISO Auto")
            menu.menu.add("ISO 100")
            menu.menu.add("ISO 200")
            menu.menu.add("ISO 400")
            menu.menu.add("ISO 800")
            menu.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Aspect 4:3" -> aspectRatio = AspectRatio.RATIO_4_3
                    "Aspect 16:9" -> aspectRatio = AspectRatio.RATIO_16_9
                    "ISO Auto" -> isoValue = null
                    "ISO 100" -> isoValue = 100
                    "ISO 200" -> isoValue = 200
                    "ISO 400" -> isoValue = 400
                    "ISO 800" -> isoValue = 800
                }
                startCamera()
                true
            }
            menu.show()
        }

        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                camera?.cameraControl?.startFocusAndMetering(action)
            }
            true
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(baseContext, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val previewBuilder = Preview.Builder().setTargetAspectRatio(aspectRatio)
            isoValue?.let {
                Camera2Interop.Extender(previewBuilder)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it)
            }
            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageCaptureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(aspectRatio)
            isoValue?.let {
                Camera2Interop.Extender(imageCaptureBuilder)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it)
            }
            imageCapture = imageCaptureBuilder.build()

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(this, lensFacing, preview, imageCapture)
            } catch (e: Exception) {
                Log.e("MinimalSecureCamera", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/MinimalSecureCamera")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(baseContext, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                    Log.e("MinimalSecureCamera", "Photo capture failed", exc)
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = outputFileResults.savedUri ?: return

                    try {
                        val inputStream: InputStream = contentResolver.openInputStream(uri) ?: return
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream.close()

                        // Получение ориентации
                        val exif = ExifInterface(contentResolver.openInputStream(uri)!!)
                        val orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                        val rotatedBitmap = rotateBitmapIfRequired(bitmap, orientation)

                        val outputStream: OutputStream = contentResolver.openOutputStream(uri, "rwt") ?: return
                        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                        outputStream.close()

                        Toast.makeText(baseContext, "Saved without EXIF: $uri", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(baseContext, "Saved, but failed to remove EXIF", Toast.LENGTH_SHORT).show()
                        Log.e("MinimalSecureCamera", "Failed to remove EXIF", e)
                    }
                }
            }
        )
    }

    private fun rotateBitmapIfRequired(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
