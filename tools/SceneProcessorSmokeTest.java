package com.hearingstudio.demo;

/** Desktop Java smoke checks for the real-time DSP path. */
public final class SceneProcessorSmokeTest {
    public static void main(String[] args) {
        final int sampleRate = 48000;
        SceneProcessor neutral=new SceneProcessor(sampleRate);
        SceneProcessor quiet=new SceneProcessor(sampleRate);
        neutral.setSettings(new SceneProcessor.Settings(0,0,20,0));
        quiet.setSettings(new SceneProcessor.Settings(100,0,20,0));
        double neutralPower=0, suppressedPower=0;
        for(int n=0;n<sampleRate*3;n++){
            double sample=0.0005 * Math.sin(2*Math.PI*900*n/sampleRate);
            double x=neutral.process(sample);
            double y=quiet.process(sample);
            if (!Double.isFinite(x) || !Double.isFinite(y))
                throw new IllegalStateException("Non-finite DSP output");
            if(n>sampleRate) {
                neutralPower+=x*x;
                suppressedPower+=y*y;
            }
        }
        if (!(suppressedPower < neutralPower * 0.9))
            throw new IllegalStateException("Noise suppression not active: "+suppressedPower+" vs "+neutralPower);

        SceneProcessor focus=new SceneProcessor(sampleRate);
        SceneProcessor baseline=new SceneProcessor(sampleRate);
        focus.setSettings(new SceneProcessor.Settings(0,100,20,0));
        baseline.setSettings(new SceneProcessor.Settings(0,0,20,0));
        double boosted=0, unboosted=0;
        for(int n=0;n<sampleRate;n++){
            double sample=0.025*Math.sin(2*Math.PI*1500*n/sampleRate);
            double a=focus.process(sample),b=baseline.process(sample);
            if(!Double.isFinite(a)||!Double.isFinite(b))
                throw new IllegalStateException("Non-finite EQ output");
            if(n>sampleRate/2){boosted+=a*a;unboosted+=b*b;}
        }
        if(!(boosted > unboosted * 1.08))
            throw new IllegalStateException("Speech band focus not active");
        System.out.println("DSP smoke tests passed (suppression, speech EQ, finite output).");
    }
}
