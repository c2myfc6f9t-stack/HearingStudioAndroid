# Hearing Studio 0.4 — Android experiment

Test app developed for **POCO C71 + AirPods Pro 3**.

## What is implemented

- Phone microphone or Bluetooth hands-free microphone, subject to Android device routing.
- Real-time 4-band adaptive noise attenuation based on signal energy and estimated background level.
- Speech-band emphasis with two peaking filters, wind/rumble high-pass, and mild peak compression.
- Scene presets: Conversation, Transport, Street, Neutral, Custom.
- Independently adjustable left/right levels and 5-band EQ in stereo media mode.
- Controls run entirely offline. Audio is never saved or uploaded.
- CPU-friendly 5-ms processing frames. Actual end-to-end latency is dominated by Android/Bluetooth and is not measured or guaranteed.

## Important limitations

- **Not a medical hearing aid.** No clinically validated audiogram fitting or calibrated sound pressure.
- No AI voice identification, no isolation of your own voice vs another speaker, no access to the AirPods transparency DSP.
- In Bluetooth headset mode Android may switch to mono, reduced-bandwidth hands-free audio: then independent left/right processing is not guaranteed.
- The noise gate **can also suppress quiet speech or alerts** and may create audible pumping. Use carefully.
- Start at low system volume. Stop if you hear feedback or discomfort. Do not use this app in traffic or for safety-critical hearing.
- Each CI build uses an ephemeral debug signing key; uninstall older versions if Android refuses to update.

## Build / validation

GitHub Actions runs a JVM DSP smoke test and standard Android Gradle Plugin assembleDebug.
Download artifact `hearing-studio-debug-apk` from the latest successful run in Actions, unpack, install `app-debug.apk`.

To install on POCO C71, allow installation from the browser or Files app when prompted.
