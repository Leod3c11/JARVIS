package com.jarvis.assistant.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.AssistantMessage
import com.jarvis.assistant.core.LocalLlmAssistantEngine
import com.jarvis.assistant.platform.DeviceInfoProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun JarvisApp() {
    val context = LocalContext.current
    val engine = remember { LocalLlmAssistantEngine(context.applicationContext) }
    val deviceInfo = remember { DeviceInfoProvider(context).snapshot() }
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var modelStatus by remember { mutableStateOf("Verificando modelo local...") }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var performanceStatus by remember { mutableStateOf("Métricas aparecerão após a primeira resposta") }

    val messages = remember {
        mutableStateListOf(
            AssistantMessage(
                id = 1,
                role = AssistantMessage.Role.ASSISTANT,
                text = "JARVIS Core 0.2 inicializado. Importe um modelo GGUF para ativar a inteligência local."
            )
        )
    }

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                importMessage = "Copiando e carregando modelo..."
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val displayName = context.contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: "modelo.gguf"

                        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        require(safeName.endsWith(".gguf", ignoreCase = true)) {
                            "O arquivo selecionado não possui extensão .gguf."
                        }

                        val modelDirectory = File(context.filesDir, "models").apply { mkdirs() }
                        val target = File(modelDirectory, safeName)
                        val source = context.contentResolver.openInputStream(uri)
                            ?: error("Não foi possível abrir o arquivo selecionado.")

                        source.use { inputStream ->
                            target.outputStream().buffered().use { outputStream ->
                                inputStream.copyTo(outputStream, bufferSize = 1024 * 1024)
                            }
                        }
                        require(target.length() > 0L) { "O arquivo GGUF está vazio." }

                        val loadError = engine.loadModel(target.absolutePath)
                        if (loadError.isNotEmpty()) throw IllegalStateException(loadError)
                        target.name
                    }
                }

                result.onSuccess { name ->
                    modelStatus = "Modelo ativo: $name"
                    importMessage = "Modelo importado e carregado com sucesso."
                }.onFailure { error ->
                    modelStatus = "Nenhum modelo ativo"
                    importMessage = error.message ?: "Falha ao importar o modelo."
                }
                importing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        val restored = engine.restoreModel()
        modelStatus = if (restored) {
            "Modelo ativo: ${engine.modelName}"
        } else {
            "Nenhum modelo ativo"
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF07111C)) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("J.A.R.V.I.S.", color = Color(0xFF54C8FF), fontSize = 26.sp)
                    Text("LOCAL ASSISTANT  •  CORE 0.2", color = Color(0xFF75E6D1), fontSize = 10.sp)
                }
                Surface(color = Color(0xFF123D3B), shape = RoundedCornerShape(50)) {
                    Text(
                        " OFFLINE ",
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = Color(0xFF75E6D1),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101F2D))) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("SISTEMA", color = Color(0xFF54C8FF), fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(deviceInfo.device, color = Color.White)
                    Text(
                        "Android ${deviceInfo.androidVersion}  •  Bateria: ${deviceInfo.batteryPercent?.let { "$it%" } ?: "indisponível"}",
                        color = Color(0xFFAAC2D5),
                        fontSize = 12.sp
                    )
                    Text(
                        "RAM livre: ${deviceInfo.availableRamMb?.let { "$it MB" } ?: "indisponível"}",
                        color = Color(0xFFAAC2D5),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(modelStatus, color = Color(0xFF75E6D1), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { modelPicker.launch(arrayOf("*/*")) },
                        enabled = !importing && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (importing) "CARREGANDO MODELO..." else "IMPORTAR MODELO GGUF")
                    }
                    importMessage?.let {
                        Text(it, color = Color(0xFFAAC2D5), fontSize = 11.sp)
                    }
                    Text(
                        "Inferência local por CPU • Contexto 2048 • Saída máx. 128 tokens",
                        color = Color(0xFF7894A8),
                        fontSize = 10.sp
                    )
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
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
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
                Modifier.fillMaxWidth()
                    .background(Color(0xFF101F2D), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(color = Color.White),
                    enabled = !busy && !importing,
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) Text("Fale com o JARVIS...", color = Color.Gray)
                            inner()
                        }
                    }
                )
                IconButton(
                    enabled = input.isNotBlank() && !busy && !importing,
                    onClick = {
                        val prompt = input.trim()
                        input = ""
                        messages.add(
                            AssistantMessage(
                                id = System.nanoTime(),
                                text = prompt,
                                role = AssistantMessage.Role.USER
                            )
                        )
                        busy = true
                        scope.launch {
                            val answer = runCatching { engine.respond(prompt) }
                                .getOrElse { "Ocorreu um erro ao processar a mensagem: ${it.message}" }
                            performanceStatus = engine.lastMetrics.ifBlank { "Métricas indisponíveis" }
                            messages.add(
                                AssistantMessage(
                                    id = System.nanoTime(),
                                    text = answer,
                                    role = AssistantMessage.Role.ASSISTANT
                                )
                            )
                            busy = false
                        }
                    }
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Enviar", tint = Color(0xFF54C8FF))
                }
            }
            Text(performanceStatus, color = Color(0xFF7894A8), fontSize = 10.sp)
            if (busy) {
                Text("Gerando resposta local...", color = Color(0xFF75E6D1), fontSize = 11.sp)
            }
        }
    }
}
