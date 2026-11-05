package com.ljone.car3d;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import org.json.JSONObject;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.ljone.car3d.ach.AchToast;

import java.util.List;

/**
 * 叠在 3D 画布上面的一层（照网页版车况页的 DOM）：字和图标（Hud）、胎压标签和车顶空调键（Tags）、右上角的齿轮、全屏键和奖杯键（成就墙）、
 * 解锁成就时从右边滑进来的提示（AchToast）、底部提示条、设置面板（Panel）。
 * CarPage 在 GL 线程里交出来的（Out）都切回主线程再改界面
 */
@SuppressLint("ViewConstructor")
final class CarUi extends FrameLayout implements CarPage.Out {
    interface Actions {
        void statusTap(); void statusPick(int i); void statusClose(); void allStatus();
        void roofTap(); void acSet(boolean on); void tagMax(float cssY);
        void panel(boolean open); void act(String name); void fullscreen(); void debug(); void trophies();
        void musicCard(boolean on, int dir); void music(String action); void musicLyrics();
        /** 点封面：打开正在放歌的这个 App（宿主 apps.launch） */ void launchApp();
        /** 封面加载：coverUrl 是会话资源标识，走 openCover（CarData 工作线程），完成后回调（用序号丢弃过期结果） */ void loadCover(String url, CarData.CoverCb cb);
    }
    /** 深浅色变了：d 是要到的，k 是现在过渡到哪（成就墙跟着变） */
    interface Theme { void on(boolean d, float k); }
    private static final String GEAR = "M19.4 13a7.6 7.6 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7.4 7.4 0 0 0-1.7-1L15 3.3h-4l-.4 2.6a7.4 7.4 0 0 0-1.7 1l-2.5-1-2 3.5L6.6 11a7.6 7.6 0 0 0 0 2l-2.1 1.6 2 3.5 2.5-1a7.4 7.4 0 0 0 1.7 1l.3 2.6h4l.4-2.6a7.4 7.4 0 0 0 1.7-1l2.5 1 2-3.5zM12 15.4a3.4 3.4 0 1 1 0-6.8 3.4 3.4 0 0 1 0 6.8z";
    /** 奖杯（实心）：杯身、两边的把手、杯脚、底座 */
    private static final String CUP = "M6 3h12v4.6a6 6 0 0 1-12 0V3zM10.6 13.4h2.8v3.4h-2.8zM7.5 17.4h9a1 1 0 0 1 1 1v1.8h-11v-1.8a1 1 0 0 1 1-1zM6 4.9H3.6a.6.6 0 0 0-.6.6v1.3a4.2 4.2 0 0 0 3.5 4.1V9.2A2.5 2.5 0 0 1 4.6 6.8v-.3H6zM18 4.9h2.4a.6.6 0 0 1 .6.6v1.3a4.2 4.2 0 0 1-3.5 4.1V9.2a2.5 2.5 0 0 0 1.9-2.4v-.3H18z";
    private static final String FS_FULL = "M4 9.5V4h5.5M14.5 4H20v5.5M20 14.5V20h-5.5M9.5 20H4v-5.5", FS_SMALL = "M9.5 4v5.5H4M20 9.5h-5.5V4M14.5 20v-5.5H20M4 14.5h5.5V20";

