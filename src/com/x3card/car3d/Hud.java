package com.ljone.car3d;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import org.json.JSONObject;

import android.os.Handler;
import android.os.Looper;

/**
 * 车况页上的字和图标（照 style.css 的 .pg-car 那部分画在一块画布上）：顶栏（挡位、车速、系统图标、电源）、车况图标两列（竖屏一排）、
 * 下面那行字（展开的列表、"全部车况 ›"）、底部数据、操作提示、正在载入。深浅色 0.6 秒过渡（同 CSS 的 transition）。
 * 尺寸按 CSS 像素 × 密度（1rem = 高度 / 10.52）。只有可点的地方接手势，别处交给下面的 3D 画布
 */
@SuppressLint("ViewConstructor")
final class Hud extends View {
    interface Tap { void status(); void pick(int i); void all(); void close(); void debug(); void card(boolean fromRight); void pageTo(int idx); void music(String a); void launchApp(); }

    // 浅色 / 深色（同 CSS 变量）：ink、mute、line、chip、ok、warn、bad、info
    private static final int[] LIGHT = { 0xff14171c, 0x8014171c, 0x1f14171c, 0xb3ffffff, 0xff0e9f57, 0xffd97706, 0xffdc2f2f, 0xff2575e6 };
    private static final int[] DARK = { 0xffeef1f6, 0x80eef1f6, 0x21ffffff, 0x1affffff, 0xff3fd98a, 0xffffb547, 0xffff6b6b, 0xff5aa2ff };
    private int ink, mute, line, chip, ok, warn, bad, info;
    private float darkK;
    private ValueAnimator themeAnim;

