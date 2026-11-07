package com.ljone.car3d;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/**
 * 媒体快照 → 琉光（第三页）的桥。把「什么时候把哪份数据交给 LgHost」从 Hud 里拆出来：
 * ① 歌词整理成逐句数组：内容没变就沿用同一个数组（LgHost 按引用判重，省掉逐行比较）；
 * ② 按页调度：停在第三页 200ms 推一次，其他页 900ms 一次（LgHost 自己还有 400ms 节流 + 内容判重）；
 * ③ 兜底补问：订阅推送一停，界面上的歌名/进度/播放态就会永远停在那一份——2.5s 没收到快照就主动问一次媒体。
 * Hud 只负责「有什么数据」（Src），这里负责调度，两边互不干扰。
 */
final class MediaBridge {
    interface Src {
        boolean on();
        boolean playing();
        boolean full();
        String title();
        String artist();
        String track();
        String pkg();
        List<String> lines();   // 歌词（整首，或者只听到的那几句）
        List<Long> times();     // 每句开始时间（没有时间就是 null，长度也可能对不上）
        int cur();              // 现在第几句（没有整首歌词时按播放位置兜底，见 Hud.cur）
        long dur();
        long pos();
        Bitmap cover();
        int page();             // 当前页索引（2 = 琉光）
        void pollMedia();       // 主动问一次媒体（订阅推送的兜底）
    }

    private final Src src;
    private final Handler main = new Handler(Looper.getMainLooper());
    private com.x3card.car3d.lg.LgHost host;
    private String[] lines;
    private long[] times;
    private List<String> lineSrc;
    private int lineN = -1;
    private long lastAt = SystemClock.elapsedRealtime();

    MediaBridge(Src s) { src = s; }

    void attach(com.x3card.car3d.lg.LgHost h) {
        host = h;
        main.removeCallbacks(loop);
        main.post(loop);
    }

    /** 收到一份新的媒体快照：记时间，给「订阅是不是停了」的判断用 */
    void seen() { lastAt = SystemClock.elapsedRealtime(); }

    /** 数据刚到、停在第三页：立刻推一次，不等下一拍（泵仍在跑，管兜底） */
    void pushNow() { if (host != null && src.page() == 2) push(); }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (host != null && src.page() == 2) {
                push();
                if (SystemClock.elapsedRealtime() - lastAt > 2500) { lastAt = SystemClock.elapsedRealtime(); src.pollMedia(); }
            }
            main.postDelayed(this, src.page() == 2 ? 200 : 900);
        }
    };

    private void push() {
        if (host == null) return;
        List<String> all = src.lines();
        if (all != lineSrc || all.size() != lineN) {
            lineSrc = all;
            lineN = all.size();
            lines = all.toArray(new String[0]);
            times = null;
            List<Long> ts = src.times();
            if (ts != null && ts.size() == all.size()) {
                times = new long[ts.size()];
                for (int i = 0; i < times.length; i++) times[i] = ts.get(i);
            }
        }
        host.media(src.on(), src.playing(), src.full(), src.title(), src.artist(), src.track(), src.pkg(),
                   lines, times, src.cur(), src.dur(), src.pos(), src.cover());
    }
}
