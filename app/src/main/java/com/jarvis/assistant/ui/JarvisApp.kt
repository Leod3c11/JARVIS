package com.jarvis.assistant.ui

import android.graphics.Paint
import android.provider.OpenableColumns
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
import kotlin.math.min

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
        logger.record("model_restore", if (restored) "Modelo restaurado: ${engine.modelName}" else "Nenhum modelo restaurado")
        diagnosticText = logger.readAll()
    }

    Surface(Modifier.fillMaxSize(), color = Background) {
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                // Home: a identidade visual é o próprio robô de dados, não os controles do modelo.
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val robotHeight = (maxHeight * 0.30f).coerceIn(112.dp, 176.dp)
                    Box(
                        Modifier.fillMaxWidth().height(robotHeight).clip(RoundedCornerShape(22.dp))
                            .background(Color(0xFF07151D))
                            .clickable { robotTapped++ },
                        contentAlignment = Alignment.Center
                    ) {
                        MatrixRobot(Modifier.fillMaxSize(), active = busy || robotTapped % 2 == 1)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (busy) "PROCESSANDO" else "JARVIS", color = MatrixCyan,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                            Text(if (busy) "ANALISANDO SUA SOLICITAÇÃO" else "À SUA DISPOSIÇÃO",
                                color = Color(0xFF8EB5C5), fontSize = 9.sp, letterSpacing = 1.sp)
                        }
                    }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (busy) "Estou pensando..." else "Olá. Como posso ajudar?",
                        color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(5.dp))
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            val isUser = message.role == AssistantMessage.Role.USER
                            Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                                Surface(
                                    color = if (isUser) Color(0xFF123C52) else Panel,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.widthIn(max = 315.dp)
                                ) {
                                    Text(message.text, Modifier.padding(13.dp), color = Color.White, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp))
                            .padding(start = 14.dp, end = 5.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = input, onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 14.sp),
                            enabled = !busy && !importing,
                            decorationBox = { inner ->
                                Box {
                                    if (input.isEmpty()) Text("Converse com o JARVIS...", color = Color.Gray, fontSize = 14.sp)
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
                            Icon(Icons.Default.Send, contentDescription = "Enviar", tint = MatrixBlue)
                        }
                    }
                    if (busy) Text("Processamento local em andamento...", color = MatrixCyan, fontSize = 10.sp)
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
                            Text("Backend atual: CPU • Contexto: 2048 • Saída máxima: 128 tokens",
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

@Composable
private fun MatrixRobot(modifier: Modifier = Modifier, active: Boolean) {
    val transition = rememberInfiniteTransition(label = "matrix-rain")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "rain-phase"
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val centerX = w / 2f
        val centerY = h / 2f
        val green = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(38, 210, 164)
            textSize = 10f
            alpha = 120
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val native = drawContext.canvas.nativeCanvas
        val columns = (w / 15f).toInt().coerceAtLeast(1)
        for (col in 0 until columns) {
            val x = col * 15f
            val offset = ((col * 37f + phase * h * 1.7f) % (h + 50f)) - 25f
            for (row in 0..7) {
                val y = (offset + row * 14f) % (h + 20f)
                native.drawText(if ((col + row) % 2 == 0) "1" else "0", x, y, green)
            }
        }
        val scale = min(w / 280f, h / 205f)
        val headW = 104f * scale
        val headH = 92f * scale
        val top = centerY - headH * 0.75f
        val left = centerX - headW / 2f
        val lineColor = if (active) MatrixCyan else MatrixBlue
        val outline = Path().apply {
            moveTo(left + 15f * scale, top)
            lineTo(left + headW - 15f * scale, top)
            lineTo(left + headW, top + 18f * scale)
            lineTo(left + headW - 8f * scale, top + headH - 8f * scale)
            lineTo(centerX + 28f * scale, top + headH)
            lineTo(centerX + 20f * scale, top + headH + 12f * scale)
            lineTo(centerX - 20f * scale, top + headH + 12f * scale)
            lineTo(centerX - 28f * scale, top + headH)
            lineTo(left + 8f * scale, top + headH - 8f * scale)
            lineTo(left, top + 18f * scale)
            close()
        }
        drawPath(outline, lineColor, style = Stroke(width = 2f * scale))
        drawLine(lineColor, androidx.compose.ui.geometry.Offset(centerX, top - 17f * scale),
            androidx.compose.ui.geometry.Offset(centerX, top), strokeWidth = 2f * scale)
        drawCircle(MatrixCyan, radius = 4f * scale,
            center = androidx.compose.ui.geometry.Offset(centerX, top - 21f * scale))
        drawLine(lineColor, androidx.compose.ui.geometry.Offset(left - 18f * scale, top + 36f * scale),
            androidx.compose.ui.geometry.Offset(left + 5f * scale, top + 42f * scale), strokeWidth = 3f * scale)
        drawLine(lineColor, androidx.compose.ui.geometry.Offset(left + headW - 5f * scale, top + 42f * scale),
            androidx.compose.ui.geometry.Offset(left + headW + 18f * scale, top + 36f * scale), strokeWidth = 3f * scale)
        drawRoundRect(lineColor.copy(alpha = 0.75f), topLeft = androidx.compose.ui.geometry.Offset(centerX - 31f * scale, top + 35f * scale),
            size = androidx.compose.ui.geometry.Size(20f * scale, 9f * scale), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * scale))
        drawRoundRect(lineColor.copy(alpha = 0.75f), topLeft = androidx.compose.ui.geometry.Offset(centerX + 11f * scale, top + 35f * scale),
            size = androidx.compose.ui.geometry.Size(20f * scale, 9f * scale), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * scale))
        drawLine(MatrixCyan.copy(alpha = 0.8f), androidx.compose.ui.geometry.Offset(centerX - 15f * scale, top + 62f * scale),
            androidx.compose.ui.geometry.Offset(centerX + 15f * scale, top + 62f * scale), strokeWidth = 2f * scale)
        drawLine(lineColor.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(centerX, top + headH + 12f * scale),
            androidx.compose.ui.geometry.Offset(centerX, top + headH + 32f * scale), strokeWidth = 2f * scale)
        drawLine(lineColor.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(centerX - 43f * scale, top + headH + 32f * scale),
            androidx.compose.ui.geometry.Offset(centerX + 43f * scale, top + headH + 32f * scale), strokeWidth = 2f * scale)
    }
}
