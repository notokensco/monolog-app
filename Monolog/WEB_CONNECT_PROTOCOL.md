# Monolog Web Connect v1

The Android app pairs with the client served by `https://web.monolog.notokens.co`. Keep this origin and use the Android and web OAuth clients from the same Google Cloud project for Drive app data. Add the production web origin to the web OAuth client's authorized JavaScript origins.

## Pairing

The browser creates a fresh P-256 ECDH key pair, 16 random session bytes, and a separate 16-byte random nonce. Encode the QR as an HTTPS URL at `/pair` on the production origin. Parameters are `v=1`, `mode=local` or `mode=web`, `session` and `nonce` (each unpadded base64url, 22 characters), `key` (unpadded base64url SPKI public key), and `stay=1` for a long-lived Web Connect session. Render the QR as text data. Never navigate to that URL or transmit the nonce. Keep the nonce and browser private key in memory for temporary sessions. The phone shows the six-digit PIN after it accepts the QR or publishes the first web snapshot. The PIN is never sent in plaintext.

Derive 32 bytes using ECDH (P-256), then HKDF-SHA-256. Use UTF-8 session ID followed by the 16 decoded nonce bytes as salt and UTF-8 `Monolog Connect v1|<mode>|<PIN>` as info. Envelopes use AES-256-GCM. The binary layout is 12 random IV bytes followed by ciphertext and 16-byte authentication tag. Additional authenticated data is UTF-8 `Monolog Connect v1|<session>|<purpose>`. The purposes are `request`, `response|<base64url of request IV>`, `snapshot`, `command`, `receipt`, and `attachment|<photo ID>`.

## Local Connect

For local QR codes with `handoff=1`, the phone opens its Wi-Fi listener and posts `{key,envelope}` to `https://web.monolog.notokens.co/api/local-pair/<session>`. `key` is the phone’s base64url SPKI. `envelope` contains the base64url-encoded encrypted `{address,expiresAt}` payload with purpose `local-handoff`. The browser fetches the offer after the user enters the PIN and can then open the envelope. Offers expire after five minutes. Older QR codes omit `handoff` and the phone displays the address and PIN for manual entry.

The browser initiates Local Network Access when the user confirms and grants access if prompted. Use the HTTPS client origin or the allowlisted loopback test origin, and `fetch` with `targetAddressSpace: "local"`. The phone listens only on its directly connected Wi-Fi address. Journal traffic goes directly over Wi-Fi, protected by the session envelope.

- `GET http://<phone address>/v1/pair` returns JSON with `session`, `key` (phone SPKI, unpadded base64url), and `expiresAt` in Unix milliseconds.
- `POST http://<phone address>/v1/exchange` takes an encrypted command envelope as `application/octet-stream` and returns an encrypted JSON response. Compare the returned session ID with the one in the QR before deriving the key.
- On logout send the encrypted `{"op":"disconnect"}` request and clear browser session state. The phone also expires idle sessions after five minutes, with an absolute limit of two hours.

### Commands

- `{"op":"sync","since":"<revision>"}` returns `{"snapshot":{...}}` or `{"unchanged":true}`. Omit `since` initially. Poll every two seconds while the browser is visible.
- `{"op":"photo","id":"<photo ID>"}` returns `{"mime":"image/jpeg","data":"<standard base64>"}`.
- `{"op":"save","type":"note","operationId":"<UUID>","id":"<note ID>","base":"<version>","title":"...","body":"..."}` updates an existing note. Omit `id` and `base` for a new journal note. Include `kind:"context"` for a new Context note.
- If the snapshot advertises `notePhotoEditing: true`, a note save may include `photos`, the complete desired attachment list of up to eight items. Use `{"id":"<existing photo ID>"}` to keep an attachment, or `{"data":"<standard base64 JPEG>"}` to add an image. An empty array removes all attachments. Omit `photos` to retain them. Clients accept source images smaller than 5 MB and encode new attachments as JPEG at up to 1600 pixels on the longest side and 480 KiB each. The phone returns `photoIds` in snapshot order on a successful attachment edit. Encrypted requests and Drive command files are limited to 7,000,000 bytes.
- Save or delete a reminder with `type:"reminder"`, the same id and version fields, and `title` and integer `dueAt` on Save.
- Save or delete an existing journal memory with `type:"memory"`, its string id and version. Save accepts `body` and `pinned`.
- Delete uses `op:"delete"`, with `type`, `operationId`, `id`, and `base`. Save and Delete responses are `{"operationId":"...","ok":true,"id":"...","version":"..."}` or `{"operationId":"...","ok":false,"error":"..."}`. Refresh the record when the phone rejects a stale version.