    final Hud hud;
    final Tags tags;
    final Panel panel;
    private final Busy busy;
    private final Settings S;
    private final Btn gear, fs, cup;
    final AchToast achToast;
    Theme onTheme;
    private boolean darkTo;
    private final TextView toast;
    private final Actions act;
    private final float dens;
    private final Handler main = new Handler(Looper.getMainLooper());
    private float rem = 10;
    private int W = 1, H = 1;
    private String fsMode = "";
    private JSONObject mediaNow;
    private String lastTrackId = "";
    private String covUrl = "", covTid = ""; // 当前正在显示的封面地址与它对应的曲目（uiplus 规范：coverUrl 即真相，不做曲目→封面缓存）
    private int covSeq = 0;                   // 换图序号：期间又推了新地址/切了歌，旧加载结果就丢弃
    private long coverHash = Long.MIN_VALUE;  // 认准的那张封面的内容指纹（没封面/取不到时为 MIN_VALUE）
    private String hashTid = "";              // 指纹对应的曲目
    private long trackAt;                     // 最近一次换歌时刻（判断"换歌后极短时间"用）
    private static final long COVER_WAIT = 3000; // 换歌后宿主常还用上一首封面，最多等这么久
    private Runnable coverRetry;              // 待执行的封面重取任务
    private int coverFailCount;               // 连续取不到的次数（限重试，避免死循环）

    private boolean panelOn;
    private boolean cardOn;
    private boolean blocked; // 连不上宿主/未授权：胎压标签随相位计算时强制藏掉

    CarUi(Context c, Settings s, Actions a) {
        super(c);
        act = a;
        S = s;
        dens = c.getResources().getDisplayMetrics().density;
        hud = new Hud(c, S, new Hud.Tap() {
            @Override public void status() { act.statusTap(); }
            @Override public void pick(int i) { act.statusPick(i); }
            @Override public void all() { act.allStatus(); }
            @Override public void close() { act.statusClose(); }
            @Override public void debug() { act.debug(); }
            @Override public void card(boolean fromRight) {
                cardOn = !cardOn;
                hud.setMusic(cardOn, fromRight);
                setPagePhase(cardOn ? 1f : 0f); // 胎压标签随页相位淡出：车模页全显、音乐页藏掉（与 Hud 车况 UI 同步）
                if (act != null) {
                    act.musicCard(cardOn, fromRight ? 1 : -1); // 车模与卡片同向滑动：fromRight→往左出/从左回，反向→往右出/从右回
                    if (cardOn) act.musicLyrics(); // 打开时补拉一次歌词
                }
            }
            @Override public void pageTo(int idx) {
                cardOn = (idx == 1);
                hud.setPageIndex(idx);                    // 0 车况 / 1 音乐 / 2 琉光
                setPagePhase(idx == 0 ? 0f : 1f);         // 非车况页藏掉胎压标签
                if (act != null) {
                    act.musicCard(cardOn, idx > 0 ? -1 : 1);
                    if (cardOn) act.musicLyrics();
                }
            }
            @Override public void music(String a) { if (act != null) act.music(a); }
            @Override public void launchApp() { if (act != null) act.launchApp(); }
        });
        hud.onMsgTop = () -> act.tagMax(hud.msgTop / dens);
        hud.onTint = this::colors; // 音乐背景明暗变化 / 进出音乐页时，让右上角按钮跟着换色
        tags = new Tags(c, hud, act::roofTap);
        addView(hud, new LayoutParams(-1, -1));
        addView(tags, new LayoutParams(-1, -1));
        gear = new Btn(c, GEAR, true);
        gear.setOnClickListener(v -> panel(true));
        fs = new Btn(c, FS_FULL, false);
        fs.setOnClickListener(v -> act.fullscreen());
        fs.setVisibility(GONE);
        addView(fs);
        cup = new Btn(c, CUP, true);
        cup.setOnClickListener(v -> act.trophies());
        addView(cup);
        addView(gear);
        achToast = new AchToast(c, act::trophies);
        addView(achToast, new LayoutParams(-1, -1));
        toast = new TextView(c);
        toast.setGravity(Gravity.CENTER);
        toast.setTextColor(0xffeef1f6);
        toast.setAlpha(0);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(0xf016191f);
        toast.setBackground(tb);
        addView(toast);
        panel = new Panel(c, s, new Panel.Act() {
            @Override public void act(String name) { act.act(name); }
            @Override public void closed() { panelOn = false; buttons(); act.panel(false); }
        });
        addView(panel, new LayoutParams(-1, -1));
        panel.setDarkKProvider(() -> hud.darkK()); // 「跟随车机」模式按车机当前明暗上色
        busy = new Busy(c);
        addView(busy, new LayoutParams(-1, -1));
        colors();
    }

