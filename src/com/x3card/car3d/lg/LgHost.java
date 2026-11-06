package com.x3card.car3d.lg;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import java.util.Calendar;
import java.util.Collections;

import javax.microedition.khronos.opengles.GL10;

/**
 * 把琉光整个搬进 car3d 的第三页：场景（Scene，GL），加上琉光自己的那层界面（Ui：右上角齿轮、全屏键、设置面板、提示）。
 * Stage 在第三页把整帧 GL 委托给这里；车况页仍走 CarPage。
 * 数据由 Hud 的媒体快照桥接成 MediaState.Snap，不另开宿主会话；播放控制交回 car3d 的媒体出口。
 * 第三页不显示任何车况的东西——car3d 那边把车况层整个藏掉，开关由 MainActivity 调。
 */
public final class LgHost implements Canvas3D, Scene.Host, Ui.Actions {
    /** 交回 car3d 的（都在主线程上执行） */
    public interface Act {
        void media(String cmd);      // togglePlayPause / next / previous
        void seek(long ms);
        void fullscreen();
        void toast(String msg);
    }

    private final GLSurfaceView glView;
    private final Context ctx;
    private final float dens;
    private final Act act;
    private final Scene scene;
    private final Settings st;
    private final SongCovers covers;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;
    private boolean created, booted, day;
    private int lastW = -1, lastH = -1, rev;
    private float cssW = 1, cssH = 1, resK = 1;

    // 上一份快照（只在真的变了才递增 rev，Scene 按 rev 判断换歌 / 换歌词，每帧都变会导致歌词反复重建）
    private String lTrack = "", lTitle = "", lArtist = "";
    private String[] lLines;          // 交给场景的那个数组（内容没变就沿用同一个引用：Snap.same 按引用比）
    private long[] lTimes;
    private int lCur = -2;
    private long lDur = -1;
    private Bitmap lCover;
    // ★这四个版本号必须一路递增、每份快照都带上：Scene 只拿它跟上次比来判断「换歌 / 换歌词 / 换封面 / 换句」。
    //   曾经它们只在变化的那一次赋值、其余时候留默认 0，于是每一帧都被当成「换了歌词」，每帧重建整套歌词贴图
    //   （画面卡、歌词不滚，GL 线程被拖慢后点击还会超时→播控点不动）。
    private int rTrack, rLines, rCover, rCur;
    private boolean lOn0, lPlaying0;  // 上次交给场景的播放态
    private long lastPushAt;
    private static final String[] NO_LINES = new String[0];
    private static final long[] NO_TIMES = new long[0];

    public LgHost(GLSurfaceView v, Context c, float density, Act a) {
        glView = v;
        ctx = c;
        dens = density;
        act = a;
        st = Settings.load(c);
        scene = new Scene(this, this, density);
        covers = new SongCovers(c);
        scene.setCovers(covers);
        scene.setOpts(st);
        day = wantDay();
        scene.setDay(day);
        scene.setSongs(Collections.<BydMedia.Song>emptyList(), -1); // car3d 的宿主不给播放队列：歌单不出现
        applyRain();
    }
    /** 玻璃上的雨珠：设置开了才有。car3d 这边没接车机天气，「自动」当没有雨（下雨的事交给车机自己的界面） */
    private void applyRain() { scene.setRain("on".equals(st.rain)); }

    // ---------- Canvas3D ----------
    @Override public Scene scene() { return scene; }
    @Override public View view() { return glView; }
    @Override public void onPause() { }
    @Override public void onResume() { }
    @Override public void requestRender() { glView.requestRender(); }
    @Override public void queueEvent(Runnable r) { glView.queueEvent(r); }
    @Override public float cssW() { return cssW; }
    @Override public float cssH() { return cssH; }
    @Override public float res() { return resK; }
    @Override public void setRes(float k) { resK = k; }

