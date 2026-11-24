# Monolog Google Play submission

Application ID `com.notokens.monolog`. Publisher NoTokens. Support and privacy contact `monolog@notokens.co`.

## App content and public pages

- Use the [public privacy policy](https://monolog.notokens.co/privacy) in Play Console. Keep it in sync with the [bundled policy](app/src/main/assets/policies/privacy.txt). The [public terms](https://monolog.notokens.co/tos) and the [bundled terms](app/src/main/assets/policies/terms.txt) cover planned subscriptions.
- Before billing goes live, verify the final checkout disclosures, entitlement data, processor, retention, and in-app subscription management entry. Update the privacy policy and Data Safety form for the production billing flow.
- Review the [commercial-use audit](COMMERCIAL_USE_AUDIT.md) with the final release artifact.
- The app discloses Quick Capture before sending the user to Android's Accessibility settings. Complete the Accessibility API declaration, describe Quick Capture in the listing, and provide a demonstration showing the disclosure, the optional Android setting, the user-triggered capture, and its save or discard flow. The app declares itself as a non-accessibility-tool use of the API.
- Complete App content, content rating, target audience, ads, and app-access fields for the final production build. The core app can be reviewed without connecting a Drive account. Use the Console's testing requirements for the publisher account.

## Data Safety review

| Feature | Data and destination |
| -- | -- |
| Keyboard, offline Ask, local suggestions | Typing, available editor context, and requested prompts are processed on the device. No ordinary typing is sent to NoTokens for model training. If the user enables the Android spell checker, review the selected provider's behavior separately. |
| Journal, microphone, photos and capture | Saved notes, media and reminders are stored locally. Voice models run locally. Quick Capture accesses a screenshot on activation and can locate the Android button when the service is enabled. |
| Clipboard | Optional local history and pinned items. Unpinned clips expire after one hour. The Android clipboard is separate. |
| Google Drive backup | Account authorization through Google. With backup enabled, encrypted backups of supported local content are uploaded to the user's Drive app-data storage. Disconnecting stops access but leaves existing Drive backups. The privacy notice explains deletion. |
| Web questions and actions | An explicit Web query goes to DuckDuckGo in the browser or through the configured DuckDuckGo search service. Optional GIF and sticker browsing and submitted searches use KLIPY. Opening an external action passes the data required for that action. Confirm the exact production web provider and its retention before declaring these flows. |
| Device weather | Rounded coordinates go to MET Norway. Android supplies device location and place-name lookup. |
| Model and SDK services | Asset hosts receive ordinary download metadata. The reviewed ML Kit and MediaPipe Tasks diagnostic loggers use the Google DataTransport CCT backend. Monolog removes that backend from the merged manifest. Confirm the final artifact and capture network traffic for SDK startup, OCR, translation, and summaries before filing. Model downloads and SDK update requests are separate. |

This matrix records app behavior. Use the Play Console definitions for collected, shared, ephemeral, and user-initiated data when submitting the final form.

Monolog can be used without a NoTokens account. Google account authorization connects an existing Drive account for optional backup. Review the Play account-deletion declaration against the production sign-in flow. The bundled privacy notice covers local removal, Drive backup removal, and privacy requests.

## Release artifact

- The Android project targets API 36 and retains Android 15 as the minimum supported release.
- Build the production Android App Bundle with the intended native ABIs and Play upload certificate. Set `MONOLOG_UPLOAD_KEYSTORE`, `MONOLOG_UPLOAD_STORE_PASSWORD`, `MONOLOG_UPLOAD_KEY_ALIAS`, and `MONOLOG_UPLOAD_KEY_PASSWORD` outside version control. The local testing certificate is a separate signing path.
- Verify the bundle's generated APKs on a 16 KB page-size device, review the final release dependency notices, and test permissions and network features on the production variant.
- Owner actions include signing, Play App Signing enrollment, final store disclosures, listing assets, and submission.

## References

- [Play target API levels](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Play App content and privacy disclosures](https://support.google.com/googleplay/android-developer/answer/9859455)
- [Play AccessibilityService API policy](https://support.google.com/googleplay/android-developer/answer/10964491)
- [Play Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Play 16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes)
