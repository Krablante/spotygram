# Verification record — 0.1.0

Verification is manual UI interaction and runtime inspection. There are no unit, integration, or smoke-test files. This document records observations, not assumed success.

Environment: Android 16 (API 36), x86-64 Pixel 6 emulator, 720×1560 at 288 dpi. Host work is performed sequentially with limited CPU/memory after the owner's load constraint.

## Observed

- Debug Kotlin/Java and Android packaging completed successfully.
- First launch and local-mode onboarding rendered correctly in the light theme.
- Android's system file picker imported the public-domain Wikimedia recording *The Entertainer* (Ogg Opus); the library showed its duration, filename fallback, and local indicator.
- Media3 playback reached the system `PLAYING` state with an advancing position and a populated media session. No application crash or ExoPlayer error was present in the inspected logs.

## Remaining release checks

Native packaging for both ABIs, release signing, production login entry, player layout in both themes, seeking, pause/resume, screen-off/offline behavior, favorites/playlists, state restoration, and release installation are checked before final publication. Update this section with actual results as they are observed.

## External authentication limit

An independent attempt to prepare a Telegram sandbox account reached Telegram's test DC (`test_mode=True`), but its documented fixed code was rejected with `PHONE_CODE_INVALID`. No production account session was copied or opened. This does not verify authenticated production-account discovery, downloads, or streaming.

The owner must enter their own phone/code/password on their device. API application credentials are not a logged-in user session. Physical-device battery drain and Bluetooth behavior cannot be inferred from an emulator.
