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
- **Web**. Ask AI can show web and image results from [Brave Search](https://brave.com/search/api/).
  Deploy `Monolog/tools/brave-search-relay.mjs` as a Cloudflare Worker with a
  `BRAVE_SEARCH_API_KEY` secret. Set `monologBraveSearchEndpoint` to its public
  HTTPS URL ending in `/search`. Bind a Cloudflare rate limiter named
  `SEARCH_RATE_LIMITER` and configure a spending limit before sharing the endpoint.
  Disable request logs containing search terms. Safe Search starts on and can be
  changed while Web is enabled. More in browser opens DuckDuckGo.
  An authorized DuckDuckGo result service can be configured with
  `monologDuckDuckGoEndpoint`. Its JSON POST accepts `query`, `kind`, and boolean
  `safeSearch`, and returns `engine: "duckduckgo"`, the `kind` and `safeSearch`
  applied, and a `results` array with `title`, `url`, `content`, and optional `thumbnail`.
  `monologWebEndpoint` configures the separate forecast service.
- **Translation**. The keyboard uses Google’s on-device ML Kit translation API.
  Release descriptions should credit Google Translate and link the
  [translation service](https://cloud.google.com/translate).
