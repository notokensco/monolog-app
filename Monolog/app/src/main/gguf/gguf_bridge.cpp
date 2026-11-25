#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
constexpr int default_context_length = 1024;
constexpr int max_context_length = 16384;

std::mutex model_mutex;
std::atomic<jlong> cancelled_through(0);
std::unique_ptr<llama_model, decltype(&llama_model_free)> cached_model(nullptr, llama_model_free);
std::string cached_path;

llama_model* load_model(JNIEnv* env, jstring path) {
    const char* fs_path = env->GetStringUTFChars(path, nullptr);
    if (fs_path == nullptr) return nullptr;
    std::string model_path(fs_path);
    env->ReleaseStringUTFChars(path, fs_path);
    if (cached_model && cached_path == model_path) return cached_model.get();

    cached_model.reset();
    cached_path.clear();
    static const bool initialized = [] { llama_backend_init(); return true; }();
    (void) initialized;
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    params.use_mmap = true;
    cached_model.reset(llama_model_load_from_file(model_path.c_str(), params));
    if (cached_model) cached_path = std::move(model_path);
    return cached_model.get();
}

struct Generation {
    jlong request;
    Clock::time_point deadline;
};

bool expired(void* generation) {
    const auto& run = *static_cast<Generation*>(generation);
    return run.request <= cancelled_through.load(std::memory_order_relaxed) || Clock::now() >= run.deadline;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_notokens_monolog_ai_GgufRuntime_prepare(JNIEnv* env, jobject, jstring path) {
    std::lock_guard lock(model_mutex);
    return load_model(env, path) != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_notokens_monolog_ai_GgufRuntime_unload(JNIEnv*, jobject) {
    std::lock_guard lock(model_mutex);
    cached_model.reset();
    cached_path.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_com_notokens_monolog_ai_GgufRuntime_cancel(JNIEnv*, jobject, jlong request) {
    auto previous = cancelled_through.load(std::memory_order_relaxed);
    while (previous < request &&
           !cancelled_through.compare_exchange_weak(previous, request, std::memory_order_relaxed)) {}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_notokens_monolog_ai_GgufRuntime_generate(
    JNIEnv* env, jobject, jstring path, jbyteArray prompt, jint count, jint context_limit,
    jlong request, jlong timeout_ms) {
    const auto size = env->GetArrayLength(prompt);
    if (size <= 0 || size > 66000 || count < 1 || count > 192 ||
        context_limit < default_context_length || context_limit > max_context_length ||
        timeout_ms < 1 || timeout_ms > 90000) return nullptr;
    std::vector<jbyte> input(size);
    env->GetByteArrayRegion(prompt, 0, size, input.data());
    if (env->ExceptionCheck()) return nullptr;

    std::lock_guard lock(model_mutex);
    if (request <= cancelled_through.load(std::memory_order_relaxed)) return nullptr;
    auto* model = load_model(env, path);
    if (model == nullptr) return nullptr;
    const auto* vocabulary = llama_model_get_vocab(model);

    std::vector<llama_token> tokens(context_limit);
    const int token_count = llama_tokenize(vocabulary, reinterpret_cast<const char*>(input.data()), size,
        tokens.data(), static_cast<int>(tokens.size()), false, true);
    if (token_count <= 0 || token_count + count > context_limit) return nullptr;
    const int context_length = std::min(context_limit,
        std::max(default_context_length, ((token_count + count + 255) / 256) * 256));
    const int batch_size = context_length == default_context_length ? context_length : 512;
    auto config = llama_context_default_params();
    config.n_ctx = context_length;
    config.n_batch = batch_size;
    config.n_threads = static_cast<int>(std::clamp(std::thread::hardware_concurrency(), 1u, 2u));
    config.n_threads_batch = config.n_threads;
    auto generation = Generation{request, Clock::now() + std::chrono::milliseconds(timeout_ms)};
    config.abort_callback = expired;
    config.abort_callback_data = &generation;
    std::unique_ptr<llama_context, decltype(&llama_free)> session(
        llama_init_from_model(model, config), llama_free);
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_init_greedy(), llama_sampler_free);
    if (!session || !sampler) return nullptr;
    for (int offset = 0; offset < token_count; offset += batch_size) {
        const int size = std::min(batch_size, token_count - offset);
        if (expired(&generation) ||
            llama_decode(session.get(), llama_batch_get_one(tokens.data() + offset, size)) != 0) return nullptr;
    }

    std::string output;
    std::vector<char> piece(256);
    for (int i = 0; i < count && !expired(&generation); i++) {
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
    if (expired(&generation) || output.empty()) return nullptr;
    auto result = env->NewByteArray(static_cast<jsize>(output.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
                                      reinterpret_cast<const jbyte*>(output.data()));
    return result;
}
