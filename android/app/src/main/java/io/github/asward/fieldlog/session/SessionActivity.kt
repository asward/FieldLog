package io.github.asward.fieldlog.session

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import io.github.asward.fieldlog.entry.Shortcuts
import io.github.asward.fieldlog.ui.FieldLogTheme
import io.github.asward.fieldlog.ui.MainActivity
import io.github.asward.fieldlog.userMessage
import io.github.asward.fieldlog.vaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Session mode. Opens straight into listening on the sticky project: no save
 * button, no project choice per entry.
 */
open class SessionActivity : ComponentActivity() {
    private val controller = mutableStateOf<SessionController?>(null)
    private var snapWhenReady = false

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        controller.value?.let { c ->
            if (has(Manifest.permission.CAMERA)) bindCamera(c)
            c.onResume(has(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        snapWhenReady = intent.getBooleanExtra(EXTRA_SNAP, false)

        setContent {
            FieldLogTheme {
                Surface(Modifier.fillMaxSize()) {
                    val c = controller.value
                    if (c == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        SessionScreen(c, onClose = { finish() })
                    }
                }
            }
        }
        start()
    }

    private fun start() {
        val vaultId = intent.getStringExtra(EXTRA_VAULT) ?: vaults.currentId
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val id = vaultId ?: error("Set up a vault first.")
                    val rec = vaults.get(id) ?: error("That vault no longer exists.")
                    val vault = vaults.open(id)
                    val projects = vault.projects()
                    val project = intent.getStringExtra(EXTRA_PROJECT)
                        ?: vaults.stickyProject(id)?.takeIf { s -> projects.any { it.slug == s } }
                        ?: projects.firstOrNull()?.slug
                        ?: error("Create a project first.")
                    vaults.setStickyProject(id, project)
                    val title = projects.firstOrNull { it.slug == project }?.title ?: project
                    Triple(rec, title, vaults.newSession(id, project) to project)
                }
            }
            result.onFailure { e ->
                Toast.makeText(this@SessionActivity, e.userMessage(), Toast.LENGTH_LONG).show()
                startActivity(Intent(this@SessionActivity, MainActivity::class.java))
                finish()
            }.onSuccess { (rec, title, pair) ->
                val (session, project) = pair
                val c = SessionController(
                    this@SessionActivity, rec.id, rec.name, title, session,
                    onCommitted = { Shortcuts.publish(this@SessionActivity, rec.id) },
                )
                vaults.touchRecent(rec.id, project)
                controller.value = c
                val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                    .filterNot(::has)
                if (needed.isEmpty()) {
                    bindCamera(c)
                    c.onResume(true)
                } else {
                    permissions.launch(needed.toTypedArray())
                }
            }
        }
    }

    private fun bindCamera(c: SessionController) {
        c.camera.bind(
            onReady = {
                if (snapWhenReady) {
                    snapWhenReady = false
                    c.snap()
                }
            },
            onError = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
        )
    }

    override fun onResume() {
        super.onResume()
        controller.value?.onResume(has(Manifest.permission.RECORD_AUDIO))
    }

    override fun onPause() {
        controller.value?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        controller.value?.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VAULT = "vault"
        const val EXTRA_PROJECT = "project"
        const val EXTRA_SNAP = "snap"

        fun intent(context: Context, vaultId: String? = null, project: String? = null, snap: Boolean = false) =
            Intent(context, SessionActivity::class.java).apply {
                vaultId?.let { putExtra(EXTRA_VAULT, it) }
                project?.let { putExtra(EXTRA_PROJECT, it) }
                putExtra(EXTRA_SNAP, snap)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}
