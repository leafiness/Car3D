package com.x3card.car3d.lg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import com.ljone.car3d.Workers;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import javax.microedition.khronos.opengles.GL10;

/**
 * 把琉光整个搬进 car3d 的第三页：场景（Scene，GL），加上琉光自己的那层界面（Ui：提示条、找歌手的"正在放"、新手引导；
 * 设置键、全屏键、设置页、曲库、搜歌、键盘都画在场景里，见 TopKeys、SetPage、Library、Keyboard）。
 * Stage 在第三页把整帧 GL 委托给这里；车况页仍走 CarPage。
 *
 * 功能接入与原版 MainActivity 相同（都是零权限）：
 * - 车机媒体控制器（BydMedia）：收藏红心、播放模式、音源、播放队列（歌单）、切音源，binding 后推给场景；
 * - 「多媒体」的导出入口（Player）：跳到某一句（seek）、点歌单第几首、曲库的语音命令、搜歌插放；
 * - 曲库（Library）：换一批歌/按歌手放歌走语音，内容（音源图标、歌手长廊）从控制器与播放队列里来；
 * - 搜歌（SongFinder）：在线音乐自己的网页接口搜，点一首插进队列；
 * - 键盘（Keyboard/Pinyin）：读拼音词库，播放队列里的歌名、歌手加进词库；
 * - 新手引导（Guide）：封面一步 + 曲库一步；
 * - 雨珠自动（RainPick/BydWeather）：跟着车机天气 + 歌词里的下雨词；
 * - 日夜跟随车机（display.dayNightMode，car3d 的 CarData 本来就订了）。
 * 播放/暂停/上下首走 car3d 自己的宿主出口（act.media）；歌词与封面来自 car3d 的媒体快照（Hud 桥接），
 * 不另开宿主订阅——控制器（BydMedia）只补宿主没有的那些。
 *
 * 和原版 liuguang 的差别（都是因为第三页是"借用"车况页那块画布）：
 * ① 抗锯齿、渲染方式、全屏收起状态栏改不了：画布是车况页那一块，设置页里这几行藏掉（SetPage.Data 的门控）；
 * ② 停在第三页要持续出帧（极光、浮尘、水面按 clock 走），见 Scene.keepAlive。
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
    private Settings st;
    private final SongCovers covers;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;
    private boolean created, booted, day, onPage;
    private int lastW = -1, lastH = -1, rev;
    private float cssW = 1, cssH = 1, resK = 1;
    private boolean resRecalled;
    private String fsMode = "";

    private final SharedPreferences prefs;
    private ArtistBook artists;
    private BydMedia bydMedia;
    private final RainPick rp = new RainPick();
    private final BydWeather weather = new BydWeather();
    private ContentObserver weatherObs;
    private BroadcastReceiver weatherRx;
    private boolean rainNow;
    private Boolean carNight; // 车机的日夜模式（null：还没报过）；「跟随车机」时用它

    // 上一份快照（只在真的变了才递增 rev，Scene 按 rev 判断换歌 / 换歌词，每帧都变会导致歌词反复重建）
    private String lTrack = "", lTitle = "", lArtist = "", lPkg = "";
    private String[] lLines;          // 交给场景的那个数组（内容没变就沿用同一个引用：Snap.same 按引用比）
    private long[] lTimes;
    private int lCur = -2;
    private long lDur = -1, lastPos;
    private boolean lastFull;
    private Bitmap lCover;
    // ★这四个版本号必须一路递增、每份快照都带上：Scene 只拿它跟上次比来判断「换歌 / 换歌词 / 换封面 / 换句」。
    //   曾经它们只在变化的那一次赋值、其余时候留默认 0，于是每一帧都被当成「换了歌词」，每帧重建整套歌词贴图
    //   （画面卡、歌词不滚，GL 线程被拖慢后点击还会超时→播控点不动）。
    private int rTrack, rLines, rCover, rCur;
    private boolean lOn0, lPlaying0;  // 上次交给场景的播放态
    private long lastPushAt;
    private long posOvr = -1, posOvrAt; // 跳进度后先按跳到的位置显示，车机的真位置跟上（或超时）就撤
    private static final String[] NO_LINES = new String[0];
    private static final long[] NO_TIMES = new long[0];

    public LgHost(GLSurfaceView v, Context c, float density, Act a) {
        glView = v;
        ctx = c;
        dens = density;
        act = a;
        st = Settings.load(c);
        Res.sharp = "sharp".equals(st.clarity); // 设置里选了"清晰优先"：不缩画面（同原版 MainActivity）
        prefs = c.getSharedPreferences("lg", Context.MODE_PRIVATE);
        scene = new Scene(this, this, density);
        covers = new SongCovers(c);
        scene.setCovers(covers);
        scene.setOpts(st);
        day = wantDay();
        scene.setDay(day);
        ArtistPics.init(c);
        artists = new ArtistBook(prefs);
        bydMedia = new BydMedia(c, main, new BydMedia.Listener() {
            @Override public void changed() {
                localArt();
                if (!onPage) { bookArtists(); noteOnline(); return; } // 不在第三页：只记账，别叫醒 GL（进页时推）
                pushSongs(); bookArtists(); noteOnline(); pushLib(); libCheck(); songCheck(); pushSnapCached();
            }
            @Override public void heartFailed() { toast("没收藏上，过一会儿再试"); }
            @Override public void modeFailed() { toast("播放模式没切过去，过一会儿再试"); }
            @Override public void position(long p, long at, float speed, boolean on) { } // 播放位置 car3d 的快照里有
        });
        scene.setSongs(Collections.<BydMedia.Song>emptyList(), -1);
        applyRain();
        pushPrefs();
    }
    /** 玻璃上的雨珠：设置开了才有。car3d 这边没接车机天气，「自动」当没有雨（下雨的事交给车机自己的界面） */
    private void applyRain() { scene.setRain("on".equals(st.rain)); }
    /** 设置页的砖：car3d 的画布是车况页那一块，抗锯齿/渲染方式建了就改不了，全屏收状态栏也归 car3d 管：这三行藏掉 */
    private void pushPrefs() { scene.setPrefs(new SetPage.Data(st.copy(), false, false, false)); }

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
    /** 场景按帧率自己定的倍率（Scene.resTick）：真的改画布尺寸，不然缩画面这条省 GPU 的路等于没接 */
    @Override public void setRes(float k) { resK = k; main.post(this::applyRes); }

    /** 画布按「min(密度,1.5)/密度 × 场景倍率」缩着画，系统拉伸到窗口大小（同琉光 SceneView.fit） */
    private void applyRes() {
        if (!onPage || dens <= 0) return; // 不在第三页就别动车况页的画布
        float k = Math.min(dens, 1.5f) / dens * resK;
        if (glView instanceof com.ljone.car3d.SceneView) ((com.ljone.car3d.SceneView) glView).setLgFit(k);
    }
    /** 把画布倍率还给车况页（第三页只借用画布，走了就还原它的画质） */
    private void releaseRes() {
        if (glView instanceof com.ljone.car3d.SceneView) ((com.ljone.car3d.SceneView) glView).setLgFit(-1);
    }

    /** MainActivity 量到窗口尺寸后告诉场景（CSS 像素） */
    public void size(float w, float h) {
        if (w > 1 && h > 1 && (Math.abs(w - cssW) > 0.5f || Math.abs(h - cssH) > 0.5f)) {
            cssW = w; cssH = h;
            resRecalled = false; // 换了窗口大小：倍率是逐窗口大小记的，下次进页重新取（同琉光 Res）
            applyRes();
        }
    }
    /** 画布尺寸还不准（布局还没走到）时从画布本身量一次 */
    private void syncCss() {
        if (cssW > 1 && cssH > 1) return;
        int vw = glView.getWidth(), vh = glView.getHeight();
        if (vw > 1 && vh > 1) { cssW = vw / dens; cssH = vh / dens; }
    }

    // ---------- 琉光的界面（第三页叠在场景上，只有这里有） ----------
    /** 第三页盖在场景上的那层（提示条、找歌手的"正在放"、新手引导）；第一次用时建 */
    public View uiView() {
        if (ui == null) {
            ui = new Ui(ctx, this);
            ui.setDay(day);
            ui.setWait(true);
            ui.setFsMode(fsMode);
        }
        return ui;
    }
    /** 全屏键：full 在小窗口里、small 在全屏页里、空的不显示 */
    public void setFsMode(String m) {
        fsMode = m == null ? "" : m;
        if (ui != null) ui.setFsMode(fsMode);
    }

    /** 进第三页：第一次进来放开场动画 */
    public void enter() {
        syncCss();
        onPage = true;
        pushSnapNow();   // 离页期间媒体还在收：先把最新状态给场景（歌名、红心、音源、播放队列）
        scene.setRain(rainNow); // 离页期间定下的雨珠一起补上
        // 上次在这个窗口大小上量出来的倍率（同琉光 Res.recall）：一开始就按不卡的尺寸画，别先卡几秒再降
        if (!resRecalled) {
            resRecalled = true;
            resK = Res.recall(ctx, Math.round(cssW) + "x" + Math.round(cssH));
        }
        applyRes();
        if (!booted) { booted = true; scene.boot(true); }
        else scene.setOpts(st);
        scene.keepAlive(true); // 停在这一页：极光这些一直在动的效果要接着跑
        bydMedia.start();      // 媒体控制器：收藏、播放模式、音源、歌单都从这里来；连上就保持（同原版前台的口径），别在切页时反复绑/解绑
        pushSongs();
        bookArtists();
        pushLib();
        rainClock();
        weatherWatch("auto".equals(st.rain)); // 显示着、雨珠是自动时跟着车机天气
        guideLater(1500);
        scene.wake();
    }
    /** 离开第三页：设置页收起来，别再持续出帧（GL 画布要还给车况页按需画） */
    public void leavePage() {
        scene.keepAlive(false);
        onPage = false;
        releaseRes(); // 画布倍率还给车况页
        scene.prefs(3); // 设置页收起来（GL 里画的，切页回来再开）
        // 连着的媒体控制器不断开（同原版：应用前台就一直连）；切页那一下的绑定/解绑是主线程上的
        // binder 往返，还会让红心、播放模式、音源在重进时先空一拍再闪出来
        weatherWatch(false);
        main.removeCallbacks(guideTry);
        if (ui != null && ui.guiding()) { ui.guideHide(); scene.guideTrack(Scene.GUIDE_OFF); } // 没看完就收起来了：没过的那几步不算看过，下次再出
        // 手势可能在切页途中断掉（UP 没送到）：给场景补一个取消，别让它一直以为手指还按着，否则下次进页点播控会没反应
        final long t = SystemClock.uptimeMillis();
        glView.queueEvent(() -> scene.touch(3, 0, 0, t));
    }

    // ---------- Ui.Actions ----------
    /** 歌单里"正在放"那块点了一下：回到正在放的那首 */
    @Override public void locate() { scene.shelfLocate(); }
    /** 右上角的键显不显示、全屏键是哪种（键画在场景里，见 TopKeys） */
    @Override public void keys(boolean show, String fs) { scene.setKeys(show, fs == null ? "" : fs); }

    /** 提示条（ui 没建出来时走 car3d 的） */
    private void toast(String msg) {
        if (ui != null) ui.toast(msg);
        else if (act != null) act.toast(msg);
    }

    // ---------- 设置 ----------
    private volatile boolean prefsOpen; // 设置页开着（GL 线程回调，主线程读）

    /** 系统返回键：引导 > 设置页 > 曲库 > 歌单，先关最上面那层（同原版 MainActivity.onBackPressed） */
    public boolean back() {
        if (ui != null && ui.guiding()) { ui.guideOk(); return true; }
        if (prefsOpen) { scene.prefs(3); return true; }
        if (libOn) { scene.library(4); return true; } // 曲库开着：先收找歌手的键盘，再关曲库
        if (ui != null && ui.shelfOn()) { scene.shelf(4); return true; } // 歌单开着：先收搜索键盘，再关歌单
        return false;
    }

    /** 琉光自己的整页层开着（设置页、曲库、歌单、新手引导）：Hud 的底部切页热区让路——
     *  歌单的搜索键盘、刻度条就在屏幕底下那条，热区不躲开会把打字、拖刻度抢成切页手势 */
    public boolean lgPagesOpen() {
        return prefsOpen || libOn || (ui != null && (ui.shelfOn() || ui.guiding()));
    }

    /** 设置页里点了一块（主线程） */
    @Override public void prefSet(final String k, final Object v) { main.post(() -> applySetting(k, v)); }

    private void applySetting(String k, Object v) {
        st.set(k, v);
        st.save(ctx);
        if ("theme".equals(k)) { day = wantDay(); scene.setDay(day); if (ui != null) ui.setDay(day); }
        if ("rain".equals(k)) { weatherWatch(onPage && "auto".equals(st.rain)); rainClock(); }
        if ("clarity".equals(k)) { Res.sharp = "sharp".equals(st.clarity); scene.wake(); }
        if ("font".equals(k)) { final String f = st.font; Workers.io(() -> { Txt.load(ctx, f); main.post(() -> scene.setOpts(st)); }); } // 后台读好再换
        // 抗锯齿、渲染方式只能在建画布时定，car3d 的画布是车况页那一块：设置页里这两行已经藏了，值不会变
        scene.setOpts(st);
        pushPrefs();
    }

    private boolean wantDay() {
        if ("light".equals(st.theme)) return true;
        if ("dark".equals(st.theme)) return false;
        if (carNight != null) return !carNight;
        String v = prefs.getString("night", null);
        if ("1".equals(v)) return false;
        if ("0".equals(v)) return true;
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
    /** 车机报的日夜模式（car3d 的 CarData 转发，主线程）：记下来，「跟随车机」时跟着走 */
    public void carNight(boolean night) {
        if (carNight != null && carNight == night) return;
        carNight = night;
        prefs.edit().putString("night", night ? "1" : "0").apply();
        if (!"auto".equals(st.theme)) return;
        day = wantDay();
        scene.setDay(day);
        if (ui != null) ui.setDay(day);
    }
    /** 主线程：回到前台重新读一次设置（全屏页和小窗口共用一份） */
    public void reloadSettings() {
        Settings n = Settings.load(ctx), o = st.copy();
        st = n;
        Res.sharp = "sharp".equals(st.clarity);
        day = wantDay();
        scene.setDay(day);
        if (ui != null) ui.setDay(day);
        for (String k : new String[] { "font", "fps", "water", "art", "ribbons", "rain", "intro", "reveal" }) {
            if (!n.get(k).equals(o.get(k))) {
                if ("rain".equals(k)) { applyRain(); weatherWatch(onPage && "auto".equals(st.rain)); rainClock(); }
                else if ("font".equals(k)) { final String f = st.font; Workers.io(() -> { Txt.load(ctx, f); main.post(() -> scene.setOpts(st)); }); }
                else scene.setOpts(st);
            }
        }
        scene.setOpts(st);
        pushPrefs();
        rainClock();
    }

    // ---------- Scene.Host（GL 线程调：交回主线程） ----------
    /** 按下播放/暂停后，车机回推状态常常慢半拍甚至不回：先本地把播放态翻过来，按钮立即可见（2 秒内以真值为准） */
    private volatile boolean pFlip;
    private volatile long pFlipAt;
    @Override public void press(String cmd) {
        if ("settings".equals(cmd)) { scene.prefs(1); return; }    // 右上角的键（画在场景里）
        if ("fullscreen".equals(cmd)) { if (act != null) main.post(act::fullscreen); return; }
        if ("heart".equals(cmd)) { main.post(this::heart); return; }  // 收藏、播放模式走车机媒体控制器，不经过 car3d 的宿主
        if ("mode".equals(cmd)) { main.post(this::mode); return; }
        if (act == null) return;
        final String m = "toggle".equals(cmd) ? "togglePlayPause" : cmd;
        if ("toggle".equals(cmd)) { pFlip = !lPlaying0; pFlipAt = SystemClock.elapsedRealtime(); }
        main.post(() -> act.media(m));
    }
    /** 跳到某一句：发给「多媒体」的导出入口（Player，零权限），同原版 */
    @Override public void seek(final long ms) {
        main.post(() -> {
            if (Player.seek(ctx, ms)) { posOvr = ms; posOvrAt = SystemClock.elapsedRealtime(); pushSnapNow(); } // 先按跳到的位置显示
            else if (act != null) act.seek(ms); // 车机导出入口不行就问 car3d 的宿主（大概率也不支持）
        });
    }
    @Override public void btnWait(final boolean w) { main.post(() -> { if (ui != null) ui.setWait(w); if (!w) guideLater(1500); }); }
    @Override public void playIndex(final int i) {
        main.post(() -> { if (!Player.playIndex(ctx, i)) toast("切不过去"); });
    }
    @Override public void shelfState(final boolean open) { main.post(() -> { if (ui != null) ui.setShelfOn(open); }); }
    @Override public void locate(final int dir, final float y) { main.post(() -> { if (ui != null) ui.setLocate(dir, y); }); }
    @Override public void guideAt(final float l, final float t, final float r, final float b) {
        main.post(() -> { if (ui != null) ui.guideHole(l, t, r, b); });
    }
    @Override public void prefsState(final boolean open) {
        main.post(() -> { prefsOpen = open; if (ui != null) ui.setPrefs(open); });
    }
    /** 封面的强调色：提示条跟着（同原版 MainActivity） */
    @Override public void palette(final int accent, final int deep) {
        main.post(() -> { if (ui != null) ui.setPalette(accent, deep); });
    }

    // ================= 歌单（场景里的 Shelf）：播放队列从车机媒体控制器来 =================
    /** 正在用的不是「多媒体」（单独装的音乐 App、蓝牙音乐……）：歌单、红心、播放模式都是「多媒体」的，对不上，当没有 */
    private boolean otherPlayer() { return lPkg != null && !lPkg.isEmpty() && !Player.PKG.equals(lPkg); }

    private void pushSongs() { scene.setSongs(queue(), bydMedia.current()); }

    private List<BydMedia.Song> queue() { return otherPlayer() ? Collections.<BydMedia.Song>emptyList() : bydMedia.songs(); }

    /** 新推来的歌单：里面的歌手记进曲库的"歌手"那一排 */
    private int listSeen = -1;
    private void bookArtists() {
        if (bydMedia.listSeq() == listSeen) return;
        listSeen = bydMedia.listSeq();
        artists.add(bydMedia.songs());
    }

    // ================= 曲库（场景里的 Library；见 Player.voice、BydMedia.changeSource） =================
    private boolean libOn;
    private String libPushed; // 上次交给场景的那份（音源、图标、歌手的版本）：没变不重做
    private int libWaitSeq = -1, libWaitSrc = -1; // 在等的：歌单的版本（换一批歌）、音源（切源）
    // 每块换成了以后的那批歌（lg 的 libDone：方法|参数 → 队列的指纹，最多 16 块）：再点同一块时「多媒体」发现正在放的就是它，什么都不做、不推新队列——这时不算没换成
    private String libWaitKey, libDoneKey;
    private int libReq; // 每发一次曲库命令加一：后台线程查到的结果按它对账（不串到后面点的那块上）
    private long libDoneAt;
    private JSONObject libDone;
    private boolean libSame;

    private JSONObject libDone() {
        if (libDone == null) { try { libDone = new JSONObject(prefs.getString("libDone", "{}")); } catch (Exception e) { libDone = new JSONObject(); } }
        return libDone;
    }

    private void noteDone(String key) {
        JSONObject m = libDone();
        if (m.length() >= 16 && !m.has(key)) m = libDone = new JSONObject();
        try { m.put(key, queueSig()); } catch (Exception ignored) { }
        prefs.edit().putString("libDone", m.toString()).apply();
    }

    /** 现在的播放队列是哪些歌：首数 + 按顺序的歌曲 id 的哈希 */
    private String queueSig() {
        List<BydMedia.Song> l = bydMedia.songs();
        int h = 1;
        for (BydMedia.Song s : l) h = h * 31 + s.id.hashCode();
        return l.size() + ":" + Integer.toHexString(h);
    }
    private int libOnline = -2;      // 上一次用的在线音乐（存在 lg 的 libOnline；-2 还没读）
    private int libHopSrc = -1;      // 点砖时当前不是在线音乐：先切到这个，切过去再发 libHop
    private String[] libHop;

    private int lastOnline() { if (libOnline == -2) libOnline = prefs.getInt("libOnline", -1); return libOnline; }

    private void noteOnline() {
        int s = bydMedia.source();
        if (BydMedia.online(s) && s != lastOnline()) { libOnline = s; prefs.edit().putInt("libOnline", s).apply(); }
    }

    /** 当前不是在线音乐时，曲库的内容在哪个在线音乐里放：上一次用的，没用过就是车上音源列表里第一个在线音乐。当前就是在线音乐（或者不是「多媒体」在放）时 -1 */
    private int libTarget() {
        int cur = bydMedia.source();
        if (cur < 0 || BydMedia.online(cur)) return -1;
        int[] src = bydMedia.sources();
        for (int s : src) if (s == lastOnline()) return s;
        for (int s : src) if (BydMedia.online(s)) return s;
        return -1;
    }

    /** 曲库要的数据变了才交给场景 */
    private void pushLib() {
        int[] src = bydMedia.sources();
        int target = libTarget();
        String k = java.util.Arrays.toString(src) + "|" + bydMedia.source() + "|" + target + "|" + bydMedia.iconSeq() + "|" + artists.seq();
        if (k.equals(libPushed)) return;
        libPushed = k;
        List<ArtistBook.Artist> l = artists.list();
        String[] names = new String[l.size()], covers = new String[l.size()];
        for (int i = 0; i < l.size(); i++) { names[i] = l.get(i).name; covers[i] = l.get(i).cover == null ? "" : l.get(i).cover; }
        android.util.SparseArray<Bitmap> icons = new android.util.SparseArray<>();
        for (int s : src) { Bitmap b = bydMedia.icon(s); if (b != null) icons.put(s, b); }
        int cur = bydMedia.source();
        if (cur >= 0 && bydMedia.icon(cur) != null) icons.put(cur, bydMedia.icon(cur));
        scene.setLib(new Library.Data(src, cur, target, icons, bydMedia.iconSeq(), names, covers, artists.seq()));
    }

    @Override public void libraryState(final boolean open) { main.post(() -> { libOn = open; if (ui != null) ui.setLib(open); }); }

    @Override
    public void libPick(final String method, final String field, final String value) {
        main.post(() -> {
            int hopping = libHopSrc;
            main.removeCallbacks(libTimeout);
            main.removeCallbacks(libSend);
            libWaitSrc = -1;
            libWaitSeq = -1;
            libHop = null;
            libHopSrc = -1;
            int t = libTarget();
            if (t < 0) { sendPick(method, field, value); return; }
            libHop = new String[] { method, field, value }; // 先切到在线音乐，切过去（libCheck）再发
            libHopSrc = t;
            main.postDelayed(libTimeout, 15000);
            if (t == hopping) return; // 已经在切过去了：只换要发的，不再切一次
            if (!bydMedia.changeSource(t)) { libHop = null; libHopSrc = -1; main.removeCallbacks(libTimeout); scene.libResult(2); toast("切不到" + BydMedia.onlineName(t)); }
        });
    }

    /** 切到在线音乐以后：等它自己的播放队列先推过来，再发 */
    private final Runnable libSend = () -> {
        String[] h = libHop;
        libHop = null;
        if (h != null) sendPick(h[0], h[1], h[2]);
    };

    private void sendPick(final String method, final String field, final String value) {
        final JSONObject p = new JSONObject();
        try { if (field != null) p.put(field, value); } catch (Exception ignored) { }
        libWaitSeq = bydMedia.listSeq();
        libWaitKey = method + "|" + field + "|" + value;
        libSame = !bydMedia.songs().isEmpty() && queueSig().equals(libDone().optString(libWaitKey));
        main.removeCallbacks(libTimeout);
        main.postDelayed(libTimeout, libSame ? 4000 : 9000); // 点的还是上次那块、队列也没变：多半正在放的就是它，等得短一点
        final int req = ++libReq;
        final android.content.Context app = ctx.getApplicationContext();
        Workers.net(() -> { // 发之前、发之后问「多媒体」在不在前台：3 秒内跑到前台多半是没登录、弹出了登录页（留着让人登录），不再等新歌单
            int before = Player.foreground(app);
            if (!Player.voice(app, method, p)) { main.post(() -> libFailed(req, "这台车机没有「多媒体」，换不了")); return; }
            if (before != 0) return;
            for (int i = 0; i < 8; i++) {
                try { Thread.sleep(400); } catch (InterruptedException e) { return; }
                if (Player.foreground(app) == 1) { main.post(() -> libFailed(req, loginName() + "还没登录：在「多媒体」弹出来的页面里登录以后再点")); return; }
            }
        });
    }

    private void libFailed(int req, String msg) {
        if (req != libReq || libWaitSeq < 0) return; // 后来又点了别的、或者已经有结果了
        libWaitSeq = -1;
        main.removeCallbacks(libTimeout);
        scene.libResult(2);
        toast(msg);
    }

    /** 曲库命令交给的那个在线音乐的名字 */
    private String loginName() {
        int s = bydMedia.source();
        return BydMedia.online(s) ? BydMedia.onlineName(s) : "音乐账号";
    }

    @Override
    public void libSource(final int src) {
        main.post(() -> {
            libWaitSeq = -1;
            libWaitSrc = src;
            libHop = null;
            libHopSrc = -1;
            main.removeCallbacks(libSend);
            main.removeCallbacks(libTimeout);
            main.postDelayed(libTimeout, 6000);
            if (!bydMedia.changeSource(src)) { libWaitSrc = -1; main.removeCallbacks(libTimeout); toast("音源切不过去"); }
        });
    }

    /** 媒体控制器推来了新东西：在等的到了没有 */
    private void libCheck() {
        if (libDoneKey != null && SystemClock.uptimeMillis() - libDoneAt < 10000 && !queueSig().equals(libDone().optString(libDoneKey))) noteDone(libDoneKey); // 换成以后队列还在一页页补全：跟着记
        if (libHopSrc >= 0 && bydMedia.source() == libHopSrc) { libHopSrc = -1; main.postDelayed(libSend, 1200); return; } // 切到在线音乐了
        if (libWaitSeq >= 0 && bydMedia.listSeq() != libWaitSeq && !bydMedia.songs().isEmpty()) { // 新的播放队列到了：曲库收起、长廊打开
            libWaitSeq = -1;
            if (libWaitKey != null) { noteDone(libWaitKey); libDoneKey = libWaitKey; libDoneAt = SystemClock.uptimeMillis(); }
            main.removeCallbacks(libTimeout);
            scene.libResult(1);
        } else if (libWaitSrc >= 0 && bydMedia.source() == libWaitSrc) { // 切过去了：曲库不关；过一会儿还没有歌就说一声
            final int s = libWaitSrc;
            libWaitSrc = -1;
            main.removeCallbacks(libTimeout);
            main.postDelayed(() -> { if (bydMedia.source() == s && bydMedia.songs().isEmpty() && !lOn0) toast(bydMedia.sourceName() + "里还没有可放的歌"); }, 4000);
        }
    }

    private final Runnable libTimeout = () -> {
        if (libHopSrc >= 0) { int t = libHopSrc; libHopSrc = -1; libHop = null; scene().libResult(2); toast("没切到" + BydMedia.onlineName(t) + "，过一会儿再试"); }
        else if (libWaitSeq >= 0 && libSame && queueSig().equals(libDone().optString(libWaitKey))) { libWaitSeq = -1; scene().libResult(1); toast("正在放的就是这个"); } // 曲库收起、长廊打开
        else if (libWaitSeq >= 0) { libWaitSeq = -1; scene().libResult(2); toast("没有换：正在放的可能就是它；也可能要先在「多媒体」里登录，或者这个音源里没有"); }
        else if (libWaitSrc >= 0) { libWaitSrc = -1; toast("音源没切过去，过一会儿再试"); }
    };

    // ================= 搜歌（曲库里的"搜歌"；见 SongFinder、Player.insertSong） =================
    private SongFinder.Hit songWant; // 点了、在等的那首
    private int songWaitSeq = -1;    // 发出去时的播放队列版本（插进去了队列会变）
    private boolean songHop;         // 先切到那首所在的音源，切过去再发
    private int songReq;

    /** 搜哪个音源：当前是在线音乐就是它，不是就是要切到的那个（同曲库） */
    private int songSrc() {
        int t = libTarget();
        if (t >= 0) return t;
        int cur = bydMedia.source();
        return BydMedia.online(cur) ? cur : -1;
    }

    @Override
    public void songSearch(final String q, final int page, final int seq) {
        main.post(() -> {
            int s = songSrc();
            if (s < 0) { scene.songPage(new SongFinder.Page(seq, page, s, q, Collections.<SongFinder.Hit>emptyList(), false, "车上没有能搜的在线音乐")); return; }
            SongFinder.search(s, q, page, seq, p -> main.post(() -> scene.songPage(p)));
        });
    }

    @Override
    public void imeOpen() {
        main.post(() -> {
            List<String> names = new ArrayList<>();
            for (BydMedia.Song x : bydMedia.songs()) { names.add(x.title); names.add(x.artist); }
            for (ArtistBook.Artist x : artists.list()) names.add(x.name);
            Pinyin.load(ctx.getApplicationContext(), names);
        });
    }

    @Override
    public void songPick(final SongFinder.Hit h) {
        main.post(() -> {
            main.removeCallbacks(songTimeout);
            main.removeCallbacks(songSend);
            main.removeCallbacks(songVerify);
            songWant = h;
            songWaitSeq = -1;
            if (bydMedia.source() == h.src) { sendSong(); return; }
            songHop = true; // 搜的时候当前不是在线音乐（或者后来换了音源）：先切到搜的那个
            main.postDelayed(songTimeout, 15000);
            if (!bydMedia.changeSource(h.src)) songDone(false, "切不到" + BydMedia.onlineName(h.src));
        });
    }

    private final Runnable songSend = this::sendSong;

    private void sendSong() {
        final SongFinder.Hit h = songWant;
        if (h == null) return;
        songHop = false;
        songWaitSeq = bydMedia.listSeq();
        String now = bydMedia.title();
        boolean playing = now != null && !now.isEmpty() && alike(now, h.title); // 点的就是正在放的：「多媒体」什么都不做、不推新队列，等得短一点
        main.removeCallbacks(songTimeout);
        main.postDelayed(songTimeout, playing ? 4000 : 9000);
        final int req = ++songReq;
        final android.content.Context app = ctx.getApplicationContext();
        Workers.net(() -> { // 同曲库：发之前、发之后问「多媒体」在不在前台，跑到前台了多半是要登录
            int before = Player.foreground(app);
            boolean ok;
            if (h.src == 10) ok = Player.insertSong(app, h.id);
            else {
                JSONObject p = new JSONObject();
                try { p.put("song", h.title); p.put("artist", h.artist); if (!h.album.isEmpty()) p.put("album", h.album); } catch (Exception ignored) { }
                ok = Player.voice(app, "searchMusic", p); // 「多媒体」把它们拼成"歌名 歌手 专辑"在这个音源里搜，插第一首
            }
            if (!ok) { main.post(() -> { if (req == songReq) songDone(false, "这台车机没有「多媒体」，放不了"); }); return; }
            if (before != 0) return;
            for (int i = 0; i < 8; i++) {
                try { Thread.sleep(400); } catch (InterruptedException e) { return; }
                if (Player.foreground(app) == 1) { main.post(() -> { if (req == songReq && songWaitSeq >= 0) songDone(false, BydMedia.onlineName(h.src) + "要登录：在「多媒体」弹出来的页面里登录以后再点"); }); return; }
            }
        });
    }

    /** 媒体控制器推来了新东西：切过去了就发；队列变了（插进去了）过一会儿核对放的是不是这首 */
    private void songCheck() {
        if (songHop && songWant != null && bydMedia.source() == songWant.src) { songHop = false; main.postDelayed(songSend, 1200); return; }
        if (songWaitSeq >= 0 && bydMedia.listSeq() != songWaitSeq) {
            songWaitSeq = -1;
            main.removeCallbacks(songTimeout);
            main.postDelayed(songVerify, 1500);
        }
    }

    private static String bare(String s) { return s == null ? "" : s.replaceAll("[\\s\\p{P}\\p{S}]", "").toLowerCase(java.util.Locale.ROOT); }

    /** 两个名字算不算一样：去掉空格标点以后相同或者一个包含另一个（合唱的歌手有的用 / 有的用 &） */
    private static boolean alike(String a, String b) {
        String x = bare(a), y = bare(b);
        return x.isEmpty() || y.isEmpty() || x.equals(y) || x.contains(y) || y.contains(x);
    }

    /** 插进去以后在放的是不是点的那首：歌名、歌手有一样对不上就说一声放成了谁的哪首 */
    private final Runnable songVerify = () -> { if (songWant != null) songDone(true, songCheckMsg(songWant, null)); };

    /** 现在在放的和点的那首对不对得上：对得上返回 same（可以是空的），对不上说放成了谁的哪首 */
    private String songCheckMsg(SongFinder.Hit h, String same) {
        String t = bydMedia.title(), a = bydMedia.artArtist();
        if (t == null || t.isEmpty() || alike(t, h.title) && (a == null || alike(a, h.artist))) return same;
        return "「多媒体」放成了" + (a == null || a.isEmpty() ? "" : a + "的") + "《" + t + "》";
    }

    private final Runnable songTimeout = () -> {
        SongFinder.Hit h = songWant;
        if (h == null) return;
        String now = bydMedia.title();
        if (!songHop && now != null && !now.isEmpty() && alike(now, h.title)) { songDone(true, songCheckMsg(h, "正在放的就是这首")); return; } // 没推新队列：放的本来就是它
        songDone(false, songHop ? "没切到" + BydMedia.onlineName(h.src) + "，过一会儿再试" : "没放成：这首在" + BydMedia.onlineName(h.src) + "里可能放不了");
    };

    private void songDone(boolean ok, String msg) {
        main.removeCallbacks(songTimeout);
        main.removeCallbacks(songSend);
        songWant = null;
        songWaitSeq = -1;
        songHop = false;
        scene.songResult(ok ? 1 : 2);
        if (msg != null) toast(msg);
    }

    // ================= 收藏红心、播放模式（车机媒体控制器；见 BydMedia） =================
    private static final String[] MODE_NAME = { "列表循环", "随机播放", "单曲循环" };

    private void heart() {
        if (!bydMedia.toggleHeart(lTitle)) toast("这首歌现在收藏不了");
    }

    private void mode() {
        int m = bydMedia.nextMode(lTitle);
        toast(m < 0 ? "现在切不了播放模式" : MODE_NAME[m]);
    }

    // ================= 新手引导（两步：封面能点也能划、曲库在歌名左边的音源图标；看过的那步不再出，存 guide、guideLib） =================
    private final Runnable guideTry = this::guideTry;

    private void guideLater(long ms) { main.removeCallbacks(guideTry); main.postDelayed(guideTry, ms); }

    /** 开场走完、有歌在放、没开着设置页/曲库/歌单时出；曲库那步等歌名左边的音源图标出来（「多媒体」在放）才讲 */
    private void guideTry() {
        boolean cover = !prefs.getBoolean("guide", false), lib = !prefs.getBoolean("guideLib", false);
        if (!cover && !lib) return;
        if (ui == null || ui.guiding() || !onPage || !ui.keysUp()) { guideLater(2000); return; }
        if (prefsOpen || libOn || ui.shelfOn() || !lOn0) { guideLater(2000); return; }
        lib = lib && scene.srcIconOn();
        if (!cover && !lib) { guideLater(5000); return; }
        int[] ids = cover && lib ? new int[] { Guide.COVER, Guide.LIBRARY } : cover ? new int[] { Guide.COVER } : new int[] { Guide.LIBRARY };
        ui.showGuide(ids, new Guide.Steps() {
            @Override public void show(int id) { scene.guideTrack(id == Guide.COVER ? Scene.GUIDE_COVER : Scene.GUIDE_SRC); }
            @Override public void seen(int id) { prefs.edit().putBoolean(id == Guide.COVER ? "guide" : "guideLib", true).apply(); }
        }, () -> {
            scene.guideTrack(Scene.GUIDE_OFF);
            if (!prefs.getBoolean("guideLib", false)) guideLater(5000); // 曲库那步还没讲（当时图标没出来）
        });
    }

    // ================= 雨珠开不开（见 RainPick、BydWeather） =================
    private final Runnable rainCheck = this::rainClock;

    /** 按设置、天气、这首歌定下不下（媒体、设置、天气变了都调一次；每分钟也看一次——逐分钟降水按现在这一分钟算） */
    private void rainClock() {
        rainApply(buildSnap(lastPos, SystemClock.elapsedRealtime()));
    }

    private void rainApply(MediaState.Snap s) {
        if (!booted || s == null) return;
        long now = SystemClock.uptimeMillis();
        if (rp.newTrack(s, now)) { main.removeCallbacks(rainCheck); main.postDelayed(rainCheck, RainPick.WAIT + 100); } // 换歌：新歌的歌词等一会儿还没到就按已有的定
        Boolean want = "on".equals(st.rain) ? Boolean.TRUE : "off".equals(st.rain) ? Boolean.FALSE : rp.auto(s, now, weather.rainingNow(System.currentTimeMillis()));
        if (want != null && want != rainNow) { rainNow = want; if (onPage) scene.setRain(want); } // 离页只记账，进页时补上
    }

    /** 读一次车机天气（后台线程），读到了再定一次 */
    private volatile boolean weatherQueued;
    private void weatherRead() {
        if (weatherQueued) return;
        weatherQueued = true;
        Workers.io(() -> {
            weatherQueued = false;
            if (!weather.read(ctx.getContentResolver())) return; // 别的车机没有这个应用：读不到，当查不到天气
            main.post(this::rainClock);
        });
    }

    /** 显示着、雨珠是自动时跟着车机天气：数据接口一变就读，也听它给第三方发的刷新广播；藏起来、不是自动就不听 */
    private void weatherWatch(boolean on) {
        if (on && weatherObs == null) {
            weatherObs = new ContentObserver(main) { @Override public void onChange(boolean self) { weatherRead(); } };
            try { ctx.getContentResolver().registerContentObserver(BydWeather.URI, false, weatherObs); } catch (Exception ignored) { }
            weatherRx = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { weatherRead(); } };
            try {
                if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(weatherRx, new IntentFilter(BydWeather.REFRESH), Context.RECEIVER_EXPORTED);
                else ctx.registerReceiver(weatherRx, new IntentFilter(BydWeather.REFRESH));
            } catch (Exception ignored) { }
            weatherRead();
        } else if (!on && weatherObs != null) {
            try { ctx.getContentResolver().unregisterContentObserver(weatherObs); } catch (Exception ignored) { }
            try { ctx.unregisterReceiver(weatherRx); } catch (Exception ignored) { }
            weatherObs = null;
            weatherRx = null;
        }
    }

    // ================= 蓝牙音乐的本地封面（车机媒体控制器把手机传来的图存在 /storage；要读存储的权限，同原版） =================
    private static boolean askedStorage;
    private static final int REQ_STORAGE = 7;
    private String artPathSeen;
    private Bitmap artBm;

    /** 控制器推来了新东西：看有没有新的本地封面文件（蓝牙音乐） */
    private void localArt() {
        final String p = bydMedia.artPath();
        if (p == null || p.equals(artPathSeen)) return;
        artPathSeen = p;
        if (p.isEmpty()) return;
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (askedStorage || !(ctx instanceof android.app.Activity)) return;
            askedStorage = true; // 一个进程里只问一次（同原版）
            ((android.app.Activity) ctx).requestPermissions(new String[] { android.Manifest.permission.READ_EXTERNAL_STORAGE }, REQ_STORAGE);
            return;
        }
        readArt(p);
    }

    /** 读存储的权限刚给了：本地封面重新读 */
    public void storagePerm(boolean ok) {
        if (!ok) return;
        artPathSeen = null;
        localArt();
    }

    private void readArt(final String file) {
        Workers.io(() -> {
            Bitmap b = null;
            try { Bitmap src = android.graphics.BitmapFactory.decodeFile(file); if (src != null) b = square(src); } catch (Exception ignored) { }
            final Bitmap fb = b;
            main.post(() -> { artBm = fb; pushSnapNow(); }); // 对不上正在放的这首时 buildSnap 里不会用它
        });
    }

    private static Bitmap square(Bitmap src) {
        if (src == null) return null;
        int n = 512, w = src.getWidth(), h = src.getHeight();
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        int dw, dh; if (w > h) { dw = n; dh = n * h / w; } else { dh = n; dw = n * w / h; }
        c.drawBitmap(src, null, new android.graphics.Rect((n - dw) / 2, (n - dh) / 2, (n + dw) / 2, (n + dh) / 2), p);
        return b;
    }

    /** 对得上正在放的这首（歌手栏、时长一样）的本地封面（同原版 MediaState.localArt 的校验） */
    private Bitmap artCover(String ti, String ar, long dur) {
        String a = bydMedia.artArtist();
        boolean ok = artBm != null && bydMedia.artDur() == dur && clean(a).equals(clean(ar));
        return ok ? artBm : placeholder(ti, ar);
    }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").trim(); }

    // ---------- Stage 调用 ----------
    /**
     * ★画布重建（GL 上下文丢了：切后台被回收显存、系统换驱动、拖动分屏重建 Surface）。
     * 这时所有 program / mesh / 贴图编号都作废，但 created 还停在 true，draw() 就不会再调
     * scene.onSurfaceCreated —— 第三页会花屏、只剩色块或者干脆什么都不画。
     * Stage.onSurfaceCreated 里叫一次，下一帧重新建。
     */
    public void surfaceCreated() {
        created = false;
        lastW = -1;
        G.resetCache(); // 车况页那边也会重建自己的 program：这边记的"已绑定"全不作数了
    }

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

    /** 换画布重建：收掉封面那几个后台线程、断开媒体控制器 */
    public void quit() {
        bydMedia.stop();
        weatherWatch(false);
        covers.quit();
    }

    // ---------- 手势 ----------
    /**
     * ★和原版 SceneView.onTouchEvent 一字不差：坐标只除以画布自身的宽高，再交给场景。
     * 原版就是这么做的（`e.getX() / getWidth()`），任何中间层的 density / CSS 换算都不需要，
     * 也不会因为某一处尺寸没同步就让命中判定整片偏掉。
     */
    public void touchNorm(final int a, float nx, float ny, long t) {
        glView.queueEvent(() -> scene.touch(a, nx, ny, t));
    }

    // ---------- 媒体数据桥接 ----------
    public void media(boolean on, boolean playing, boolean full, String title, String artist, String track, String pkg,
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
        boolean chPkg = !android.text.TextUtils.equals(pkg == null ? "" : pkg, lPkg);
        boolean idle = !chTrack && !chText && !chLines && !chCover && !chCur && !chPkg && playing == lPlaying0 && on == lOn0 && dur == lDur;
        long now = SystemClock.elapsedRealtime();
        // 什么都没变、也没到半秒：连快照都不建（播放位置由 Scene 自己按墙钟往后推，不用每帧对齐）
        if (idle && now - lastPushAt < 400) return;
        if (chTrack) { rTrack = ++rev; lTrack = tr; }
        if (chText) { lTitle = ti; lArtist = ar; }
        if (chLines) { rLines = ++rev; lLines = ls; lTimes = times == null ? NO_TIMES : times; }
        if (chCover) { rCover = ++rev; lCover = cover; }
        if (chCur) { rCur = ++rev; lCur = cur; }
        if (chPkg) lPkg = pkg == null ? "" : pkg;
        lDur = dur; lOn0 = on; lPlaying0 = playing; lastFull = full; lastPos = pos; lastPushAt = now;

        MediaState.Snap s = buildSnap(pos, now);
        if (onPage) scene.setSnap(s); // 不在第三页只记账不叫醒 GL（车况页在按需出帧，别为它多画一帧）
        rainApply(s);
    }

    /**
     * 用缓存的媒体数据拼一份快照（主线程）。红心、播放模式、音源从车机媒体控制器（BydMedia）来，
     * 队列可跳（seek：在放的是「多媒体」、有整首歌词、有总时长），同原版 MediaState 的字段口径。
     */
    private MediaState.Snap buildSnap(long pos, long now) {
        MediaState.Snap s = new MediaState.Snap();
        boolean op = otherPlayer();
        int src = op ? -1 : bydMedia.source();
        s.on = lOn0;
        s.playing = lPlaying0;
        s.full = lastFull; // 有整首歌词才滚整首；只有听到的句子时只显示当前句（同琉光 MediaState.full）
        s.rawPlaying = lPlaying0;
        s.canPlay = true;
        s.seek = lOn0 && !op && lastFull && lDur > 0 && src != 4 && src != 8 && src != 9;
        s.track = lTrack;
        s.title = lTitle;
        s.artist = lArtist;
        s.trackCmd = "";
        s.lines = lLines == null ? NO_LINES : lLines;
        s.times = lTimes == null ? NO_TIMES : lTimes;
        s.cur = lCur;
        s.dur = lDur;
        // 跳进度后车机的真位置还没跟上：先按跳到的位置显示（真位置到了或超时就撤）
        if (posOvr >= 0) {
            if (Math.abs(pos - posOvr) < 2500 || now - posOvrAt > 5000) posOvr = -1;
            else pos = posOvr;
        }
        s.posBase = pos;
        s.posAt = now;
        s.cover = lCover != null ? lCover : artCover(lTitle, lArtist, lDur);
        s.coverTrack = s.track;
        s.heart = lOn0 ? bydMedia.heart(lTitle) : BydMedia.HIDE;
        s.mode = lOn0 && !op ? bydMedia.mode(lTitle) : -1;
        s.src = op ? -1 : src;
        s.revTrack = rTrack;
        s.revLines = rLines;
        s.revCover = rCover;
        s.revCur = rCur;
        return s;
    }

    /** 控制器推来了新东西（收藏、播放模式、歌单、音源变了）：把缓存的那份快照重推一次，红心这些不用等下一拍媒体快照 */
    private void pushSnapCached() { main.post(this::pushSnapNow); }
    private void pushSnapNow() {
        if (!onPage) return; // 离页只记账；enter() 里会推最新的一份
        scene.setSnap(buildSnap(lastPos, SystemClock.elapsedRealtime()));
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
