# Hearing Studio (Android experiment)

Test version for POCO C71, Android 15 Go and connected AirPods Pro 3.

- Uses **the phone microphone**, not AirPods' external microphones.
- Plays stereo processed audio through Android's currently selected media output.
- Separate output levels and five EQ controls per ear (250, 500, 1000, 2000, 4000 Hz).
- Runs locally. No networking permission, no audio file storage.
- Build with GitHub Actions. Download the `hearing-studio-debug-apk` artifact after a successful run.

**Not a medical device or hearing aid.** No audiogram fitting, calibration, acoustic SPL guarantee, voice separation, or AirPods transparency control. Start at low volume, stop if you hear feedback. Bluetooth delay makes this unsuitable for safety-critical listening.
