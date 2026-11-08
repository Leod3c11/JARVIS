# JARVIS — Local Android Assistant

Assistente Android com interface Kotlin + Jetpack Compose e inferência local baseada em llama.cpp.

## Core 0.2 — LLM local

- Importação de modelos GGUF pelo seletor de documentos do Android.
- Modelo copiado para o armazenamento privado do aplicativo.
- Inferência nativa C++/JNI usando llama.cpp v0.9.0.
- Build inicial para Android ARM64 (arm64-v8a).
- Caminho inicial de inferência por CPU; aceleração GPU será validada em etapa separada.
- O APK não inclui pesos de modelos. O usuário fornece seu próprio arquivo GGUF.

### Como ativar

1. Instale o APK gerado pelo GitHub Actions.
2. Obtenha um modelo GGUF de instrução compatível com llama.cpp.
3. Abra o JARVIS e toque em **IMPORTAR MODELO GGUF**.
4. Selecione o arquivo .gguf e aguarde o carregamento.
5. Envie uma mensagem para testar a inferência offline.

Comece com um modelo pequeno e quantizado. O tamanho do modelo, o contexto e a memória disponível determinam se ele poderá ser carregado no aparelho.

## Segurança e limitações

- Não é necessário acesso à internet, microfone, acessibilidade ou root.
- O modelo é executado localmente; mensagens não são enviadas a um servidor.
- A saída do LLM é texto e nunca é executada como comando do sistema.
- A primeira versão não mantém histórico de contexto entre mensagens.
- O backend inicial é CPU-only; Vulkan/Mali e NPU não estão habilitados nem presumidos.

Consulte docs/ROADMAP.md e docs/SECURITY.md.