Snapshot fields are `revision`, `zone` (IANA time zone), `notes`, `memories`, and `reminders`. All IDs and versions are strings. Times are Unix milliseconds. Notes have `id`, `version`, `kind` (`journal` or `context`), `title`, `body`, `createdAt`, `updatedAt`, and a list of `photos` with `id` and `mime`. Context notes can also contain tags and voice annotations. Audio uses a standard base64 payload. Memories have `id`, `version`, `body`, `timestamp`, `day`, `source`, `pinned`, and optional `noteId` or `reminderId`. Reminders have `id`, `version`, `title`, and `dueAt`. Build Today from saved entries in the snapshot's time zone. The client supplies its own editor and display. AI is phone-only.

`dayBrief` is the latest available saved daily summary from the phone and contains `day` (ISO date), `text`, and `updatedAt`, or `null` if none exists. Web Connect uses the brief from the last published snapshot.

`personalProfile` contains the phone's preferred `name` and the `localSignIn` and `driveSignIn` timestamps (zero if unavailable). The first Local Connect `sync` after pairing includes `signIn: true`. A fresh Web Connect sign-in sends an encrypted `signin` command with an `operationId`. The phone records the Drive file's creation time when processing the command. Use `analysis` for screenshot OCR search without rendering it in note previews.

## Web Connect

Use Google Identity Services with scope `https://www.googleapis.com/auth/drive.appdata`. After Sync now on the phone, sign in with the same Google account in the browser. Drive files live in `appDataFolder` with these `appProperties`:

| Property | Value |
| --- | --- |
| `monologWeb` | `1` |
| `session` | ID from browser QR |
| `role` | `snapshot`, `command`, `receipt`, or `attachment` |
| `keyA`, `keyB` | Concatenate these snapshot properties to get the phone's public key |
| `until` | Session expiration in milliseconds |
| `operation` | UUID on command and receipt files |
| `item` | Photo ID on attachment files |

List files within the requested session. The phone writes the snapshot as a binary `snapshot` envelope containing the inner snapshot object. Join snapshot `keyA` and `keyB` before decoding the SPKI. Prefer the newest snapshot. Photo metadata includes `fileId`. Download each requested photo with `files.get?alt=media`, then open the `attachment|<photo ID>` envelope as JPEG bytes. On each explicit Save or Delete, upload one encrypted `command` file. The phone applies each operation once, writes an encrypted `receipt` with the result, and removes successful commands. For a follow-up Save or Delete before the earlier receipt arrives, include `after` with the earlier operation UUID to use its resulting ID and version. In case of conflict, the original command remains available for recovery. Show the receipt and ask for a fresh phone snapshot before retrying.

Temporary browser sessions clear content and keys on logout. On logout the browser can delete its snapshots, attachments, and receipts. Keep submitted commands in Drive until the phone has applied them. Warn users to run Sync now or Restore web changes on the phone before ending a session. The phone checks Drive about every three hours, subject to Android scheduling. Temporary and explicitly retained Drive pairings expire within 24 hours. The phone gives pending commands one final processing opportunity before cleaning up an expired pairing.
