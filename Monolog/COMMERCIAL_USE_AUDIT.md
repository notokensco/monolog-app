# Monolog commercial-use audit
23 November 2026

Reviewed the Android release dependency graph, the latest ARM64 package's native libraries, checked-in app assets, configured downloads, and the production website source. This review records licensing evidence and release follow-ups. It does not establish exclusive rights in the Monolog name or resolve ownership of every contributor's work.

| Area | Commercial-use finding and obligations |
| --- | --- |
| Original keyboard and UI | Source and packaging review found no Gboard or SwiftKey binaries, models, or dictionary assets in the release. The competing APKs are local references. AOSP LatinIME was used as a layout reference. Keep the separate Lexiteria dictionary data out unless an applicable grant is confirmed. |
| Typing data | The SCOWL/Hunspell dictionary permits reuse with its included notices. The FLORES-200 and Dakshina tables are modified CC BY-SA 4.0 data. The app credits both sources, describes the modifications and licenses the derived tables separately from its original code. Preserve those terms if the tables are mirrored or exported. Unicode character and emoji data carry a separate Unicode notice. |
| Fonts and artwork | Noto Emoji and the Science Gothic font subset carry their SIL OFL notices. The site also distributes Manrope, Beth Ellen, IBM Plex Mono and Roboto with the notices from their installed font packages. The supplied NoTokens mark is used with the publisher's permission. The website repository documents generated art and retained product concepts. Retain author permissions for any contributor-supplied artwork, voice sample or other media used in the release and its listing. |
| Voice models | The distributed Moonshine SDK and downloaded English Small Streaming model are MIT. The pinned SDK also uses separately licensed native components, including Eigen under MPL 2.0. Their notices and source location are now accessible in the app. The pinned Shruti ONNX publisher identifies the model as Apache 2.0 and credits the upstream Cactus Whistle model. Keep the Shruti notice and pinned source with that integration. |
| Writing and journal models | The Qwen models and their Unsloth quantizations are Apache 2.0. The multilingual MiniLM source is Apache 2.0 and the app downloads the pinned Xenova conversion. The Gemma 3 artifact is downloaded from a mirror that identifies it as an unchanged LiteRT Community file with Gemma terms. The 200M journal summarizer has the same Gemma terms, which the publisher confirms for the exact artifact. New downloads show the restrictions and request agreement. The app includes the Gemma agreement and notice. |
| SDKs and native runtimes | AndroidX, Kotlin and principal JVM libraries use permissive licenses. Google Play services and ML Kit additionally have provider terms. The review added the vendor license bundles for LiteRT-LM and MediaPipe, plus notices for Moonshine's native dependencies, protobuf, the Checker Framework qualifiers, and JSON for Modern C++. ONNX Runtime, llama.cpp and the Rust dependencies already have bundled notices. The Checker Framework compiler is a separate project from the annotation artifacts in the runtime graph. Review the final bundle if the dependency graph changes. |
| Online content | Weather data is attributed to MET Norway with its CC BY 4.0 license and the formatting changes disclosed. The client uses the server's cache expiry and conditional requests. The operator must respect MET's aggregate traffic limits and arrange a compliant proxy or agreement if traffic warrants it. Web results use DuckDuckGo in the browser or through the configured DuckDuckGo search service. KLIPY media uses its commercial API terms and attribution. Google ML Kit translation uses Google Translate attribution adjacent to the live editor. |

## Before submitting a paid release

- Geist is included under the bundled SIL OFL. Confirm redistribution rights for the supplied Tick and Typewriter audio before distributing them commercially.

- Retain written ownership and permission records for both builders, any outside contributions, and listing media. Clear the brand in launch markets if trademark protection or exclusivity is important. Copyright and trademark are distinct.
- Confirm the final dependency notices and corresponding source access for shipped MPL-covered components, including the vendor SDK builds. Confirm the exact production search provider, if configured, and the permitted rights to use and display its results.
- Confirm the actual billing provider, purchase metadata, checkout disclosures, and cancellation flow. Update the privacy policy and Play disclosures from the production behavior before activating billing.
- The Android source repository is publicly viewable. The policy describes user data practices and leaves implementation details out. Repository visibility and published release artifacts are separate publication decisions.

## Primary sources

- [Gemma terms](https://gemma.ai/resources/terms/) and [MediaPipe Text Summarizer model terms](https://developers.google.com/edge/mediapipe/solutions/text/text_summarizer)
- [Moonshine licenses](https://github.com/moonshine-ai/moonshine/blob/v0.1.5/LICENSE) and [native library source](https://github.com/moonshine-ai/moonshine/tree/v0.1.5/core/third-party)
- [LiteRT-LM source for the shipped version](https://github.com/google-ai-edge/LiteRT-LM/tree/v0.18.1) and [MediaPipe source for the shipped version](https://github.com/google-ai-edge/mediapipe/tree/v1.2.3)
- [SCOWL dictionary terms](https://wordlist.aspell.net/hunspell-readme/), [Dakshina](https://github.com/google-research-datasets/dakshina) and [FLORES](https://github.com/facebookresearch/flores)
- [MET service terms](https://docs.api.met.no/doc/TermsOfService) and [ML Kit terms and privacy](https://developers.google.com/ml-kit/terms)
