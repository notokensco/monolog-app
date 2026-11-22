# Monolog keyboard provenance

| Component | Origin | Scope |
| --- | --- | --- |
| Typing engine and JNI integration | Original Monolog implementation | Rust loads and indexes word lists once per language, finds prefix and one-edit candidates, and scores gesture paths using endpoint buckets. Kotlin handles Android editor state and privacy. |
| English and Hinglish input | Existing Monolog vocabulary and SCOWL-derived Hunspell asset | The ranker uses the existing vocabulary and a small previous-word map. Rust consumes the English dictionary directly and applies its supported suffix flags. The dictionary's upstream credits and redistribution terms are bundled in `app/src/main/assets/licenses/English-dictionary.txt`. |
| French, German and Spanish key ordering | [AOSP LatinIME layout reference at `127336e9f29d69607eab55982324b210279ae8c5`](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/127336e9f29d69607eab55982324b210279ae8c5/tests/src/com/android/inputmethod/keyboard/layout/) | Apache-2.0 upstream. Consulted the AZERTY, QWERTZ and Spanish conventions. Monolog implements its own rendering and accent access. Monolog adds independent starter vocabularies, tap suggestions, accent menus, and swipe for these layouts. AOSP's bundled dictionary data carries a separate Lexiteria permission notice. Monolog's international starter vocabularies are authored separately. |
| Noto Emoji font | [Google Fonts Noto Emoji](https://github.com/googlefonts/noto-emoji#license) | Existing SIL OFL 1.1 font, SHA-256 `0ae57fe58645638523ba35f388d93739d292539a9acb84df5700c81b1e1a28d2`. See `app/src/main/assets/noto-emoji-license.txt`. Unicode emoji data has its own bundled notice. |
| Rust JNI bindings | [jni-rs 0.21.1](https://crates.io/crates/jni/0.21.1) | MIT or Apache-2.0. Dependency versions are in `app/src/main/rust/Cargo.lock`. License texts are bundled in `app/src/main/assets/licenses/Rust-typing-notices.txt`. |
| Vox pronunciation | Original Monolog rules | Whisper uses vocabulary hints and Monolog's final cleanup. Rust phonetic keys help identify known terms in context. English and Hinglish filler words and explicit pause markers are removed from transcripts. |
| Vox dictation | [whisper.cpp v1.8.3](https://github.com/ggml-org/whisper.cpp/releases/tag/v1.8.3), commit `2eeeba56e9edd762b4b38467bab96c2517163158`, and [Whisper Small multilingual Q5_1](https://huggingface.co/ggerganov/whisper.cpp/blob/main/ggml-small-q5_1.bin) | MIT. Monolog's C++ adapter decodes short windows during capture and the full clip at the end. The 190,085,487-byte model is SHA-256 checked and stored outside APKs and backups. Transcription runs in the private Vox process. All microphone entry points require this model. |
| GGUF text runtime | [llama.cpp b8999](https://github.com/ggml-org/llama.cpp/releases/tag/b8999), commit `b97ebdc98f6053604a19d861c08d8087601b96e0` | MIT. Monolog's JNI adapter runs CPU-only text inference on 64-bit Android. See `app/src/main/assets/licenses/Llama-cpp-MIT.txt`. |
| Qwen text models | [Qwen3 0.6B](https://huggingface.co/Qwen/Qwen3-0.6B) and [Qwen3.5 0.8B](https://huggingface.co/Qwen/Qwen3.5-0.8B) | Apache-2.0. Downloads are pinned, size-checked, and SHA-256 checked. The Q4_K_M quantizations are [Unsloth Qwen3 0.6B](https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/tree/50968a4468ef4233ed78cd7c3de230dd1d61a56b) (397 MB) and [Unsloth Qwen3.5 0.8B](https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/tree/6ab461498e2023f6e3c1baea90a8f0fe38ab64d0) (533 MB). |

## Building the keyboard

The Android build uses stable Rust via rustup and the Android NDK for its JNI library. Install the Rust target for the requested ABI, or all four for the default build.

```sh
rustup toolchain install stable --profile minimal
rustup target add --toolchain stable aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
./gradlew :app:assembleDebug -PmonologAbi=arm64-v8a
```

On a Windows machine with the GNU Rust toolchain, select it with `-PmonologRustToolchain=stable-x86_64-pc-windows-gnu` after installing the Android target for that toolchain. The default native toolchain is NDK `25.1.8937393`. Set `-PmonologRustNdk` to select an installed version under the Android SDK's `ndk` directory. Pass `-PmonologRustTyping=false` to build with the Kotlin fallback for troubleshooting. The native ranker introduces no bundled machine-learning model.

For offline Vox and Qwen support, supply external checkouts of the pinned whisper.cpp and llama.cpp releases using `-PmonologWhisperSource=/absolute/path/to/whisper.cpp` and `-PmonologLlamaSource=/absolute/path/to/llama.cpp`. Android SDK CMake 3.22.1 builds both runtimes. `:app:compileDebugKotlin` and `:app:mergeDebugNativeLibs` verify compilation and produce inspectable native libraries without packaging an APK. ONNX Runtime Android 1.24.3 replaces 1.20.0 for [Android 16 KB native page sizes](https://developer.android.com/guide/practices/page-sizes).

Onboarding offers Whisper Small, a choice of one text model, the semantic encoder, and the brief summarizer where supported. Switching text models removes the old weights after the incoming download passes verification. Keyboard dictation, spoken notes, journal notes, Quick Shortcuts and screenshot notes share Whisper Small. Vox refreshes provisional text during speech and finalizes after silence, Stop, or the 30-second clip limit. Saved notes retain mono AAC audio if transcription fails. Semantic search retains the same encoder and ranking and releases the native inference session after it goes idle.

## Typing preferences

The personal dictionary stores explicitly added words on the device. These entries participate in tap suggestions and protect saved spellings from English autocorrect. Smart Toning is opt-in and uses the selected offline text model to preview professional rewrites in recognized work apps and casual rewrites in recognized chat apps. It inserts text only when the user accepts the preview. The dashboard clipboard is opt-in and keeps up to ten copied text items in memory. Entering Incognito or disabling clipboard history clears that list.

## APK retention

Keep at most two local Monolog APKs, the current artifact and the immediately preceding artifact. Preserve tracked release artifacts and third-party input samples. Verify the changes before packaging again.

## Manual device checks

- Open the keyboard from a cold start and type straight away.
- In English and Hinglish, check suggestions while typing and try selecting an alternate immediately after a swipe. Switch editors while a swipe is being decoded and confirm it does not insert in the new field.
- Toggle Incognito and try a password field. Confirm suggestions are hidden and normal taps still work.
- Select French, German, or Spanish in language settings and exercise the letter rows, accent menus, swipe suggestions, symbols, and backspace.
- Dictate a phrase with a supported name and a similar ordinary word. Check the finalized transcript and note playback for each microphone entry point. Check that Whisper voice notes wait for transcription before saving and omit filler words and pause markers. Cancel an active note, switch apps, and check that a late result is discarded.
