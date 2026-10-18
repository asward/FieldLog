package io.github.asward.fieldlog.session

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/** In-app camera so "snap" captures immediately, without leaving the session. */
class Camera(private val context: Context, private val owner: LifecycleOwner) {
    val previewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private var capture: ImageCapture? = null
    private val worker = Executors.newSingleThreadExecutor()

    fun bind(onReady: () -> Unit = {}, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic)
                capture = ic
                onReady()
            } catch (e: Exception) {
                onError("Camera unavailable: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Capture, then deliver normalised JPEG bytes on a background thread. */
    fun takePhoto(onBytes: (ByteArray) -> Unit, fail: (String) -> Unit) {
        val ic = capture ?: return fail("Camera not ready")
        ic.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()
                    onBytes(Photos.normalize(bytes, rotation))
                } catch (e: Exception) {
                    image.close()
                    fail("Couldn't read photo: ${e.message}")
                }
            }

            override fun onError(exception: ImageCaptureException) {
                fail("Photo failed: ${exception.message}")
            }
        })
    }

    fun shutdown() {
        worker.shutdown()
    }
}
