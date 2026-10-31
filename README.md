# Hearing Studio 0.5 — Android background audio

Experimental sound enhancement app for **POCO C71 / AirPods Pro 3**.

## What works
- Local microphone monitoring: phone microphone or Bluetooth hands-free mic (if offered by Android).
- Scene filters: four-band adaptive attenuation, speech-band emphasis, low-frequency high-pass and light compression.
- Presets Conversation, Transport, Street, Neutral, Custom.
- Stereo left/right level and 5-band EQ; Bluetooth communication output may be mono.
- **Foreground service of type microphone**, started only by the user while the app screen is visible.
- Runs when the UI is minimized or screen turned off, with an ongoing Android notification and **Stop** action.
- Retains settings across UI restarts using private SharedPreferences.
- No recordings, network access or upload. Does not automatically restart mic after app process is killed.

## Important
- **Not a medical hearing aid.** No calibrated output or validated audiogram fitting.
- No access to AirPods transparency, no separate source recognition or noise classification.
- Bluetooth input/output may add substantial latency and reduce sound quality.
- Start with very low volume. Noise filtering may suppress speech and warning sounds.
- The phone may shut down the service due to battery optimization. If it stops in the background, try Settings > Apps > Hearing Studio > Battery > Unrestricted (labels vary).
- Active mic is indicated by Android. The app must be in the foreground to start capture; you cannot covertly enable mic in the background.
- Android 13+ notification permission is requested; Android may show foreground service operation in the task manager even when notification permission is declined.

## Build
GitHub Actions: JVM DSP checks + Android Gradle Plugin debug APK.
Download artifact from the latest successful workflow run and extract `app-debug.apk`.
CI debug APK signatures may differ across runs: if update installation fails, uninstall the previous build before installing 0.5.