    /** MainActivity 量到窗口尺寸后告诉场景（CSS 像素） */
    public void size(float w, float h) {
        if (w > 1 && h > 1) { cssW = w; cssH = h; }
    }
    /** 画布尺寸还不准（布局还没走到）时从画布本身量一次 */
    private void syncCss() {
        if (cssW > 1 && cssH > 1) return;
        int vw = glView.getWidth(), vh = glView.getHeight();
        if (vw > 1 && vh > 1) { cssW = vw / dens; cssH = vh / dens; }
    }

    // ---------- 琉光的界面（第三页叠在场景上，只有这里有） ----------
    /** 第三页盖在场景上的那层（齿轮、全屏键、设置面板、提示）；第一次用时建 */
    public View uiView() {
        if (ui == null) {
            ui = new Ui(ctx, st, this);
            ui.setDay(day);
            ui.setWait(true);
        }
        return ui;
    }
    /** 全屏键：full 在小窗口里、small 在全屏页里、空的不显示 */
    public void setFsMode(String m) { if (ui != null) ui.setFsMode(m); }

    /** 进第三页：第一次进来放开场动画 */
    public void enter() {
        syncCss();
        if (!booted) { booted = true; scene.boot(true); }
        else scene.setOpts(st);
        scene.keepAlive(true); // 停在这一页：极光这些一直在动的效果要接着跑
        scene.wake();
    }
    /** 离开第三页：设置面板收起来，别再持续出帧（GL 画布要还给车况页按需画） */
    public void leavePage() {
        scene.keepAlive(false);
        if (ui != null) ui.closeAll();
        // 手势可能在切页途中断掉（UP 没送到）：给场景补一个取消，别让它一直以为手指还按着，否则下次进页点播控会没反应
        final long t = SystemClock.uptimeMillis();
        glView.queueEvent(() -> scene.touch(3, 0, 0, t));
    }

    // ---------- Ui.Actions ----------
    @Override public void set(String k, Object v) {
        st.set(k, v);
        st.save(ctx);
        if ("theme".equals(k)) { day = wantDay(); scene.setDay(day); if (ui != null) ui.setDay(day); }
        if ("rain".equals(k)) applyRain();
        scene.setOpts(st);
        if (ui != null) ui.refresh();
        // 抗锯齿、渲染方式只能在建画布时定，car3d 的画布是车况页那一块：改了等下次打开生效
        if (("aa".equals(k) || "render".equals(k)) && act != null) act.toast("重新打开应用后生效");
    }
    @Override public void fullscreen() { if (act != null) act.fullscreen(); }
    @Override public void locate() { scene.shelfLocate(); }

