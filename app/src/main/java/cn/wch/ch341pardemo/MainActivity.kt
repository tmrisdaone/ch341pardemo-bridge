package cn.wch.ch341pardemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import cn.wch.ch341pardemo.data.SettingsRepository
import cn.wch.ch341pardemo.ui.CH341App
import cn.wch.ch341pardemo.ui.theme.AppThemeMode
import cn.wch.ch341pardemo.ui.theme.CH341Theme

/**
 * Single Compose host. All UI lives in [CH341App] composable tree.
 * The activity is intentionally tiny — it just sets up the theme and
 * delegates to the navigation graph.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // super first on OEM ROMs (One UI 5, MIUI 13) — enableEdgeToEdge
        // touches the Window and SparklingDonutClass needs super.onCreate
        // to have run before the window exists.
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = SettingsRepository(applicationContext)
        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = AppThemeMode.System)
            CH341Theme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CH341App()
                }
            }
        }
    }
}