    private final float dens;
    private final Tap tap;
    private final Settings S;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), sp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface num300, num500, num700, ui500, ui600, ui700;
    private float rem = 10;
    /** 上方区块（顶栏/车况图标/状态/音乐卡片）整体相对底部数据上移的像素；底部车况数据不平移 */
    private float topShift;
    private int W = 1, H = 1;
    private boolean portrait;

    // 状态
    private String gear = "", speed = "0", power = "—", loading = null;
    private boolean speedOn, live, hint = true, fsOn;
    private List<String[]> stats = new ArrayList<>();
    private List<CarPage.St> status = new ArrayList<>();
    private boolean stOpen;
    private List<CarStat.Item> tl = new ArrayList<>(), tr = new ArrayList<>();
    /** drawTells 每帧重排这一排图标：容器复用，别每帧 new 一个 ArrayList */
    private final List<CarStat.Item> tellAll = new ArrayList<>();
    /** drawStatus 那行状态文案变化很慢，却每帧都要重新测量换行：缓存住，省掉每帧的 measureText 循环 */
    private String wrapKey;
    private float wrapMax = -1f;
    private List<String> wrapCache = new ArrayList<>();
    private List<String> wrapCached(String s, float max) {
        if (s.equals(wrapKey) && Math.abs(max - wrapMax) < 0.5f) return wrapCache;
        wrapKey = s; wrapMax = max; wrapCache = wrapText(s, max);
        return wrapCache;
    }
    private List<CarStat.Sys> sys = new ArrayList<>();
    private float hintA = 1;
    // 可点的地方
    private final RectF pillR = new RectF(), allR = new RectF(), coverR = new RectF();
    /** 绘制复用的临时对象：icon()/fan() 每帧被调多次，原来每次都 new Matrix+Path，改用成员复用（车机低端机 GC 压力大） */
    private final Matrix tmpM = new Matrix();
    private final Path tmpPath = new Path();
    /** 音乐背景取不到颜色时的兜底色（原来每帧 new 一个 int[3]） */
    private static final int[] BG_FALLBACK = { 22, 32, 46 };
    /** 音乐整页背景矩形（每帧复用，原来每帧 new 一个 RectF） */
    private final RectF bgFull = new RectF();
    /** 深浅模式背景的径向渐变与缩放矩阵：缓存上一帧的（见 drawThemeBg），原来每帧各 new 一个 */
    private RadialGradient radCache;
    private final Matrix radM = new Matrix();
    private float rCx, rCy, rRy, rRx; private int rCa, rCb, rCc;
    private final List<RectF> listR = new ArrayList<>();
    private boolean pillOn;
    /** 下面那行字的上沿（胎压标签最多到这里）；变了告诉外面 */
    float msgTop;
    Runnable onMsgTop;

    // 时钟：顶栏每秒刷新（让秒数走动）
    private final Handler clockH = new Handler(Looper.getMainLooper());
    private final Runnable clockRun = this::clockTick;
    private void clockTick() {
        invalidate();
        clockH.postDelayed(clockRun, (musicOn && playing) ? 200 : 1000);
    }

    Hud(Context c, Settings s, Tap tap) {
        super(c);
        this.tap = tap;
        this.S = s;
        dens = c.getResources().getDisplayMetrics().density;
        num300 = outfit(c, 300); num500 = outfit(c, 500); num700 = outfit(c, 700);
        ui500 = Typeface.create(Typeface.DEFAULT, 500, false);
        ui600 = Typeface.create(Typeface.DEFAULT, 600, false);
        ui700 = Typeface.create(Typeface.DEFAULT, 700, false);
        palette(0);
        clockH.post(clockRun);
    }
    @Override protected void onDetachedFromWindow() { clockH.removeCallbacks(clockRun); super.onDetachedFromWindow(); }
    static Typeface outfit(Context c, int w) {
        try { return new Typeface.Builder(c.getAssets(), "Outfit.ttf").setFontVariationSettings("'wght' " + w).build(); }
        catch (Exception e) { return Typeface.create(Typeface.DEFAULT, w, false); }
    }
    private static int mix(int a, int b, float k) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * k) << 24) | (Math.round(ar + (br - ar) * k) << 16) | (Math.round(ag + (bg - ag) * k) << 8) | Math.round(ab + (bb - ab) * k);
    }
    private void palette(float k) {
        darkK = k;
        int[] c = new int[8];
        for (int i = 0; i < 8; i++) c[i] = mix(LIGHT[i], DARK[i], k);
        ink = c[0]; mute = c[1]; line = c[2]; chip = c[3]; ok = c[4]; warn = c[5]; bad = c[6]; info = c[7];
    }
    int ink() { return ink; } int mute() { return mute; } int chip() { return chip; } int line() { return line; } int info() { return info; } int warn() { return warn; } int bad() { return bad; }
    float darkK() { return darkK; }

    // ================= 外面交进来的（主线程） =================
    void setDark(boolean d, boolean anim, Runnable each) {
        float to = d ? 1 : 0;
        if (themeAnim != null) themeAnim.cancel();
        if (!anim) { palette(to); invalidate(); if (each != null) each.run(); return; }
        themeAnim = ValueAnimator.ofFloat(darkK, to);
        themeAnim.setDuration(600);
        themeAnim.addUpdateListener(a -> { palette((Float) a.getAnimatedValue()); invalidate(); if (each != null) each.run(); });
        themeAnim.start();
    }
    void setTop(String g, boolean on, String s, String pw, boolean lv) { gear = g; speedOn = on; speed = s; power = pw; live = lv; invalidate(); }
    void setStats(List<String[]> l) { stats = l; invalidate(); }
    void setStatus(List<CarPage.St> l, boolean open) { status = l; stOpen = open; invalidate(); }
    private List<CarStat.Item> allTellL, allTellR; // 全量车况图标（缓存，改 tells 设置后立即重过滤）
    void setTells(List<CarStat.Item> l, List<CarStat.Item> r) {
        allTellL = l; allTellR = r; // 记住全量列表，设置改了 tells 后无需等车况数据刷新就能重过滤
        refilterTells();
    }
    /** 按当前 S.tells() 重新过滤车况图标：改「车况图标」设置后立即生效，不必等下一次车况数据回调 */
    void refilterTells() {
        if (allTellL == null || allTellR == null) return;
        List<String> on = S.tells();
        tl = new ArrayList<>(); tr = new ArrayList<>();
        for (CarStat.Item it : allTellL) if (on.contains(it.id)) tl.add(it);
        for (CarStat.Item it : allTellR) if (on.contains(it.id)) tr.add(it);
        invalidate();
    }
    void setSys(List<CarStat.Sys> s) { sys = s; invalidate(); }
    void setLoading(String s) { loading = s; invalidate(); }
    void setHint(boolean on) {
        if (on == hint) return;
        hint = on;
        ValueAnimator a = ValueAnimator.ofFloat(hintA, on ? 1 : 0);
        a.setDuration(600);
        a.addUpdateListener(x -> { hintA = (Float) x.getAnimatedValue(); invalidate(); });
        a.start();
    }
    void setFs(boolean on) { fsOn = on; invalidate(); }
    /** 调试宿主：顶栏电源后面一个"调试数据"（横屏才有），点了打开调试面板 */
    void setDemo(boolean on) { demo = on; invalidate(); }
    /** 面板开着（竖屏时车况图标那一排、车速藏起来） */
    void setCovered(boolean on) { if (covered != on) { covered = on; invalidate(); } }
    private boolean covered;
    private boolean demo;
    private final RectF demoR = new RectF();

    // 音乐卡片
    private boolean musicOn, playing;
    private String mTitle, mArtist, mLyric, mCover;
    private android.graphics.Bitmap coverBm;
    private int[] coverDomRgb; // 封面主色缓存（换封面时清空，music 背景跟随封面用）
    private android.graphics.Bitmap coverBlurBm; // 封面缩糊位图（跟随封面背景用，拉伸即虚化）
    private int[] coverLight, coverDark;          // 封面最亮/最暗两极色（双色流动/主色光晕用）
    private Paint coverPaint;                     // 画模糊封面用（开 bitmap 滤镜）
    private int[] musicBgRgb;  // 音乐整页背景主色（供音乐组件/顶栏/底部数据文字配色自适应）
    private long mPos, mDur, mRecv;
    private long mPosReported = -1; // 宿主上次上报的 positionMs（-1=还没收到过）：用来判断「位置真的变了」才重锚定，避免换句把进度清零
    private boolean posSet;         // 当前锚点是否对得上当前曲目（换歌后重设为 true，照琉光 posSet）
    private boolean mHadMedia;      // 是否曾经播过歌（媒体为空时据此决定保留上一首还是显示「暂无音乐」）
    private List<String> lyrics = new ArrayList<>();
    private List<Long> lyrTimes = new ArrayList<>();           // 与 lyrics 平行：每句在整首里的开始时间（宿主没给是 -1）
    private final List<String> liveLyrics = new ArrayList<>(); // 宿主只给逐句快照时累积的歌词行（换歌清空）
    private int lyrIdx = -1;      // 当前句是第几句（持久状态，照琉光 lyr.idx：不再每帧从 0 重扫，重复句按顺序递进）
    private String lyrLine = "";  // 当前显示的句子（判断宿主推来的句子有没有换）
    private String lyrStale = ""; // 换歌时上一首留下的那句：宿主换歌后头几条快照常常还是它，照琉光 lyr.stale 挡掉
    private Runnable lyrAdvance;  // 连着两句一模一样时按时间自己往下走（琉光 chainRepeat）
    private String liveTrack = "", mTrackId = "";
    private float tellBottom, speedBottom, statusTop, tellLw, tellRw; // 音乐卡片要避开的其他区域几何（每帧随画更新）
    private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF statsR = new RectF(), cardR = new RectF();
    private final RectF prevR = new RectF(), playR = new RectF(), nextR = new RectF();
    private float downX, downY;
    private float statTop;
    /** 音乐页背景的等效明暗：0=浅底（用深字），1=深底（用浅字）；
     *  -1 = 不在音乐页，或背景模式是「深浅模式」——这两种情况都用全局主题色 */
    private float tintK = -1f;
    Runnable onTint; // 明暗切换 / 进出音乐页时通知外层（右上角按钮等不在 Hud 里画的元素要跟着换色）
    float musicTintK() { return tintK; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        W = Math.max(1, w); H = Math.max(1, h);
        rem = H / 10.52f;
        portrait = W < H;
        topShift = rem * (portrait ? -0.32f : -0.34f); // 整体相对底部数据上移：头部终点约等于 0.16rem
    }
    private float r(float v) { return v * rem; }

    // ================= 画 =================
    @Override protected void onDraw(Canvas c) {
        if (pageIdx == 2) pushLg(); // 第三页：把当前媒体快照推给琉光 3D 场景
        // 顶栏、车况图标、车速、下面那行字在两个页面都照常显示，浮在音乐整页背景之上
        // 底部车况数据固定不动；其余元素整体随 topShift 上移（drawStats 在平移之外单独绘制）
        float ph = phase; // 折回 [0,2) 的循环相位：0/2=车模页、1=音乐页（越过边界即循环到另一页）
        cardOffPx = (1 - ph) * W; // 音乐层偏移：ph=0 车模页（音乐藏右侧）、ph=1 音乐页（音乐就位）；与 GL 车模交叉滑动
        float k = Math.max(0f, Math.min(1f, 1f - Math.abs(ph - 1f))); // 音乐整页背景/组件淡入：ph=1 满、车模页(0/2)为 0
        c.save();
        c.translate(0, topShift); // 顶栏/车况图标/状态/音乐卡片统一上移，作为刚体
        // 音乐整页背景：最底层，铺满整屏（含滑动偏移）；顶栏/车况图标/状态浮在其上
        if (k > 0.01f) {
            // layer 要覆盖真正的整屏：此时已 translate(0, topShift)，若还写 (0,0,W,H)，
            // 底部会空出 |topShift| 一条画不到音乐背景，露出下面的车模层（显成一条异色横条）
            int s = c.saveLayerAlpha(0, -topShift, W, H - topShift, Math.round(255 * k), Canvas.ALL_SAVE_FLAG);
            c.translate(cardOffPx, 0);
            drawMusicBackground(c);
            c.restore();
        }
        // 背景模式「深浅模式」：背景本身就跟随全局主题，文字与胶囊直接用主题色（天然配套，和车况页一致）。
        // 背景模式「固定色 / 跟随封面」：背景是外来色，按它的明暗整套切配色（文字 + 胶囊 + 分隔线 + 进度条），
        // 既保证可读，也让底部这些元素和页面背景同步；palette() 连 darkK 一起改，挡位的字色会自动和高亮块形成反差。
        float dk0 = darkK;
        float tk = -1f;
        if (k > 0.01f && !"theme".equals(S.str("mBgMode"))) {
            int[] bg = (musicBgRgb != null) ? musicBgRgb : BG_FALLBACK;
            tk = lumin(bg) > 0.55f ? 0f : 1f;
        }
        if (tk != tintK) { tintK = tk; if (onTint != null) onTint.run(); } // 明暗变了 / 进出音乐页：通知外层刷新右上角按钮
        if (tk >= 0f) {
            palette(tk);
            // 固定色 / 跟随封面：按背景明暗整套自适应（深底白字、浅底深字）。
            // 「上方车况图标、下方车况数据、全部车况」三处都走同一套 ink/mute，互相一致、且随背景深浅变换。
            // 次文字不透明度提到 ~78%，防中明度背景（如蓝）上灰字发虚看不清。
            mute = withA(ink, 0.78f);
            darkK = tk;
        }
        drawHead(c);
        drawTells(c);
        drawStatus(c);
        if (k > 0.01f) { // 音乐组件（歌词/播控）：整层横移 + 淡入，组件本身无背景，直接浮在页面背景上
            int s = c.saveLayerAlpha(0, 0, W, H, Math.round(255 * k), Canvas.ALL_SAVE_FLAG);
            c.translate(cardOffPx, 0);
            drawMusicContent(c);
            c.restore();
        } else if (!musicOn) {
            if (loading != null) {
                p.setTypeface(Typeface.DEFAULT); p.setTextSize(r(0.22f)); p.setColor(mute); p.setTextAlign(Paint.Align.CENTER);
                c.drawText(loading, W / 2f, H * 0.48f + r(0.22f), p);
                p.setTextAlign(Paint.Align.LEFT);
            }
        }
        c.restore();
        drawStats(c); // 底部车况数据不随上移（配色随背景模式走：深浅模式用主题色，否则按背景明暗）
        if (k > 0.01f && !"theme".equals(S.str("mBgMode"))) palette(dk0); // 还原全局主题色（含 darkK），不影响下一帧车况页与深浅过渡动画
    }
    private static int withA(int c, float a) { return (Math.round((c >>> 24) * a) << 24) | (c & 0xffffff); }

    private void drawHead(Canvas c) {
        float left = r(portrait ? 0.3f : 0.4f), sz = r(0.46f), top = r(portrait ? 0.48f : 0.5f), cy = top + sz / 2;
        float lx = drawClock(c, left, cy);
        // 挡位不算顶栏：下移一行显示（车速横屏跟随挡位行；竖屏车速仍在下方原位）
        float topG = top + r(portrait ? 0.56f : 0.58f), gy = topG + sz / 2;
        String[] gs = { "P", "R", "N", "D" };
        p.setTypeface(ui600); p.setTextSize(r(0.28f)); p.setTextAlign(Paint.Align.CENTER);
        float x = left;
        for (String g : gs) {
            boolean on = g.equals(gear);
            if (on) { p.setColor(ink); c.drawRoundRect(x, topG, x + sz, topG + sz, r(0.12f), r(0.12f), p); }
            p.setColor(on ? mix(0xfff4f5f7, 0xff111317, darkK) : mute);
            c.drawText(g, x + sz / 2, baseline(gy, p), p);
            x += sz + r(0.06f);
        }
        p.setTextAlign(Paint.Align.LEFT);
        if (portrait) speedBottom = 0; // 每帧重置：车速没画时卡片不必避开
        if (speedOn && !(portrait && covered)) { // 车速（竖屏在车况图标那一排下面：同网页版，顶栏往下 1.22rem，顶栏本身在 top；竖屏开着面板时车挤到上面，会压在车上，同车况图标一起藏）
            float sx = portrait ? left : x - r(0.06f) + r(0.26f), sy = portrait ? top + r(1.62f) + r(0.4f) : gy;
            p.setTypeface(num300); p.setTextSize(r(0.4f)); p.setColor(ink);
            float bl = portrait ? top + r(1.62f) + r(0.4f) * 0.95f : baseline(sy, p);
            c.drawText(speed, sx, bl, p);
            if (portrait) speedBottom = bl + r(0.12f); // 音乐卡片要避开竖屏车速
            float sw = p.measureText(speed);
            p.setTypeface(Typeface.DEFAULT); p.setTextSize(r(0.2f)); p.setColor(mute);
            c.drawText("km/h", sx + sw + r(0.06f), bl, p);
        }
        // 右边：系统图标、电源（右边留给齿轮、全屏键、奖杯键）
        float right = W - r(portrait ? 0.3f : 0.4f) - r(portrait ? 0.62f : fsOn ? 2.02f : 1.32f);
        p.setTypeface(ui500); p.setTextSize(r(0.2f));
        demoR.setEmpty();
        if (demo && !portrait) {
            float dw = p.measureText("调试数据") + r(0.32f), dh = r(0.2f) * 1.45f + r(0.1f);
            demoR.set(right - dw, cy - dh / 2, right, cy + dh / 2);
            sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(Math.max(1, dens)); sp.setColor(line);
            c.drawRoundRect(demoR, r(0.3f), r(0.3f), sp);
            sp.setStyle(Paint.Style.FILL);
            p.setColor(warn);
            c.drawText("调试数据", demoR.left + r(0.16f), baseline(cy, p), p);
            right = demoR.left - r(0.1f);
        }
        float pw = p.measureText(power) + (live ? r(0.09f + 0.08f) : 0), ch = r(0.2f) * 1.45f + r(0.1f), cx0 = right - pw - r(0.32f);
        RectF cr = new RectF(cx0, cy - ch / 2, right, cy + ch / 2);
        sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(Math.max(1, dens)); sp.setColor(line);
        c.drawRoundRect(cr, r(0.3f), r(0.3f), sp);
        sp.setStyle(Paint.Style.FILL);
        float tx = cx0 + r(0.16f);
        p.setColor(live ? ok : mute);
        if (live) { c.drawCircle(tx + r(0.045f), cy, r(0.045f), p); tx += r(0.17f); }
        c.drawText(power, tx, baseline(cy, p), p);
        // 系统图标：从右往左排在电源前面
        float ix = cx0 - r(0.06f);
        for (int i = sys.size() - 1; i >= 0; i--) {
            if (portrait && i >= 2) continue; // 竖屏只放两个
            CarStat.Sys s = sys.get(i);
            float w = r(0.3f) + (s.label != null ? labelW(s.label) : 0);
            ix -= w;
            drawSys(c, s, ix, cy);
            ix -= r(0.1f);
        }
    }
    
    /** 顶栏左端（与右侧系统图标同一行）：时间加粗在前、日期（M月d日）同行；支持 24/12 小时制与秒 */
    private float drawClock(Canvas c, float left, float cy) {
        Calendar now = Calendar.getInstance();
        boolean h24 = S.bool("hour24");
        boolean sec = S.bool("showSec") && !(S.bool("secHideNarrow") && portrait);
        int h = now.get(Calendar.HOUR_OF_DAY), m = now.get(Calendar.MINUTE), s = now.get(Calendar.SECOND);
        String ampm = "";
        if (!h24) { boolean am = h < 12; h = h % 12; if (h == 0) h = 12; ampm = am ? " AM" : " PM"; }
        String time = (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m + (sec ? ":" + (s < 10 ? "0" : "") + s : "") + ampm;
        String date = (now.get(Calendar.MONTH) + 1) + "月" + now.get(Calendar.DAY_OF_MONTH) + "日";
        p.setTypeface(num700); p.setTextSize(r(portrait ? 0.30f : 0.42f)); p.setColor(ink);
        // 固定时间槽宽：Outfit 是比例字体（数字 1 与 0 等宽度不同），直接按时间实测宽度定位会让日期随秒/分/时数字左右跳动。
        // 改用当前格式下“最宽”的时间串（数字全用 8）测出固定槽宽，日期位置不再随数字变化而移动。
        String ref = "88:88" + (sec ? ":88" : "") + ampm;
        float tw = p.measureText(ref);
        c.drawText(time, left, baseline(cy, p), p);
        float tx = left + tw + r(0.12f);
        p.setTypeface(Typeface.DEFAULT); p.setTextSize(r(portrait ? 0.21f : 0.27f)); p.setColor(mute);
        c.drawText(date, tx, baseline(cy, p), p);
        return tx + p.measureText(date);
    }
    private float labelW(String s) { p.setTypeface(ui700); p.setTextSize(r(0.17f)); return r(0.02f) + p.measureText(s); }
    private static float baseline(float cy, Paint p) { Paint.FontMetrics m = p.getFontMetrics(); return cy - (m.ascent + m.descent) / 2; }

    /** 24×24 的图标画在 (x, y-0.15rem) 起的 0.3rem 方块里 */
    private void icon(Canvas c, String d, float x, float cy, float size, int color, boolean fill, float alpha) {
        Path path = SvgPath.of(d);
        tmpM.reset();
        tmpM.setScale(size / 24, size / 24);
        tmpM.postTranslate(x, cy - size / 2);
        tmpPath.reset();
        path.transform(tmpM, tmpPath);
        sp.setColor(withA(color, alpha));
        if (fill) { sp.setStyle(Paint.Style.FILL); }
        else { sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(1.8f * size / 24); sp.setStrokeCap(Paint.Cap.ROUND); sp.setStrokeJoin(Paint.Join.ROUND); }
        c.drawPath(tmpPath, sp);
        sp.setStyle(Paint.Style.FILL);
    }
    private void fan(Canvas c, float x, float cy, float size, int color) {
        Path path = SvgPath.of(CarStat.FAN_D);
        for (int k = 0; k < 4; k++) {
            tmpM.reset();
            tmpM.setRotate(90 * k, 12, 12);
            tmpM.postScale(size / 24, size / 24);
            tmpM.postTranslate(x, cy - size / 2);
            tmpPath.reset();
            path.transform(tmpM, tmpPath);
            sp.setColor(color); sp.setStyle(Paint.Style.FILL);
            c.drawPath(tmpPath, sp);
        }
        c.drawCircle(x + 12 * size / 24, cy, 1.9f * size / 24, sp);
    }
    private void drawSys(Canvas c, CarStat.Sys s, float x, float cy) {
        float sz = r(0.3f), a = s.dim ? 0.4f : 1;
        switch (s.kind) {
            case "bars": {
                for (int i = 0; i < 4; i++) {
                    float bh = 5 + i * (14 / 3f), bx = 3 + i * 4.6f;
                    sp.setColor(withA(ink, a * (i < s.level ? 1 : 0.25f)));
                    float k = sz / 24, y0 = cy - sz / 2;
                    c.drawRoundRect(x + bx * k, y0 + (20 - bh) * k, x + (bx + 4.6f - 1.6f) * k, y0 + 20 * k, k, k, sp);
                }
                break;
            }
            case "wifi": {
                String[] arcs = { "M12 18.5h.01", "M9.2 15.6a4 4 0 0 1 5.6 0", "M6.4 12.8a8 8 0 0 1 11.2 0", "M3.6 10a12 12 0 0 1 16.8 0" };
                for (int i = 0; i < 4; i++) icon(c, arcs[i], x, cy, sz, ink, false, a * (!s.hollow && i <= s.level ? 1 : 0.25f));
                break;
            }
            default: icon(c, CarStat.ICON.get(s.kind), x, cy, sz, ink, false, a);
        }
        if (s.label != null) { p.setTypeface(ui700); p.setTextSize(r(0.17f)); p.setColor(withA(ink, a)); c.drawText(s.label, x + sz + r(0.02f), baseline(cy, p), p); }
        if (s.warn) { p.setTypeface(ui700); p.setTextSize(r(0.16f)); p.setColor(warn); c.drawText("!", x + sz - r(0.02f), cy - sz / 2 + r(0.12f), p); }
    }

    // ---------------- 车况图标 ----------------
    private float itemW(CarStat.Item it, float fs) {
        if (!it.modes.isEmpty()) {
            p.setTypeface(ui700); p.setTextSize(r(0.19f)); p.setLetterSpacing(0.04f);
            float w = r(0.04f) * 2;
            for (String[] m : it.modes) w += p.measureText(m[0]) + r(0.24f) + r(0.04f);
            p.setLetterSpacing(0);
            return w;
        }
        float w = r(0.1f) + r(0.3f) + (it.text.isEmpty() ? r(0.1f) : r(0.08f) + r(0.16f));
        p.setTypeface(ui500); p.setTextSize(fs);
        w += p.measureText(it.text);
        p.setTextSize(r(0.18f));
        for (String t : it.tags) w += r(0.08f) + p.measureText(t) + r(0.16f);
        return w;
    }
    private int clsColor(String cls, boolean svg) {
        if (cls.contains("bad")) return bad;
        if (cls.contains("warn") || (svg && cls.contains("warm"))) return warn;
        if (cls.contains("ok")) return ok;
        if (cls.contains("blue") || (svg && cls.contains("on"))) return info;
        return ink;
    }
    private float drawItem(Canvas c, CarStat.Item it, float x, float y, float fs) {
        float h = fs * 1.45f + r(0.1f), w = itemW(it, fs), cy = y + h / 2;
        sp.setColor(chip);
        c.drawRoundRect(x, y, x + w, y + h, r(0.3f), r(0.3f), sp);
        if (!it.modes.isEmpty()) {
            float bx = x + r(0.04f);
            p.setTypeface(ui700); p.setTextSize(r(0.19f)); p.setLetterSpacing(0.04f);
            for (String[] m : it.modes) {
                float tw = p.measureText(m[0]), bw = tw + r(0.24f), bh = r(0.19f) * 1.3f + r(0.04f);
                sp.setColor(line);
                c.drawRoundRect(bx + r(0.02f), cy - bh / 2, bx + r(0.02f) + bw, cy + bh / 2, r(0.24f), r(0.24f), sp);
                p.setColor("eco".equals(m[1]) || "ev".equals(m[1]) ? ok : "sport".equals(m[1]) ? bad : "comfort".equals(m[1]) || "hev".equals(m[1]) ? info : ink);
                c.drawText(m[0], bx + r(0.02f) + r(0.12f), baseline(cy, p), p);
                bx += bw + r(0.04f);
            }
            p.setLetterSpacing(0);
            return h;
        }
        float ix = x + r(0.1f), isz = r(0.3f);
        if ("fan".equals(it.icon)) fan(c, ix, cy, isz, clsColor(it.cls, true));
        else if (it.icon != null) icon(c, CarStat.ICON.get(it.icon), ix, cy, isz, clsColor(it.cls, true), false, 1);
        float tx = ix + isz + r(0.08f);
        p.setTypeface(ui500); p.setTextSize(fs); p.setColor(it.cls.contains("on") && !it.cls.contains("blue") ? ink : clsColor(it.cls, false));
        if (!it.text.isEmpty()) c.drawText(it.text, tx, baseline(cy, p), p);
        tx += p.measureText(it.text);
        p.setTextSize(r(0.18f));
        for (String t : it.tags) {
            float tw = p.measureText(t), bh = r(0.18f) * 1.35f;
            sp.setColor(line);
            c.drawRoundRect(tx + r(0.08f), cy - bh / 2, tx + r(0.08f) + tw + r(0.16f), cy + bh / 2, r(0.1f), r(0.1f), sp);
            p.setColor(mute);
            c.drawText(t, tx + r(0.08f) + r(0.08f), baseline(cy, p), p);
            tx += r(0.08f) + tw + r(0.16f);
        }
        return h;
    }
    private void drawTells(Canvas c) {
        tellLw = tellRw = 0;
        tellBottom = portrait ? r(1.64f) + r(0.19f) * 1.45f + r(0.1f) : 0; // 竖屏这排固定占位，音乐卡片从它下面开始
        if (portrait) { // 一排，放不下的藏掉（告警排前面）
            if (covered) return;
            List<CarStat.Item> all = tellAll; all.clear();
            for (CarStat.Item it : tl) if (it.bad()) all.add(it);
            for (CarStat.Item it : tr) if (it.bad()) all.add(it);
            for (CarStat.Item it : tl) if (!it.bad() && it.warn()) all.add(it);
            for (CarStat.Item it : tr) if (!it.bad() && it.warn()) all.add(it);
            for (CarStat.Item it : tl) if (!it.bad() && !it.warn()) all.add(it);
            for (CarStat.Item it : tr) if (!it.bad() && !it.warn()) all.add(it);
            float x = r(0.3f), lim = W - r(0.88f), y = r(1.64f), fs = r(0.19f);
            for (CarStat.Item it : all) {
                float w = itemW(it, fs);
                if (x + w > lim + 1) break;
                drawItem(c, it, x, y, fs);
                x += w + r(0.08f);
            }
            return;
        }
        float top = r(1.68f), bottom = H - r(2.3f), fs = r(0.21f);
        float y = top;
        for (CarStat.Item it : tl) {
            tellLw = Math.max(tellLw, itemW(it, fs)); // 左列最宽项：音乐卡片（宽屏左控制器）要避开
            float h = fs * 1.45f + r(0.1f);
            if (y + h > bottom + 1) break;
            drawItem(c, it, r(0.4f), y, fs);
            y += h + r(0.1f);
        }
        y = top - (fs * 1.45f + r(0.2f)); // 宽屏：右侧车况图标列上移一行
        for (CarStat.Item it : tr) {
            float h = fs * 1.45f + r(0.1f), w = itemW(it, fs);
            tellRw = Math.max(tellRw, w); // 右列最宽项：音乐卡片（宽屏右歌词）要避开
            if (y + h > bottom + 1) break;
            drawItem(c, it, W - r(0.4f) - w, y, fs);
            y += h + r(0.1f);
        }
    }

    // ---------------- 下面那行字 ----------------
    private int lvColor(int lv) { return lv == 3 ? bad : lv == 2 ? warn : lv == 1 ? info : ok; }
    private void drawStatus(Canvas c) {
        listR.clear(); pillR.setEmpty(); allR.setEmpty(); pillOn = false;
        float bottom = H - r(portrait ? 2.42f : 1.66f), fs = r(portrait ? 0.24f : 0.26f);
        List<CarPage.St> L = status;
        int topLv = L.isEmpty() ? 0 : L.get(0).lv;
        List<String> shown = new ArrayList<>();
        for (CarPage.St s : L) if (s.lv == topLv && shown.size() < 2) shown.add(s.t);
        int more = L.size() - shown.size();
        String msg = String.join(" · ", shown);
        // "全部车况 ›"
        p.setTypeface(ui500); p.setTextSize(r(0.2f));
        float allW = p.measureText("全部车况 ›") + r(0.32f), allH = r(0.2f) * 1.45f + r(0.1f);
        p.setTextSize(fs); p.setTypeface(ui500);
        float maxText = W * 0.96f - allW - r(0.12f) - r(0.44f) - r(0.24f) - (more > 0 ? r(0.5f) : 0);
        List<String> lines = wrapCached(msg, maxText);
        float lh = fs * 1.35f, pillH = Math.max(lh * lines.size(), lh) + r(0.14f);
        statusTop = bottom - pillH; // 气泡顶沿：音乐卡片下沿要避开它
        float textW = 0;
        for (String s : lines) textW = Math.max(textW, p.measureText(s));
        p.setTextSize(r(0.2f)); p.setTypeface(ui600);
        float badgeW = more > 0 ? p.measureText("+" + more) + r(0.24f) : 0;
        float pillW = r(0.22f) * 2 + r(0.12f) + r(0.12f) + textW + (more > 0 ? r(0.12f) + badgeW : 0);
        float rowW = pillW + r(0.12f) + allW, x0 = (W - rowW) / 2, rowCy = bottom - pillH / 2;
        if (Math.abs(msgTop - (bottom - pillH)) > 0.5f) { msgTop = bottom - pillH; if (onMsgTop != null) post(onMsgTop); }
        boolean clickable = L.size() > 1 || (L.size() == 1 && L.get(0).go());
        if (!msg.isEmpty()) {
            pillR.set(x0, bottom - pillH, x0 + pillW, bottom);
            pillOn = clickable;
            if (clickable) { sp.setColor(chip); c.drawRoundRect(pillR, r(0.3f), r(0.3f), sp); sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(Math.max(1, dens)); sp.setColor(withA(ink, 0.55f)); c.drawRoundRect(pillR, r(0.3f), r(0.3f), sp); sp.setStyle(Paint.Style.FILL); }
            float dx = x0 + r(0.22f) + r(0.06f);
            int col = lvColor(topLv);
            sp.setColor(withA(col, 0.18f)); c.drawCircle(dx, rowCy, r(0.06f) + r(0.05f), sp);
            sp.setColor(col); c.drawCircle(dx, rowCy, r(0.06f), sp);
            p.setTypeface(ui500); p.setTextSize(fs); p.setColor(ink);
            float ty = rowCy - lh * lines.size() / 2 + lh / 2;
            for (String s : lines) { c.drawText(s, dx + r(0.06f) + r(0.12f), baseline(ty, p), p); ty += lh; }
            if (more > 0) {
                float bx = dx + r(0.18f) + textW + r(0.12f), bh = r(0.32f);
                sp.setColor(chip); c.drawRoundRect(bx, rowCy - bh / 2, bx + badgeW, rowCy + bh / 2, r(0.16f), r(0.16f), sp);
                p.setTypeface(ui600); p.setTextSize(r(0.2f)); p.setColor(ink);
                c.drawText("+" + more, bx + r(0.12f), baseline(rowCy, p), p);
            }
        }
        allR.set(x0 + pillW + r(0.12f), rowCy - allH / 2, x0 + pillW + r(0.12f) + allW, rowCy + allH / 2);
        sp.setColor(chip); c.drawRoundRect(allR, r(0.3f), r(0.3f), sp);
        sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(Math.max(1, dens)); sp.setColor(withA(ink, 0.55f)); c.drawRoundRect(allR, r(0.3f), r(0.3f), sp); sp.setStyle(Paint.Style.FILL);
        p.setTypeface(ui500); p.setTextSize(r(0.2f)); p.setColor(ink);
        c.drawText("全部车况 ›", allR.left + r(0.16f), baseline(rowCy, p), p);
        if (stOpen && L.size() > 1) { // 展开的列表：在那行字上面
            float fs2 = r(0.24f), ih = fs2 * 1.35f + r(0.12f), lw = 0;
            p.setTypeface(ui500); p.setTextSize(fs2);
            for (CarPage.St s : L) lw = Math.max(lw, p.measureText(s.t) + (s.go() ? r(0.14f) + p.measureText("›") : 0));
            lw = Math.min(W * 0.92f, lw + r(0.24f) + r(0.24f) + r(0.24f));
            float lh2 = ih * L.size() + r(0.2f), ly = bottom - pillH - r(0.1f) - lh2, lx = (W - lw) / 2;
            sp.setColor(chip); c.drawRoundRect(lx, ly, lx + lw, ly + lh2, r(0.22f), r(0.22f), sp);
            float y = ly + r(0.1f);
            for (CarPage.St s : L) {
                float cy = y + ih / 2;
                sp.setColor(lvColor(s.lv)); c.drawCircle(lx + r(0.24f) + r(0.06f), cy, r(0.06f), sp);
                p.setColor(ink); c.drawText(s.t, lx + r(0.24f) + r(0.24f), baseline(cy, p), p);
                if (s.go()) { p.setColor(mute); c.drawText("›", lx + r(0.48f) + p.measureText(s.t) + r(0.14f), baseline(cy, p), p); }
                listR.add(new RectF(lx, y, lx + lw, y + ih));
                y += ih;
            }
        }
    }
    private List<String> wrapText(String s, float max) {
        List<String> out = new ArrayList<>();
        if (s.isEmpty()) { out.add(""); return out; }
        int st = 0;
        while (st < s.length()) {
            int n = p.breakText(s, st, s.length(), true, Math.max(1, max), null);
            if (n <= 0) n = 1;
            out.add(s.substring(st, st + n));
            st += n;
        }
        return out;
    }

    // ---------------- 底部数据 ----------------
    private void drawStats(Canvas c) {
        int n = Math.max(1, stats.size());
        float fsK = 1f, lab = r(0.2f * fsK);
        float left = r(portrait ? 0.3f : 0.4f), right = W - left, bottom = H - r(portrait ? 0.32f : 0.42f);
        int cols = portrait ? (n <= 1 ? 1 : 3) : n, rows = (n + cols - 1) / cols;
        float cw = (right - left) / cols, vfs = r((portrait ? 0.44f : 0.5f) * fsK);
        float cellH = lab * 1.2f + r(0.02f) + vfs * 1.1f + r(0.13f);
        float gap = portrait ? r(0.22f) : 0, top = bottom - rows * cellH - (rows - 1) * gap;
        for (int i = 0; i < stats.size(); i++) {
            String[] s = stats.get(i);
            int col = i % cols, row = i / cols;
            float x = left + col * cw, y = top + row * (cellH + gap);
            boolean firstCol = col == 0;
            if (!firstCol) { sp.setColor(line); c.drawRect(x, y, x + Math.max(1, dens), y + cellH, sp); }
            float tx = x + (firstCol ? 0 : r(0.24f));
            p.setTypeface(ui600); p.setTextSize(lab); p.setColor(mute); p.setLetterSpacing(0.04f);
            c.drawText(s[1], tx, y + lab, p);
            p.setLetterSpacing(0);
            float vb = y + lab * 1.2f + r(0.02f) + vfs * 0.95f;
            if (s[2] == null) { p.setTypeface(num500); p.setTextSize(vfs); p.setColor(ink); c.drawText("—", tx, vb, p); }
            else {
                String[] seg = s[2].split("\u0001");
                float vx = tx;
                for (int k = 0; k < seg.length; k++) {
                    boolean small = k % 2 == 1;
                    p.setTypeface(small ? ui600 : num500); p.setTextSize(small ? lab : vfs); p.setColor(small ? mute : ink);
                    if (small) vx += r(0.04f);
                    c.drawText(seg[k], vx, vb, p);
                    vx += p.measureText(seg[k]) + (small ? r(0.04f) : 0);
                }
                if (s[3] != null && !s[3].isEmpty()) { p.setTypeface(ui600); p.setTextSize(lab); p.setColor(mute); c.drawText(s[3], vx + r(0.04f), vb, p); }
            }
            if (s[4] != null) { // 电量 / 油量的进度条
                float bw = (cw - (firstCol ? 0 : r(0.24f))) * 0.72f, by = vb + r(0.08f) + vfs * 0.15f, bh = r(0.05f);
                sp.setColor(line); c.drawRoundRect(tx, by, tx + bw, by + bh, r(0.03f), r(0.03f), sp);
                float f = Float.parseFloat(s[4]);
                sp.setColor("low".equals(s[5]) ? bad : "mid".equals(s[5]) ? warn : ok);
                if (f > 0) c.drawRoundRect(tx, by, tx + bw * f, by + bh, r(0.03f), r(0.03f), sp);
            }
        }
        statTop = top;
        statsR.set(0, top - r(0.12f), W, H);
        // 底部提示：当前页名 · 左右滑动切回 下一页名（车模页 ↔ 音乐卡片页）
        float iy = H - r(portrait ? 0.16f : 0.22f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(ui600); p.setTextSize(r(0.19f)); p.setColor(withA(mute, 0.92f));
        c.drawText(musicOn ? "音乐  ·  左右滑动切回 车模" : "车模  ·  左右滑动切回 音乐", W / 2f, iy, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    // ================= 音乐卡片 =================
    void setMusic(boolean on, boolean fromRight) { setPageIndex(on ? 1 : 0); }
    /** 三页切换：0=车况、1=音乐、2=琉光 */
    void setPageIndex(int idx) {
        if (idx < 0 || idx > 2) return;
        if (idx == pageIdx && Math.abs(phase - idx) < 0.001f) return;
        pageIdx = idx;
        musicOn = (idx == 1); // 立即同步目标页状态：触摸命中分支依赖它
        if (idx != 1) { coverR.setEmpty(); cardR.setEmpty(); } // 离开音乐页：可点区作废
        animPage(nearestPage(page, idx));
        invalidate();
    }
    int pageIndex() { return pageIdx; }
    void setLgHost(com.x3card.car3d.lg.LgHost h) { lgHost = h; }
    /** 把 car3d 已有的媒体快照桥接给琉光场景（不另开宿主会话） */
    private void pushLg() {
        if (lgHost == null) return;
        List<String> all = lyrics.isEmpty() ? liveLyrics : lyrics;
        String[] lines = all.toArray(new String[0]);
        long[] times = null;
        if (!lyrics.isEmpty() && lyrTimes.size() == lyrics.size()) {
            times = new long[lyrTimes.size()];
            for (int i = 0; i < times.length; i++) times[i] = lyrTimes.get(i);
        }
        lgHost.media(mHadMedia, playing, mTitle, mArtist, mTrackId, lines, times, lyrIdx, mDur, livePos(), coverBm);
    }
    // 同级页面：连续页索引 page（不封顶，循环）；page mod 3：0=车况页、1=音乐页、2=琉光页。
    // 渲染用相位 phase=wrapPhase(page)∈[0,3)；音乐层偏移 cardOffPx=(1-phase)*W，ph=0 在右、1 就位、2 在左；
    // 淡入 k=1-|ph-1| 天然让 ph=2 时音乐层完全淡出，第三页交给 Stage 委托的琉光 3D 场景。
    private float page = 0;
    private int pageIdx = 0;       // 当前页序号 0/1/2
    private com.x3card.car3d.lg.LgHost lgHost; // 第三页的媒体数据出口
    private float phase = 0;       // 派生：每帧由 page 折算到 [0,3)
    private float carPhase;        // 派生：= phase 折回 [-1,1]，0=车模页居中、±1=音乐页
    private float cardOffPx; // 派生：onDraw 里按 phase 算
    private ValueAnimator offAnim;
    private Runnable onPageOffset; // 页偏移变化（动画/拖拽）时通知外层把 GL 整层平移，做到两页真正同级交叉滑动
    void setOnPageOffset(Runnable r) { onPageOffset = r; }
    float pagePhase() { return phase; }       // 给 MainActivity 决定 GL 停画（phase≈1 停在音乐页）
    float pageOffset() { return phase; }      // 兼容旧调用（语义同 pagePhase）
    boolean dragging() { return dragging; }
    private static float wrapPhase(float p) { p = p % 3; if (p < 0) p += 3; return p; }
    private void syncPhase() {
        phase = wrapPhase(page);
        carPhase = (phase <= 1.5f) ? phase : phase - 3f;
        pageIdx = ((Math.round(phase) % 3) + 3) % 3;
        musicOn = (pageIdx == 1);
    }
    /** 取离 p 最近的、奇偶性为 parity(0=偶数车模页 / 1=奇数音乐页) 的整数页。
     *  注意：best 只能从满足奇偶性的候选里选，绝不允许返回与 parity 相反的点（旧实现以 Math.round(p) 起步，
     *  当 p 正好落在某整数上时 best 会卡在错误的奇偶性，导致「车模页↔音乐页」归位失效、页面卡在半途）。 */
    private static float nearestPage(float p, int idx) {
        int base = Math.round(p);
        float best = Float.NaN, bd = Float.POSITIVE_INFINITY;
        for (int c = base - 3; c <= base + 3; c++) {
            if (((c % 3) + 3) % 3 != idx) continue;   // 只接受目标页序号（三页循环）
            float d = Math.abs(c - p);
            if (Float.isNaN(best) || d < bd - 1e-6 || (Math.abs(d - bd) <= 1e-6 && Math.abs(c) < Math.abs(best))) { best = c; bd = d; }
        }
        if (Float.isNaN(best)) best = base;           // 理论不会到这（整数必有同奇偶候选）
        return best;
    }
    // 同级页面拖拽（手指跟手）：MOVE 时实时平移两页（不封顶，越过边界即循环），UP 时按偏移 + 甩动速度回弹到最近页
    private boolean dragging;   // 拖拽进行中
    private float dragBase;     // 拖拽起点的 page
    private float lastX;        // 上一帧手指 x（算速度）
    private long lastT;         // 上一帧时间
    private float dragVel;      // 手指水平速度 px/ms，正=向右（回上一页）/负=向左（去下一页）
    private void animPage(float to) {
        if (offAnim != null) offAnim.cancel();
        offAnim = ValueAnimator.ofFloat(page, to);
        offAnim.setDuration(280);
        offAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        offAnim.addUpdateListener(a -> {
            page = (Float) a.getAnimatedValue();
            syncPhase();
            if (onPageOffset != null) onPageOffset.run();
            invalidate();
        });
        offAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            // 无论正常结束还是被取消（如蓝牙通话触发 ACTION_CANCEL），都把 page 收尾为「最近的整数页」并重新折算相位，
            // 否则 page 会卡在分数值：音乐层停在半页（控件视觉偏移但命中矩形不偏移→点击落空）、musicOn 推导错乱→车身区被当成音乐页吞掉 GL 旋转
            @Override public void onAnimationEnd(android.animation.Animator animation) { page = Math.round(page); syncPhase(); }
            @Override public void onAnimationCancel(android.animation.Animator animation) { page = Math.round(page); syncPhase(); }
        });
        offAnim.start();
    }
    /** 媒体快照（宿主每次推来的播放状态，含当前句歌词）。照琉光 MediaState 的播放位置做法：
     *  宿主的 positionMs 只在开始 / 暂停 / 拖进度时可靠更新，平时（尤其每换一句歌词推一次）常常不带位置或带 0；
     *  所以位置用「锚点」记：收到真实、变化的位置才重锚定，之后按经过的墙钟时间往后推，不随换句清空。 */
    void setMedia(JSONObject m) {
        if (m == null) {
            if (!mHadMedia) { // 从没播过歌：直接空白
                mTitle = mArtist = mLyric = mCover = null; mPos = mDur = 0; mRecv = 0; mPosReported = -1; posSet = false; playing = false; mTrackId = ""; mPkg = ""; liveLyrics.clear(); lyrics.clear(); lyrTimes.clear(); lyrIdx = -1; lyrLine = ""; lyrStale = ""; stopAdvance(); coverBm = null; invalidate(); return;
            }
            // 播过歌但此刻媒体为空：常是接蓝牙电话时宿主把媒体会话挂起（status 不再是 ok）。保留上一首的歌名/封面/进度，只冻结播放态，不空白
            playing = false; mTrackId = ""; mPosReported = -1; lyrStale = ""; invalidate(); return;
        }
        mHadMedia = true;
        String prevLyric = mLyric; // 换歌前那句（下面 trackChanged 时记为「上一首残留的那句」）
        mTitle = m.optString("title", ""); mArtist = m.optString("artist", "");
        mLyric = lyricText(m.optString("lyric", "")); mCover = m.optString("coverUrl", "");
        mPkg = m.optString("packageName", ""); // 放歌的 App：点封面要靠它打开
        if ("null".equals(mPkg)) mPkg = "";   // 宿主给的是 JSONObject.NULL 时 optString 会返回 "null"
        long newDur = m.optLong("durationMs");
        boolean hasPos = m.has("positionMs") && !m.isNull("positionMs");   // 这条快照到底带没带位置（不带位置=纯换句推送）
        long newPos = hasPos ? m.optLong("positionMs") : -1;
        boolean newPlaying = m.optBoolean("playing");
        boolean wasPlaying = playing; // 上一帧的播放态（playing 字段在本方法末尾才被新值覆盖）
        String newTrack = m.optString("trackId", "");
        if ("null".equals(newTrack)) newTrack = "";
        boolean trackChanged = !newTrack.equals(mTrackId);
        long now = System.currentTimeMillis();
        if (trackChanged) {
            // 换歌：以宿主给的位置重新锚定（没给位置就从 0 起算）
            mPos = hasPos ? newPos : 0;
            mRecv = now;
            posSet = true;
        } else if (hasPos) {
            boolean posChanged = newPos != mPosReported; // 宿主这次上报的位置和上次不一样
            if (wasPlaying && !newPlaying && posSet) {
                // 暂停：把锚点位置对齐到「当前实时播放位置」，冻结的是准确位置（照琉光 anchor(pos())，不再卡在最后一次上报的位置）
                mPos = livePos(); mRecv = now;
            } else if (posChanged && newPos != 0) {
                // 位置真变了（开始 / 拖进度 / 正常推进）：重锚定。播放中途的 0 视为宿主没给有效数字，忽略（不是真跳回开头）
                mPos = newPos; mRecv = now;
            } else if (newPlaying && !wasPlaying) {
                // 暂停→播放：锚点位置不变，只把锚点时间对齐到现在，从冻结处继续走
                mRecv = now;
            }
            // 否则（纯换句、positionMs 没变）：完全不动锚点，由墙钟继续往后推——进度不再被换句清零
        } else {
            // 这条快照没有位置（常是「只推了当前句歌词」）：不动位置锚点；恢复播放时把锚点时间对齐到现在
            if (newPlaying && !wasPlaying && posSet) mRecv = now;
        }
        mPosReported = hasPos ? newPos : mPosReported;  // 记住宿主上报的位置，下次比对
        mDur = newDur;
        mTrackId = newTrack;
        if (trackChanged) { // 换歌：上一首的整首歌词、累积句、当前句索引全部作废（整首歌词随后由 CarUi 重新拉）
            liveTrack = mTrackId;
            lyrics.clear(); lyrTimes.clear(); lyrIdx = -1; lyrLine = ""; liveLyrics.clear(); stopAdvance();
            lyrStale = prevLyric == null ? "" : prevLyric; // 换歌后宿主头几条快照常常还是上一首最后那句
        }
        if (!lyrStale.isEmpty() && !mLyric.equals(lyrStale)) lyrStale = ""; // 歌词变了：上一首那句过去了
        if (!mLyric.isEmpty() && !mLyric.equals(lyrStale)) {
            if (!mLyric.equals(lyrLine)) setLine(mLyric); // 换句了：按「从上一句往后找」重新定位（重复句按顺序递进，不再跳回第一次出现的位置）
            else chainRepeat();                            // 推来的还是同一句：可能是连着两句一模一样，按时间自己往下走
        }
        playing = newPlaying;
        invalidate();
    }
    /** 当前曲目的 trackId（拉整首歌词时做迟到校验用） */
    String trackId() { return mTrackId; }
    void setLyrics(List<CarData.LyLine> l) {
        lyrics = new ArrayList<>();
        lyrTimes = new ArrayList<>();
        if (l != null) for (CarData.LyLine ln : l) {
            if (ln == null || ln.t == null) continue;
            if (ln.t.contains("\n")) { // 有的宿主一行给整首：拆开，时间算在第一行上
                boolean first = true;
                for (String p : ln.t.split("\n", -1)) {
                    String t = lyricText(p);
                    if (t.isEmpty()) continue;
                    lyrics.add(t); lyrTimes.add(first ? ln.at : -1L); first = false;
                }
            } else {
                String t = lyricText(ln.t);
                if (t.isEmpty()) continue;
                lyrics.add(t); lyrTimes.add(ln.at);
            }
        }
        if (!lyrics.isEmpty()) {
            liveLyrics.clear();                                 // 有整首歌词就不需要快照累积了
            lyrIdx = -1;                                        // 索引按新列表重来
            String cur = !lyrLine.isEmpty() ? lyrLine : mLyric;  // 用当前这句在新列表里重新定位（琉光 loadLyrics 回调同款）
            if (cur != null && !cur.isEmpty()) setLine(cur);
            if (lyrIdx < 0) lyrIdx = idxByPos();                 // 定位不到：按播放位置兜底
        }
        invalidate();
    }
    /** 宿主歌词行可能是 {"text":"...","timeMs":...} 的 JSON 串，解析出纯文本（兼容 text/content/words/lyric 几个键） */
    /** 歌词文本归一（照琉光 clean）：折叠连续空白再 trim —— 快照里的句子和整首里的同一句必须长得一样才对得上 */
    private static String clean(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").trim(); }
    private static String lyricText(String s) {
        if (s == null) return "";
        s = clean(s);
        if (s.startsWith("{") && s.endsWith("}")) {
            try {
                JSONObject o = new JSONObject(s);
                String t = o.optString("text", "");
                if (t.isEmpty()) t = o.optString("content", "");
                if (t.isEmpty()) t = o.optString("words", "");
                if (t.isEmpty()) t = o.optString("lyric", s);
                return clean(t);
            } catch (Exception ignored) { }
        }
        return s;
    }
    /** 当前这句在整首里是第几句：从上一句往后找（副歌重复的按顺序对上），找不到再从头找（拖了进度、单曲循环）——照琉光 locate */
    private int locate(String t) {
        if (t == null || t.isEmpty()) return -1;
        int from = lyrIdx;
        for (int i = from + 1; i < lyrics.size(); i++) if (t.equals(lyrics.get(i))) return i;
        for (int i = 0; i <= from && i < lyrics.size(); i++) if (t.equals(lyrics.get(i))) return i;
        return -1;
    }
    /** 定位当前句（照琉光 setLine）：有整首就找它在整首里的位置；没有整首就把听到的句子接在后面 */
    private void setLine(String t) {
        lyrLine = t;
        stopAdvance();
        if (!lyrics.isEmpty()) {
            int i = locate(t);
            if (i < 0) return;                    // 这句不在整首里（不是这首的 / 上一首残留）：不动索引
            lyrIdx = i;
            chainRepeat();
        } else {                                  // 没有整首：宿主只推当前句时，听到的新句子接在后面
            if (liveLyrics.isEmpty() || !t.equals(liveLyrics.get(liveLyrics.size() - 1))) {
                liveLyrics.add(t);
                if (liveLyrics.size() > 200) liveLyrics.remove(0);
            }
            lyrIdx = liveLyrics.size() - 1;
        }
    }
    /** 连着两句一模一样：宿主推来的歌词没变化、认不出换了句，到下一句的时间自己往下走一句（照琉光 chainRepeat） */
    private void chainRepeat() {
        stopAdvance();
        if (lyrics.isEmpty() || lyrIdx < 0 || !playing) return;
        final int i = lyrIdx;
        if (i + 1 >= lyrics.size()) return;
        if (!lyrics.get(i + 1).equals(lyrics.get(i))) return;  // 下一句不一样：等宿主推下一句
        long at = i + 1 < lyrTimes.size() ? lyrTimes.get(i + 1) : -1L;
        if (at < 0) return;                                     // 没有每句时间就没法算什么时候往下走
        long wait = Math.max(0, at - livePos());
        lyrAdvance = () -> {
            lyrAdvance = null;
            if (lyrIdx != i || !playing) return;
            lyrIdx = i + 1;
            chainRepeat();                                      // 可能连着三句都一样，继续排
            invalidate();
        };
        clockH.postDelayed(lyrAdvance, wait);
    }
    private void stopAdvance() { if (lyrAdvance != null) { clockH.removeCallbacks(lyrAdvance); lyrAdvance = null; } }
    /** 兜底：宿主还没给当前句 / 这句不在整首里时，按播放位置找「唱到第几句了」 */
    private int idxByPos() {
        long p = livePos();
        if (p <= 0 || lyrics.isEmpty()) return 0;
        int k = 0;
        for (int i = 0; i < lyrics.size() && i < lyrTimes.size(); i++) {
            long at = lyrTimes.get(i);
            if (at < 0) continue;
            if (at <= p) k = i; else break;
        }
        return k;
    }
    private long livePos() { if (!posSet) return 0; if (playing && mDur > 0) return Math.min(mPos + (System.currentTimeMillis() - mRecv), mDur); return mPos; }
    private static String fmtTime(long ms) {
        long s = Math.max(0, ms) / 1000, m = s / 60, ss = s % 60;
        return m + ":" + (ss < 10 ? "0" : "") + ss;
    }
    private String ellipsize(String s, float maxW) {
        if (s == null) return "";
        if (p.measureText(s) <= maxW) return s;
        String e = s;
        while (e.length() > 1 && p.measureText(e + "…") > maxW) e = e.substring(0, e.length() - 1);
        return e + "…";
    }
    /** 歌名按可用列宽自动换行：英文按词断、中文逐字断；最多 maxLines 行，超出则末行加省略号 */
    private List<String> wrapTitle(String s, float maxW) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        p.setTypeface(ui700); // 与歌名字号保持一致（调用前应已设 textSize=tf）
        String[] toks = s.split(" ");
        StringBuilder line = new StringBuilder();
        for (String tok : toks) {
            String add = tok;
            while (p.measureText(add) > maxW && add.length() > 1) { // 单个长词（如长串无空格）逐字断
                int i = add.length();
                while (i > 1 && p.measureText(add.substring(0, i)) > maxW) i--;
                String head = add.substring(0, i);
                if (line.length() > 0) { out.add(line.toString()); line.setLength(0); }
                out.add(head);
                add = add.substring(i);
            }
            String tryLine = line.length() > 0 ? line + " " + add : add;
            if (p.measureText(tryLine) <= maxW) { line.setLength(0); line.append(tryLine); }
            else { if (line.length() > 0) out.add(line.toString()); line.setLength(0); line.append(add); }
        }
        if (line.length() > 0) out.add(line.toString());
        final int maxLines = 2;
        if (out.size() > maxLines) {
            List<String> capped = new ArrayList<>(out.subList(0, maxLines - 1));
            capped.add(ellipsize(out.get(maxLines - 1), maxW));
            return capped;
        }
        return out;
    }
    private void drawMusicContent(Canvas c) {
        // 卡片只占车模显示区：顶栏/挡位/车况图标/车速、下面那行字、底部数据都照常显示、不被盖住
        // （宽屏不做左右车况列的内缩：车况列在上方，播控/歌词垂直居中不会碰到；左右各占一半、各自水平居中）
        float down = r(0.3f); // 音乐卡片整体下移一点：顶边往下推，底边仍贴状态栏（有 r(2.4f) 兜底防极矮屏）
        float cardTop = (portrait ? Math.max(tellBottom, speedBottom) + r(0.12f) : r(1.6f)) + down;
        // statTop 在平移之外（底部数据）计算，转进当前平移坐标系再取较小边
        float cardBottom = Math.min(statusTop, statTop - topShift) - r(0.08f);
        float cardLeft = portrait ? r(0.3f) : r(0.4f);
        float cardRight = portrait ? W - r(0.3f) : W - r(0.4f);
        if (cardBottom - cardTop < r(2.4f)) cardTop = cardBottom - r(2.4f); // 极矮屏兜底：保证控制器放得下
        cardR.set(cardLeft, cardTop, cardRight, cardBottom);
        // 整页背景已在 drawMusicBackground 铺满（与车模卡片背景 bg 分开设置）；组件本身无背景。
        // 车况页和音乐页统一：文字色都用全局主题色（ink/mute），不随封面背景明暗切换
        p.setTextAlign(Paint.Align.LEFT);
            if (mTitle == null || mTitle.isEmpty()) {
                p.setTypeface(ui600); p.setTextSize(r(0.26f)); p.setColor(mute); p.setTextAlign(Paint.Align.CENTER);
                c.drawText("暂无音乐", (cardLeft + cardRight) / 2f, (cardTop + cardBottom) / 2f, p);
                p.setTextAlign(Paint.Align.LEFT);
                return;
            }
            float pad = r(0.25f);
            // 歌词舞台：歌词是主视觉，给它更大的区域；左侧封面/播控退为辅助。背景仍由 drawMusicBackground 按原逻辑绘制，不改。
            float lyCap = portrait ? r(3.8f) : r(6.0f);
            if (portrait) {
                float mid = cardTop + (cardBottom - cardTop) * 0.58f; // 竖屏：歌词占上半区约六成
                float ah = mid - cardTop - pad * 2, lyH = Math.min(ah, lyCap);
                drawLyrics(c, cardLeft + pad, cardTop + pad + (ah - lyH) / 2f, cardRight - cardLeft - pad * 2, lyH);
                drawController(c, cardLeft + pad, mid + pad, cardRight - cardLeft - pad * 2, cardBottom - mid - pad * 2, false);
            } else {
                float split = cardLeft + (cardRight - cardLeft) * 0.40f; // 宽屏：歌词占右侧六成，播控占左侧四成
                drawController(c, cardLeft + pad, cardTop + pad, split - cardLeft - pad * 1.5f, cardBottom - cardTop - pad * 2, true);
                float ah = cardBottom - cardTop - pad * 2, lyH = Math.min(ah, lyCap);
                drawLyrics(c, split + pad * 0.5f, cardTop + pad + (ah - lyH) / 2f, cardRight - split - pad * 1.5f, lyH);
        }
    }
    private void drawController(Canvas c, float x, float y, float w, float h, boolean center) {
        if (!center) { drawControllerPortrait(c, x, y, w, h); return; }
        // ===== 宽屏：歌词舞台——左侧封面/曲名/播控退为辅助，不画任何面板，原有整页背景直接透出 =====
        float br = Math.min(r(0.27f), h * 0.13f);
        float compW = w * 3f / 4f;                 // 组件横向占左半屏的四分之三
        final int acc = coverAccent();              // 封面衍生强调色（驱动播放键/进度/歌词）
        float gcx = x + (w - compW) / 2f;          // 组件在左半屏内水平居中
        // 窄窗口（七分屏）补偿：rem = H/10.52 只跟高度走，窗口变窄时 compW 缩水但字号不变。
        // 用组件区自身宽高比推出 narrow（全屏≈0、七分屏≈1），只补窄窗口，不写死屏幕分辨率。
        float narrow = Math.max(0f, Math.min(1f, (1.5f - (h > 0 ? w / h : 1.5f)) / 0.4f));
        float cov = Math.min(Math.min(h * 0.34f, compW * (0.24f + 0.10f * narrow)), r(1.45f + 0.35f * narrow));
        float barGap = r(0.20f), barH = r(0.035f), timeGap = r(0.42f), btnGap = r(0.22f);
        // 整组垂直居中；区域不够高时按比例缩小封面与按钮（矮屏不再顶到上沿，保证真正居中）
        float fixedH = barGap + timeGap + btnGap; // 进度条厚在间隙内，不计入视觉组高
        float flexH = cov + br * 2.5f;            // 2.5=中间键(br*1.25)直径
        if (fixedH + flexH > h && flexH > 0) {
            float k = Math.max(0.55f, (h - fixedH) / flexH);
            cov *= k; br *= k;
        }
        float groupH = cov + fixedH + br * 2.5f;
        float top = y + Math.max(0, h - groupH) / 2f; // 宽屏：整组在左半屏垂直居中（图1）
        float tf = 0.30f + 0.06f * narrow, af = 0.23f + 0.03f * narrow;
        float textW = Math.max(r(0.5f), compW - cov - r(0.18f));
        float gx = gcx, tx = gx + cov + r(0.18f);
        // 歌词舞台不加面板：背景颜色完全沿用 drawMusicBackground 的原有逻辑
        drawCover(c, gx, top, cov);
        p.setTypeface(ui700); p.setTextSize(r(tf)); p.setColor(ink);
        List<String> titleLines = wrapTitle(mTitle, textW);
        // 歌名：多行时行高顶部与封面顶部齐平，单行时在封面高度内垂直居中；歌手位置不变：贴封面底部
        float th = titleLines.size() * r(tf);
        float artistTop = top + cov - r(af) - r(0.02f); // 歌手行大致顶沿：歌名块不与它相碰
        float baseTop = (titleLines.size() > 1) ? top : top + Math.max(0f, (cov - th) / 2f); // 多行齐平封面顶、单行居中
        float blockTop = Math.min(baseTop, artistTop - th); // 别压到歌手
        blockTop = Math.max(top, blockTop);
        for (int i = 0; i < titleLines.size(); i++) c.drawText(titleLines.get(i), tx, baseline(blockTop + r(tf) * i + r(tf) / 2f, p), p);
        p.setTypeface(ui500); p.setTextSize(r(af)); p.setColor(mute);
        c.drawText(ellipsize(mArtist, textW), tx, top + cov - p.descent() - r(0.02f), p);
        float progY = top + cov + barGap, pw = compW, px = gcx;
        float prog = mDur > 0 ? (float) livePos() / mDur : 0;
        sp.setStyle(Paint.Style.FILL); sp.setColor(line);
        c.drawRoundRect(px, progY, px + pw, progY + barH, r(0.03f), r(0.03f), sp);
        sp.setColor(acc);
        if (prog > 0) c.drawRoundRect(px, progY, px + pw * prog, progY + barH, r(0.03f), r(0.03f), sp);
        // 发光播放头：外晕 + 实色 + 白芯（仅在有进度时）
        if (prog > 0 && prog < 1) {
            float hx = px + pw * prog, hy = progY + barH / 2f;
            sp.setStyle(Paint.Style.FILL);
            sp.setColor(withA(acc, 0.30f)); c.drawCircle(hx, hy, barH * 1.9f, sp);
            sp.setColor(withA(acc, 0.95f)); c.drawCircle(hx, hy, barH * 0.95f, sp);
            sp.setColor(0xffffffff); c.drawCircle(hx, hy, barH * 0.42f, sp);
        }
        // 时间：当前 / 总时长（图1/图2 均为右端合并显示）
        p.setTypeface(ui500); p.setTextSize(r(0.30f)); p.setColor(mute);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(fmtTime(livePos()) + " / " + fmtTime(mDur), px + pw, progY + timeGap, p);
        p.setTextAlign(Paint.Align.LEFT);
        float brP = br * 1.25f; // 中间 播放/暂停 键比两侧上一首/下一首大一圈
        float by = progY + timeGap + btnGap + brP, gap = Math.min(br * 3.2f, compW / 2f - br), bcx = gcx + compW / 2f; // 间距比两侧按钮加宽些（不超出组件宽）
        prevR.set(bcx - gap - br, by - br, bcx - gap + br, by + br);
        playR.set(bcx - brP, by - brP, bcx + brP, by + brP);
        nextR.set(bcx + gap - br, by - br, bcx + gap + br, by + br);
        float sw = Math.max(1.5f, dens * 1.5f);
        int playIcon = 0xffffffff; // 播放键改为强调色渐变，图标用白保证对比
        // 上一曲/下一曲：仅描边圆圈（中间无背景），描边与图标用强调色
        sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(sw); sp.setColor(withA(acc, 0.6f));
        c.drawCircle(prevR.centerX(), by, br - sw / 2, sp);
        c.drawCircle(nextR.centerX(), by, br - sw / 2, sp);
        // 暂停/播放：强调色渐变圆（顶亮底深），图标用白色
        int[] ac = new int[]{ (acc >> 16) & 255, (acc >> 8) & 255, acc & 255 };
        int acc2 = rgb2i(shade(ac, 0.78f));
        LinearGradient pg = new LinearGradient(playR.left, playR.top, playR.left, playR.bottom, acc, acc2, Shader.TileMode.CLAMP);
        sp.setStyle(Paint.Style.FILL); sp.setShader(pg); c.drawCircle(playR.centerX(), by, brP, sp); sp.setShader(null);
        drawPrevIcon(c, prevR.centerX(), by, br * 0.5f, acc);
        drawPlayPauseIcon(c, playR.centerX(), by, brP * 0.5f, playIcon);
        drawNextIcon(c, nextR.centerX(), by, br * 0.5f, acc);
    }
    /** 窄屏（图2）：横向播控条——左封面、中歌名/歌手、右播控按钮（同一行），下方整行进度条+时间（右端合并显示） */
    private void drawControllerPortrait(Canvas c, float x, float y, float w, float h) {
        float br = Math.min(r(0.23f), h * 0.17f);
        final int acc = coverAccent();              // 封面衍生强调色
        float cov = Math.min(Math.min(w * 0.22f, h * 0.66f), r(1.5f));   // 封面尺寸
        float gap = br * 2.6f, btnW = gap * 2f + br * 2f;       // 右侧按钮组总宽（gap 留出中间大键空间）
        float textLeft = x + cov + r(0.2f);
        float textRight = x + w - btnW - r(0.12f);
        float textW = Math.max(r(0.4f), textRight - textLeft);
        float tf = 0.30f, af = 0.23f;
        float groupH = cov + r(0.18f) + r(0.05f) + r(0.34f);    // 封面行 + 间距 + 进度条 + 时间
        float top = y + Math.max(0, h - groupH) / 2f;           // 整条在控制器区域内垂直居中（图2 中部偏下）
        float cy = top + cov / 2f;                             // 封面/文字/按钮的水平中线
        // 歌词舞台不加面板：背景颜色完全沿用 drawMusicBackground 的原有逻辑
        drawCover(c, x, top, cov);
        p.setTypeface(ui700); p.setTextSize(r(tf)); p.setColor(ink);
        List<String> titleLines = wrapTitle(mTitle, textW);
        // 歌名：多行时行高顶部与封面顶部齐平，单行时在封面高度内垂直居中；歌手位置不变：贴封面底部
        float th = titleLines.size() * r(tf);
        float artistTop = top + cov - r(af) - r(0.02f); // 歌手行大致顶沿：歌名块不与它相碰
        float baseTop = (titleLines.size() > 1) ? top : top + Math.max(0f, (cov - th) / 2f); // 多行齐平封面顶、单行居中
        float blockTop = Math.min(baseTop, artistTop - th); // 别压到歌手
        blockTop = Math.max(top, blockTop);
        for (int i = 0; i < titleLines.size(); i++) c.drawText(titleLines.get(i), textLeft, baseline(blockTop + r(tf) * i + r(tf) / 2f, p), p);
        p.setTypeface(ui500); p.setTextSize(r(af)); p.setColor(mute);
        c.drawText(ellipsize(mArtist, textW), textLeft, top + cov - p.descent() - r(0.02f), p);
        float brP = br * 1.25f; // 中间 播放/暂停 键比两侧上一首/下一首大一圈
        float bcx = x + w - gap - br;
        prevR.set(bcx - gap - br, cy - br, bcx - gap + br, cy + br);
        playR.set(bcx - brP, cy - brP, bcx + brP, cy + brP);
        nextR.set(bcx + gap - br, cy - br, bcx + gap + br, cy + br);
        float sw = Math.max(1.5f, dens * 1.5f);
        int playIcon = 0xffffffff; // 播放键改为强调色渐变，图标用白保证对比
        // 上一曲/下一曲：仅描边圆圈（中间无背景），描边与图标用强调色
        sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(sw); sp.setColor(withA(acc, 0.6f));
        c.drawCircle(prevR.centerX(), cy, br - sw / 2, sp);
        c.drawCircle(nextR.centerX(), cy, br - sw / 2, sp);
        // 暂停/播放：强调色渐变圆（顶亮底深），图标用白色
        int[] ac = new int[]{ (acc >> 16) & 255, (acc >> 8) & 255, acc & 255 };
        int acc2 = rgb2i(shade(ac, 0.78f));
        LinearGradient pg = new LinearGradient(playR.left, playR.top, playR.left, playR.bottom, acc, acc2, Shader.TileMode.CLAMP);
        sp.setStyle(Paint.Style.FILL); sp.setShader(pg); c.drawCircle(playR.centerX(), cy, brP, sp); sp.setShader(null);
        drawPrevIcon(c, prevR.centerX(), cy, br * 0.5f, acc);
        drawPlayPauseIcon(c, playR.centerX(), cy, brP * 0.5f, playIcon);
        drawNextIcon(c, nextR.centerX(), cy, br * 0.5f, acc);
        float barY = top + cov + r(0.18f), barH = r(0.035f), pw = w, px = x;
        float prog = mDur > 0 ? (float) livePos() / mDur : 0;
        sp.setStyle(Paint.Style.FILL); sp.setColor(line);
        c.drawRoundRect(px, barY, px + pw, barY + barH, r(0.03f), r(0.03f), sp);
        sp.setColor(acc);
        if (prog > 0) c.drawRoundRect(px, barY, px + pw * prog, barY + barH, r(0.03f), r(0.03f), sp);
        // 发光播放头：外晕 + 实色 + 白芯（仅在有进度时）
        if (prog > 0 && prog < 1) {
            float hx = px + pw * prog, hy = barY + barH / 2f;
            sp.setStyle(Paint.Style.FILL);
            sp.setColor(withA(acc, 0.30f)); c.drawCircle(hx, hy, barH * 1.9f, sp);
            sp.setColor(withA(acc, 0.95f)); c.drawCircle(hx, hy, barH * 0.95f, sp);
            sp.setColor(0xffffffff); c.drawCircle(hx, hy, barH * 0.42f, sp);
        }
        p.setTypeface(ui500); p.setTextSize(r(0.26f)); p.setColor(mute);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(fmtTime(livePos()) + " / " + fmtTime(mDur), px + pw, barY + r(0.30f), p);
        p.setTextAlign(Paint.Align.LEFT);
    }
    /** 歌词舞台：不再绘制任何半透明面板，音乐页背景完全由 drawMusicBackground 按原有逻辑铺满 */
    /** 歌词字号 / 行高：竖屏 / 宽屏各自的绝对字号（数值即百分之一 rem，同网页版 lyricSize，14~52），行高 = 字号 × 1.42 + 少量行距（同网页版 CSS line-height，不再单独设行间距） */
    private float[] lyricMetrics() {
        int size = portrait ? 23 : 27;
        try { size = Integer.parseInt(S.str(portrait ? "lyricSizeP" : "lyricSizeL").trim()); } catch (Exception ignored) { }
        if (size < 14 || size > 52) size = portrait ? 23 : 27;
        float fontPx = r(size / 100f);
        float lh = fontPx * 1.42f + r(0.12f);
        return new float[] { fontPx, lh };
    }
    private void drawLyrics(Canvas c, float x, float y, float w, float h) {
        List<String> all = lyrics.isEmpty() ? liveLyrics : lyrics; // 整首优先；没有就用快照逐句累积的行
        if (!all.isEmpty()) {
            int cur = lyrIdx;                                   // 用持久的当前句索引：不再每帧从 0 重扫（重复句会跳回第一次出现的位置）
            if (cur < 0 || cur >= all.size()) cur = idxByPos(); // 还没定位到：按播放位置兜底，不再退回整首中间
            drawLyricLines(c, x, y, w, h, all, cur);
            return;
        }
        float[] m = lyricMetrics();
        float fontPx = m[0], lh = m[1];
        if (mLyric != null && !mLyric.isEmpty()) {
            // 宿主没给整首歌词时：快照里的歌词若含多行（有的宿主一行返回整首），按行显示；单行显示不开也自动折行，不省略
            List<String> ls = splitLines(mLyric);
            if (ls.size() > 1) { drawLyricLines(c, x, y, w, h, ls, -1); return; }
            p.setTypeface(ui700); p.setTextSize(fontPx); p.setColor(ink); p.setTextAlign(Paint.Align.CENTER);
            List<String> wl = wrapText(mLyric, w);
            int show = Math.min(wl.size(), 3);
            float top = y + h / 2f - (show - 1) * lh / 2f;
            for (int i = 0; i < show; i++) c.drawText(wl.get(i), x + w / 2, baseline(top + i * lh, p), p);
            p.setTextAlign(Paint.Align.LEFT);
            return;
        }
        p.setTypeface(ui500); p.setTextSize(fontPx * 0.8f); p.setColor(mute); p.setTextAlign(Paint.Align.CENTER);
        c.drawText("暂无歌词", x + w / 2, y + h / 2, p); p.setTextAlign(Paint.Align.LEFT);
    }
    private static List<String> splitLines(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split("\n", -1)) { String t = p.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }
    /** 歌词舞台：固定视口 + 行槽模型（同网页版 .mp-ly：窗口高度 = 行高 × N、overflow hidden）。
     *  行数由「窗口裁出几个行槽」决定，与循环画几次无关，所以设置几行就恒显示几行；
     *  整列平移把当前句送进第 above 个槽，开头贴顶、结尾贴底，窗口始终填满。
     *  当前句放大并逐字扫字高亮（已唱强调色 / 未唱主文字色），其余句缩小弱化；无时间轴则整句强调色。 */
    private void drawLyricLines(Canvas c, float x, float y, float w, float h, List<String> list, int cur) {
        if (list.isEmpty()) return;
        int rows = 5;
        try { rows = Integer.parseInt(S.str(portrait ? "lyricLinesP" : "lyricLinesL").trim()); } catch (Exception ignored) { }
        rows = Math.max(1, Math.min(8, rows)); // 档位 1～8

        // 行槽：槽高取自然行高，但不超过 h/rows；窗口高度 = 槽高 × rows，在可用区内居中
        float[] m = lyricMetrics();
        float fontPx = m[0];
        float slot = Math.min(m[1], h / rows);
        float minLh = fontPx * 1.2f;
        if (slot < minLh) fontPx *= slot / minLh; // 区域太矮就等比缩字号，保证不叠字
        float curFont = Math.min(fontPx * 1.18f, slot * 0.90f);
        float subFont = fontPx * 0.86f;
        float viewH = slot * rows;
        float viewY = y + (h - viewH) / 2f;

        // 每句按「当前句最大字号」折行，保证放大后也不溢出；每个物理行占一个槽
        List<String> phys = new ArrayList<>();
        List<Integer> owner = new ArrayList<>();
        int[] firstPhys = new int[list.size()];
        p.setTypeface(ui700); p.setTextSize(curFont);
        for (int i = 0; i < list.size(); i++) {
            firstPhys[i] = phys.size();
            String ln = list.get(i);
            List<String> wl = wrapText(ln == null || ln.isEmpty() ? "♪" : ln, w);
            for (String s : wl) { phys.add(s); owner.add(i); }
        }
        if (phys.isEmpty()) return;
        int curPhys = (cur >= 0 && cur < list.size()) ? firstPhys[cur] : phys.size() / 2;

        // 整列平移：当前句落在第 above 个槽；钳制保证开头贴顶、结尾贴底，窗口恒有 rows 行
        int above = (rows - 1) / 2;
        float shift = (above - curPhys) * slot;
        shift = Math.max(Math.min(0f, viewH - phys.size() * slot), Math.min(0f, shift));

        // 当前句已唱比例：整首歌词带时间戳时按「本句起点 → 下一句起点」线性推进；拿不到就整句强调色
        float sung = -1f;
        if (!lyrics.isEmpty() && cur >= 0 && cur < lyrTimes.size()) {
            long st = lyrTimes.get(cur), en = -1;
            for (int j = cur + 1; j < lyrTimes.size(); j++) {
                long t = lyrTimes.get(j);
                if (t >= 0) { en = t; break; }
            }
            if (st >= 0 && en > st) {
                sung = (float) (livePos() - st) / (float) (en - st);
                if (sung < 0f) sung = 0f; else if (sung > 1f) sung = 1f;
            }
        }

        int clip = c.save();
        c.clipRect(x, viewY, x + w, viewY + viewH); // ★ 窗口高度 = 槽高 × N：裁出恰好 N 行
        p.setTextAlign(Paint.Align.CENTER);
        int accL = coverAccent(); // 封面衍生强调色
        for (int i = 0; i < phys.size(); i++) {
            float cy = viewY + (i + 0.5f) * slot + shift;
            if (cy < viewY - slot || cy > viewY + viewH + slot) continue; // 视口外跳过
            String txt = phys.get(i);
            if (owner.get(i) == cur) {
                p.setTypeface(ui700); p.setTextSize(curFont);
                float by = baseline(cy, p);
                if (sung >= 0f && txt.length() > 1) {
                    // 逐字扫字：折行后的每个物理行按同一比例切分
                    int cut = Math.max(1, Math.min(txt.length() - 1, Math.round(txt.length() * sung)));
                    String done = txt.substring(0, cut), rest = txt.substring(cut);
                    p.setTextAlign(Paint.Align.LEFT);
                    float sx = x + w / 2f - p.measureText(txt) / 2f;
                    p.setColor(accL); c.drawText(done, sx, by, p);
                    p.setColor(ink); c.drawText(rest, sx + p.measureText(done), by, p);
                    p.setTextAlign(Paint.Align.CENTER);
                } else {
                    p.setColor(accL);
                    c.drawText(txt, x + w / 2, by, p);
                }
            } else {
                p.setTypeface(ui500); p.setTextSize(subFont);
                p.setColor(withA(mute, 0.52f));
                c.drawText(txt, x + w / 2, baseline(cy, p), p);
            }
        }
        c.restore();
        p.setTextAlign(Paint.Align.LEFT);
    }
    /** 封面位图（CarUi 后台加载好给过来）；没有时画占位音符。同时预生成「缩糊位图」与「最亮/最暗两极色」，供跟随封面背景用 */
    void setCover(android.graphics.Bitmap b) {
        if (coverBlurBm != null && coverBlurBm != b) coverBlurBm.recycle();
        coverBm = b; coverDomRgb = null; coverBlurBm = null; coverLight = coverDark = null;
        if (b != null && !b.isRecycled()) {
            try {
                int N = 48;
                Bitmap small = Bitmap.createScaledBitmap(b, N, N, true);
                coverBlurBm = small;
                int[] px = new int[N * N];
                small.getPixels(px, 0, N, 0, 0, N, N);
                int lr = 0, lg = 0, lb = 0, ln = 0, dr = 0, dg = 0, db = 0, dn = 0;
                for (int col : px) {
                    if ((col >>> 24) < 8) continue;
                    int rr = (col >> 16) & 255, gg = (col >> 8) & 255, bl = col & 255;
                    float rrf = rr / 255f, ggf = gg / 255f, bbf = bl / 255f;
                    float mx = Math.max(rrf, Math.max(ggf, bbf)), mn = Math.min(rrf, Math.min(ggf, bbf)), dl = mx - mn;
                    float sat = dl == 0 ? 0 : (mx > 0.5f ? dl / (2 - mx - mn) : dl / (mx + mn));
                    float lum = (mx + mn) / 2f;
                    if (sat < 0.12f) continue;
                    if (lum > 0.45f) { lr += rr; lg += gg; lb += bl; ln++; }
                    else if (lum < 0.4f) { dr += rr; dg += gg; db += bl; dn++; }
                }
                if (ln > 0) coverLight = new int[]{ lr / ln, lg / ln, lb / ln };
                if (dn > 0) coverDark = new int[]{ dr / dn, dg / dn, db / dn };
            } catch (Exception ignored) { }
        }
        invalidate();
    }

    // ================= 音源 App：认包名（照网页版 home.js 的 APPS 表） =================
    /** 认出来的音源 App：key、名字、底色（角标用） */
    static final class SrcApp {
        final String key, name; final int color;
        SrcApp(String key, String name, int color) { this.key = key; this.name = name; this.color = color; }
    }
    /** 认包名：按包名里含的关键词判断（各家包名在车机上写法五花八门，关键词比写死完整包名稳）。认不出来返回 null */
    static SrcApp srcApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        String pl = pkg.toLowerCase();
        if (pl.contains("qqmusic")) return new SrcApp("qq", "QQ音乐", 0xff31c27c);
        if (pl.contains("netease") || pl.contains("cloudmusic")) return new SrcApp("wy", "网易云音乐", 0xffc62f2f);
        if (pl.contains("kuwo")) return new SrcApp("kw", "酷我音乐", 0xffff7a1a);
        if (pl.contains("kugou")) return new SrcApp("kg", "酷狗音乐", 0xff0a9be8);
        return null;
    }
    /** 当前放歌的 App 包名（宿主媒体快照给的） */
    private String mPkg = "";
    String mediaPkg() { return mPkg; }
    /** 音源 App 名（认出来给名字，认不出来给通用名——绝不显示包名，避免点封面弹「正在打开 com.xxx」） */
    static String srcName(String pkg) {
        SrcApp a = srcApp(pkg);
        if (a != null) return a.name;
        return pkg == null || pkg.isEmpty() ? "" : "音乐应用";
    }
    private void drawCover(Canvas c, float x, float y, float sz) {
        RectF cr = new RectF(x, y, x + sz, y + sz);
        coverR.set(cr); // 封面可点：点它打开放歌的 App（和网页版一致）
        if (coverBm != null && !coverBm.isRecycled()) {
            Path clip = new Path(); clip.addRoundRect(cr, r(0.2f), r(0.2f), Path.Direction.CW);
            int save = c.save();
            c.clipPath(clip);
            float bw = coverBm.getWidth(), bh = coverBm.getHeight(), s = Math.max(sz / bw, sz / bh); // 居中裁剪铺满
            // 用 Matrix 把缩放真正画上去（之前只算了 s 却没用，drawBitmap 按原像素画，被裁剪框只露出左上角）
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate(x + (sz - bw * s) / 2f, y + (sz - bh * s) / 2f);
            c.drawBitmap(coverBm, m, bp);
            c.restore();
            drawSrcBadge(c, x, y, sz);
            return;
        }
        sp.setStyle(Paint.Style.FILL); sp.setColor(withA(ink, 0.08f));
        c.drawRoundRect(cr, r(0.2f), r(0.2f), sp);
        sp.setColor(withA(ink, 0.5f));
        float r0 = sz * 0.07f, hy = y + sz * 0.6f, hx1 = x + sz * 0.38f, hx2 = x + sz * 0.58f;
        c.drawCircle(hx1, hy, r0, sp); c.drawCircle(hx2, hy, r0, sp);
        sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(Math.max(2, dens * 2));
        c.drawLine(hx1, hy - r0, hx1, y + sz * 0.32f, sp);
        c.drawLine(hx2, hy - r0, hx2, y + sz * 0.32f, sp);
        c.drawLine(hx1, y + sz * 0.32f, hx2, y + sz * 0.32f, sp);
        sp.setStyle(Paint.Style.FILL);
        drawSrcBadge(c, x, y, sz);
    }
    /** 音源 App 角标：封面左下角压一个小圆角方块（品牌底色 + 白音符），认不出 App 就不画 */
    private void drawSrcBadge(Canvas c, float x, float y, float sz) {
        SrcApp a = srcApp(mPkg);
        if (a == null) return;
        float bs = Math.max(r(0.26f), sz * 0.26f), m = sz * 0.06f;
        float bx = x + m, by = y + sz - bs - m;
        sp.setStyle(Paint.Style.FILL); sp.setColor(a.color);
        c.drawRoundRect(bx, by, bx + bs, by + bs, bs * 0.26f, bs * 0.26f, sp);
        // 白音符（两个圆 + 两根竖线 + 顶横杠）
        float cx = bx + bs / 2f, cy = by + bs / 2f, u = bs * 0.5f;
        float r0 = u * 0.17f, hx1 = cx - u * 0.24f, hx2 = cx + u * 0.24f, hy = cy + u * 0.30f, top = cy - u * 0.34f;
        sp.setColor(0xffffffff);
        c.drawCircle(hx1, hy, r0, sp); c.drawCircle(hx2, hy, r0, sp);
        sp.setStrokeWidth(Math.max(1.5f, bs * 0.075f)); sp.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(hx1, hy - r0, hx1, top, sp);
        c.drawLine(hx2, hy - r0, hx2, top, sp);
        c.drawLine(hx1, top, hx2, top, sp);
        sp.setStrokeCap(Paint.Cap.BUTT);
    }
    // ================= 音乐卡片背景（与车模卡片背景 bg 分开设置） =================
    /** 画音乐卡片背景，返回解析出的底色 rgb（用于算文字对比） */
    /** 音乐整页背景：铺满整屏（含滑动偏移，不裁圆角）；音乐组件本身无背景，直接浮在页面背景上 */
    private void drawMusicBackground(Canvas c) {
        // 强制整页背景 100% 不透明：共用画笔 p 的透明度上一帧末尾可能被文字（如底部滑动提示 withA(mute,0.92)）改成非 255，
        // 不重置会让整张音乐背景变成半透明、底下 GL 车况背景（bg 的影棚/暖调/冷调/图片）透出来。横滑淡入由外层 saveLayerAlpha(255*k) 负责。
        p.setAlpha(255);
        bgFull.set(-cardOffPx - r(1f), -r(1f), W + r(1f), H + r(1f)); // 每帧复用，原来每帧 new 一个 RectF
        RectF full = bgFull;
        int[] rgb;
        String mode = S.str("mBgMode");
        if ("theme".equals(mode)) { rgb = drawThemeBg(c, full); } // 深浅模式：背景与车模页（车况页）完全一致（同套影棚/暖调/冷调径向渐变 + 同 darkK）
        else if ("fixed".equals(mode)) { rgb = hexRgb(S.str("mBgColor")); if (rgb == null) rgb = BG_FALLBACK; fillGrad(c, full, shade(rgb, 1.06f), shade(rgb, 0.94f)); }
        else { // 跟随封面：车况页（车模页）为深色主题时，封面亮背景压暗一些，避免和深色车况页割裂、刺眼
            rgb = coverDominant(); if (rgb == null) rgb = BG_FALLBACK;
            if (darkK > 0.01f) rgb = shade(rgb, 1 - 0.34f * darkK); // 深色越重压得越暗（darkK=1 时约 66% 亮度）
            drawCoverBg(c, full, rgb);
        }
        // 朝文字色方向压一层极淡 scrim，避免中明度背景文字发灰（深浅模式不压：要和车模页背景一模一样）
        if (!"theme".equals(mode)) {
            p.setColor(withA(lumin(rgb) > 0.55 ? 0xff15181e : 0xffeef1f6, 0.08f));
            c.drawRect(full, p);
        }
        musicBgRgb = rgb;
    }
    /** 渐变对象每帧重建太重（背景几乎不动）：参数没变就复用上一帧的 Shader */
    private LinearGradient gradCache;
    private float gL, gT, gR, gB; private int gCa, gCb;
    private void fillGrad(Canvas c, RectF R, int[] a, int[] b) {
        int ca = rgb2i(a), cb = rgb2i(b);
        if (gradCache == null || R.left != gL || R.top != gT || R.right != gR || R.bottom != gB || ca != gCa || cb != gCb) {
            gradCache = new LinearGradient(R.left, R.top, R.right, R.bottom, ca, cb, Shader.TileMode.CLAMP);
            gL = R.left; gT = R.top; gR = R.right; gB = R.bottom; gCa = ca; gCb = cb;
        }
        p.setShader(gradCache); c.drawRect(R.left, R.top, R.right, R.bottom, p); p.setShader(null);
    }
    /** 车模页（车况页）背景的三站亮 / 暗色（sRGB 0xRRGGBB，照 Bg.java 的 LIGHT / DARK），0 影棚、1 暖调、2 冷调 */
    private static final int[][] BG_LIGHT = { { 0xffffff, 0xf0f2f5, 0xdde1e7 }, { 0xfffaf3, 0xf3ebe0, 0xe2d6c6 }, { 0xf6fbff, 0xe4edf7, 0xcbd9ea } };
    private static final int[][] BG_DARK  = { { 0x2b3038, 0x16191e, 0x0b0c0f }, { 0x3b3027, 0x1c1612, 0x0d0a08 }, { 0x1f2d42, 0x101824, 0x060a10 } };
    /** 画「深浅模式」音乐背景：和车模页（车况页）同一套径向渐变（椭圆 75%×46%、中心 50%×60%），按 darkK 在亮暗间插值，做到滑动时背景无缝衔接 */
    private int[] drawThemeBg(Canvas c, RectF full) {
        int k = "warm".equals(S.str("bg")) ? 1 : "cool".equals(S.str("bg")) ? 2 : 0;
        float dk = darkK;
        int[] a = mixRgb(bgRgb(BG_LIGHT, k, 0), bgRgb(BG_DARK, k, 0), dk);
        int[] b = mixRgb(bgRgb(BG_LIGHT, k, 1), bgRgb(BG_DARK, k, 1), dk);
        int[] cc = mixRgb(bgRgb(BG_LIGHT, k, 2), bgRgb(BG_DARK, k, 2), dk);
        float cx = full.centerX();
        float cy = full.top + full.height() * 0.4f; // Bg 中心在 50%×60%（y 向上），画布 y 向下 → 0.4 处
        float rx = full.width() * 0.75f, ry = full.height() * 0.46f;
        int ca = rgb2i(a), cb = rgb2i(b), ccc = rgb2i(cc);
        // 径向渐变 + 缩放矩阵每帧重建太重：几何和三站色都没变就复用上一帧的（滑动/切页时 darkK 在动，会自动重建）
        if (radCache == null || cx != rCx || cy != rCy || ry != rRy || rx != rRx || ca != rCa || cb != rCb || ccc != rCc) {
            radCache = new RadialGradient(cx, cy, ry, new int[]{ ca, cb, ccc }, new float[]{ 0f, 0.46f, 1f }, Shader.TileMode.CLAMP);
            radM.reset(); radM.setScale(rx / ry, 1f, cx, cy); radCache.setLocalMatrix(radM); // 圆渐变拉成同款椭圆
            rCx = cx; rCy = cy; rRy = ry; rRx = rx; rCa = ca; rCb = cb; rCc = ccc;
        }
        p.setShader(radCache); c.drawRect(full, p); p.setShader(null);
        return cc; // 边色占面积最大，作代表色供对比
    }
    private static int[] bgRgb(int[][] pal, int k, int stop) { int c = pal[k][stop]; return new int[]{ (c >> 16) & 255, (c >> 8) & 255, c & 255 }; }
    private static int[] mixRgb(int[] lit, int[] dk, float t) {
        return new int[]{ Math.round(lit[0] + (dk[0] - lit[0]) * t), Math.round(lit[1] + (dk[1] - lit[1]) * t), Math.round(lit[2] + (dk[2] - lit[2]) * t) };
    }
    /** 跟随封面背景：纯色(solid) / 模糊+主色(blend) / 模糊呼吸(breath) / 双色流动(flow) / 主色光晕(glow)。除纯色外都从封面本身取色，不再用无关色/扫光 */
    private void drawCoverBg(Canvas c, RectF R, int[] rgb) {
        String cov = S.str("mBgCover");
        if ("solid".equals(cov)) {
            fillGrad(c, R, shade(rgb, 1.03f), shade(rgb, 0.97f));
        } else if ("blend".equals(cov)) {
            fillGrad(c, R, shade(rgb, 1.04f), shade(rgb, 0.92f));
            if (coverBlurBm != null && !coverBlurBm.isRecycled()) {
                if (coverPaint == null) { coverPaint = new Paint(); coverPaint.setFilterBitmap(true); }
                coverPaint.setAlpha(130); c.drawBitmap(coverBlurBm, null, R, coverPaint); coverPaint.setAlpha(255);
            }
        } else if ("breath".equals(cov)) {
            drawBlurredCover(c, R, true);
        } else if ("flow".equals(cov)) {
            drawFlow(c, R);
        } else if ("glow".equals(cov)) {
            drawGlow(c, R, rgb);
        }
    }
    /** 用封面本身的缩糊位图铺满整屏（拉伸即虚化）；breath 时随呼吸轻微缩放，像慢速推拉镜头 */
    private void drawBlurredCover(Canvas c, RectF R, boolean breath) {
        if (coverBlurBm == null || coverBlurBm.isRecycled()) { fillGrad(c, R, shade(BG_FALLBACK, 1.03f), shade(BG_FALLBACK, 0.97f)); return; }
        if (coverPaint == null) { coverPaint = new Paint(); coverPaint.setFilterBitmap(true); }
        RectF dr = R;
        if (breath) {
            double ph = Math.sin(System.currentTimeMillis() / 4200.0) * 0.5 + 0.5; // 0..1
            float s = 1.0f + 0.10f * (float) ph;
            dr = new RectF(R.centerX() - R.width() * s / 2, R.centerY() - R.height() * s / 2,
                           R.centerX() + R.width() * s / 2, R.centerY() + R.height() * s / 2);
        }
        c.drawBitmap(coverBlurBm, null, dr, coverPaint);
    }
    /** 双色流动：取封面最亮/最暗两极色做平滑渐变，渐变中心随时间长慢移动，设计感强、不跑色 */
    private void drawFlow(Canvas c, RectF R) {
        int[] hi = (coverLight != null) ? coverLight : shade(BG_FALLBACK, 1.3f);
        int[] lo = (coverDark != null) ? coverDark : shade(BG_FALLBACK, 0.7f);
        double ang = (System.currentTimeMillis() / 9000.0) % 1.0 * Math.PI * 2;
        float cx = R.centerX() + (float) Math.cos(ang) * R.width() * 0.28f;
        float cy = R.centerY() + (float) Math.sin(ang) * R.height() * 0.28f;
        LinearGradient g = new LinearGradient(cx - R.width() * 0.6f, cy - R.height() * 0.6f, cx + R.width() * 0.6f, cy + R.height() * 0.6f,
            new int[]{ rgb2i(hi), rgb2i(lo) }, new float[]{ 0, 1 }, Shader.TileMode.CLAMP);
        p.setShader(g); c.drawRect(R.left, R.top, R.right, R.bottom, p); p.setShader(null);
    }
    /** 主色光晕：主色打底，叠一层缓慢旋转的柔光（主色提亮），安静不抢眼 */
    private void drawGlow(Canvas c, RectF R, int[] rgb) {
        fillGrad(c, R, shade(rgb, 1.05f), shade(rgb, 0.9f));
        double ang = (System.currentTimeMillis() / 11000.0) % 1.0 * Math.PI * 2;
        float cx = R.centerX() + (float) Math.cos(ang) * R.width() * 0.3f;
        float cy = R.centerY() + (float) Math.sin(ang) * R.height() * 0.3f;
        int[] gl = shade(rgb, 1.5f);
        RadialGradient g = new RadialGradient(cx, cy, Math.max(R.width(), R.height()) * 0.5f,
            new int[]{ withA(rgb2i(gl), 0.27f), 0x00000000 }, new float[]{ 0, 1 }, Shader.TileMode.CLAMP);
        p.setShader(g); c.drawRect(R.left, R.top, R.right, R.bottom, p); p.setShader(null);
    }
    /** 采样封面主色（缩到 32×32，按色相分箱找主色相，避开纯黑/纯白；灰阶封面回退到明暗加权均值） */
    private int[] coverDominant() {
        if (coverDomRgb != null) return coverDomRgb;
        if (coverBm == null || coverBm.isRecycled()) return null;
        int[] out = new int[]{ 22, 32, 46 };
        try {
            int N = 32;
            Bitmap b = Bitmap.createScaledBitmap(coverBm, N, N, true);
            int[] px = new int[N * N];
            b.getPixels(px, 0, N, 0, 0, N, N);
            if (b != coverBm) b.recycle();
            int BINS = 18;
            float[] bw = new float[BINS]; int[] rs = new int[BINS], gs = new int[BINS], bs = new int[BINS];
            int ar = 0, ag = 0, ab = 0, ac = 0; float satN = 0;
            for (int i = 0; i < px.length; i++) {
                int col = px[i]; if ((col >>> 24) < 8) continue;
                int rr = (col >> 16) & 255, gg = (col >> 8) & 255, bl = col & 255;
                ar += rr; ag += gg; ab += bl; ac++;
                float rrf = rr/255f, ggf = gg/255f, bbf = bl/255f;
                float mx = Math.max(rrf, Math.max(ggf, bbf)), mn = Math.min(rrf, Math.min(ggf, bbf)), dl = mx - mn;
                float lum = (mx + mn) / 2f;
                float sat = dl == 0 ? 0 : (lum > 0.5f ? dl / (2 - mx - mn) : dl / (mx + mn));
                satN++;
                if (sat < 0.05f) continue;
                float hue = rgbHue(rrf, ggf, bbf);
                int bin = (int)(hue / (360f / BINS)) % BINS;
                float w = sat * (lum > 0.1f && lum < 0.92f ? 1f : 0.18f);
                bw[bin] += w; rs[bin] += rr * w; gs[bin] += gg * w; bs[bin] += bl * w;
            }
            int best = -1; float bestW = 0;
            for (int bi = 0; bi < BINS; bi++) { float ww = bw[bi] + bw[(bi + 1) % BINS] * 0.5f + bw[(bi + BINS - 1) % BINS] * 0.5f; if (ww > bestW) { bestW = ww; best = bi; } }
            if (best >= 0 && bestW > 0) {
                int s2r = 0, s2g = 0, s2b = 0, ws = 0;
                for (int bi = best - 1; bi <= best + 1; bi++) { int bx = ((bi % BINS) + BINS) % BINS; s2r += rs[bx]; s2g += gs[bx]; s2b += bs[bx]; ws += bw[bx]; }
                if (ws > 0) out = new int[]{ Math.round(s2r / ws), Math.round(s2g / ws), Math.round(s2b / ws) };
            } else if (ac > 0) out = new int[]{ ar / ac, ag / ac, ab / ac }; // 灰阶封面：明暗加权均值
        } catch (Exception ignored) { }
        coverDomRgb = out;
        return out;
    }
    /** 封面衍生强调色：取封面主色相，按深浅模式固定到「鲜明但不刺眼」的明度，驱动播放键/进度/歌词高亮；无封面时用主题 info */
    private int coverAccent() {
        int[] d = coverDominant();
        if (d == null) return info;
        boolean dark = (ink == 0xffeef1f6);
        float h = rgbHue(d[0] / 255f, d[1] / 255f, d[2] / 255f);
        float s = 0.70f;
        float v = dark ? 0.95f : 0.70f; // 深色背景用亮强调色、浅色背景用深强调色，保证对比
        return hsl2i(h, s, v, 1f);
    }
    // ---- 颜色工具 ----
    private int[] hexRgb(String h) { if (h == null || h.length() != 7) return null; try { int n = Integer.parseInt(h.substring(1), 16); return new int[]{ (n >> 16) & 255, (n >> 8) & 255, n & 255 }; } catch (Exception e) { return null; } }
    private float rgbHue(float r, float g, float b) {
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn, h = 0;
        if (d != 0) { if (mx == r) h = ((g - b) / d) % 6; else if (mx == g) h = (b - r) / d + 2; else h = (r - g) / d + 4; h *= 60; if (h < 0) h += 360; }
        return h;
    }
    private float lumin(int[] rgb) { float[] a = new float[]{ rgb[0]/255f, rgb[1]/255f, rgb[2]/255f }; for (int i = 0; i < 3; i++) { a[i] = a[i] <= 0.03928f ? a[i]/12.92f : (float)Math.pow((a[i] + 0.055)/1.055, 2.4); } return 0.2126f*a[0] + 0.7152f*a[1] + 0.0722f*a[2]; }
    private int[] shade(int[] rgb, float f) { return new int[]{ clamp255(rgb[0]*f), clamp255(rgb[1]*f), clamp255(rgb[2]*f) }; }
    private int clamp255(float v) { return v < 0 ? 0 : v > 255 ? 255 : Math.round(v); }
    private int rgb2i(int[] rgb) { return 0xff000000 | (clamp255(rgb[0]) << 16) | (clamp255(rgb[1]) << 8) | clamp255(rgb[2]); }
    /** HSL→带透明度的 int（装饰光带用，近似即可） */
    private int hsl2i(float h, float s, float l, float a) {
        float v = (l <= 0.5f) ? l * (1 + s) : l + s - l * s;
        float ss = v == 0 ? 0 : 2 * (1 - l / v);
        int rgb = Color.HSVToColor(new float[]{ h, Math.max(0, Math.min(1, ss)), Math.max(0, Math.min(1, v)) });
        return (Math.round(a * 255) << 24) | (rgb & 0xffffff);
    }

    private void drawPrevIcon(Canvas c, float cx, float cy, float s, int col) {
        sp.setStyle(Paint.Style.FILL); sp.setColor(col);
        float t = s * 0.5f;
        // 上一曲：两个朝左的双三角（«）；镜像下一曲的形状
        Path p1 = new Path(); p1.moveTo(cx + t, cy - t); p1.lineTo(cx + t * 0.1f, cy); p1.lineTo(cx + t, cy + t); p1.close();
        Path p2 = new Path(); p2.moveTo(cx - t * 0.1f, cy - t); p2.lineTo(cx - t, cy); p2.lineTo(cx - t * 0.1f, cy + t); p2.close();
        c.drawPath(p1, sp); c.drawPath(p2, sp);
    }
    private void drawNextIcon(Canvas c, float cx, float cy, float s, int col) {
        sp.setStyle(Paint.Style.FILL); sp.setColor(col);
        float t = s * 0.5f;
        Path p1 = new Path(); p1.moveTo(cx - t, cy - t); p1.lineTo(cx - t * 0.1f, cy); p1.lineTo(cx - t, cy + t); p1.close();
        Path p2 = new Path(); p2.moveTo(cx + t * 0.1f, cy - t); p2.lineTo(cx + t, cy); p2.lineTo(cx + t * 0.1f, cy + t); p2.close();
        c.drawPath(p1, sp); c.drawPath(p2, sp);
    }
    private void drawPlayPauseIcon(Canvas c, float cx, float cy, float s, int col) {
        sp.setStyle(Paint.Style.FILL); sp.setColor(col);
        if (playing) {
            float bw = s * 0.2f, bh = s;
            c.drawRoundRect(cx - s * 0.45f - bw / 2, cy - bh / 2, cx - s * 0.45f + bw / 2, cy + bh / 2, r(0.02f), r(0.02f), sp);
            c.drawRoundRect(cx + s * 0.45f - bw / 2, cy - bh / 2, cx + s * 0.45f + bw / 2, cy + bh / 2, r(0.02f), r(0.02f), sp);
        } else {
            Path p = new Path(); p.moveTo(cx - s * 0.5f, cy - s * 0.6f); p.lineTo(cx - s * 0.5f, cy + s * 0.6f); p.lineTo(cx + s * 0.6f, cy); p.close();
            c.drawPath(p, sp);
        }
    }

    // ================= 手势：只接可点的地方 =================
    private int downOn = -1;
    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY() - topShift; // 转入被上移元素的坐标系（底部数据仍用屏幕坐标）
        float yScreen = e.getY();
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = x; downY = y;
            downOn = -1;
            if (statsR.contains(x, yScreen)) { downOn = 20; return true; } // 底部车况区：横滑切换车模 / 音乐卡片
            if (musicOn) {
                if (prevR.contains(x, y)) downOn = 10;
                else if (playR.contains(x, y)) downOn = 11;
                else if (nextR.contains(x, y)) downOn = 12;
                else {
                    // 底部那行字 / 全部车况在卡片区域之外，不能被卡片的提前 return 挡住（否则音乐卡片显示时点不动）
                    for (int i = 0; i < listR.size(); i++) if (listR.get(i).contains(x, y)) { downOn = 100 + i; break; }
                    if (downOn < 0 && allR.contains(x, y)) downOn = 2;
                    if (downOn < 0 && pillOn && pillR.contains(x, y)) downOn = 1;
                    if (downOn < 0 && !coverR.isEmpty() && coverR.contains(x, y)) downOn = 22; // 封面：打开放歌的 App
                    if (downOn < 0 && cardR.contains(x, y)) downOn = 20; // 卡片其余区域：左右拖拽切回车模（按钮/封面已先判定）
                }
                if (downOn < 0) downOn = 20; // 音乐卡片背景（歌词/留白等）：左右滑动切回车模
                return true;
            }
            for (int i = 0; i < listR.size(); i++) if (listR.get(i).contains(x, y)) downOn = 100 + i;
            if (downOn < 0 && pillOn && pillR.contains(x, y)) downOn = 1;
            if (downOn < 0 && allR.contains(x, y)) downOn = 2;
            if (downOn < 0 && !demoR.isEmpty() && demoR.contains(x, y)) downOn = 3;
            if (downOn < 0 && stOpen) { tap.close(); return false; } // 点别处收起列表（同网页版），这一下照常交给 3D
            if (downOn < 0 && yScreen < r(1.5f)) { downOn = 20; return true; } // 车模页顶部栏带：左右滑动切到音乐卡片（中间车身仍让 3D 自转）
            return downOn >= 0;
        }
        if (downOn < 0) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE && downOn == 20) {
            if (!dragging) { // 进入拖拽：以当前页为基准，手指跟手平移两页（不封顶，越界即循环）
                dragging = true; dragBase = page; lastX = x; lastT = e.getEventTime(); dragVel = 0;
                if (offAnim != null) offAnim.cancel();
            }
            float dx = x - downX;
            page = dragBase - dx / Math.max(1, W); // 往左滑(dx<0)→page 增大去下一页；不封顶，越过边界即循环
            syncPhase();
            long t = e.getEventTime(); float dt = t - lastT;
            if (dt > 0) dragVel = (x - lastX) / dt; // px/ms，正=向右
            lastX = x; lastT = t;
            if (onPageOffset != null) onPageOffset.run(); // GL 整层跟随
            invalidate();
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            int d = downOn;
            downOn = -1;
            if (d == 20) { // 松手：按偏移 + 甩动速度决定落点，弹回 0（车模）或 1（音乐）
                if (dragging) {
                    dragging = false;
                    int t;
                    if (dragVel < -0.5f) t = Math.round(page) + 1;     // 快速左甩 → 去下一页（循环）
                    else if (dragVel > 0.5f) t = Math.round(page) - 1; // 快速右甩 → 回上一页（循环）
                    else t = Math.round(page);                         // 否则落最近页
                    tap.pageTo(((t % 3) + 3) % 3); // 三页循环：0 车况 / 1 音乐 / 2 琉光
                }
            } else if (d == 10) tap.music("previous");
            else if (d == 11) tap.music("togglePlayPause");
            else if (d == 12) tap.music("next");
            else if (d == 1) tap.status();
            else if (d == 2) tap.all();
            else if (d == 3) tap.debug();
            else if (d == 22) tap.launchApp(); // 点封面：打开放歌的 App
            else if (d >= 100) tap.pick(d - 100);
            // d == 21：原卡片空白区已改为 20（可拖拽切页），此处不再出现
        }
        if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) { downOn = -1; if (dragging) { dragging = false; page = Math.round(page); syncPhase(); invalidate(); } } // 拖拽被中断（如通话）：page 收尾到最近整数页，避免卡半页
        return true;
    }
}
