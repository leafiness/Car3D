package com.ljone.car3d;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
    interface Tap { void status(); void pick(int i); void all(); void close(); void debug(); void card(boolean fromRight); void music(String a); }

    // 浅色 / 深色（同 CSS 变量）：ink、mute、line、chip、ok、warn、bad、info
    private static final int[] LIGHT = { 0xff14171c, 0x8014171c, 0x1f14171c, 0xb3ffffff, 0xff0e9f57, 0xffd97706, 0xffdc2f2f, 0xff2575e6 };
    private static final int[] DARK = { 0xffeef1f6, 0x80eef1f6, 0x21ffffff, 0xb81c1f25, 0xff3fd98a, 0xffffb547, 0xffff6b6b, 0xff5aa2ff };
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
    private List<CarStat.Sys> sys = new ArrayList<>();
    private float hintA = 1;
    // 可点的地方
    private final RectF pillR = new RectF(), allR = new RectF();
    private final List<RectF> listR = new ArrayList<>();
    private boolean pillOn;
    /** 下面那行字的上沿（胎压标签最多到这里）；变了告诉外面 */
    float msgTop;
    Runnable onMsgTop;

    // 时钟 + 红绿灯倒计时：顶栏每秒刷新（让倒计时走动）
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
    void setTells(List<CarStat.Item> l, List<CarStat.Item> r) { tl = l; tr = r; invalidate(); }
    void setSys(List<CarStat.Sys> s) { sys = s; invalidate(); }
    void setLoading(String s) { loading = s; invalidate(); }
    // 红绿灯数据源（实验分支）：顶栏倒计时从此拉取；为 null 时不显示
    private TrafficLightSource lightSrc;
    void setTrafficLightSource(TrafficLightSource s) { lightSrc = s; }
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
    private long mPos, mDur, mRecv;
    private List<String> lyrics = new ArrayList<>();
    private float tellBottom, speedBottom, statusTop, tellLw, tellRw; // 音乐卡片要避开的其他区域几何（每帧随画更新）
    private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF statsR = new RectF(), cardR = new RectF();
    private final RectF prevR = new RectF(), playR = new RectF(), nextR = new RectF();
    private float downX, downY;
    private float statTop;

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
        // 顶栏、车况图标、车速、下面那行字在两个页面都照常显示；音乐卡片只占车模显示区
        // 底部车况数据固定不动；其余元素整体随 topShift 上移（drawStats 在平移之外单独绘制）
        if (cardOffPx < 0) cardOffPx = W; // 首帧兜底
        float k = Math.max(0f, Math.min(1f, 1 - Math.abs(cardOffPx) / Math.max(1, W)));
        c.save();
        c.translate(0, topShift); // 顶栏/车况图标/状态/音乐卡片统一上移，作为刚体
        drawHead(c);
        drawTells(c);
        drawStatus(c);
        if (k > 0.01f) { // 音乐卡片：整层横移（跟手或动画）+ 淡入淡出
            int save = c.saveLayerAlpha(0, 0, W, H, Math.round(255 * k), Canvas.ALL_SAVE_FLAG);
            c.translate(cardOffPx, 0);
            drawMusic(c);
            c.restore();
        } else if (!musicOn) {
            if (hintA > 0.01f) { // 操作提示
                p.setTypeface(Typeface.DEFAULT); p.setTextSize(r(0.22f)); p.setColor(withA(mute, hintA)); p.setTextAlign(Paint.Align.CENTER);
                c.drawText("按住车身左右拖，转着看", W / 2f, r(portrait ? 2.3f : 1.22f) + r(0.22f), p);
                p.setTextAlign(Paint.Align.LEFT);
            }
            if (loading != null) {
                p.setTypeface(Typeface.DEFAULT); p.setTextSize(r(0.22f)); p.setColor(mute); p.setTextAlign(Paint.Align.CENTER);
                c.drawText(loading, W / 2f, H * 0.48f + r(0.22f), p);
                p.setTextAlign(Paint.Align.LEFT);
            }
        }
        c.restore();
        drawStats(c); // 底部车况数据不随上移
    }
    private static int withA(int c, float a) { return (Math.round((c >>> 24) * a) << 24) | (c & 0xffffff); }

    private void drawHead(Canvas c) {
        float left = r(portrait ? 0.3f : 0.4f), sz = r(0.46f), top = r(portrait ? 0.48f : 0.5f), cy = top + sz / 2;
        float lx = drawClock(c, left, cy);
        // 红绿灯倒计时（实验分支）：时钟右侧画 灯点 + 剩余秒
        if (lightSrc != null) {
            LightInfo li = lightSrc.current();
            if (li.valid()) {
                int col = li.state == LightInfo.RED ? bad : li.state == LightInfo.GREEN ? ok : warn;
                float dx = lx + r(0.18f), dy = cy;
                p.setColor(col);
                c.drawCircle(dx + r(0.08f), dy, r(0.08f), p);
                p.setTypeface(num700); p.setTextSize(r(0.26f)); p.setColor(ink);
                c.drawText(String.valueOf(li.remainSec), dx + r(0.24f), baseline(dy, p), p);
            }
        }
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
        c.drawText(time, left, baseline(cy, p), p);
        float tx = left + p.measureText(time) + r(0.12f);
        p.setTypeface(Typeface.DEFAULT); p.setTextSize(r(portrait ? 0.21f : 0.27f)); p.setColor(mute);
        c.drawText(date, tx, baseline(cy, p), p);
        return tx + p.measureText(date);
    }
    private float labelW(String s) { p.setTypeface(ui700); p.setTextSize(r(0.17f)); return r(0.02f) + p.measureText(s); }
    private static float baseline(float cy, Paint p) { Paint.FontMetrics m = p.getFontMetrics(); return cy - (m.ascent + m.descent) / 2; }

    /** 24×24 的图标画在 (x, y-0.15rem) 起的 0.3rem 方块里 */
    private void icon(Canvas c, String d, float x, float cy, float size, int color, boolean fill, float alpha) {
        Path path = SvgPath.of(d);
        Matrix m = new Matrix();
        m.setScale(size / 24, size / 24);
        m.postTranslate(x, cy - size / 2);
        Path q = new Path();
        path.transform(m, q);
        sp.setColor(withA(color, alpha));
        if (fill) { sp.setStyle(Paint.Style.FILL); }
        else { sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(1.8f * size / 24); sp.setStrokeCap(Paint.Cap.ROUND); sp.setStrokeJoin(Paint.Join.ROUND); }
        c.drawPath(q, sp);
        sp.setStyle(Paint.Style.FILL);
    }
    private void fan(Canvas c, float x, float cy, float size, int color) {
        Path path = SvgPath.of(CarStat.FAN_D);
        for (int k = 0; k < 4; k++) {
            Matrix m = new Matrix();
            m.setRotate(90 * k, 12, 12);
            m.postScale(size / 24, size / 24);
            m.postTranslate(x, cy - size / 2);
            Path q = new Path();
            path.transform(m, q);
            sp.setColor(color); sp.setStyle(Paint.Style.FILL);
            c.drawPath(q, sp);
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
            List<CarStat.Item> all = new ArrayList<>();
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
        List<String> lines = wrapText(msg, maxText);
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
            if (clickable) { sp.setColor(chip); c.drawRoundRect(pillR, r(0.3f), r(0.3f), sp); }
            float dx = x0 + r(0.22f) + r(0.06f);
            int col = lvColor(topLv);
            sp.setColor(withA(col, 0.18f)); c.drawCircle(dx, rowCy, r(0.06f) + r(0.05f), sp);
            sp.setColor(col); c.drawCircle(dx, rowCy, r(0.06f), sp);
            p.setTypeface(ui500); p.setTextSize(fs); p.setColor(ink);
            float ty = rowCy - lh * lines.size() / 2 + lh / 2;
            for (String s : lines) { c.drawText(s, dx + r(0.06f) + r(0.12f), baseline(ty, p), p); ty += lh; }
            if (more > 0) {
                float bx = dx + r(0.18f) + textW + r(0.12f), bh = r(0.32f);
                sp.setColor(line); c.drawRoundRect(bx, rowCy - bh / 2, bx + badgeW, rowCy + bh / 2, r(0.16f), r(0.16f), sp);
                p.setTypeface(ui600); p.setTextSize(r(0.2f)); p.setColor(mute);
                c.drawText("+" + more, bx + r(0.12f), baseline(rowCy, p), p);
            }
        }
        allR.set(x0 + pillW + r(0.12f), rowCy - allH / 2, x0 + pillW + r(0.12f) + allW, rowCy + allH / 2);
        sp.setColor(chip); c.drawRoundRect(allR, r(0.3f), r(0.3f), sp);
        sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(Math.max(1, dens)); sp.setColor(line); c.drawRoundRect(allR, r(0.3f), r(0.3f), sp); sp.setStyle(Paint.Style.FILL);
        p.setTypeface(ui500); p.setTextSize(r(0.2f)); p.setColor(mute);
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
        // 两页提示：车模页 ↔ 音乐卡片页，底部这一块左右滑动切换
        float iy = H - r(portrait ? 0.16f : 0.22f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(ui600); p.setTextSize(r(0.19f)); p.setColor(withA(mute, 0.92f));
        c.drawText(musicOn ? "音乐卡片  ·  左右滑动切回车模" : "车模  ·  左右滑动切到音乐卡片", W / 2f, iy, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    // ================= 音乐卡片 =================
    void setMusic(boolean on, boolean fromRight) { // fromRight：新页面从右进（往左滑）；否则从左进（往右滑）
        if (on == musicOn) return;
        musicOn = on;
        if (on) { if (Math.abs(cardOffPx - W) < 1 || cardOffPx < 0) cardOffPx = fromRight ? W : -W; }
        animOff(on ? 0 : (fromRight ? -W : W)); // 退出方向与滑动方向一致：往左滑卡片往左出
        invalidate();
    }
    /** 卡片横移像素（0=就位；±W=藏在右/左），类似 ViewPager 的页偏移；初始藏在右侧 */
    private float cardOffPx = -1;
    private ValueAnimator offAnim;
    private void animOff(float to) {
        if (cardOffPx < 0) cardOffPx = W;
        if (offAnim != null) offAnim.cancel();
        offAnim = ValueAnimator.ofFloat(cardOffPx, to);
        offAnim.setDuration(280);
        offAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        offAnim.addUpdateListener(a -> { cardOffPx = (Float) a.getAnimatedValue(); invalidate(); });
        offAnim.start();
    }
    void setMedia(JSONObject m) {
        if (m == null) { mTitle = mArtist = mLyric = mCover = null; mPos = mDur = 0; playing = false; invalidate(); return; }
        mTitle = m.optString("title", ""); mArtist = m.optString("artist", "");
        mLyric = lyricText(m.optString("lyric", "")); mCover = m.optString("coverUrl", "");
        mPos = m.optLong("positionMs"); mDur = m.optLong("durationMs"); mRecv = System.currentTimeMillis();
        playing = m.optBoolean("playing");
        invalidate();
    }
    void setLyrics(List<String> l) {
        lyrics = new ArrayList<>();
        if (l != null) for (String s : l) {
            if (s != null && s.contains("\n")) for (String p : s.split("\n", -1)) { String t = p.trim(); if (!t.isEmpty()) lyrics.add(lyricText(t)); }
            else lyrics.add(lyricText(s));
        }
        invalidate();
    }
    /** 宿主歌词行可能是 {"text":"...","timeMs":...} 的 JSON 串，解析出纯文本（兼容 text/content/words/lyric 几个键） */
    private static String lyricText(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("{") && s.endsWith("}")) {
            try {
                JSONObject o = new JSONObject(s);
                String t = o.optString("text", "");
                if (t.isEmpty()) t = o.optString("content", "");
                if (t.isEmpty()) t = o.optString("words", "");
                if (t.isEmpty()) t = o.optString("lyric", s);
                return t;
            } catch (Exception ignored) { }
        }
        return s;
    }
    private long livePos() { if (playing && mDur > 0) return Math.min(mPos + (System.currentTimeMillis() - mRecv), mDur); return mPos; }
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
    private void drawMusic(Canvas c) {
        // 卡片只占车模显示区：顶栏/挡位/车况图标/车速、下面那行字、底部数据都照常显示、不被盖住
        // （宽屏不做左右车况列的内缩：车况列在上方，播控/歌词垂直居中不会碰到；左右各占一半、各自水平居中）
        float cardTop = portrait ? Math.max(tellBottom, speedBottom) + r(0.12f) : r(1.6f);
        // statTop 在平移之外（底部数据）计算，转进当前平移坐标系再取较小边
        float cardBottom = Math.min(statusTop, statTop - topShift) - r(0.08f);
        float cardLeft = portrait ? r(0.3f) : r(0.4f);
        float cardRight = portrait ? W - r(0.3f) : W - r(0.4f);
        if (cardBottom - cardTop < r(2.4f)) cardTop = cardBottom - r(2.4f); // 极矮屏兜底：保证控制器放得下
        cardR.set(cardLeft, cardTop, cardRight, cardBottom); // 只标区域（歌词/控制直接画在页面背景上，不再单独铺底色）
        p.setTextAlign(Paint.Align.LEFT);
        if (mTitle == null || mTitle.isEmpty()) {
            p.setTypeface(ui600); p.setTextSize(r(0.26f)); p.setColor(mute); p.setTextAlign(Paint.Align.CENTER);
            c.drawText("暂无音乐", (cardLeft + cardRight) / 2f, (cardTop + cardBottom) / 2f, p);
            p.setTextAlign(Paint.Align.LEFT);
            return;
        }
        float pad = r(0.25f);
        if (portrait) {
            float mid = cardTop + (cardBottom - cardTop) * 0.50f;
            drawLyrics(c, cardLeft + pad, cardTop + pad, cardRight - cardLeft - pad * 2, mid - cardTop - pad * 2);
            drawController(c, cardLeft + pad, mid + pad, cardRight - cardLeft - pad * 2, cardBottom - mid - pad * 2, false);
        } else {
            float split = (cardLeft + cardRight) / 2f; // 宽屏：左右各占一半
            drawController(c, cardLeft + pad, cardTop + pad, split - cardLeft - pad * 1.5f, cardBottom - cardTop - pad * 2, true);
            drawLyrics(c, split + pad * 0.5f, cardTop + pad, cardRight - split - pad * 1.5f, cardBottom - cardTop - pad * 2);
        }
    }
    private void drawController(Canvas c, float x, float y, float w, float h, boolean center) {
        float br = Math.min(r(0.32f), h * 0.16f);
        // 宽屏：播控组件横向占左半屏的三分之二，并在左半屏内水平居中
        float compW = center ? w * 2f / 3f : w;
        float gcx = center ? x + (w - compW) / 2f : x; // 组件在左半屏内的水平起点
        float cov = Math.min(Math.min(h * 0.46f, compW * 0.5f), r(1.9f));
        // 控件整组高度（封面 + 间距 + 进度条 + 时间 + 播控），用于宽屏垂直居中
        float barGap = r(0.20f), barH = r(0.05f), timeGap = r(0.42f), btnGap = r(0.22f);
        float groupH = cov + barGap + barH + timeGap + btnGap + br * 2f;
        if (center) y += Math.max(0, (h - groupH) / 2); // 宽屏：播控整体在左半部垂直居中
        // 歌名/歌手字号：宽屏、窄屏都放大
        float tf = center ? 0.34f : 0.30f, af = center ? 0.26f : 0.23f;
        // 文字列宽随组件宽自适应（不随歌名长度伸缩）；超长截断
        float textW = Math.max(r(0.5f), compW - cov - r(0.18f));
        p.setTypeface(ui700); p.setTextSize(r(tf));
        // 歌名：超宽时按可用列宽自动换行（最多两行，超出末行加省略号）
        List<String> titleLines = wrapTitle(mTitle, textW);
        p.setTypeface(ui500); p.setTextSize(r(af)); p.setColor(mute);
        String t2 = ellipsize(mArtist, textW);
        float gx = gcx, tx = gx + cov + r(0.18f);
        drawCover(c, gx, y, cov);
        // 歌名：封面右侧（顶部），逐行绘制
        p.setTypeface(ui700); p.setTextSize(r(tf)); p.setColor(ink);
        float ty = y + r(0.32f);
        for (int i = 0; i < titleLines.size(); i++) { c.drawText(titleLines.get(i), tx, ty, p); ty += r(tf); }
        // 歌手名：封面右侧、与封面底部对齐
        p.setTypeface(ui500); p.setTextSize(r(af)); p.setColor(mute);
        c.drawText(t2, tx, y + cov - p.descent() - r(0.02f), p);
        // 进度条：宽屏占组件宽（左半屏三分之二）并居中；窄屏保持整行
        float pw = compW, px = gcx;
        float progY = y + cov + barGap;
        float prog = mDur > 0 ? (float) livePos() / mDur : 0;
        sp.setStyle(Paint.Style.FILL); sp.setColor(line);
        c.drawRoundRect(px, progY, px + pw, progY + barH, r(0.03f), r(0.03f), sp);
        sp.setColor(info);
        if (prog > 0) c.drawRoundRect(px, progY, px + pw * prog, progY + barH, r(0.03f), r(0.03f), sp);
        // 进度时间：字号放大到原来的 2 倍（r(0.15)→r(0.30)），宽屏窄屏一致
        p.setTypeface(ui500); p.setTextSize(r(0.30f)); p.setColor(mute);
        c.drawText(fmtTime(livePos()), px, progY + timeGap, p);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(fmtTime(mDur), px + pw, progY + timeGap, p);
        p.setTextAlign(Paint.Align.LEFT);
        // 播控：组件下方居中（组件宽的中点，宽屏下仍落在左半屏正中）
        float by = progY + timeGap + btnGap + br;
        float gap = br * 2.6f, bcx = gcx + compW / 2f;
        prevR.set(bcx - gap - br, by - br, bcx - gap + br, by + br);
        playR.set(bcx - br, by - br, bcx + br, by + br);
        nextR.set(bcx + gap - br, by - br, bcx + gap + br, by + br);
        sp.setStyle(Paint.Style.FILL); sp.setColor(withA(ink, 0.12f));
        c.drawCircle(prevR.centerX(), by, br, sp);
        c.drawCircle(playR.centerX(), by, br, sp);
        c.drawCircle(nextR.centerX(), by, br, sp);
        drawPrevIcon(c, prevR.centerX(), by, br * 0.5f);
        drawPlayPauseIcon(c, playR.centerX(), by, br * 0.5f);
        drawNextIcon(c, nextR.centerX(), by, br * 0.5f);
    }
    private void drawLyrics(Canvas c, float x, float y, float w, float h) {
        if (lyrics.isEmpty()) {
            if (mLyric != null && !mLyric.isEmpty()) {
                // 宿主没给整首歌词时：快照里的歌词若含多行（有的宿主一行返回整首），按行显示，避免只显一行
                List<String> ls = splitLines(mLyric);
                if (ls.size() > 1) { drawLyricLines(c, x, y, w, h, ls, -1); return; }
                p.setTypeface(ui700); p.setTextSize(r(0.24f)); p.setColor(ink); p.setTextAlign(Paint.Align.CENTER);
                c.drawText(ellipsize(mLyric, w), x + w / 2, y + h / 2, p); p.setTextAlign(Paint.Align.LEFT);
                return;
            }
            p.setTypeface(ui500); p.setTextSize(r(0.18f)); p.setColor(mute); p.setTextAlign(Paint.Align.CENTER);
            c.drawText("暂无歌词", x + w / 2, y + h / 2, p); p.setTextAlign(Paint.Align.LEFT);
            return;
        }
        int cur = -1;
        for (int i = 0; i < lyrics.size(); i++) if (lyrics.get(i).equals(mLyric)) { cur = i; break; }
        drawLyricLines(c, x, y, w, h, lyrics, cur);
    }
    private static List<String> splitLines(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split("\n", -1)) { String t = p.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }
    /** 歌词行渲染（整首或快照拆分出的多行共用）：当前句高亮、窗口滚动、字大了自动少显几行 */
    private void drawLyricLines(Canvas c, float x, float y, float w, float h, List<String> list, int cur) {
        if (list.isEmpty()) return;
        int rows = 5;
        try { rows = Integer.parseInt(S.str(portrait ? "lyrRowsP" : "lyrRowsL").trim()); } catch (Exception ignored) { }
        rows = Math.max(3, Math.min(8, rows));
        float fk = 1;
        try { fk = Float.parseFloat(S.str(portrait ? "lyrFontP" : "lyrFontL").trim()); } catch (Exception ignored) { }
        if (fk < 0.5f || fk > 4f) fk = 1;
        rows = Math.max(1, Math.min(rows, (int) (h / (r(0.30f) * fk)))); // 字大了行高跟不上就少显示几行
        int start = Math.max(0, cur - (rows - 1) / 2);
        start = Math.max(0, Math.min(start, list.size() - rows));
        int end = Math.min(list.size(), start + rows);
        float lh = Math.max(Math.min(h / rows, r(0.42f) * fk), r(0.30f) * fk); // 行距保底不叠字
        float baseY = y + h / 2 - (Math.min(rows, end - start) - 1) * lh / 2;
        for (int i = start; i < end; i++) {
            boolean on = i == cur;
            p.setTypeface(on ? ui700 : ui500);
            p.setTextSize(on ? r(0.22f) * fk : r(0.18f) * fk);
            p.setColor(on ? ink : withA(mute, 0.7f));
            p.setTextAlign(Paint.Align.CENTER);
            String line = list.get(i);
            if (line == null || line.isEmpty()) line = "♪";
            c.drawText(ellipsize(line, w), x + w / 2, baseY + (i - start) * lh + lh * 0.35f, p);
        }
        p.setTextAlign(Paint.Align.LEFT);
    }
    /** 封面位图（CarUi 后台加载好给过来）；没有时画占位音符 */
    void setCover(android.graphics.Bitmap b) { coverBm = b; invalidate(); }
    private void drawCover(Canvas c, float x, float y, float sz) {
        RectF cr = new RectF(x, y, x + sz, y + sz);
        if (coverBm != null && !coverBm.isRecycled()) {
            Path clip = new Path(); clip.addRoundRect(cr, r(0.2f), r(0.2f), Path.Direction.CW);
            int save = c.save();
            c.clipPath(clip);
            float bw = coverBm.getWidth(), bh = coverBm.getHeight(), s = Math.max(sz / bw, sz / bh); // 居中裁剪铺满
            c.drawBitmap(coverBm, x + (sz - bw * s) / 2f, y + (sz - bh * s) / 2f, bp);
            c.restore();
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
    }
    private void drawPrevIcon(Canvas c, float cx, float cy, float s) {
        sp.setStyle(Paint.Style.FILL); sp.setColor(ink);
        float t = s * 0.5f;
        Path p1 = new Path(); p1.moveTo(cx - t, cy - t); p1.lineTo(cx - t * 0.1f, cy); p1.lineTo(cx - t, cy + t); p1.close();
        Path p2 = new Path(); p2.moveTo(cx + t * 0.1f, cy - t); p2.lineTo(cx + t, cy); p2.lineTo(cx + t * 0.1f, cy + t); p2.close();
        c.drawPath(p1, sp); c.drawPath(p2, sp);
    }
    private void drawNextIcon(Canvas c, float cx, float cy, float s) {
        sp.setStyle(Paint.Style.FILL); sp.setColor(ink);
        float t = s * 0.5f;
        Path p1 = new Path(); p1.moveTo(cx - t, cy - t); p1.lineTo(cx - t * 0.1f, cy); p1.lineTo(cx - t, cy + t); p1.close();
        Path p2 = new Path(); p2.moveTo(cx + t * 0.1f, cy - t); p2.lineTo(cx + t, cy); p2.lineTo(cx + t * 0.1f, cy + t); p2.close();
        c.drawPath(p1, sp); c.drawPath(p2, sp);
    }
    private void drawPlayPauseIcon(Canvas c, float cx, float cy, float s) {
        sp.setStyle(Paint.Style.FILL); sp.setColor(ink);
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
                else if (cardR.contains(x, y)) downOn = 21; // 卡片其余区域：吞掉手势，不转车模
                return downOn >= 0;
            }
            for (int i = 0; i < listR.size(); i++) if (listR.get(i).contains(x, y)) downOn = 100 + i;
            if (downOn < 0 && pillOn && pillR.contains(x, y)) downOn = 1;
            if (downOn < 0 && allR.contains(x, y)) downOn = 2;
            if (downOn < 0 && !demoR.isEmpty() && demoR.contains(x, y)) downOn = 3;
            if (downOn < 0 && stOpen) { tap.close(); return false; } // 点别处收起列表（同网页版），这一下照常交给 3D
            return downOn >= 0;
        }
        if (downOn < 0) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            int d = downOn;
            downOn = -1;
            if (d == 20) { // 横滑：往左滑卡片从右进，往右滑从左进
                float dx = x - downX, dy = y - downY;
                if (Math.abs(dx) > r(0.6f) && Math.abs(dx) > Math.abs(dy) * 1.4f) tap.card(dx < 0);
            } else if (d == 10) tap.music("previous");
            else if (d == 11) tap.music("togglePlayPause");
            else if (d == 12) tap.music("next");
            else if (d == 1) tap.status();
            else if (d == 2) tap.all();
            else if (d == 3) tap.debug();
            else if (d >= 100) tap.pick(d - 100);
            // d == 21：卡片空白区，已吞掉
        }
        if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) downOn = -1;
        return true;
    }
}
