package com.pelita.autocontinue.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pelita.autocontinue.core.AutomationConfig
import com.pelita.autocontinue.core.AutomationState
import com.pelita.autocontinue.core.GenerationState
import com.pelita.autocontinue.core.LogEntry
import com.pelita.autocontinue.core.Presence
import com.pelita.autocontinue.service.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppScreen(
    uiState: UiState,
    accessibilityEnabled: Boolean,
    showOnboarding: Boolean,
    onDismissOnboarding: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRefreshAccessibility: () -> Unit,
    onDelayChange: (Int) -> Unit,
    onMessageChange: (String) -> Unit,
    onTargetPackageChange: (String) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onCancelCountdown: () -> Unit,
    onRefreshDebug: () -> Unit,
    onClearLog: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }

    if (showOnboarding) {
        OnboardingDialog(
            onOpenSettings = {
                onDismissOnboarding()
                onOpenAccessibilitySettings()
            },
            onDismiss = onDismissOnboarding,
        )
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                text = "PELITA AUTO CONTINUE",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp),
            )

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }) {
                    Text("Home", modifier = Modifier.padding(12.dp))
                }
                Tab(
                    selected = tab == 1,
                    onClick = {
                        tab = 1
                        onRefreshDebug()
                    },
                ) {
                    Text("Debug", modifier = Modifier.padding(12.dp))
                }
            }

            when (tab) {
                0 -> HomeTab(
                    uiState = uiState,
                    accessibilityEnabled = accessibilityEnabled,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onRefreshAccessibility = onRefreshAccessibility,
                    onDelayChange = onDelayChange,
                    onMessageChange = onMessageChange,
                    onStart = onStart,
                    onPause = onPause,
                    onResume = onResume,
                    onStop = onStop,
                    onCancelCountdown = onCancelCountdown,
                    onClearLog = onClearLog,
                )

                else -> DebugTab(
                    uiState = uiState,
                    onRefresh = onRefreshDebug,
                    onTargetPackageChange = onTargetPackageChange,
                )
            }
        }
    }
}

@Composable
private fun OnboardingDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Izin Accessibility") },
        text = {
            Text(
                "Pelita Auto Continue membutuhkan Accessibility Service untuk membaca " +
                    "status UI ChatGPT dan melakukan input \"Lanjut\".\n\n" +
                    "Service ini hanya digunakan untuk automation tersebut. Aplikasi tidak " +
                    "membaca, menyimpan, atau mengirim isi percakapan Anda, dan tidak " +
                    "memiliki izin internet.\n\n" +
                    "Anda harus mengaktifkannya sendiri di Pengaturan Android. Aplikasi " +
                    "tidak akan mengaktifkannya secara otomatis.",
            )
        },
        confirmButton = {
            Button(onClick = onOpenSettings) { Text("Buka Accessibility Settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Nanti") }
        },
    )
}

@Composable
private fun HomeTab(
    uiState: UiState,
    accessibilityEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onRefreshAccessibility: () -> Unit,
    onDelayChange: (Int) -> Unit,
    onMessageChange: (String) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onCancelCountdown: () -> Unit,
    onClearLog: () -> Unit,
) {
    val state = uiState.automationState

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Status", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "● ",
                            color = state.indicatorColor(),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = state.headline(uiState.countdownSeconds),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (state == AutomationState.POST_RESPONSE_DELAY) {
                        Spacer(Modifier.height(8.dp))
                        Text("Mengirim \"${uiState.config.message}\" dalam:")
                        Text(
                            text = "${uiState.countdownSeconds}",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        OutlinedButton(onClick = onCancelCountdown) { Text("BATALKAN") }
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Accessibility", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = if (accessibilityEnabled) "ENABLED" else "DISABLED",
                        fontWeight = FontWeight.Bold,
                        color = if (accessibilityEnabled) Ok else Warn,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenAccessibilitySettings) {
                            Text("AKTIFKAN ACCESSIBILITY")
                        }
                        OutlinedButton(onClick = onRefreshAccessibility) { Text("CEK") }
                    }
                }
            }
        }

        item {
            SettingsCard(
                uiState = uiState,
                onDelayChange = onDelayChange,
                onMessageChange = onMessageChange,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onStart,
                    enabled = accessibilityEnabled && !uiState.isActive(),
                ) { Text("MULAI") }

                if (state == AutomationState.PAUSED) {
                    OutlinedButton(onClick = onResume) { Text("RESUME") }
                } else {
                    OutlinedButton(onClick = onPause, enabled = uiState.isActive()) {
                        Text("PAUSE")
                    }
                }

                OutlinedButton(onClick = onStop) { Text("STOP") }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Log aktivitas", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClearLog) { Text("Bersihkan") }
            }
        }

        items(uiState.log) { entry -> LogRow(entry) }
    }
}

