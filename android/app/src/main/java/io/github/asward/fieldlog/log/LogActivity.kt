package io.github.asward.fieldlog.log

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.asward.fieldlog.logEngine
import io.github.asward.fieldlog.media.Camera
import io.github.asward.fieldlog.ui.FieldLogTheme
import io.github.asward.fieldlog.ui.MainActivity
import io.github.asward.fieldlog.userMessage
import io.github.asward.fieldlog.vaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The log screen (spec §2–3). Opens a new log in the active project, resumes
 * the open one, or re-opens a committed log for editing. Usable over the lock
 * screen; listening continues after the screen locks.
 */
class LogActivity : ComponentActivity() {
    private val engine get() = logEngine
    private lateinit var camera: Camera
    private var cameraReady = false
    private val ready = mutableStateOf(false)
    private var snapWhenReady = false

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { afterPermissions() }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private val taker = PhotoTaker { onBytes, fail -> camera.takePhoto(onBytes, fail) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        camera = Camera(this, this)
        snapWhenReady = intent.getBooleanExtra(EXTRA_SNAP, false)

        setContent {
            FieldLogTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (!ready.value) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        LogScreen(engine, camera, onFinished = { finishToProject() })
                    }
                }
            }
        }
        open(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SNAP, false)) {
            if (cameraReady) engine.takePhoto() else snapWhenReady = true
        }
        if (!engine.isOpen) open(intent)
    }

    private fun open(intent: Intent) {
        val vaults = vaults
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val id = intent.getStringExtra(EXTRA_VAULT) ?: engine.vaultId ?: vaults.currentId
                        ?: error("Set up a vault first.")
                    vaults.get(id) ?: error("That vault no longer exists.")
                    val req = when (intent.getStringExtra(EXTRA_MODE)) {
                        MODE_EDIT -> OpenRequest.Edit(intent.getStringExtra(EXTRA_PATH) ?: error("No log to edit"))
                        MODE_RESUME -> OpenRequest.Resume
                        else -> {
                            val projects = vaults.open(id).projects()
                            val project = intent.getStringExtra(EXTRA_PROJECT)
                                ?: vaults.activeProject(id)?.takeIf { s -> projects.any { it.slug == s } }
                                ?: projects.firstOrNull()?.slug
                                ?: error("Create a project first.")
                            vaults.setActiveProject(id, project)
                            OpenRequest.New(project)
                        }
                    }
                    id to req
                }
            }
            val (id, req) = result.getOrElse { e ->
                Toast.makeText(this@LogActivity, e.userMessage(), Toast.LENGTH_LONG).show()
                startActivity(Intent(this@LogActivity, MainActivity::class.java))
                finish()
                return@launch
            }
            engine.open(id, req).onFailure { e ->
                if (!engine.isOpen) {
                    Toast.makeText(this@LogActivity, e.userMessage(), Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
                Toast.makeText(this@LogActivity, e.message ?: "A log is already open", Toast.LENGTH_LONG).show()
            }
            ready.value = true
            val needed = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                add(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.filterNot(::has)
            if (needed.isEmpty()) afterPermissions() else permissions.launch(needed.toTypedArray())
        }
    }

    private fun afterPermissions() {
        if (has(Manifest.permission.CAMERA)) bindCamera()
        if (has(Manifest.permission.RECORD_AUDIO) && engine.isOpen && !engine.micOn && engine.speechAvailable) {
            engine.startMic()
        }
    }

    private fun bindCamera() {
        camera.bind(
            onReady = {
                cameraReady = true
                if (engine.visible) engine.camera = taker
                if (snapWhenReady) {
                    snapWhenReady = false
                    engine.takePhoto()
                }
            },
            onError = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
        )
    }

    private fun finishToProject() {
        engine.acknowledgeSave()
        finish()
    }

    /** Volume up = snap, volume down = mic on/off. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (engine.isOpen && vaults.volumeKeys) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event.repeatCount == 0) engine.takePhoto()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.repeatCount == 0 && has(Manifest.permission.RECORD_AUDIO)) engine.toggleMic()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (engine.isOpen && vaults.volumeKeys &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        engine.visible = true
        if (cameraReady) engine.camera = taker
        LogNotifications.cancelAlerts(this)
    }

    override fun onPause() {
        engine.visible = false
        engine.camera = null
        super.onPause()
    }

    override fun onDestroy() {
        camera.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_VAULT = "vault"
        private const val EXTRA_PROJECT = "project"
        private const val EXTRA_SNAP = "snap"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_PATH = "path"
        private const val MODE_NEW = "new"
        private const val MODE_RESUME = "resume"
        private const val MODE_EDIT = "edit"

        private fun base(context: Context) = Intent(context, LogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** New log in [project] (default: the active project). Resumes an open log instead. */
        fun newLogIntent(context: Context, vaultId: String? = null, project: String? = null, snap: Boolean = false) =
            base(context).apply {
                putExtra(EXTRA_MODE, MODE_NEW)
                vaultId?.let { putExtra(EXTRA_VAULT, it) }
                project?.let { putExtra(EXTRA_PROJECT, it) }
                putExtra(EXTRA_SNAP, snap)
            }

        fun resumeIntent(context: Context, vaultId: String? = null) = base(context).apply {
            putExtra(EXTRA_MODE, MODE_RESUME)
            vaultId?.let { putExtra(EXTRA_VAULT, it) }
        }

        fun editIntent(context: Context, vaultId: String, path: String) = base(context).apply {
            putExtra(EXTRA_MODE, MODE_EDIT)
            putExtra(EXTRA_VAULT, vaultId)
            putExtra(EXTRA_PATH, path)
        }
    }
}
