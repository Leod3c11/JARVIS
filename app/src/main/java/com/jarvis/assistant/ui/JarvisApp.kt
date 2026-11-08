package com.jarvis.assistant.ui

import android.provider.OpenableColumns
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.AssistantMessage
import com.jarvis.assistant.core.LocalLlmAssistantEngine
import com.jarvis.assistant.core.JarvisLogger
import com.jarvis.assistant.platform.DeviceInfoProvider
import com.jarvis.assistant.platform.DeviceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val MatrixCyan = Color(0xFF55E6D0)
private val MatrixBlue = Color(0xFF54C8FF)
private val Background = Color(0xFF050D13)
private val Panel = Color(0xFF0D1B25)

@Composable
fun JarvisApp() {
    val context = LocalContext.current
    val engine = remember { LocalLlmAssistantEngine(context.applicationContext) }
    val logger = remember { JarvisLogger(context.applicationContext) }
    var diagnosticText by remember { mutableStateOf("") }
    val provider = remember { DeviceInfoProvider(context.applicationContext) }
    var deviceInfo by remember { mutableStateOf(provider.snapshot()) }
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var modelStatus by remember { mutableStateOf("Verificando modelo local...") }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var performanceStatus by remember { mutableStateOf("Métricas aparecerão após a primeira resposta") }
    var backendStatus by remember { mutableStateOf("Verificando aceleradores...") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var robotTapped by remember { mutableIntStateOf(0) }

    val messages = remember {
        mutableStateListOf(
            AssistantMessage(
                id = 1,
                role = AssistantMessage.Role.ASSISTANT,
                text = "Olá. Sou o JARVIS. Estou aqui e pronto para conversar. Como posso ajudar?"
            )
        )
    }

    val logExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(logger.readAll()) }
                ?: error("Não foi possível criar o arquivo de log.")
            logger.record("log_export", "Arquivo de diagnóstico exportado.")
            diagnosticText = logger.readAll()
        }.onFailure { logger.record("log_export_failed", it.message ?: "erro", "ERROR") }
    }

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                importMessage = "Copiando e carregando modelo..."
                logger.record("model_import_started", "Importação de modelo iniciada.")
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val displayName = context.contentResolver.query(
                            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: "modelo.gguf"
                        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        require(safeName.endsWith(".gguf", ignoreCase = true)) {
                            "O arquivo selecionado não possui extensão .gguf."
                        }
                        val modelDirectory = File(context.filesDir, "models").apply { mkdirs() }
                        val target = File(modelDirectory, safeName)
                        context.contentResolver.openInputStream(uri)?.use { source ->
                            target.outputStream().buffered().use { output ->
                                source.copyTo(output, bufferSize = 1024 * 1024)
                            }
                        } ?: error("Não foi possível abrir o arquivo selecionado.")
                        require(target.length() > 0L) { "O arquivo GGUF está vazio." }
                        val loadError = engine.loadModel(target.absolutePath)
                        if (loadError.isNotEmpty()) throw IllegalStateException(loadError)
                        target.name
                    }
                }
                result.onSuccess { name ->
                    modelStatus = "Modelo ativo: $name"
                    backendStatus = engine.backendInfo
                    importMessage = "Modelo importado e carregado com sucesso."
                }.onFailure { error ->
                    modelStatus = "Nenhum modelo ativo"
                    importMessage = error.message ?: "Falha ao importar o modelo."
                    logger.record("model_load_failed", error.stackTraceToString(), "ERROR")
                }
                importing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        logger.record("app_started", "JARVIS Core 0.3 iniciado.")
        val restored = engine.restoreModel()
        modelStatus = if (restored) "Modelo ativo: ${engine.modelName}" else "Nenhum modelo ativo"
        backendStatus = engine.backendInfo
        logger.record("model_restore", if (restored) "Modelo restaurado: ${engine.modelName}" else "Nenhum modelo restaurado")
        diagnosticText = logger.readAll()
    }

    Surface(Modifier.fillMaxSize(), color = Background) {
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("J.A.R.V.I.S.", color = MatrixBlue, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("PERSONAL LOCAL INTELLIGENCE", color = MatrixCyan, fontSize = 9.sp, letterSpacing = 2.sp)
                }
                Surface(color = Color(0xFF10332F), shape = RoundedCornerShape(50)) {
                    Text("● OFFLINE", Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        color = MatrixCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(10.dp))
            if (selectedTab == 0) {
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("ASSISTENTE PESSOAL", color = Color(0xFF91A9B8),
                                fontSize = 9.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.SemiBold)
                            Text("Em que posso ajudar?", color = Color.White,
                                fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Surface(color = Color(0xFF102B2B), shape = RoundedCornerShape(10.dp)) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(MatrixCyan))
                                Spacer(Modifier.width(6.dp))
                                Text(if (busy) "PENSANDO" else "PRONTO", color = MatrixCyan,
                                    fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFF1A303B))
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            val isUser = message.role == AssistantMessage.Role.USER
                            Column(Modifier.fillMaxWidth(),
                                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                                Text(if (isUser) "VOCÊ" else "JARVIS",
                                    color = if (isUser) MatrixBlue else MatrixCyan,
                                    fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp))
                                Surface(
                                    color = if (isUser) Color(0xFF12384A) else Color(0xFF101D26),
                                    shape = RoundedCornerShape(
                                        topStart = 16.dp, topEnd = 16.dp,
                                        bottomStart = if (isUser) 16.dp else 5.dp,
                                        bottomEnd = if (isUser) 5.dp else 16.dp
                                    ),
                                    modifier = Modifier.widthIn(max = 340.dp)
                                ) {
                                    Text(message.text.ifBlank { if (busy && !isUser) " " else "" },
                                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        color = Color(0xFFE7F0F4), fontSize = 14.sp,
                                        lineHeight = 20.sp)
                                }
                            }
                        }
                        if (busy) {
                            item {
                                Text("JARVIS ESTÁ PROCESSANDO…", color = MatrixCyan,
                                    fontSize = 9.sp, letterSpacing = 1.sp,
                                    modifier = Modifier.padding(start = 5.dp))
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF101D26))
                            .padding(start = 15.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = input, onValueChange = { input = it },
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 42.dp)
                                .padding(vertical = 11.dp),
                            textStyle = LocalTextStyle.current.copy(
                                color = Color.White, fontSize = 14.sp, lineHeight = 19.sp
                            ),
                            enabled = !busy && !importing,
                            decorationBox = { inner ->
                                Box {
                                    if (input.isEmpty()) Text("Mensagem para o JARVIS…",
                                        color = Color(0xFF78909D), fontSize = 14.sp)
                                    inner()
                                }
                            }
                        )
                        IconButton(
                            enabled = input.isNotBlank() && !busy && !importing,
                            onClick = {
                                val prompt = input.trim()
                                input = ""
                                messages.add(AssistantMessage(id = System.nanoTime(), text = prompt,
                                    role = AssistantMessage.Role.USER))
                                busy = true
                                logger.record("inference_started", "chars=${prompt.length}")
                                val responseId = System.nanoTime()
                                messages.add(AssistantMessage(id = responseId, text = "", role = AssistantMessage.Role.ASSISTANT))
                                scope.launch {
                                    val startedAt = System.nanoTime()
                                    val result = runCatching {
                                        engine.respondStreaming(prompt) { token ->
                                            Handler(Looper.getMainLooper()).post {
                                                val index = messages.indexOfFirst { it.id == responseId }
                                                if (index >= 0) messages[index] = messages[index].copy(
                                                    text = messages[index].text + token
                                                )
                                            }
                                        }
                                    }
                                    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                                    val answer = result.getOrElse { error ->
                                        logger.record("inference_exception", error.stackTraceToString(), "ERROR")
                                        "Erro ao processar: ${error.message}"
                                    }
                                    val index = messages.indexOfFirst { it.id == responseId }
                                    if (index >= 0) messages[index] = messages[index].copy(text = answer)
                                    performanceStatus = engine.lastMetrics.ifBlank { "Métricas indisponíveis" }
                                    if (result.isSuccess) logger.record("inference_success", "tempo_total_ms=${elapsedMs}; ${performanceStatus}")
                                    else logger.record("inference_failed", "tempo_total_ms=${elapsedMs}; ${answer}", "ERROR")
                                    diagnosticText = logger.readAll()
                                    busy = false
                                }
                            }
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Enviar",
                                tint = if (input.isNotBlank() && !busy) MatrixCyan else Color(0xFF58717F))
                        }
                    }
                }
            } else if (selectedTab == 1) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        SettingsSectionTitle("INTELIGÊNCIA LOCAL")
                        InfoCard {
                            Text("MODELO GGUF", color = MatrixBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(7.dp))
                            Text(modelStatus, color = Color.White, fontSize = 13.sp)
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { modelPicker.launch(arrayOf("*/*")) },
                                enabled = !importing && !busy,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF124B59))
                            ) {
                                Text(if (importing) "CARREGANDO MODELO..." else "IMPORTAR / TROCAR MODELO")
                            }
                            importMessage?.let {
                                Text(it, color = MatrixCyan, fontSize = 11.sp)
                            }
                            Text("Os arquivos GGUF ficam no armazenamento privado do JARVIS.",
                                color = Color(0xFF8EA6B6), fontSize = 10.sp)
                        }
                    }
                    item {
                        SettingsSectionTitle("DESEMPENHO DO MODELO")
                        InfoCard {
                            Text(performanceStatus, color = Color.White, fontSize = 12.sp)
                            Spacer(Modifier.height(5.dp))
                            Text("Backend detectado: $backendStatus",
                                color = MatrixCyan, fontSize = 10.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("GPU Vulkan automática quando disponível • CPU ARM/KleidiAI como base e fallback • 4–6 threads • Contexto: 1536",
                                color = Color(0xFF8EA6B6), fontSize = 10.sp)
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingsSectionTitle("MAPEAMENTO DO DISPOSITIVO")
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { deviceInfo = provider.snapshot() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Atualizar diagnóstico", tint = MatrixCyan)
                            }
                        }
                        InfoCard {
                            HardwareRow("Dispositivo", deviceInfo.device)
                            HardwareRow("SoC / plataforma", deviceInfo.soc)
                            HardwareRow("Android", deviceInfo.androidVersion)
                            HardwareRow("Arquitetura", deviceInfo.abis)
                            HardwareRow("Núcleos de CPU", deviceInfo.cpuCores.toString())
                            HardwareRow("RAM total", "${deviceInfo.totalRamMb} MB")
                            HardwareRow("RAM disponível", "${deviceInfo.availableRamMb ?: "indisponível"} MB")
                            HardwareRow("OpenGL ES", deviceInfo.openGles)
                            HardwareRow("Vulkan", deviceInfo.vulkan)
                            HardwareRow("Sensores", "${deviceInfo.sensorCount} detectados")
                            HardwareRow("Estado térmico", deviceInfo.thermal)
                            HardwareRow("Bateria", deviceInfo.batteryPercent?.let { "$it%" } ?: "indisponível")
                            Spacer(Modifier.height(8.dp))
                            Text("GPU, NPU/APU, drivers, frequências e interfaces de controle avançadas serão investigados em uma etapa específica. A ausência de uma interface pública não significa ausência do hardware.",
                                color = Color(0xFF8EA6B6), fontSize = 10.sp)
                        }
                    }
                    item {
                        SettingsSectionTitle("PRIVACIDADE E SEGURANÇA")
                        InfoCard {
                            Text("O processamento do modelo é local. Recursos privilegiados, como root e interfaces de hardware, só serão adicionados com permissões explícitas, validação e registro das operações.",
                                color = Color(0xFFB4C9D5), fontSize = 12.sp)
                        }
                    }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsSectionTitle("DIAGNÓSTICO E LOGS")
                    Text("Os registros são mantidos localmente. O conteúdo das suas perguntas e respostas não é gravado por padrão.",
                        color = Color(0xFF8EA6B6), fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { diagnosticText = logger.readAll() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF124B59))) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(5.dp)); Text("ATUALIZAR")
                        }
                        Button(onClick = { logExporter.launch("jarvis-diagnostico.txt") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF124B59))) {
                            Text("EXPORTAR .TXT")
                        }
                    }
                    Text("${logger.entryCount()} eventos registrados • limite local de 1 MB",
                        color = MatrixCyan, fontSize = 10.sp)
                    Surface(Modifier.weight(1f).fillMaxWidth(), color = Panel, shape = RoundedCornerShape(14.dp)) {
                        Text(diagnosticText.lineSequence().toList().takeLast(180).joinToString("\n").ifBlank { "Nenhum evento registrado ainda." },
                            Modifier.fillMaxSize().padding(10.dp), color = Color(0xFFB4C9D5), fontSize = 9.sp,
                            lineHeight = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = Color(0xFF17303D))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                NavigationItem("JARVIS", selectedTab == 0, { selectedTab = 0 }, Icons.Default.Chat)
                NavigationItem("CONFIG", selectedTab == 1, { selectedTab = 1 }, Icons.Default.Settings)
                NavigationItem("LOGS", selectedTab == 2, { selectedTab = 2; diagnosticText = logger.readAll() }, Icons.Default.BugReport)
            }
        }
    }
}

@Composable
private fun NavigationItem(label: String, selected: Boolean, onClick: () -> Unit,
                           icon: androidx.compose.ui.graphics.vector.ImageVector) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, tint = if (selected) MatrixCyan else Color(0xFF7894A8))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (selected) MatrixCyan else Color(0xFF7894A8),
            fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(title, color = MatrixCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), content = content)
    }
}

@Composable
private fun HardwareRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFF8EA6B6), fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

