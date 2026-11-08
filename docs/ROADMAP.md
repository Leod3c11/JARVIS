# Roadmap

## 0.1 — Fundação
- [x] Aplicativo Compose
- [x] Interface conversacional inicial
- [x] Snapshot básico de bateria e RAM
- [x] Pipeline GitHub Actions

## 0.2 — LLM local
- [x] Integração llama.cpp nativa por CMake/JNI (release v0.9.0 fixada)
- [x] Importar modelo GGUF pelo Storage Access Framework
- [x] Copiar o modelo para armazenamento privado do aplicativo
- [x] Carregar modelo local e gerar respostas por CPU
- [x] Limites iniciais de contexto, threads e tokens de saída
- [x] Streaming de tokens e cancelamento de geração
- [ ] Configurações editáveis de contexto, threads e amostragem
- [ ] Validar desempenho e estabilidade em dispositivos reais

## 0.5 — Rosto Matrix e Root Broker (implementado nesta versão)
- [x] Rosto interativo em chuva de glifos (piscar, olhar, boca por token, estados)
- [x] Root Broker com allowlist, validação, guardas térmica/bateria, snapshot e auditoria
- [x] Roteador de intenções em português com confirmação para ações que alteram o sistema
- [x] Boost opcional de CPU durante a geração
- [ ] Validar em aparelho real (governors/frequências por SoC) e ajustar nós específicos do fabricante

## 0.3 — Memória
- [ ] Room/SQLite
- [ ] Conversas persistentes
- [ ] Preferências explícitas e editáveis

## 0.4 — Voz
- [ ] STT offline
- [ ] TTS offline
- [ ] Wake word opcional com controle de bateria

## 0.5 — Android tools
- [ ] Intents e APIs Android
- [ ] Serviço de notificações com consentimento
- [x] Root broker com allowlist, validação, confirmação e auditoria (CPU/memória/térmica; GPU/devfreq pendentes)

## 0.6 — Otimização
- [ ] Benchmark CPU
- [ ] Benchmark Vulkan na Mali
- [ ] Avaliação de runtime NPU MediaTek
- [ ] Perfis de consumo e suspensão
