package dev.coreblock

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val aicoreAuditor = AicoreForegroundEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = CoreBlockDatabase.getInstance(applicationContext).dao()

        setContent {
            MaterialTheme {
                CoreBlockDashboardScreen(
                    dao = dao,
                    aicoreAuditor = aicoreAuditor,
                    onOpenListenerSettings = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                )
            }
        }
    }
}

@Composable
fun CoreBlockDashboardScreen(
    dao: CoreBlockDao,
    aicoreAuditor: AicoreForegroundEngine,
    onOpenListenerSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var decisions by remember { mutableStateOf<List<DecisionLogEntity>>(emptyList()) }
    var listenerEnabled by remember {
        mutableStateOf(CoreBlockNotificationListener.isNotificationAccessGranted(context))
    }
    var suppressionEnabled by remember {
        mutableStateOf(SuppressionSettings.isEnabled(context))
    }

    LaunchedEffect(Unit) {
        listenerEnabled = CoreBlockNotificationListener.isNotificationAccessGranted(context)
        aicoreAuditor.runForegroundShadowAudit(dao)
        decisions = dao.getRecentDecisions(limit = 50)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Text(
                text = "CoreBlock — Pixel 11 Pro Firewall",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (!listenerEnabled) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Notification Listener access is required to filter promotions.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onOpenListenerSettings) {
                            Text("Grant Notification Access")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Cancel promotional notifications",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Off = dry-run: decisions are logged only, nothing is cancelled",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = suppressionEnabled,
                        onCheckedChange = { enabled ->
                            SuppressionSettings.setEnabled(context, enabled)
                            suppressionEnabled = enabled
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Decisions (${decisions.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            listenerEnabled = CoreBlockNotificationListener.isNotificationAccessGranted(context)
                            decisions = dao.getRecentDecisions(limit = 50)
                        }
                    }
                ) {
                    Text("Refresh")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(decisions, key = { it.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "${item.packageName} • ${item.verdict} (${(item.confidence * 100).toInt()}% via ${item.engineId}, ${item.latencyMs} ms)",
                                style = MaterialTheme.typography.labelMedium
                            )
                            if (item.needsUserReview) {
                                Text(
                                    text = "Needs Review: Gemini Nano foreground audit disagreed with LiteRT",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (item.actionTaken == "DRY_RUN") {
                                Text(
                                    text = "Dry-run: would have cancelled",
                                    color = MaterialTheme.colorScheme.tertiary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = item.title, style = MaterialTheme.typography.titleSmall)
                            Text(text = item.body, style = MaterialTheme.typography.bodySmall)
                            if (item.actionTaken == "CANCELLED" && !item.userUndone) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            CoreBlockNotificationListener.undoAndReplay(
                                                context = context,
                                                dao = dao,
                                                decision = item
                                            )
                                            decisions = dao.getRecentDecisions(limit = 50)
                                        }
                                    }
                                ) {
                                    Text("Undo Block & Open App")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
