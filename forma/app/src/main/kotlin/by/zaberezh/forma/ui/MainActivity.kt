package by.zaberezh.forma.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.R

private data class Tab(val title: String, val icon: Int)

private val TABS = listOf(
    Tab("Сегодня", R.drawable.nav_today),
    Tab("Зал", R.drawable.nav_gym),
    Tab("Турник", R.drawable.nav_turnik),
    Tab("Сон", R.drawable.nav_sleep),
    Tab("Еда", R.drawable.nav_food),
    Tab("Тело", R.drawable.nav_body),
    Tab("Отчёт", R.drawable.nav_report),
)
private const val SETTINGS_TAB = 7

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = Scheme) {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                BackHandler(enabled = tab != 0) { tab = 0 }
                Scaffold(
                    containerColor = C.bg,
                    topBar = {
                        Row(
                            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(start = 20.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (tab == SETTINGS_TAB) "Настройки" else TABS[tab].title,
                                Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            )
                            IconButton(onClick = { tab = if (tab == SETTINGS_TAB) 0 else SETTINGS_TAB }) {
                                Icon(painterResource(R.drawable.ic_settings), "Настройки", tint = if (tab == SETTINGS_TAB) C.accent else C.muted)
                            }
                        }
                    },
                    bottomBar = {
                        // своя компактная панель: 7 вкладок помещаются на любом экране
                        Row(
                            Modifier.fillMaxWidth().background(C.card).navigationBarsPadding().height(64.dp).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TABS.forEachIndexed { i, t ->
                                val sel = tab == i
                                Column(
                                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                                        .clickable { tab = i }.padding(vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                                ) {
                                    Box(
                                        Modifier.clip(RoundedCornerShape(10.dp)).background(if (sel) C.accent.copy(alpha = 0.16f) else C.card)
                                            .padding(horizontal = 10.dp, vertical = 3.dp),
                                    ) { Icon(painterResource(t.icon), t.title, Modifier.size(22.dp), tint = if (sel) C.accent else C.muted) }
                                    Text(t.title, fontSize = 10.sp, maxLines = 1, color = if (sel) C.accent else C.muted)
                                }
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad).imePadding()) {
                        Column {
                            when (tab) {
                                0 -> TodayScreen(onTab = { tab = it })
                                1 -> GymScreen()
                                2 -> TurnikScreen()
                                3 -> SleepScreen()
                                4 -> FoodScreen()
                                5 -> BodyScreen()
                                6 -> ReportScreen()
                                else -> SettingsScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}
