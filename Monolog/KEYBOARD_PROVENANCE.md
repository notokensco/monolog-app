# Monolog keyboard provenance

The VOX wordmark uses a three-glyph subset of [Science Gothic](https://github.com/googlefonts/science-gothic)
at weight 600 under SIL OFL 1.1. Its license is bundled in `app/src/main/assets/licenses/Science-Gothic-OFL.txt`.

| Component | Origin | Scope |
| --- | --- | --- |
| Typing engine and JNI integration | Original Monolog implementation | Rust loads and indexes word lists once per language, finds prefix and one-edit candidates, and scores gesture paths using endpoint buckets. Kotlin handles Android editor state and privacy. |
| English and Hinglish input | Existing Monolog vocabulary and SCOWL-derived Hunspell asset | The Rust ranker uses a small, authored English and Hinglish phrase corpus for unigram, bigram and trigram scoring. The previous-word map also supports the Kotlin fallback. Rust consumes the English dictionary directly and applies its supported suffix flags. The dictionary's upstream credits and redistribution terms are bundled in `app/src/main/assets/licenses/English-dictionary.txt`. |
| French, German and Spanish key ordering | [AOSP LatinIME layout reference at `127336e9f29d69607eab55982324b210279ae8c5`](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/127336e9f29d69607eab55982324b210279ae8c5/tests/src/com/android/inputmethod/keyboard/layout/) | Apache-2.0 upstream. Consulted the AZERTY, QWERTZ and Spanish conventions. Monolog implements its own rendering and accent access. Monolog adds independent starter vocabularies, tap suggestions, accent menus, and swipe for these layouts. AOSP's bundled dictionary data carries a separate Lexiteria permission notice. Monolog's international starter vocabularies are authored separately. |
| Noto Emoji font | [Google Fonts Noto Emoji](https://github.com/googlefonts/noto-emoji#license) | Existing SIL OFL 1.1 font, SHA-256 `0ae57fe58645638523ba35f388d93739d292539a9acb84df5700c81b1e1a28d2`. See `app/src/main/assets/noto-emoji-license.txt`. Unicode emoji data has its own bundled notice. |
| Rust JNI bindings | [jni-rs 0.21.1](https://crates.io/crates/jni/0.21.1) | MIT or Apache-2.0. Dependency versions are in `app/src/main/rust/Cargo.lock`. License texts are bundled in `app/src/main/assets/licenses/Rust-typing-notices.txt`. |
| Vox pronunciation | Original Monolog rules | Monolog normalizes recognized speech. Rust phonetic keys help identify known terms in context. English and Hinglish filler words and explicit pause markers are removed from transcripts. |
| Moonshine streaming | [Moonshine Voice 0.1.5](https://github.com/moonshine-ai/moonshine/tree/v0.1.5) and the English Small Streaming model | MIT. The SDK downloads and loads the full model bundle outside the APK and backups. The shared Vox controller feeds it live mono PCM with adaptive gain and receives incremental captions, including when recording a voice note. |
| VOX AI India | [Udgam Labs Shruti ONNX](https://huggingface.co/udgamlabs/Shruti-ONNX), revision `a464a502cc732e4335313869e1ccf393ff8ba56b` | Apache-2.0. Twenty-two selectable downloadable INT8 packs, 52.9–53.5 MB each. The Hindi pack also handles Indian English and code-switched speech. The shared Vox controller uses the existing ONNX Runtime and verifies four pinned files per pack against SHA-256. Hinglish uses Android ICU to Romanize Devanagari. Recognition previews arrive from phrase windows. Latency and accuracy on the Android integration need device measurement. |
| GGUF text runtime | [llama.cpp b8999](https://github.com/ggml-org/llama.cpp/releases/tag/b8999), commit `b97ebdc98f6053604a19d861c08d8087601b96e0` | MIT. Monolog's JNI adapter runs CPU-only text inference on 64-bit Android. See `app/src/main/assets/licenses/Llama-cpp-MIT.txt`. |
| Qwen text models | [Qwen3 0.6B](https://huggingface.co/Qwen/Qwen3-0.6B) and [Qwen3.5 0.8B](https://huggingface.co/Qwen/Qwen3.5-0.8B) | Apache-2.0. Downloads are pinned, size-checked, and SHA-256 checked. The Q4_K_M quantizations are [Unsloth Qwen3 0.6B](https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/tree/50968a4468ef4233ed78cd7c3de230dd1d61a56b) (397 MB) and [Unsloth Qwen3.5 0.8B](https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/tree/6ab461498e2023f6e3c1baea90a8f0fe38ab64d0) (533 MB). |

## Gboard comparison and reuse

REA's Gboard 18.6.5 package inventories are `ev_2da15655cdaa72d6682121da235b70aa473ff99ba98c580cdbbca8eb2dcf6816`
for the APKM and `ev_7ef8fbba2ba2406909e55e69ffadafa00d9428608a13acd86a6891ce95ebade2` for base.apk.
The package has a separate dictation split and the base contains an integrated ARM library. These are static
packaging observations. They establish neither Gboard's runtime ranking behavior nor reuse rights.
The JADX provider was unavailable in this session.

[SCOWL's Hunspell data](https://wordlist.aspell.net/hunspell-readme/) permits commercial reuse with its
notices. Separately licensed [AOSP LatinIME source](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/127336e9f29d69607eab55982324b210279ae8c5/NOTICE)
can be reused under Apache-2.0 where applicable. The AOSP notice lists Lexiteria dictionary material
under a separate permission. Its terms would need confirmation before extracting or distributing those data.
Monolog uses its existing SCOWL-derived dictionary and independently written Hinglish data. English (India)
now includes Hinglish candidates and code-switched phrase ranking. No Gboard binaries, dictionaries,
frequency tables, or model weights were incorporated.

The model chooser labels Gemma 3 270M IT **Good**, Qwen3 0.6B **Better**, Qwen3.5 0.8B **Best**,
and Moonshine Small Streaming **VOX AI**. The recommendation uses installed runtime support, RAM, and
logical processor count. It is a hardware estimate, and the separate speed check measures inference on the device.

The optional Android spell checker uses the system-selected provider for spelling candidates on the current
word. It stays off by default and receives no text from Incognito or protected editors.

Weather uses [MET Norway Locationforecast](https://api.met.no/doc/locationforecast/HowTO) with attribution.
The app requests location in the foreground and rounds coordinates to two decimal places before each
request. It keeps the forecast briefly in memory and does not retain the coordinates in journal data.

## Building the keyboard

The Android build uses stable Rust via rustup and the Android NDK for its JNI library. Install the Rust target for the requested ABI, or all four for the default build.

```sh
rustup toolchain install stable --profile minimal
rustup target add --toolchain stable aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
./gradlew :app:assembleDebug -PmonologAbi=arm64-v8a
```

On a Windows machine with the GNU Rust toolchain, select it with `-PmonologRustToolchain=stable-x86_64-pc-windows-gnu` after installing the Android target for that toolchain. The default native toolchain is NDK `25.1.8937393`. Set `-PmonologRustNdk` to select an installed version under the Android SDK's `ndk` directory. Pass `-PmonologRustTyping=false` to build with the Kotlin fallback for troubleshooting. The native ranker introduces no bundled machine-learning model.

For Qwen support, supply an external checkout of the pinned llama.cpp release using `-PmonologLlamaSource=/absolute/path/to/llama.cpp`. Android SDK CMake 3.22.1 builds the runtime. `:app:compileDebugKotlin` and `:app:mergeDebugNativeLibs` verify compilation and produce inspectable native libraries without packaging an APK. The full ONNX Runtime Android 1.23.2 matches the Moonshine JNI symbol version and supports [Android 16 KB native page sizes](https://developer.android.com/guide/practices/page-sizes).

Onboarding offers Moonshine and optional VOX AI India packs, a choice of one text model, the semantic encoder, and the brief summarizer where supported. Switching text models removes the old weights after the incoming download passes verification. Keyboard dictation and voice notes use the selected shared Vox backend. Monolog keeps the selected local speech model warm while the IME is visible. It also prepares the selected LiteRT or GGUF text model while Android reports sufficient memory. Android speech is available for keyboard dictation. Vox refreshes provisional text during speech and finalizes on Stop or when a 30-second clip ends. Saved notes retain mono AAC audio if transcription fails. Semantic search retains the same encoder and ranking and releases the native inference session after it goes idle.

## Typing preferences

The personal dictionary stores explicitly added words on the device. These entries participate in tap suggestions and protect saved spellings from English autocorrect. Smart Toning is opt-in and uses the selected offline text model to preview professional rewrites in recognized work apps and casual rewrites in recognized chat apps. It inserts text only when the user accepts the preview. The dashboard clipboard is opt-in and keeps up to ten copied text items in memory. Entering Incognito or disabling clipboard history clears that list.

## APK retention

Keep at most two local Monolog APKs, the current artifact and the immediately preceding artifact. Preserve tracked release artifacts and third-party input samples. Verify the changes before packaging again.

## Manual device checks

- Open the keyboard from a cold start and type straight away.
- In English and Hinglish, check suggestions while typing and try selecting an alternate immediately after a swipe. Switch editors while a swipe is being decoded and confirm it does not insert in the new field.
- Toggle Incognito and try a password field. Confirm suggestions are hidden and normal taps still work.
- Select French, German, or Spanish in language settings and exercise the letter rows, accent menus, swipe suggestions, symbols, and backspace.
- Dictate a phrase with a supported name and a similar ordinary word. Check the finalized transcript and note playback for each microphone entry point. Check that Moonshine voice notes wait for transcription before saving and omit filler words and pause markers. Cancel an active note, switch apps, and check that a late result is discarded.
