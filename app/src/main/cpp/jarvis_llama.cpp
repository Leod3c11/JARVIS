#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <iomanip>
#include <sstream>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {
constexpr const char * TAG = "JARVIS-LLM";
std::mutex g_mutex;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
bool g_backend_initialized = false;
std::string g_last_metrics;

void unload_locked() {
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

jstring java_string(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(256);
    int32_t length = llama_token_to_piece(
        vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true
    );
    if (length < 0) {
        buffer.resize(static_cast<size_t>(-length));
        length = llama_token_to_piece(
            vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true
        );
    }
    if (length <= 0) return {};
    return std::string(buffer.data(), static_cast<size_t>(length));
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jarvis_assistant_core_LocalLlmAssistantEngine_nativeLoadModel(
        JNIEnv * env, jobject, jstring model_path, jint context_size, jint threads) {
    if (model_path == nullptr) return java_string(env, "Caminho do modelo vazio.");

    const char * path_chars = env->GetStringUTFChars(model_path, nullptr);
    if (!path_chars) return java_string(env, "Não foi possível ler o caminho do modelo.");
    const std::string path(path_chars);
    env->ReleaseStringUTFChars(model_path, path_chars);

    std::lock_guard<std::mutex> lock(g_mutex);
    unload_locked();

    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU fallback until GPU support is validated on-device.
    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (!g_model) {
        return java_string(env, "Não foi possível abrir o GGUF. Verifique se o arquivo está íntegro e é compatível.");
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(std::clamp(context_size, 512, 4096));
    context_params.n_batch = 512;
    context_params.n_ubatch = 512;
    context_params.n_threads = std::clamp(threads, 1, 8);
    context_params.n_threads_batch = std::clamp(threads, 1, 8);
    context_params.no_perf = true;

    g_context = llama_init_from_model(g_model, context_params);
    if (!g_context) {
        unload_locked();
        return java_string(env, "Não foi possível reservar memória para o contexto. Tente um modelo menor.");
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "GGUF loaded: %s", path.c_str());
    return java_string(env, "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jarvis_assistant_core_LocalLlmAssistantEngine_nativeGenerate(
        JNIEnv * env, jobject, jstring user_prompt, jint max_tokens,
        jfloat temperature, jint top_k, jfloat top_p) {
    if (user_prompt == nullptr) return java_string(env, "Mensagem vazia.");
    const char * prompt_chars = env->GetStringUTFChars(user_prompt, nullptr);
    if (!prompt_chars) return java_string(env, "Não foi possível ler a mensagem.");
    const std::string user_text(prompt_chars);
    env->ReleaseStringUTFChars(user_prompt, prompt_chars);

    std::lock_guard<std::mutex> lock(g_mutex);
    g_last_metrics.clear();
    if (!g_model || !g_context) {
        return java_string(env, "Nenhum modelo local está carregado. Importe um GGUF primeiro.");
    }

    // Cada chamada começa uma conversa nova nesta versão. Limpar o KV cache
    // evita misturar tokens de mensagens anteriores e posições antigas.
    llama_memory_clear(llama_get_memory(g_context), true);

    const std::string system_text =
        "Você é JARVIS, um assistente pessoal local e offline. "
        "Responda sempre em português do Brasil, com clareza, precisão e "
        "naturalidade. Seja direto e conciso por padrão; aprofunde somente "
        "quando solicitado. Não invente fatos nem afirme que executou ações "
        "no dispositivo.";
    const llama_chat_message chat[] = {
        {"system", system_text.c_str()},
        {"user", user_text.c_str()}
    };

    // Usar o template gravado no próprio GGUF (ex.: Qwen ChatML) é essencial:
    // o formato genérico ### Instruções não corresponde ao treinamento do modelo.
    const char * chat_template = llama_model_chat_template(g_model, nullptr);
    std::string prompt;
    if (chat_template != nullptr) {
        std::vector<char> formatted(8192);
        int32_t formatted_size = llama_chat_apply_template(
            chat_template, chat, 2, true, formatted.data(),
            static_cast<int32_t>(formatted.size())
        );
        if (formatted_size > 0) {
            if (formatted_size >= static_cast<int32_t>(formatted.size())) {
                formatted.resize(static_cast<size_t>(formatted_size) + 1);
                formatted_size = llama_chat_apply_template(
                    chat_template, chat, 2, true, formatted.data(),
                    static_cast<int32_t>(formatted.size())
                );
            }
            if (formatted_size > 0 &&
                formatted_size < static_cast<int32_t>(formatted.size())) {
                prompt.assign(formatted.data(), static_cast<size_t>(formatted_size));
            }
        }
    }

    // Fallback para GGUFs sem template de conversa reconhecido.
    if (prompt.empty()) {
        prompt = "### Instruções\\n" + system_text +
                 "\\n\\n### Usuário\\n" + user_text + "\\n\\n### JARVIS\\n";
    }

    const auto inference_start = std::chrono::steady_clock::now();
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int32_t required = -llama_tokenize(
        vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        nullptr, 0, true, false
    );
    if (required <= 0) {
        return java_string(env, "Não foi possível tokenizar a mensagem.");
    }

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(required));
    const int32_t token_count = llama_tokenize(
        vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        prompt_tokens.data(), required, true, false
    );
    if (token_count < 0) {
        return java_string(env, "Falha ao preparar os tokens da mensagem.");
    }

    const uint32_t context_limit = llama_n_ctx(g_context);
    const int32_t requested_output = std::clamp(max_tokens, 16, 512);
    const int32_t remaining = static_cast<int32_t>(context_limit) - token_count - 1;
    const int32_t output_limit = std::min(requested_output, remaining);
    if (output_limit < 1) {
        return java_string(env, "A mensagem excede o contexto configurado. Reduza o texto.");
    }

    llama_batch_ext * batch = llama_batch_ext_init(g_context);
    if (!batch) return java_string(env, "Não foi possível criar o lote de inferência.");

    bool batch_ok = true;
    for (int32_t i = 0; i < token_count; ++i) {
        const int32_t index = llama_batch_ext_add_token(
            batch, 0, prompt_tokens[static_cast<size_t>(i)]
        );
        const llama_pos position = i;
        if (index < 0 ||
            !llama_batch_ext_set_pos(batch, index, &position) ||
            !llama_batch_ext_set_output_logits(batch, index, i == token_count - 1)) {
            batch_ok = false;
            break;
        }
    }

    if (!batch_ok || llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
        llama_batch_ext_free(batch);
        return java_string(env, "Falha ao processar o início da conversa.");
    }

    const auto prefill_end = std::chrono::steady_clock::now();
    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    if (!sampler) {
        llama_batch_ext_free(batch);
        return java_string(env, "Não foi possível inicializar o amostrador.");
    }

    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(std::clamp(top_k, 1, 100)));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(std::clamp(top_p, 0.1f, 1.0f), 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(std::clamp(temperature, 0.1f, 1.5f)));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string output;
    output.reserve(2048);
    llama_pos position = token_count;
    int32_t generated_count = 0;
    auto first_token_time = prefill_end;
    const auto generation_start = std::chrono::steady_clock::now();

    for (int32_t generated = 0; generated < output_limit; ++generated) {
        const llama_token next = llama_sampler_sample(sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, next)) break;

        output += token_piece(vocab, next);
        ++generated_count;
        if (generated_count == 1) first_token_time = std::chrono::steady_clock::now();

        llama_batch_ext_clear(batch);
        const int32_t index = llama_batch_ext_add_token(batch, 0, next);
        if (index < 0 ||
            !llama_batch_ext_set_pos(batch, index, &position) ||
            !llama_batch_ext_set_output_logits(batch, index, true)) {
            break;
        }
        ++position;
        if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            break;
        }
    }

    const auto generation_end = std::chrono::steady_clock::now();
    llama_sampler_free(sampler);
    llama_batch_ext_free(batch);

    const double prefill_seconds = std::chrono::duration<double>(prefill_end - inference_start).count();
    const double generation_seconds = std::chrono::duration<double>(generation_end - generation_start).count();
    const double ttft_seconds = std::chrono::duration<double>(first_token_time - inference_start).count();
    const double prompt_tps = prefill_seconds > 0.0 ? token_count / prefill_seconds : 0.0;
    const double generation_tps = generation_seconds > 0.0 ? generated_count / generation_seconds : 0.0;
    std::ostringstream metrics;
    metrics << std::fixed << std::setprecision(2)
            << "Prompt: " << token_count << " tokens (" << prompt_tps << " tok/s)"
            << " • 1º token: " << ttft_seconds << " s"
            << " • Geração: " << generated_count << " tokens (" << generation_tps << " tok/s)";
    g_last_metrics = metrics.str();

    if (output.empty()) {
        return java_string(env, "O modelo não gerou texto. Use um modelo GGUF de instrução compatível.");
    }
    return java_string(env, output);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jarvis_assistant_core_LocalLlmAssistantEngine_nativeGetLastMetrics(
        JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return java_string(env, g_last_metrics);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_assistant_core_LocalLlmAssistantEngine_nativeUnloadModel(
        JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unload_locked();
}