    private boolean wantDay() {
        if ("light".equals(st.theme)) return true;
        if ("dark".equals(st.theme)) return false;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY); // 车机没报日夜：6～19 点是白天（同琉光）
        return h >= 6 && h < 19;
    }
    /** 主线程：日夜按钟点走时，过一会儿再看一次（开车进隧道、天黑） */
    public void tickTheme() {
        if (!"auto".equals(st.theme)) return;
        boolean d = wantDay();
        if (d == day) return;
        day = d;
        scene.setDay(d);
        if (ui != null) ui.setDay(d);
    }
    /** 主线程：回到前台重新读一次设置（全屏页和小窗口共用一份） */
    public void reloadSettings() {
        st.copyFrom(Settings.load(ctx));
        scene.setOpts(st);
        applyRain();
        day = wantDay();
        scene.setDay(day);
        if (ui != null) { ui.setDay(day); ui.refresh(); }
    }

    // ---------- Scene.Host（GL 线程调：交回主线程） ----------
    /** 按下播放/暂停后，车机回推状态常常慢半拍甚至不回：先本地把播放态翻过来，按钮立即可见（2 秒内以真值为准） */
    private volatile boolean pFlip;
    private volatile long pFlipAt;
    @Override public void press(String cmd) {
        if (act == null) return;
        if ("heart".equals(cmd)) return; // 收藏是车机媒体控制器的，car3d 这边不给：红心不出现
        final String m = "toggle".equals(cmd) ? "togglePlayPause" : cmd;
        if ("toggle".equals(cmd)) { pFlip = !lPlaying0; pFlipAt = SystemClock.elapsedRealtime(); }
        pressN.incrementAndGet();
        lastCmd = cmd;
        main.post(() -> act.media(m));
    }
    @Override public void seek(final long ms) { if (act != null) main.post(() -> act.seek(ms)); }
    @Override public void btnWait(final boolean w) { main.post(() -> { if (ui != null) ui.setWait(w); }); }
    @Override public void playIndex(int i) { }
    @Override public void shelfState(final boolean open) { main.post(() -> { if (ui != null) ui.setShelfOn(open); }); }
    @Override public void locate(final int dir, final float y) { main.post(() -> { if (ui != null) ui.setLocate(dir, y); }); }
    @Override public void coverAt(float l, float t, float r, float b) { } // 新手引导在 car3d 里不出

    // ---------- Stage 调用 ----------
    /** 第一次进第三页时补一次 surface 创建；之后每帧直接委托 */
    public void draw(GL10 gl, int w, int h) {
        syncCss();
        if (!created) {
            scene.onSurfaceCreated(gl, null);
            created = true;
            lastW = -1; // 建完 GL 资源后必须补一次尺寸
        }
        if (w != lastW || h != lastH) { // 只在尺寸变化时重置，不能每帧调 onSurfaceChanged
            lastW = w; lastH = h;
            scene.onSurfaceChanged(gl, w, h);
        }
        scene.onDrawFrame(gl);
    }

    /** 画布尺寸变化（分屏/旋屏）：打个标记，让下一帧在 GL 线程里补尺寸 */
    public void surfaceChanged(int w, int h) { lastW = -1; lastH = -1; }

    /** 离开页面时清 GL 缓存，避免和车况页的 VAO/FBO/program 串味 */
    public void leave() { G.resetCache(); }

    /** 换画布重建：收掉封面那几个后台线程 */
    public void quit() { covers.quit(); }

    // ---------- 手势 ----------
    /**
     * ★和原版 SceneView.onTouchEvent 一字不差：坐标只除以画布自身的宽高，再交给场景。
     * 原版就是这么做的（`e.getX() / getWidth()`），任何中间层的 density / CSS 换算都不需要，
     * 也不会因为某一处尺寸没同步就让命中判定整片偏掉。
     */
    public void touchNorm(final int a, float nx, float ny, long t) {
        if (a == 0) touchN.incrementAndGet();
        glView.queueEvent(() -> scene.touch(a, nx, ny, t));
    }

    // ---------- 自检（第三页左上角一行小字，定位「摸不到 / 命中不了 / 命令没发」） ----------
    private final java.util.concurrent.atomic.AtomicInteger touchN = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger pressN = new java.util.concurrent.atomic.AtomicInteger();
    private volatile String lastCmd = "";
    /** 收、发计数：触=手指按到场景的次数、令=真的发出媒体命令的次数 */
    public String diag() {
        String s = "触" + touchN.get() + " 令" + pressN.get() + (lastCmd.isEmpty() ? "" : "·" + lastCmd);
        if (scene != null) s += " " + scene.diag();
        return s;
    }

    // ---------- 媒体数据桥接 ----------
    public void media(boolean on, boolean playing, boolean full, String title, String artist, String track,
                      String[] lines, long[] times, int cur, long dur, long pos, Bitmap cover) {
        String tr = track == null ? "" : track, ti = title == null ? "" : title, ar = artist == null ? "" : artist;
        String[] ls = lines == null ? NO_LINES : lines;
        // 刚按过播放/暂停：车机还没回推就把本地翻过来的状态顶上去（真值一到立刻以真值为准）
        if (pFlipAt > 0) {
            if (playing == pFlip) pFlipAt = 0;                             // 车机跟上了：撤掉乐观值
            else if (SystemClock.elapsedRealtime() - pFlipAt < 2000) playing = pFlip; // 还没跟上：先按本地值画
            else pFlipAt = 0;                                              // 太久没跟上：以后台为准，别一直骗人
        }
        // ★换歌只认曲目号（没有曲目号时才认歌名+歌手）。原版这个判定在 MediaState 内部由它自己维护；
        //   桥接层要是把时长也算进去，车机把 duration 微调一次就被当成换了歌 —— 场景会反复「等新封面」，
        //   歌词停在原处不动、封面反复翻面。这是「歌词不滚动」的一个真正来源。
        boolean chText = !ti.equals(lTitle) || !ar.equals(lArtist);
        // 有曲目号就只认曲目号；没有曲目号（蓝牙这类）才认歌名+歌手。
        // 曲目号从有变成空（车机偶尔漏报）不算换歌，沿用上一首的号 —— 否则会被当成一首全新的歌
        boolean chTrack = !tr.isEmpty() ? !tr.equals(lTrack) : (lTrack.isEmpty() && chText);
        // 先看有没有真的变：Hud 会定时推，没变就不建对象、不叫醒场景
        boolean chLines = !sameLines(ls, times);
        boolean chCover = cover != lCover;
        boolean chCur = cur != lCur;
        boolean idle = !chTrack && !chText && !chLines && !chCover && !chCur && playing == lPlaying0 && on == lOn0 && dur == lDur;
        long now = SystemClock.elapsedRealtime();
        // 什么都没变、也没到半秒：连快照都不建（播放位置由 Scene 自己按墙钟往后推，不用每帧对齐）
        if (idle && now - lastPushAt < 400) return;
        if (chTrack) { rTrack = ++rev; lTrack = tr; lTitle = ti; lArtist = ar; }
        else if (chText) { lTitle = ti; lArtist = ar; } // 歌名/歌手变了：只换文字，不算换歌
        if (chLines) { rLines = ++rev; lLines = ls; lTimes = times == null ? NO_TIMES : times; }
        if (chCover) { rCover = ++rev; lCover = cover; }
        if (chCur) { rCur = ++rev; lCur = cur; }
        lDur = dur; lOn0 = on; lPlaying0 = playing; lastPushAt = now;

        MediaState.Snap s = new MediaState.Snap();
        s.on = on;
        s.playing = playing;
        s.full = full; // 有整首歌词才滚整首；只有听到的句子时只显示当前句（同琉光 MediaState.full）
        s.rawPlaying = playing;
        s.canPlay = true;
        s.track = lTrack;
        s.title = lTitle;
        s.artist = lArtist;
        s.trackCmd = "";
        s.lines = lLines == null ? NO_LINES : lLines;
        s.times = lTimes == null ? NO_TIMES : lTimes;
        s.cur = cur;
        s.dur = dur;
        s.posBase = pos;
        s.posAt = now;
        s.cover = cover != null ? cover : placeholder(ti, ar);
        s.coverTrack = s.track;
        s.heart = BydMedia.HIDE;
        s.revTrack = rTrack;
        s.revLines = rLines;
        s.revCover = rCover;
        s.revCur = rCur;
        scene.setSnap(s);
    }

    /** 宿主没给封面（蓝牙音乐、没内嵌封面的歌）时用琉光自己的占位封面，同原版 MediaState.makeCover。
     *  否则场景在换歌后一直认为「这首的封面还没到」（Scene.syncMedia 的 pend 分支），歌词会停在原处不动 */
    private Bitmap phCover;
    private String phKey = "";
    private Bitmap placeholder(String ti, String ar) {
        String k = ti + "\u0001" + ar;
        if (phCover == null || !k.equals(phKey)) { phKey = k; phCover = MediaState.makeCover(ti, ar); }
        return phCover;
    }

    /** 歌词有没有真的变（Hud 每帧都传一份新的数组/时间表：按内容比，不然每帧都当成换歌词，歌词贴图会反复重建） */
    private boolean sameLines(String[] a, long[] ta) {
        String[] b = lLines;
        long[] tb = lTimes;
        if (a == b && ta == tb) return true;
        if (b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) { String x = a[i], y = b[i]; if (x == null ? y != null : !x.equals(y)) return false; }
        if (ta == tb) return true;
        if (ta == null || tb == null || ta.length != tb.length) return false;
        for (int i = 0; i < ta.length; i++) if (ta[i] != tb[i]) return false;
        return true;
    }
}
