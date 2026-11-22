#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <chrono>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
constexpr int context_length = 1024;

bool expired(void* deadline) {
    return Clock::now() >= *static_cast<Clock::time_point*>(deadline);
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_notokens_monolog_ai_GgufRuntime_generate(
    JNIEnv* env, jobject, jstring path, jbyteArray prompt, jint count) {
    const auto size = env->GetArrayLength(prompt);
    if (size <= 0 || size > 16000 || count < 1 || count > 192) return nullptr;
    std::vector<jbyte> input(size);
    env->GetByteArrayRegion(prompt, 0, size, input.data());
    if (env->ExceptionCheck()) return nullptr;

    const char* fs_path = env->GetStringUTFChars(path, nullptr);
    if (fs_path == nullptr) return nullptr;
    std::string model_path(fs_path);
    env->ReleaseStringUTFChars(path, fs_path);
    static const bool initialized = [] { llama_backend_init(); return true; }();
    (void) initialized;
    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.use_mmap = true;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
        llama_model_load_from_file(model_path.c_str(), model_params), llama_model_free);
    if (!model) return nullptr;
    const auto* vocabulary = llama_model_get_vocab(model.get());

    std::vector<llama_token> tokens(context_length);
    const int token_count = llama_tokenize(vocabulary, reinterpret_cast<const char*>(input.data()), size,
        tokens.data(), static_cast<int>(tokens.size()), false, true);
    if (token_count <= 0 || token_count + count > context_length) return nullptr;
    auto config = llama_context_default_params();
    config.n_ctx = context_length;
    config.n_batch = context_length;
    config.n_threads = static_cast<int>(std::clamp(std::thread::hardware_concurrency(), 1u, 4u));
    config.n_threads_batch = config.n_threads;
    auto deadline = Clock::now() + std::chrono::seconds(28);
    config.abort_callback = expired;
    config.abort_callback_data = &deadline;
    std::unique_ptr<llama_context, decltype(&llama_free)> session(
        llama_init_from_model(model.get(), config), llama_free);
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_init_greedy(), llama_sampler_free);
    if (!session || !sampler || llama_decode(session.get(), llama_batch_get_one(tokens.data(), token_count)) != 0) {
        return nullptr;
    }

    std::string output;
    std::vector<char> piece(256);
    for (int i = 0; i < count && !expired(&deadline); i++) {
        auto token = llama_sampler_sample(sampler.get(), session.get(), -1);
        if (llama_vocab_is_eog(vocabulary, token)) break;
        int length = llama_token_to_piece(vocabulary, token, piece.data(), piece.size(), 0, false);
        if (length < 0 && length > -4096) {
            piece.resize(-length);
            length = llama_token_to_piece(vocabulary, token, piece.data(), piece.size(), 0, false);
        }
        if (length > 0) output.append(piece.data(), length);
        if (llama_decode(session.get(), llama_batch_get_one(&token, 1)) != 0) return nullptr;
    }
    if (expired(&deadline) || output.empty()) return nullptr;
    auto result = env->NewByteArray(static_cast<jsize>(output.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
                                      reinterpret_cast<const jbyte*>(output.data()));
    return result;
}
