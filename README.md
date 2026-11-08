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

## Core 0.5 — Rosto Matrix e controle com root (opcional)

- **Rosto em código Matrix**: a tela inicial desenha um rosto com chuva de glifos. Ele pisca, o olhar
  acompanha o seu dedo, a boca se move com os tokens e a expressão muda entre
  *à disposição / ouvindo / pensando / respondendo*. Toque no rosto para chamar a atenção dele.
- **Botão Parar**: interrompe a geração em andamento (cancelamento nativo).
- **Root Broker**: com root autorizado (ex.: Magisk), o JARVIS pode aplicar perfis de CPU
  (Desempenho / Equilibrado / Economia), liberar memória, ler temperaturas e restaurar o estado original.
- **Comandos no chat**: “modo desempenho”, “modo economia”, “modo normal”, “liberar memória”,
  “temperatura do celular”, “estado da CPU”, “restaurar estado original”.
  Ações que alteram o sistema pedem confirmação.
- **Boost durante respostas** (opt-in em CONFIG): sobe a CPU só enquanto o modelo gera texto e restaura em seguida.
  É recusado se o aparelho estiver quente ou com bateria < 15%.

## Segurança e limitações

- Internet, microfone e acessibilidade não são necessários. Root é **opcional** e só é usado nas operações acima.
- O modelo é executado localmente; mensagens não são enviadas a um servidor.
- A saída do LLM é texto e nunca é executada como comando do sistema.
- Operações com root são uma lista fixa (`PrivilegedAction`); valores são validados contra o que o kernel anuncia.
- A primeira versão não mantém histórico de contexto entre mensagens.
- O backend inicial é CPU-only; Vulkan/Mali e NPU não estão habilitados nem presumidos.

Consulte docs/ROADMAP.md e docs/SECURITY.md.
