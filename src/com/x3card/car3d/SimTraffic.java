package com.ljone.car3d;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * 模拟红绿灯数据源：红→绿→黄 循环，带倒计时，每秒推进。
 * 用于实验分支演示顶栏倒计时效果，不依赖任何外部 SDK。
 */
public final class SimTraffic implements TrafficLightSource {
    private final LightInfo cur = new LightInfo();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final int[] seq = { LightInfo.RED, LightInfo.GREEN, LightInfo.YELLOW };
    private final int[] dur = { 9, 12, 3 };   // 各相位时长（秒）
    private int phase = 0, tick = 0;

    private final Runnable run = this::tick;
    private void tick() { step(); h.postDelayed(run, 1000); }

    private void step() {
        tick++;
        if (tick > dur[phase]) { phase = (phase + 1) % seq.length; tick = 0; }
        cur.state = seq[phase];
        cur.remainSec = Math.max(0, dur[phase] - tick);
        cur.distanceM = 80 + rnd.nextInt(500);
        cur.lightId = 1;
        cur.ts = System.currentTimeMillis();
    }

    @Override public void attach(Context c) { step(); h.post(run); }
    @Override public void detach() { h.removeCallbacks(run); }
    @Override public LightInfo current() { return cur; }
}
