package com.jarvis.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.AssistantMessage
import com.jarvis.assistant.core.BootstrapAssistantEngine
import com.jarvis.assistant.platform.DeviceInfoProvider
import kotlinx.coroutines.launch

@Composable
fun JarvisApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val engine = remember { BootstrapAssistantEngine() }
    val deviceInfo = remember { DeviceInfoProvider(context).snapshot() }
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val messages = remember {
        mutableStateListOf(
            AssistantMessage(
                id = 1,
                role = AssistantMessage.Role.ASSISTANT,
                text = "JARVIS Core 0.1 inicializado. Estou pronto para receber sua primeira mensagem."
            )
        )
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF07111C)) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("J.A.R.V.I.S.", color = Color(0xFF54C8FF), fontSize = 26.sp)
                    Text("LOCAL ASSISTANT  •  CORE 0.1", color = Color(0xFF75E6D1), fontSize = 10.sp)
                }
                Surface(color = Color(0xFF123D3B), shape = RoundedCornerShape(50)) {
                    Text(" OFFLINE ", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = Color(0xFF75E6D1), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(18.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101F2D))) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("SISTEMA", color = Color(0xFF54C8FF), fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(deviceInfo.device, color = Color.White)
                    Text("Android ${deviceInfo.androidVersion}  •  Bateria: ${deviceInfo.batteryPercent?.let { "$it%" } ?: "indisponível"}",
                        color = Color(0xFFAAC2D5), fontSize = 12.sp)
                    Text("RAM livre: ${deviceInfo.availableRamMb?.let { "$it MB" } ?: "indisponível"}",
                        color = Color(0xFFAAC2D5), fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("CONVERSA", color = Color(0xFF54C8FF), fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    val isUser = message.role == AssistantMessage.Role.USER
                    Row(Modifier.fillMaxWidth(), horizontalArrangement =
                        if (isUser) Arrangement.End else Arrangement.Start) {
                        Surface(
                            color = if (isUser) Color(0xFF16415B) else Color(0xFF132536),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.widthIn(max = 310.dp)
                        ) {
                            Text(message.text, Modifier.padding(13.dp), color = Color.White)
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().background(Color(0xFF101F2D), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(color = Color.White),
                    enabled = !busy,
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) Text("Fale com o JARVIS...", color = Color.Gray)
                            inner()
                        }
                    }
                )
                IconButton(
                    enabled = input.isNotBlank() && !busy,
                    onClick = {
                        val prompt = input.trim()
                        input = ""
                        messages.add(AssistantMessage(
                            id = System.nanoTime(),
                            text = prompt,
                            role = AssistantMessage.Role.USER
                        ))
                        busy = true
                        scope.launch {
                            val answer = runCatching { engine.respond(prompt) }
                                .getOrElse { "Ocorreu um erro ao processar a mensagem." }
                            messages.add(AssistantMessage(
                                id = System.nanoTime(),
                                text = answer,
                                role = AssistantMessage.Role.ASSISTANT
                            ))
                            busy = false
                        }
                    }
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Enviar", tint = Color(0xFF54C8FF))
                }
            }
            if (busy) Text("Processando...", color = Color(0xFF75E6D1), fontSize = 11.sp)
        }
    }
}
