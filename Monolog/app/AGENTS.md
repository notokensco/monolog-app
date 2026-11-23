# Quick Capture

Keep the existing keyboard M-key and companion Quick Capture flows unless the user explicitly asks to change them. Use Android's existing accessibility shortcut for capture outside the keyboard. Show the double-tap menu only beside a verified system button. Do not create a separate Monolog button. Both entry points use the same protected capture flow.

Tapping Save in the capture editor generates a title from the user's notes or on-device screenshot analysis and saves directly. The title can be edited later. Closing the editor discards an unsaved capture.

# Local APK retention

Keep at most the newest two locally built Monolog APKs. When delivering another build, keep the current APK and its immediate predecessor if one exists, and remove older generated APKs. Apply this to Monolog build outputs and copies produced for testing. Preserve third-party samples and tracked release artifacts. Honor a user-requested pause on APK builds.

# Voice features

Apply speech backend and transcript cleanup upgrades through the shared Vox controller for keyboard dictation, spoken notes, journal notes, Quick Shortcuts, and capture notes. Use the selected offline model for keyboard and note recording when the model supports the chosen language. Moonshine Small Streaming supports English. Keep Android on-device speech available for keyboard dictation. Preserve voice-note audio when recognition fails.
