# Account and encrypted Drive backup verification

Started on main at e14cc32. The model integration is preserved.
Account setup is in Settings → Account & backup.

## Implemented

- Credential Manager uses the supplied Web OAuth client ID. Drive consent is a
  separate AuthorizationClient request pinned to the selected Google account.
  The drive.appdata scope is checked before any upload.
- Guest journal and keyboard use remain available. After Drive consent and
  recovery setup, the existing account's current year is merged with guest data
  before the device backs up.
- Backups contain only selected notes/journal entries, saved screen context and
  attachments. API keys, account credentials and settings are excluded.
- Versioned yearly snapshots use a separate random AES-256-GCM key. Android
  Keystore protects the local copy. A user-held random recovery code unlocks the
  backup on another phone.
- Restore authenticates and validates every selected file before importing in a
  Room transaction. Stable record IDs and content hashes prevent repeated
  restores from duplicating records. Local edits and imported conflicts survive
  as separate versions.
- Years use the source device's local creation dates. Successfully backed-up
  older years are hidden from normal browsing until loaded through Restore.
  Local rows are retained, and unbacked edits stay visible. Guests see all their
  local entries.
- Manual is the default. Daily and Weekly are enabled after a successful backup.
  WorkManager requires network access and sufficient battery. Sign-out, account
  switching and Drive disconnect cancel scheduled work.
- Drive retains yearly snapshots. Restore uses the newest snapshot per source
  device, year and content selection.

## Passed checks

- Debug builds and JVM unit tests.
- Physical OnePlus installation using com.notokens.monolog.
- Google sign-in, separate Drive connection and a successful backup on the
  physical phone, confirmed by the user and the app's saved status.
- Six isolated API 35 emulator tests using real Room and Android Keystore with a
  simulated Drive transport. They covered attachment recovery with a recovery
  code, repeat restore, conflict preservation, wrong-code rejection, interrupted
  download, failed upload, revoked consent, account switching during upload,
  and migration from database version 3 with encrypted guest data.
- A pattern-based scan of tracked files, unignored working files and Git history
  found no Google API keys, OAuth client secrets, private keys or GitHub tokens.
  The local .env and signing keystores are ignored.

The debug certificate SHA-1 from the signing report is
A5:FC:79:11:99:8D:29:51:4F:13:A2:39:B2:68:CF:2D:DF:3B:ED:6B.

## Physical failure and correction

Reproduction on the initial build:

1. Sign in successfully with the registered test account.
2. Tap Connect Drive and grant Drive access.
3. Monolog showed “Drive authorized a different account. Reconnect Drive.”

The initial check required an email profile field in a Drive-only authorization
result. The corrected check compares the stable Google account ID when present,
otherwise compares the email if supplied. When profile fields are absent, it
uses the account already pinned by AuthorizationRequest.setAccount. A returned
different account ID or email is still rejected. Unit tests cover these cases.

## Remaining real-service checks

A real Drive upload completed after the account-field fix. A Google-backed
restore on a second signed-in device remains unverified. That check needs the
user's Google consent and private recovery code. The simulated transport tests
verify the restore logic and encryption independently of Google.

1. Install the updated APK. Open Settings → Account & backup, choose the desired
   content and tap Connect Drive again.
2. Create a synthetic note with a disposable picture. On a new account, privately
   save and confirm the displayed recovery code. On an existing account, enter
   its original recovery code and load the current year. Check the success
   message and Last backup time.
3. On a second phone or clean test profile, install the signed APK, sign in with
   the same test account and separately connect Drive.
4. Enter an incorrect recovery code first and verify existing notes remain
   intact. Then restore with the correct code and verify the note and picture.
5. Restore again and check the note count. Edit the note locally, create a
   changed backup on the first phone and restore it. Both versions should remain.
6. Interrupt a restore download. Existing notes should remain intact. Retry.
7. Deny Drive consent, then separately revoke the grant through Google account
   settings. Verify guest use and Reconnect Drive.
8. Switch accounts or sign out. Verify local entries remain and old scheduled
   work is canceled. Disconnect networking and attempt an upload. The Last
   backup time should remain unchanged.
9. After a successful manual backup, select Daily or Weekly. Execution after the
   full scheduled interval has not been observed during this session.

The Android implementation uses no Firebase backend, Cloud Run service, client
secret or custom-domain verification step.
