@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package by.zaberezh.forma.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val TABS = listOf("Сегодня", "Зал", "Еда", "Тело", "Отчёт", "Настройки")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8AB4F8), background = Color(0xFF111316), surface = Color(0xFF111316))) {
                Surface(Modifier.fillMaxSize()) {
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    Column(Modifier.safeDrawingPadding()) {
                        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                            TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                        }
                        when (tab) {
                            0 -> TodayScreen(onTab = { tab = it })
                            1 -> GymScreen()
                            2 -> FoodScreen()
                            3 -> BodyScreen()
                            4 -> ReportScreen()
                            else -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}
