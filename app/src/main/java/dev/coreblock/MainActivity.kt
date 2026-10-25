package dev.coreblock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var showAllowlist by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        listenerEnabled = CoreBlockNotificationListener.isNotificationAccessGranted(context)
        aicoreAuditor.runForegroundShadowAudit(dao)
        decisions = dao.getRecentDecisions(limit = 50)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        if (showAllowlist) {
            AllowlistScreen(
                dao = dao,
                onBack = { showAllowlist = false }
            )
            return@Scaffold
        }
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

            OutlinedButton(
                onClick = { showAllowlist = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Per-App Allowlist")
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

@Composable
fun AllowlistScreen(
    dao: CoreBlockDao,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(AllowlistSettings.getAllowed(context)) }
    var seenPackages by remember { mutableStateOf<List<String>>(emptyList()) }
    var launcherApps by remember { mutableStateOf<List<LauncherApp>>(emptyList()) }
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pickerQuery by remember { mutableStateOf("") }

    // PackageManager and Room IPC stay off the main thread so first composition
    // stays cheap on slow devices.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            seenPackages = dao.getDistinctLoggedPackages()
            launcherApps = queryLauncherApps(context)
        }
    }

    LaunchedEffect(allowed, seenPackages) {
        val missing = (allowed + seenPackages).filter { it !in labels }.distinct()
        if (missing.isEmpty()) return@LaunchedEffect
        val resolved = withContext(Dispatchers.IO) {
            missing.associateWith { resolveAppLabel(context, it) }
        }
        labels = labels + resolved
    }

    fun label(pkg: String): String = labels[pkg] ?: pkg

    fun addPackage(pkg: String) {
        if (pkg !in allowed) {
            allowed = allowed + pkg
            AllowlistSettings.setAllowed(context, allowed)
        }
    }

    fun removePackage(pkg: String) {
        if (pkg in allowed) {
            allowed = allowed - pkg
            AllowlistSettings.setAllowed(context, allowed)
        }
    }

    val seenCandidates = seenPackages.filter { it !in allowed }.sorted()
    val pickerCandidates = launcherApps
        .filter { it.packageName !in allowed }
        .filter {
            pickerQuery.isBlank() ||
                it.label.contains(pickerQuery, ignoreCase = true) ||
                it.packageName.contains(pickerQuery, ignoreCase = true)
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Per-App Allowlist", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Allowlisted apps are never suppressed and never logged.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(text = "Always-Allow Apps", style = MaterialTheme.typography.titleMedium)
            }
            if (allowed.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Nothing allowlisted — every app is eligible for filtering.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                items(allowed.sorted(), key = { it }) { pkg ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = label(pkg), style = MaterialTheme.typography.titleSmall)
                                Text(text = pkg, style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(onClick = { removePackage(pkg) }) {
                                Text("Remove")
                            }
                        }
                    }
                }
            }

            item {
                Text(text = "Apps We've Seen", style = MaterialTheme.typography.titleMedium)
            }
            if (seenCandidates.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Apps CoreBlock has evaluated will appear here for one-tap allowlisting.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                items(seenCandidates, key = { it }) { pkg ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = label(pkg), style = MaterialTheme.typography.titleSmall)
                                Text(text = pkg, style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(onClick = { addPackage(pkg) }) {
                                Text("Always Allow")
                            }
                        }
                    }
                }
            }

            item {
                Text(text = "Add Other App", style = MaterialTheme.typography.titleMedium)
            }
            item {
                OutlinedTextField(
                    value = pickerQuery,
                    onValueChange = { pickerQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Filter installed apps") }
                )
            }
            if (pickerCandidates.isEmpty()) {
                item {
                    Text(
                        text = "No matching apps.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                items(pickerCandidates, key = { it.packageName }) { app ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = app.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            OutlinedButton(onClick = { addPackage(app.packageName) }) {
                                Text("Add")
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        AllowlistSettings.resetToDefault(context)
                        allowed = RulesEngine.DEFAULT_ALLOWLIST
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reset to Default Allowlist")
                }
            }
        }
    }
}

private data class LauncherApp(val packageName: String, val label: String)

@Suppress("DEPRECATION") // queryIntentActivities(Intent, Int): the flags overload needs no flags here
private fun queryLauncherApps(context: Context): List<LauncherApp> {
    val pm = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcherIntent, 0)
        .asSequence()
        .mapNotNull { it.activityInfo?.applicationInfo }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .map { LauncherApp(it.packageName, it.loadLabel(pm).toString()) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        .toList()
}

private fun resolveAppLabel(context: Context, packageName: String): String =
    try {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(packageName, 0)
            .loadLabel(context.packageManager)
            .toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }
