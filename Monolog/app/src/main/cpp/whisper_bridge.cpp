#include <jni.h>
#include <whisper.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
constexpr jsize max_bytes = 16'000 * 2 * 30;
constexpr jsize min_bytes = 16'000 / 2;
std::mutex model_lock;
std::string loaded_file;
std::unique_ptr<whisper_context, decltype(&whisper_free)> context(nullptr, whisper_free);

std::string read_string(JNIEnv* env, jstring input) {
    const char* chars = env->GetStringUTFChars(input, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(input, chars);
    return result;
}

bool timed_out(void* deadline) {
    return Clock::now() > *static_cast<Clock::time_point*>(deadline);
}
} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_notokens_monolog_ime_vox_WhisperTranscriptionService_transcribePcm(
    JNIEnv* env, jobject, jstring model_file, jstring language_tag, jstring initial_prompt, jbyteArray pcm, jboolean provisional) {
    const auto size = env->GetArrayLength(pcm);
    if (size < min_bytes || size > max_bytes || size % 2 != 0) return nullptr;

    std::vector<jbyte> audio(size);
    env->GetByteArrayRegion(pcm, 0, size, audio.data());
    if (env->ExceptionCheck()) return nullptr;
    std::vector<float> samples(size / 2);
    double energy = 0;
    for (jsize i = 0; i < size; i += 2) {
        const int bits = static_cast<uint8_t>(audio[i]) | (static_cast<uint8_t>(audio[i + 1]) << 8);
        const auto sample = (bits < 32768 ? bits : bits - 65536) / 32768.0f;
        samples[i / 2] = sample;
        energy += sample * sample;
    }
    if (energy / samples.size() < 0.000001) return env->NewByteArray(0);

    const auto file = read_string(env, model_file);
    const auto tag = read_string(env, language_tag);
    const auto prompt = read_string(env, initial_prompt);
    if (env->ExceptionCheck()) return nullptr;
    std::lock_guard guard(model_lock);
    if (!context || loaded_file != file) {
        context.reset();
        auto config = whisper_context_default_params();
        config.use_gpu = false;
        context.reset(whisper_init_from_file_with_params(file.c_str(), config));
        loaded_file = file;
    }
    if (!context) return nullptr;

    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = static_cast<int>(std::clamp(std::thread::hardware_concurrency(), 1u, 4u));
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.no_context = true;
    params.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
    params.no_timestamps = true;
    params.language = whisper_lang_id(tag.c_str()) >= 0 ? tag.c_str() : "auto";
    if (provisional) {
        params.audio_ctx = std::clamp(static_cast<int>(samples.size() / 320) + 80, 160,
                                      whisper_n_audio_ctx(context.get()));
        params.single_segment = true;
    }
    auto deadline = Clock::now() + std::chrono::seconds(provisional ? 8 : 60);
    params.abort_callback = timed_out;
    params.abort_callback_user_data = &deadline;
    if (whisper_full(context.get(), params, samples.data(), static_cast<int>(samples.size())) != 0) return nullptr;

    std::string transcript;
    for (int segment = 0; segment < whisper_full_n_segments(context.get()); ++segment) {
        if (const char* text = whisper_full_get_segment_text(context.get(), segment)) transcript += text;
    }
    auto result = env->NewByteArray(static_cast<jsize>(transcript.size()));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(transcript.size()),
                                reinterpret_cast<const jbyte*>(transcript.data()));
    }
    return result;
}
