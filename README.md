# monolog
# monolog-app

## Local APK signing

Use `com.notokens.monolog` as the Android application ID for every Monolog APK.
Register this package name with the certificate fingerprint for Android integrations.

For APKs that update the current Monolog test install, keep the original
`monolog-testing.keystore` in a private location. Set
`monologTestingKeystore` to its absolute path in your user Gradle
`gradle.properties` file. Use forward slashes in Windows paths. Debug and
optimized builds verify that the certificate matches the existing test install.
Use the same keystore when building an update from another computer.

From the `Monolog` directory, run `.\gradlew.bat :app:signingReport` on Windows
or `./gradlew :app:signingReport` on macOS or Linux to check the certificate.


## Optional online keyboard services

- **KLIPY**. Create a test key in the [KLIPY Partner Panel](https://partner.klipy.com/api-keys),
  then request production access. Deploy `Monolog/tools/klipy-relay.mjs` as an HTTPS
  fetch handler at the root of a dedicated endpoint. Set `KLIPY_APP_KEY` as a
  server-side secret and set the public HTTPS base URL as the Android Gradle
  property `monologKlipyEndpoint`. Configure abuse limits at the host without logging
  search terms or caching responses. Keep ads disabled for this key until the
  partner ad agreement and isolated rendering have been verified. The keyboard
  streams selected GIFs and stickers to compatible editors without retaining a media file.
- **Web**. Ask searches [DuckDuckGo](https://duckduckgo.com/) in the browser by default.
  Safe Search starts on and can be changed while Web is enabled. To enable compact
  in-keyboard results, set `monologDuckDuckGoEndpoint` to an HTTPS service authorized
  to deliver DuckDuckGo results that honors both Safe Search modes. It accepts a JSON
  POST with `query` and boolean `safeSearch`. Return `engine: "duckduckgo"`, the boolean
  `safeSearch` actually applied, and a `results` array of `title`, `url`, and `content`.
  The app discards responses that fail to confirm the requested setting.
  `monologWebEndpoint` is the separate forecast service.
- **Translation**. The keyboard uses Google’s on-device ML Kit translation API.
  Release descriptions should credit Google Translate and link the
  [translation service](https://cloud.google.com/translate).