@Composable
private fun SettingsCard(
    uiState: UiState,
    onDelayChange: (Int) -> Unit,
    onMessageChange: (String) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", style = MaterialTheme.typography.labelMedium)

            // Dragging is local state; the setting is only persisted on release
            // so a drag does not write preferences on every frame.
            var delay by remember(uiState.config.postResponseDelaySeconds) {
                mutableFloatStateOf(uiState.config.postResponseDelaySeconds.toFloat())
            }
            Text("Delay setelah response: ${delay.toInt()} detik")
            Slider(
                value = delay,
                onValueChange = { delay = it },
                onValueChangeFinished = { onDelayChange(delay.toInt()) },
                valueRange = AutomationConfig.MIN_DELAY_SECONDS.toFloat()..
                    AutomationConfig.MAX_DELAY_SECONDS.toFloat(),
            )

            var message by remember(uiState.config.message) {
                mutableStateOf(uiState.config.message)
            }
            OutlinedTextField(
                value = message,
                onValueChange = {
                    message = it
                    if (it.isNotBlank()) onMessageChange(it)
                },
                label = { Text("Pesan") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Target: ChatGPT (${uiState.config.targetPackage})")
            Text("Mode: MODE B - WAIT FOR RESPONSE")
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = TIME_FORMAT.format(Date(entry.timestampMs)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(text = entry.message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DebugTab(
    uiState: UiState,
    onRefresh: () -> Unit,
    onTargetPackageChange: (String) -> Unit,
) {
    val snapshot = uiState.lastSnapshot

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Accessibility Debug", style = MaterialTheme.typography.titleMedium)
            Text(
                "Gunakan halaman ini jika UI ChatGPT berubah dan automation berhenti " +
                    "mengenali tombol. Tidak ada isi percakapan yang ditampilkan atau disimpan.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item { Button(onClick = onRefresh) { Text("REFRESH") } }

        item { DebugRow("Package", snapshot?.foregroundPackage ?: "-") }
        item { DebugRow("ChatGPT foreground", snapshot?.targetForeground.label()) }
        item { DebugRow("Input", snapshot?.inputField.label()) }
        // NOT FOUND here is normal while the composer is empty: the ChatGPT app
        // shows a microphone until there is text to send.
        item { DebugRow("Send (empty = normal)", snapshot?.sendButton.label()) }
        item { DebugRow("Stop generating", snapshot?.stopGeneratingButton.label()) }
        item { DebugRow("Generating indicator", snapshot?.generatingIndicator.label()) }
        item { DebugRow("Input has text", snapshot?.inputHasText.label()) }
        item {
            DebugRow(
                "Generating",
                when (uiState.generationState) {
                    GenerationState.GENERATING -> "TRUE"
                    GenerationState.FINISHED -> "FALSE"
                    GenerationState.UNKNOWN -> "UNKNOWN"
                },
            )
        }
        item {
            DebugRow(
                "Response complete",
                when (uiState.generationState) {
                    GenerationState.FINISHED -> "TRUE"
                    GenerationState.GENERATING -> "FALSE"
                    GenerationState.UNKNOWN -> "UNKNOWN"
                },
            )
        }
        item { DebugRow("Automation state", uiState.automationState.name) }
        item {
            DebugRow(
                "Last message signature",
                snapshot?.lastMessageSignature ?: "-",
            )
        }

        item {
            var pkg by remember(uiState.config.targetPackage) {
                mutableStateOf(uiState.config.targetPackage)
            }
            OutlinedTextField(
                value = pkg,
                onValueChange = {
                    pkg = it
                    if (it.isNotBlank()) onTargetPackageChange(it.trim())
                },
                label = { Text("Target package") },
                supportingText = {
                    Text(
                        "Ubah jika baris \"Package\" di atas menunjukkan identifier lain " +
                            "ketika ChatGPT sedang terbuka.",
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ---------------------------------------------------------------------------

private val Ok = Color(0xFF2E7D32)
private val Warn = Color(0xFFB26A00)
private val Busy = Color(0xFF1565C0)
private val Off = Color(0xFF757575)
private val Bad = Color(0xFFC62828)

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun UiState.isActive(): Boolean =
    automationState != AutomationState.IDLE &&
        automationState != AutomationState.STOPPED &&
        automationState != AutomationState.ERROR

private fun Presence?.label(): String = when (this) {
    Presence.FOUND -> "FOUND"
    Presence.NOT_FOUND -> "NOT FOUND"
    Presence.UNKNOWN, null -> "UNKNOWN"
}

private fun AutomationState.headline(countdownSeconds: Int): String = when (this) {
    AutomationState.IDLE -> "IDLE"
    AutomationState.WAITING_FOR_CHATGPT -> "WAITING FOR CHATGPT"
    AutomationState.CHATGPT_GENERATING -> "GENERATING"
    AutomationState.CHATGPT_FINISHED -> "RESPONSE FINISHED"
    AutomationState.POST_RESPONSE_DELAY -> "WAITING ${countdownSeconds}s"
    AutomationState.FILLING_INPUT -> "TYPING"
    AutomationState.SENDING -> "SENDING"
    AutomationState.WAITING_FOR_NEXT_RESPONSE -> "WAITING FOR NEXT RESPONSE"
    AutomationState.PAUSED -> "PAUSED"
    AutomationState.STOPPED -> "STOPPED"
    AutomationState.ERROR -> "ERROR"
}

private fun AutomationState.indicatorColor(): Color = when (this) {
    AutomationState.IDLE, AutomationState.STOPPED -> Off
    AutomationState.PAUSED -> Warn
    AutomationState.ERROR -> Bad
    AutomationState.CHATGPT_GENERATING,
    AutomationState.WAITING_FOR_CHATGPT,
    AutomationState.WAITING_FOR_NEXT_RESPONSE,
    -> Busy

    else -> Ok
}
