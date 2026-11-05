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
    private String[] lLines;
    private long[] lTimes;
    private int lCur = -2;
    private long lDur = -1;
    private Bitmap lCover;
    private int lastRev = -1;         // 上次交给场景时的 rev
    private boolean lOn0, lPlaying0;  // 上次交给场景的播放态
    private long lastPushAt;

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
        scene.wake();
    }
    /** 离开第三页：设置面板收起来 */
    public void leavePage() { if (ui != null) ui.closeAll(); }

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
    @Override public void press(String cmd) {
        if (act == null) return;
        if ("heart".equals(cmd)) return; // 收藏是车机媒体控制器的，car3d 这边不给：红心不出现
        final String m = "toggle".equals(cmd) ? "togglePlayPause" : cmd;
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

    // ---------- 手势（Hud 把第三页的触摸转过来；x、y 是 CSS 像素） ----------
    public void touch(final int a, float x, float y, long t) {
        syncCss();
        final float w = cssW, h = cssH;
        if (w <= 1 || h <= 1) return;
        final float nx = x / w, ny = y / h;
        glView.queueEvent(() -> scene.touch(a, nx, ny, t));
    }

    // ---------- 媒体数据桥接 ----------
    public void media(boolean on, boolean playing, String title, String artist, String track,
                      String[] lines, long[] times, int cur, long dur, long pos, Bitmap cover) {
        MediaState.Snap s = new MediaState.Snap();
        s.on = on;
        s.playing = playing;
        s.full = lines != null && lines.length > 0;
        s.rawPlaying = playing;
        s.canPlay = true;
        s.track = track == null ? "" : track;
        s.title = title == null ? "" : title;
        s.artist = artist == null ? "" : artist;
        s.trackCmd = "";
        s.lines = lines == null ? new String[0] : lines;
        s.times = times;
        s.cur = cur;
        s.dur = dur;
        s.posBase = pos;
        s.posAt = SystemClock.elapsedRealtime();
        s.cover = cover;
        s.coverTrack = s.track;
        s.heart = BydMedia.HIDE;
        // 只有真的变了才递增：Scene 按 rev 判断换歌、换歌词，每帧都递增会把歌词贴图反复重建
        if (!s.track.equals(lTrack) || !s.title.equals(lTitle) || !s.artist.equals(lArtist) || dur != lDur) {
            s.revTrack = ++rev;
            lTrack = s.track; lTitle = s.title; lArtist = s.artist; lDur = dur;
        }
        if (!sameLines(s.lines, s.times)) { s.revLines = ++rev; lLines = s.lines; lTimes = s.times; }
        if (cover != lCover) { s.revCover = ++rev; lCover = cover; }
        if (cur != lCur) { s.revCur = ++rev; lCur = cur; }
        long now = SystemClock.elapsedRealtime();
        // Hud 每帧都来推一份：没变就少建几个对象（真的变了、或者播放位置差不多每半秒对齐一次才交给场景）
        if (rev == lastRev && playing == lPlaying0 && on == lOn0 && now - lastPushAt < 400) return;
        lastRev = rev; lOn0 = on; lPlaying0 = playing; lastPushAt = now;
        scene.setSnap(s);
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
