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
