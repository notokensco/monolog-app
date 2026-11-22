Journal memory delivery status

Implemented in the current source:
- First-run setup for memory, keyboard activation, optional capture/microphone/notification permissions, appearance, and links to optional account and offline AI settings.
- The companion header no longer shows its AI provider. Capture details render note and pin cards instead of generated metadata Markdown.
- Compact encrypted memory records from Monolog-assisted typing, explicitly saved notes, captures, and reminders. Drafted typing is never labeled as a sent message.
- Edit, pin, delete, date periods, source links, keyword lookup, opt-in offline sentence encoder download, and grounded journal questions.
- Recaps are requested explicitly. Opening the journal does not automatically start Gemma inference.
- Versioned encrypted Drive exports now include memory records and retain conflicting imported versions.

Build:
- :app:assembleDebug -PmonologAbi=arm64-v8a succeeded on 21 November 2026.
- No unit, device, UI, or APK tests were run for this delivery, as requested.

Still unfinished or unverified:
- Full yearly archive reclamation, removable encrypted year caches, safe expiry while a year is open, and rollover UX are unfinished. Existing local data is retained; automatic eviction is disabled.
- Cross-phone recovery of the new memory payload, actual multilingual encoder equivalence, search relevance, synthetic-year storage/latency, and typing responsiveness remain unverified.
- A successful APK build establishes compilation and packaging only, not runtime acceptance.