    // ================= 右上角的键 =================
    void panel(boolean on) {
        if (on == panelOn) { if (!on) panel.show(false); return; }
        panelOn = on;
        panel.show(on);
        buttons();
        if (on) act.panel(true);
    }
    boolean panelOpen() { return panelOn; }
    private boolean infoOn;
    /** 全部车况、调试面板开着：齿轮、全屏键藏起来（会盖住面板的关闭键） */
    void infoOpen(boolean on) { infoOn = on; buttons(); }
    /** 连不上宿主、没授权时（上面盖着说明）：字和标签藏起来，齿轮照常能用 */
    void setBlocked(boolean b) {
        blocked = b;
        hud.animate().alpha(b ? 0 : 1).setDuration(300).start();
        setPagePhase(hud.pagePhase()); // 重算胎压标签：连不上宿主时按 blocked 强制藏掉
    }
    /** 胎压标签随页面相位淡出：车模页(ph=0/2)全显、音乐页(ph=1)全隐，与 Hud 车况 UI 同步，避免滑到音乐页残留标签 */
    void setPagePhase(float ph) {
        float carVis = Math.abs(ph - 1f);           // 1=车模页全显、0=音乐页全隐
        float a = blocked ? 0f : carVis;
        tags.setAlpha(a);
        tags.setVisibility(a < 0.02f ? View.GONE : View.VISIBLE);
    }
    /** 全屏键："full" 全屏、"small" 恢复小屏、"" 没有（同 fullscreen.js：在全屏页里是恢复小屏） */
    void setFsMode(String m) {
        fsMode = m;
        fs.d = "small".equals(m) ? FS_SMALL : FS_FULL;
        fs.setVisibility(m.isEmpty() ? GONE : VISIBLE);
        hud.setFs(!m.isEmpty());
        panel.setFs("small".equals(m));
        fs.invalidate();
        post(this::relayout); // 奖杯键的位置看有没有全屏键
    }
    /** 待庆祝的成就几枚（奖杯键上的红点） */
    void setBadge(int n) { cup.badge = n; cup.invalidate(); }

