# Roadmap

## 0.1 — Fundação
- [x] Aplicativo Compose
- [x] Interface conversacional inicial
- [x] Snapshot básico de bateria e RAM
- [x] Pipeline GitHub Actions

## 0.2 — LLM local
- [ ] Integrar llama.cpp como submódulo ou dependência nativa fixada em commit
- [ ] JNI bridge Kotlin/C++
- [ ] Importar modelo GGUF pelo Storage Access Framework
- [ ] Streaming de tokens e cancelamento
- [ ] Configuração de threads, contexto e limite de memória

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
- [ ] Root broker com allowlist, validação, confirmação e auditoria

## 0.6 — Otimização
- [ ] Benchmark CPU
- [ ] Benchmark Vulkan
- [ ] Avaliação de runtime NPU MediaTek
- [ ] Perfis de consumo e suspensão
