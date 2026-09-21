package com.cameraframelab;

import java.util.ArrayList;
import java.util.List;

public final class IntervalAnalyzerTest {
    public static void main(String[] args) {
        testPerfect60();
        testOneMissingFrame();
        testNoFalseGapFromMinorJitter();
        System.out.println("IntervalAnalyzer tests: OK");
    }

    private static void testPerfect60() {
        List<Long> t = make(120, 16_666_667L);
        IntervalAnalyzer.Stats s = IntervalAnalyzer.analyzeNanoseconds(t, 60);
        require(s.gapCount == 0, "perfect60 gapCount");
        require(s.estimatedMissingFrames == 0, "perfect60 missing");
        require(Math.abs(s.effectiveFps - 60.0) < 0.01, "perfect60 fps=" + s.effectiveFps);
    }

    private static void testOneMissingFrame() {
        List<Long> t = new ArrayList<>();
        long now = 0;
        for (int i=0;i<120;i++) {
            t.add(now);
            now += (i == 50 ? 33_333_334L : 16_666_667L);
        }
        IntervalAnalyzer.Stats s = IntervalAnalyzer.analyzeNanoseconds(t, 60);
        require(s.gapCount == 1, "missing gapCount=" + s.gapCount);
        require(s.estimatedMissingFrames == 1, "missing estimate=" + s.estimatedMissingFrames);
    }

    private static void testNoFalseGapFromMinorJitter() {
        List<Long> t = new ArrayList<>();
        long now=0;
        long[] jitter={16_000_000L,17_100_000L,16_300_000L,17_000_000L};
        for(int i=0;i<200;i++){t.add(now); now += jitter[i%jitter.length];}
        IntervalAnalyzer.Stats s=IntervalAnalyzer.analyzeNanoseconds(t,60);
        require(s.gapCount==0,"jitter gapCount="+s.gapCount);
    }

    private static List<Long> make(int count, long step) {
        List<Long> out=new ArrayList<>(); long n=0; for(int i=0;i<count;i++){out.add(n); n+=step;} return out;
    }
    private static void require(boolean ok, String msg) { if(!ok) throw new AssertionError(msg); }
}
