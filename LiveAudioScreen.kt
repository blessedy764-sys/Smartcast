package com.gitco.smartcast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LiveAudioScreen(
    sessionTitle: String,
    hostName: String,
    connectedCount: Int,
    signalHealthText: String,
    selectedChannel: Int,
    onChannelSelected: (Int) -> Unit,
    onToggleRecord: () -> Unit,
    onRaiseHand: () -> Unit
) {
    var volume by remember { mutableStateOf(0.8f) }
    var isRecording by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Status Bar
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Badge(containerColor = Color.Red) {
                        Text("  🔴 LIVE (00:14:22)  ", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Text("👥 $connectedCount Connected", fontWeight = FontWeight.SemiGraphite)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(text = sessionTitle, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(text = "Host: $hostName", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)

                Spacer(modifier = Modifier.height(16.dp))

                // Waveform Visualizer Placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("~ ~ ~ 📈 Audio Waveform ~ ~ ~", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Channel Selector
                Text("🎧 Select Audio / Language Channel:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))

                ChannelOption(
                    title = "Channel 1: Original Audio (Guide)",
                    selected = selectedChannel == 1,
                    onClick = { onChannelSelected(1) }
                )
                ChannelOption(
                    title = "Channel 2: French Translation (AI)",
                    selected = selectedChannel == 2,
                    onClick = { onChannelSelected(2) }
                )
                ChannelOption(
                    title = "Channel 3: Spanish Translation (AI)",
                    selected = selectedChannel == 3,
                    onClick = { onChannelSelected(3) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Telemetry Health Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Signal Health: $signalHealthText",
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Bottom Controls: Volume & Actions
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            isRecording = !isRecording
                            onToggleRecord()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isRecording) "⏹️ Stop Record" else "🔴 Record Tour")
                    }
                    Button(
                        onClick = onRaiseHand,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("✋ Raise Hand")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text("Volume", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ChannelOption(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = title, fontSize = 14.sp)
    }
}