    /** 面板开着时齿轮转 90° 淡出、全屏键和奖杯键跟着藏；解锁的提示先排着 */
    private void buttons() {
        boolean hide = panelOn || infoOn;
        gear.animate().rotation(panelOn ? 90 : 0).alpha(hide ? 0 : 1).setDuration(400).start();
        fs.animate().alpha(hide ? 0 : 1).setDuration(300).start();
        cup.animate().alpha(hide ? 0 : 1).setDuration(300).start();
        gear.setClickable(!hide);
        fs.setClickable(!hide);
        cup.setClickable(!hide);
        achToast.hold(hide);
        hud.setCovered(hide); // 竖屏开着面板：车挤到上面那一小块，车况图标、车顶空调键会压在车上
        tags.setCovered(hide);
    }
    private void colors() {
        float k = hud.darkK();
        float mk = hud.musicTintK();
        if (mk >= 0f) k = mk; // 音乐页且背景是「固定色/跟随封面」：按钮跟着音乐背景明暗走，否则深色封面上会是一颗黑按钮
        int bg = mix(0x1214171c, 0x1affffff, k), fg = mix(0xff14171c, 0xffffffff, k);
        gear.colors(bg, fg);
        fs.colors(bg, fg);
        cup.colors(bg, fg);
        cup.red = hud.bad();
        achToast.setDark(k);
        if (onTheme != null) onTheme.on(darkTo, k);
    }
    private static int mix(int a, int b, float k) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * k) << 24) | (Math.round(ar + (br - ar) * k) << 16) | (Math.round(ag + (bg - ag) * k) << 8) | Math.round(ab + (bb - ab) * k);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        W = Math.max(1, w); H = Math.max(1, h);
        rem = H / 10.52f;
        post(this::relayout);
    }
    private int px(float r) { return Math.round(r * rem); }
    private void relayout() {
        boolean p = W < H;
        float up = p ? 0.32f : 0.34f; // 与 Hud.topShift 一致：顶栏上移的幅度，右上角三键同步上移（仅底部车况数据不动）
        LayoutParams g = new LayoutParams(px(0.56f), px(0.56f), Gravity.TOP | Gravity.END);
        g.topMargin = px(p ? 0.43f - up : 0.45f - up); g.rightMargin = px(p ? 0.22f : 0.26f);
        gear.setLayoutParams(g);
        LayoutParams f = new LayoutParams(px(0.56f), px(0.56f), Gravity.TOP | Gravity.END);
        f.topMargin = px(p ? 1.11f - up : 0.45f - up); f.rightMargin = px(p ? 0.22f : 0.96f);
        fs.setLayoutParams(f);
        // 奖杯键：横屏在全屏键左边、竖屏在全屏键下面（没有全屏键时占它的位置）
        boolean hasFs = fs.getVisibility() == VISIBLE;
        LayoutParams cp = new LayoutParams(px(0.56f), px(0.56f), Gravity.TOP | Gravity.END);
        cp.topMargin = px(p ? (hasFs ? 1.79f - up : 1.11f - up) : 0.45f - up); cp.rightMargin = px(p ? 0.22f : hasFs ? 1.66f : 0.96f);
        cup.setLayoutParams(cp);
        achToast.place(p ? (hasFs ? 2.5f - up : 1.82f - up) : 1.16f - up, p ? 0.22f : 0.26f);
        LayoutParams t = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        t.bottomMargin = px(p ? 2.9f : 1.3f);
        toast.setLayoutParams(t);
        toast.setMaxWidth(Math.min(px(p ? 4.6f : 7f), Math.round(W * 0.9f)));
        toast.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.23f));
        toast.setLineSpacing(0, 1.5f / 1.17f);
        toast.setPadding(px(0.3f), px(0.14f), px(0.3f), px(0.14f));
        ((GradientDrawable) toast.getBackground()).setCornerRadius(px(0.3f));
        toast.setElevation(px(0.1f));
    }

    // ================= 导入车模的框 =================
    /** 开（title）/ 关（null）；进度条的颜色同设置面板的强调色（跟着车漆） */
    void busy(String title) {
        if (title == null) { busy.hide(); return; }
        String a = S.pick("paint").acc;
        int acc = 0xffff4b55;
        try { if (a != null) acc = android.graphics.Color.parseColor(a); } catch (Exception ignored) { }
        busy.show(title, acc);
    }
    void step(String step, float f) { busy.progress(step, f); }

    // ================= 提示条（同 D3.toast：0.3 秒淡入、从下面浮上来） =================
    private Runnable toastOff;
    private void showToast(String msg, int ms) {
        if (toastOff != null) removeCallbacks(toastOff);
        toast.animate().cancel();
        if (msg == null) { toast.animate().alpha(0).translationY(px(0.2f)).setDuration(300).start(); return; }
        toast.setText(msg);
        if (toast.getAlpha() < 0.01f) toast.setTranslationY(px(0.2f));
        toast.animate().alpha(1).translationY(0).setDuration(300).start();
        toastOff = () -> toast.animate().alpha(0).translationY(px(0.2f)).setDuration(300).start();
        postDelayed(toastOff, ms > 0 ? ms : 2600);
    }

    // ================= CarPage.Out（GL 线程里调，切回主线程） =================
    @Override public void loading(String text) { main.post(() -> hud.setLoading(text)); }
    @Override public void top(String g, boolean on, String s, String pw, boolean lv) { main.post(() -> hud.setTop(g, on, s, pw, lv)); }
    @Override public void stats(List<String[]> items) { main.post(() -> hud.setStats(items)); }
    @Override public void status(List<CarPage.St> list, boolean open) { main.post(() -> hud.setStatus(list, open)); }
    @Override public void tells(List<CarStat.Item> l, List<CarStat.Item> r, boolean p) { main.post(() -> hud.setTells(l, r)); }
    @Override public void sys(List<CarStat.Sys> s) { main.post(() -> hud.setSys(s)); }
    @Override public void tyres(float[] xy, String[] cls, String[] text, String[] temp) { main.post(() -> tags.setTyres(xy, cls, text, temp)); }
    @Override public void roof(boolean has, boolean show, float x, float y, boolean on, boolean wait) { main.post(() -> tags.setRoof(has, show, x, y, on, wait)); }
    @Override public void dark(boolean d) { main.post(() -> { darkTo = d; hud.setDark(d, true, () -> { tags.invalidate(); colors(); }); }); }
    @Override public void hint(boolean show) { main.post(() -> hud.setHint(show)); }
    @Override public void toast(String s, int ms) { main.post(() -> showToast(s, ms)); }
    @Override public void modelUI(String name, String credit, boolean resetOff, String[] tips) { main.post(() -> panel.setModel(name, credit, resetOff, tips)); }
    @Override public void acSet(boolean on) { main.post(() -> act.acSet(on)); }
    @Override public void progress(String step, float f) { main.post(() -> busy.progress(step, f)); }
    @Override public void imported(String msg) { main.post(() -> { busy.hide(); showToast(msg, msg.length() > 40 ? 8000 : 4000); }); }
    /** 媒体快照（主线程）：交给 Hud 画音乐卡片；换歌了去拉整首歌词、换封面 */
    @Override public void media(JSONObject m) { main.post(() -> {
        hud.setMedia(m);
        // 曲目号：trackId 优先，没给就用歌名兜底（照 uiplus 规范，trackId 可能为空）
        String tid = m == null ? "" : (m.optString("trackId", "").isEmpty() ? m.optString("title", "") : m.optString("trackId", ""));
        boolean trackChanged = !tid.equals(lastTrackId);
        if (trackChanged) { lastTrackId = tid; trackAt = android.os.SystemClock.uptimeMillis(); if (act != null) act.musicLyrics(); }
        String url = m == null ? "" : m.optString("coverUrl", "");
        // 换歌后宿主头一两秒给的封面常常还是上一首的（同缓存文件、内容没换，地址也一样）：
        // 取到的和上一首确认过的封面一模一样时先不用，每 0.3s 再取一次，换成新图了才算这首的；3s 还一样当真是同一张（同专辑）。
        // 认准后再过 2s 看一眼（宿主有时过会儿才换内容）。没封面地址先占位。做法照琉光音乐。
        if (url.isEmpty()) {
            covSeq++; covUrl = ""; covTid = ""; coverHash = Long.MIN_VALUE; hashTid = ""; coverFailCount = 0;
            if (coverRetry != null) { main.removeCallbacks(coverRetry); coverRetry = null; }
            hud.setCover(null); // 占位
            return;
        }
        if (url.equals(covUrl) && tid.equals(covTid)) return; // 同歌同图：不重复加载
        final int seq = ++covSeq;
        final String fUrl = url, fTid = tid;
        covUrl = url; covTid = tid; coverFailCount = 0;
        if (coverRetry != null) { main.removeCallbacks(coverRetry); coverRetry = null; }
        if (act != null) fetchCover(seq, fUrl, fTid); // 下完才上屏；期间又换了就丢弃
    }); }

    /** 取封面（带序号，过期结果丢弃）；到货后走 onCoverArrived 决定要不要上屏/要不要重取 */
    private void fetchCover(int seq, String fUrl, String fTid) {
        if (act == null) return;
        act.loadCover(fUrl, (b, h) -> onCoverArrived(seq, fUrl, fTid, b, h));
    }
    /** 封面到货：若和上一首确认过的那张一模一样（换歌后宿主还没换文件），每 0.3s 重取，最长 COVER_WAIT；否则上屏并 2s 后再看一眼 */
    private void onCoverArrived(int seq, String fUrl, String fTid, android.graphics.Bitmap b, long h) {
        if (seq != covSeq || !covUrl.equals(fUrl)) return; // 过期结果 / 已换歌换图：丢弃
        if (h != 0 && h == coverHash) { // 和上一首确认过的封面一模一样
            if (!hashTid.equals(fTid) && android.os.SystemClock.uptimeMillis() - trackAt < COVER_WAIT) {
                // 换歌后还是上一首那张：宿主多半还没换封面文件，过一会儿再取
                final Runnable r = () -> fetchCover(++covSeq, fUrl, fTid);
                coverRetry = r; main.postDelayed(r, 300);
                return;
            }
            hashTid = fTid; // 等够了还一样：真是同一张（同专辑），认它属于这首
            return;
        }
        onCover(b, h, fTid);
        if (b != null) { // 认准后过 2s 再看一眼（宿主有时过会儿才换内容）
            final Runnable r = () -> fetchCover(++covSeq, fUrl, fTid);
            coverRetry = r; main.postDelayed(r, 2000);
        } else if (++coverFailCount <= 6) { // 没取到：过 2s 再看（宿主可能还没准备好封面文件），最多重试几次
            final Runnable r = () -> fetchCover(++covSeq, fUrl, fTid);
            coverRetry = r; main.postDelayed(r, 2000);
        }
    }
    /** 把一张确认过的封面送进画布（同时记它对应的曲目与内容指纹） */
    private void onCover(android.graphics.Bitmap b, long h, String fTid) {
        coverHash = (h == 0) ? Long.MIN_VALUE : h;
        hashTid = fTid;
        hud.setCover(b);
    }
    /** 整首歌词（行列表） */
    void setLyrics(List<CarData.LyLine> l) { main.post(() -> hud.setLyrics(l)); }
    /** 一打开就用对深浅色（不渐变） */
    void darkNow(boolean d) { darkTo = d; hud.setDark(d, false, null); colors(); }
    boolean darkTo() { return darkTo; }

    /** 圆形图标键（齿轮、奖杯实心，全屏键描边）；badge > 0 时右上角挂一个红点写个数 */
    private final class Btn extends View {
        String d; final boolean fill; int bg, fg, badge, red = 0xffdc2f2f;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Btn(Context c, String d, boolean fill) { super(c); this.d = d; this.fill = fill; }
        void colors(int b, int f) { bg = b; fg = f; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(isPressed() ? mix(bg, 0x38ffffff, 0.5f) : bg);
            c.drawCircle(w / 2, h / 2, Math.min(w, h) / 2, p);
            float pad = (fill ? 0.12f : 0.13f) * rem, sz = w - pad * 2;
            Matrix m = new Matrix();
            m.setScale(sz / 24, sz / 24); m.postTranslate(pad, pad);
            Path q = new Path(); SvgPath.of(d).transform(m, q);
            p.setColor(fg);
            if (fill) c.drawPath(q, p);
            else { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.2f * sz / 24); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); c.drawPath(q, p); }
            if (badge > 0) {
                String t = badge > 99 ? "99+" : Integer.toString(badge);
                p.setStyle(Paint.Style.FILL);
                p.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 700, false));
                p.setTextSize(0.15f * rem);
                p.setTextAlign(Paint.Align.CENTER);
                float bh = 0.22f * rem, bw = Math.max(bh, p.measureText(t) + 0.1f * rem), bx = w - bw / 2, by = bh / 2;
                p.setColor(red);
                c.drawRoundRect(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2, bh / 2, bh / 2, p);
                p.setColor(0xffffffff);
                Paint.FontMetrics fm = p.getFontMetrics();
                c.drawText(t, bx, by - (fm.ascent + fm.descent) / 2, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
        }
        @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    }
}
