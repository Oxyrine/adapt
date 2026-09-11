package com.popgamma.tutor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class Screen { PROFILE, CHAT, COMPARE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorApp(viewModel: TutorViewModel, micGranted: Boolean, onRequestMicPermission: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var screen by remember { mutableStateOf(Screen.PROFILE) }
    var textInput by remember { mutableStateOf("") }

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Adaptive Tutor Tone Demo") }) }) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                when (screen) {
                    Screen.PROFILE -> ProfileScreen(
                        onPick = { perf, reg ->
                            viewModel.selectProfile(perf, reg)
                            screen = Screen.CHAT
                        },
                        onCompare = { screen = Screen.COMPARE },
                        canCompare = state.snapshots.size >= 1
                    )
                    Screen.CHAT -> ChatScreen(
                        state = state,
                        micGranted = micGranted,
                        onRequestMicPermission = onRequestMicPermission,
                        textInput = textInput,
                        onTextChange = { textInput = it },
                        onSend = {
                            viewModel.sendText(textInput)
                            textInput = ""
                        },
                        onToggleVoice = viewModel::toggleVoiceMode,
                        onStartVoiceTurn = viewModel::startVoiceTurn,
                        onStopVoiceTurn = viewModel::stopVoiceTurnAndScore,
                        onToggleOffline = viewModel::toggleOfflineMode,
                        onSnapshot = viewModel::snapshotCurrentProfile,
                        onBackToProfiles = { screen = Screen.PROFILE },
                        onDismissError = viewModel::dismissError
                    )
                    Screen.COMPARE -> CompareScreen(
                        snapshots = state.snapshots,
                        onBack = { screen = Screen.PROFILE }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(
    onPick: (Performance, Regularity) -> Unit,
    onCompare: () -> Unit,
    canCompare: Boolean
) {
    Text("Pick a simulated student", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    val combos = listOf(
        Performance.STRONG to Regularity.CONSISTENT,
        Performance.STRONG to Regularity.GAPPED,
        Performance.STRUGGLING to Regularity.CONSISTENT,
        Performance.STRUGGLING to Regularity.GAPPED,
    )
    combos.forEach { (perf, reg) ->
        Button(
            onClick = { onPick(perf, reg) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Text("${perf.name} / ${reg.name}")
        }
    }
    Spacer(Modifier.height(24.dp))
    if (canCompare) {
        OutlinedButton(onClick = onCompare, modifier = Modifier.fillMaxWidth()) {
            Text("Compare snapshots")
        }
    }
}

// ColumnScope receiver: the message LazyColumn below uses Modifier.weight() to fill remaining
// height, which is only resolvable with a ColumnScope in scope -- ChatScreen is always called
// from inside TutorApp's Column{}, which provides it.
@Composable
private fun ColumnScope.ChatScreen(
    state: TutorUiState,
    micGranted: Boolean,
    onRequestMicPermission: () -> Unit,
    textInput: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleVoice: (Boolean) -> Unit,
    onStartVoiceTurn: (BankQuestion) -> Unit,
    onStopVoiceTurn: () -> Unit,
    onToggleOffline: (Boolean) -> Unit,
    onSnapshot: () -> Unit,
    onBackToProfiles: () -> Unit,
    onDismissError: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBackToProfiles) { Text("< Profiles") }
        Spacer(Modifier.weight(1f, fill = true))
        Text("${state.performance} / ${state.regularity}", style = MaterialTheme.typography.labelLarge)
    }

    state.error?.let { err ->
        Card(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(err, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onDismissError) { Text("Dismiss") }
            }
        }
    }

    MetricsStrip(state)
    Spacer(Modifier.height(8.dp))

    LazyColumn(Modifier.weight(1f, fill = true)) {
        items(state.messages) { msg ->
            Text(
                text = (if (msg.fromAlbert) "Albert: " else "Student: ") + msg.text,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Voice mode")
        Switch(checked = state.voiceMode, onCheckedChange = onToggleVoice)
        Spacer(Modifier.weight(1f, fill = true))
        Text("Offline sample")
        Switch(checked = state.offlineMode, onCheckedChange = onToggleOffline)
    }

    if (state.voiceMode) {
        VoiceInputRow(
            state = state,
            micGranted = micGranted,
            onRequestMicPermission = onRequestMicPermission,
            onStartVoiceTurn = onStartVoiceTurn,
            onStopVoiceTurn = onStopVoiceTurn
        )
    } else {
        Row {
            OutlinedTextField(
                value = textInput,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f, fill = true),
                placeholder = { Text("Type your answer...") }
            )
            Button(onClick = onSend, enabled = textInput.isNotBlank() && !state.busy) { Text("Send") }
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onSnapshot, modifier = Modifier.fillMaxWidth()) {
        Text("Snapshot this profile's metrics")
    }
}

@Composable
private fun VoiceInputRow(
    state: TutorUiState,
    micGranted: Boolean,
    onRequestMicPermission: () -> Unit,
    onStartVoiceTurn: (BankQuestion) -> Unit,
    onStopVoiceTurn: () -> Unit
) {
    if (!micGranted) {
        Button(onClick = onRequestMicPermission) { Text("Grant microphone access") }
        return
    }
    Column {
        Text("Question bank", style = MaterialTheme.typography.labelLarge)
        QuestionBank.questions.forEach { q ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(q.prompt, Modifier.weight(1f, fill = true))
                when {
                    state.micState == MicState.RECORDING && state.currentBankQuestion == q ->
                        // Mic auto-armed the instant this question was asked (Finding 3) --
                        // Done just stops the clip, it doesn't start the measurement.
                        Button(onClick = onStopVoiceTurn) { Text("Done") }
                    state.micState == MicState.IDLE ->
                        Button(onClick = { onStartVoiceTurn(q) }, enabled = !state.busy) { Text("Ask") }
                    else -> {}
                }
            }
        }
        if (state.micState == MicState.PROCESSING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Scoring answer...")
        }
    }
}

@Composable
private fun MetricsStrip(state: TutorUiState) {
    val m = state.sessionMetrics
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text("Live metrics", style = MaterialTheme.typography.titleSmall)
            Text("Turns: ${m.turns}   Avg words/turn: ${"%.1f".format(m.avgWordsPerTurn)}")
            Text("Scaffolding steps: ${m.totalScaffolding}   Jokes/tangents: ${m.totalJokes}")
            Text("Praise markers: ${m.totalPraise}   (avg/turn: ${"%.2f".format(m.avgPraisePerTurn)})")
            state.lastConfidenceReadout?.let { r ->
                Spacer(Modifier.height(4.dp))
                // Raw transcript shown deliberately -- see plan's diagnostic order: check the
                // transcript before suspecting the scorer, and it's the demo's answer to
                // "how do you know it isn't judging the kid" (Caveat 1).
                Text("Last answer: \"${r.transcript}\"")
                Text("Latency ${r.signals.latencyMs}ms   Hedge rate ${"%.2f".format(r.signals.hedgeRate)}   Self-corrections ${r.signals.selfCorrections}")
                Text("Band: ${r.band}   Correct: ${r.correct}   Follow-up triggered: ${r.triggeredFollowUp}")
                Text(
                    "Thresholds -- latency > ${ConfidenceThresholds.LATENCY_MS}ms, " +
                        "hedge > ${ConfidenceThresholds.HEDGE_RATE}, " +
                        "corrections > ${ConfidenceThresholds.SELF_CORRECTIONS}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun CompareScreen(snapshots: List<MetricsSnapshot>, onBack: () -> Unit) {
    TextButton(onClick = onBack) { Text("< Back") }
    Text("Compare snapshots", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth()) {
        snapshots.forEach { snap ->
            Card(
                Modifier
                    .weight(1f, fill = true)
                    .padding(4.dp)
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text(snap.label, style = MaterialTheme.typography.titleSmall)
                    Text("Turns: ${snap.metrics.turns}")
                    Text("Avg words/turn: ${"%.1f".format(snap.metrics.avgWordsPerTurn)}")
                    Text("Scaffolding steps: ${snap.metrics.totalScaffolding}")
                    Text("Jokes/tangents: ${snap.metrics.totalJokes}")
                    Text("Praise markers: ${snap.metrics.totalPraise}")
                    Text("Avg praise/turn: ${"%.2f".format(snap.metrics.avgPraisePerTurn)}")
                }
            }
        }
    }
}
