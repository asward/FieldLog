package io.github.asward.fieldlog.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.asward.fieldlog.entry.Shortcuts
import io.github.asward.fieldlog.vaults

sealed interface Screen {
    data object Home : Screen
    data class Project(val slug: String, val title: String) : Screen
    data class Entry(val path: String) : Screen
    data object Search : Screen
    data object Settings : Screen
    data object Speech : Screen
    /** `vaultId == null` adds a new vault. */
    data class VaultSetup(val vaultId: String?) : Screen
}

/** Simple back stack plus a counter screens watch to reload after changes. */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val version = mutableIntStateOf(0)
    val top: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun changed() { version.intValue++ }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vaults.currentId?.let { Shortcuts.publish(this, it) }
        setContent {
            FieldLogTheme {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nav?.changed()
    }

    private var nav: Nav? = null

    @Composable
    private fun App() {
        val nav = remember { Nav().also { this.nav = it } }
        BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
        when (val s = nav.top) {
            Screen.Home -> HomeScreen(nav)
            is Screen.Project -> ProjectScreen(nav, s.slug, s.title)
            is Screen.Entry -> EntryScreen(nav, s.path)
            Screen.Search -> SearchScreen(nav)
            Screen.Settings -> SettingsScreen(nav)
            Screen.Speech -> SpeechScreen(nav)
            is Screen.VaultSetup -> VaultSetupScreen(nav, s.vaultId)
        }
    }
}
