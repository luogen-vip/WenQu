package com.wenqu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.wenqu.app.ui.ChatScreen
import com.wenqu.app.ui.QuickSearchScreen
import com.wenqu.app.ui.SettingsScreen
import com.wenqu.app.ui.WebViewScreen
import com.wenqu.app.ui.WenquTheme

private sealed interface Screen {
    data object Chat : Screen
    data object QuickSearch : Screen
    data object Settings : Screen
    data class Web(val url: String) : Screen
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ChatRepo.init(applicationContext)
        setContent {
            var themeMode by remember { mutableStateOf(Prefs.themeMode) }
            WenquTheme(themeMode) {
                AppRoot(onThemeChanged = {
                    Prefs.themeMode = it
                    themeMode = it
                })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { if (ChatRepo.isInitialized()) ChatRepo.voice.shutdown() } catch (_: Exception) {}
    }
}

private fun ChatRepo.isInitialized(): Boolean {
    return try {
        ChatRepo.voice != null
    } catch (e: Exception) {
        false
    }
}

@Composable
private fun AppRoot(onThemeChanged: (Int) -> Unit) {
    var screen by remember { mutableStateOf<Screen>(Screen.Chat) }
    when (val s = screen) {
        is Screen.Chat -> ChatScreen(
            onOpenQuick = { screen = Screen.QuickSearch },
            onOpenSettings = { screen = Screen.Settings },
            onOpenLink = { screen = Screen.Web(it) }
        )
        is Screen.QuickSearch -> QuickSearchScreen(
            onBack = { screen = Screen.Chat },
            onOpenLink = { screen = Screen.Web(it) }
        )
        is Screen.Settings -> SettingsScreen(
            onBack = { screen = Screen.Chat },
            onThemeChanged = onThemeChanged
        )
        is Screen.Web -> WebViewScreen(
            url = s.url,
            onClose = { screen = Screen.Chat }
        )
    }
}
