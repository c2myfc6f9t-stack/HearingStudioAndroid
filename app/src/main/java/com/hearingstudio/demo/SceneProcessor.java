package com.hearingstudio.demo;

/**
 * Lightweight real-time, single-channel DSP processor for experimental listening.
 * No AI source separation: speech presence is NOT classified. The adaptive gate
 * reduces quasi-stationary low-level noise when it is below each band's estimated
 * noise floor; voices and alarms may also be affected.
 *
 * This code never records or transmits audio. It uses no look-ahead, and adds
 * no explicit processing latency, but Bluetooth routing latency remains.
 */
public final class SceneProcessor {
    public static final class Settings {
        public final int noise, speech, rumble, compression; // 0..100
        public Settings(int noise, int speech, int rumble, int compression) {
            this.noise = clip(noise);
            this.speech = clip(speech);
            this.rumble = clip(rumble);
            this.compression = clip(compression);
        }
        private static int clip(int value) { return Math.max(0, Math.min(100, value)); }
    }

    private static final double[] SPLITS = {350, 1800, 5400};
    private final double[] splitState = new double[3];
    private final double[] splitAlpha = new double[3];
    private final double[] envelope = new double[4];
    private final double[] noiseFloor = {0.003, 0.003, 0.003, 0.003};
    private final double[] gain = {1.0, 1.0, 1.0, 1.0};
    private final double[] bands = new double[4];
    private final double sampleRate;
    private Settings active;
    private final PeakFilter speech1, speech2;
    private double hpX, hpY, compressionEnvelope, highPassAlpha;

    public SceneProcessor(int sampleRate) {
        if (sampleRate <= 0) throw new IllegalArgumentException("invalid rate");
        this.sampleRate = sampleRate;
        for (int i = 0; i < 3; i++)
            splitAlpha[i] = 1.0 - Math.exp(-2.0 * Math.PI * SPLITS[i] / sampleRate);
        speech1 = new PeakFilter(sampleRate, 1350, 0.8);
        speech2 = new PeakFilter(sampleRate, 2700, 1.1);
        setSettings(new Settings(35, 65, 40, 45));
    }

    /** Call on the audio thread at most once per buffer, with immutable settings. */
    public void setSettings(Settings settings) {
        if (settings == null || settings == active) return;
        active = settings;
        highPassAlpha = Math.exp(-2.0 * Math.PI * (35 + settings.rumble * 1.15) / sampleRate);
        speech1.updateDb(settings.speech * 0.043); // <= +4.3 dB
        speech2.updateDb(settings.speech * 0.035); // <= +3.5 dB
    }

    /** Process one normalized mono PCM sample. */
    public double process(double sample) {
        final Settings config = active;
        // A tunable rumble / wind / traffic low-cut: 35 to 150 Hz.
        final double hp = highPassAlpha * (hpY + sample - hpX);
        hpX = sample;
        hpY = hp;

        // Complementary one-pole crossover bands, summing to input exactly.
        for (int i = 0; i < 3; i++)
            splitState[i] += splitAlpha[i] * (hp - splitState[i]);
        bands[0] = splitState[0];
        bands[1] = splitState[1] - splitState[0];
        bands[2] = splitState[2] - splitState[1];
        bands[3] = hp - splitState[2];

        double sum = 0.0;
        final double strength = config.noise / 100.0;
        final double minGain = 1.0 - 0.78 * strength; // max ~13 dB attenuation, not silence
        for (int i = 0; i < 4; i++) {
            final double magnitude = Math.abs(bands[i]);
            // 8 ms attack / 90 ms release envelope.
            final double smooth = magnitude > envelope[i] ? 0.0026 : 0.00024;
            envelope[i] += smooth * (magnitude - envelope[i]);
            final double env = envelope[i];
            // Learn minima relatively quickly, rise very slowly (~5 seconds).
            final double learning = env < noiseFloor[i] ? 0.0004 : 0.000004;
            noiseFloor[i] = Math.max(0.00002,
                noiseFloor[i] + learning * (env - noiseFloor[i]));
            final double snr = env / (noiseFloor[i] + 0.00002);
            final double open = Math.max(0.0, Math.min(1.0, (snr - 1.2) / 2.2));
            final double desired = minGain + (1.0 - minGain) * open;
            // Open quickly, close slowly to reduce syllable chopping.
            final double gainSmoothing = desired > gain[i] ? 0.004 : 0.0003;
            gain[i] += gainSmoothing * (desired - gain[i]);
            sum += bands[i] * gain[i];
        }
        sum = speech2.process(speech1.process(sum));
        // Compressor only controls peaks; it cannot reconstruct missing speech.
        final double abs = Math.abs(sum);
        compressionEnvelope += (abs > compressionEnvelope ? 0.0018 : 0.00012)
            * (abs - compressionEnvelope);
        final double ratio = 1.0 + config.compression * 0.019; // 1:1 to 2.9:1
        final double threshold = 0.065; // about -24 dBFS
        if (compressionEnvelope > threshold && config.compression > 0) {
            double dynamicGain = Math.pow(threshold / compressionEnvelope, 1.0 - 1.0 / ratio);
            sum *= dynamicGain;
        }
        return Double.isFinite(sum) ? sum : 0;
    }

    private static final class PeakFilter {
        private final double rate, frequency, q;
        private double b0=1, b1, b2, a1, a2, s1, s2;
        PeakFilter(double rate, double frequency, double q) {
            this.rate=rate;this.frequency=frequency;this.q=q;
        }
        void updateDb(double db) {
            final double A = Math.pow(10, db / 40);
            final double w0 = 2*Math.PI*frequency/rate;
            final double alpha = Math.sin(w0)/(2*q);
            final double norm = 1+alpha/A;
            b0=(1+alpha*A)/norm;
            b1=(-2*Math.cos(w0))/norm;
            b2=(1-alpha*A)/norm;
            a1=(-2*Math.cos(w0))/norm;
            a2=(1-alpha/A)/norm;
        }
        double process(double x){
            final double y=b0*x+s1;
            s1=b1*x-a1*y+s2;
            s2=b2*x-a2*y;
            return y;
        }
    }
}
