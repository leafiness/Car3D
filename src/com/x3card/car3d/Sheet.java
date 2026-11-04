package com.ljone.car3d;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 面板的外壳（照网页版 style.css 的 .set / .set-card）：深色卡片、顶上一道强调色细线、标题和关闭键、能滚动的内容；
 * 横屏靠右占 46%（从右边转着滑进来），竖屏在下面占 64%（从下面翻上来），内容一行行浮上来。
 * modal 的点面板外面关掉（设置）；不是的面板外面不挡手势（全部车况：照样能拖车，同 .tune）
 */
@SuppressLint("ViewConstructor")
abstract class Sheet extends FrameLayout {
    protected final Settings S;
    private final View shade;
    protected final LinearLayout card, body;
    protected final ScrollView scroll;
    private final TextView title;
    private final IconBtn close;
    protected final float dens;
    protected float rem = 10;
    protected int W = 1, H = 1;
    protected boolean open;
    private final boolean modal;
    private final PathInterpolator ease = new PathInterpolator(0.2f, 0.8f, 0.2f, 1f);
    // 分组卡片化：每个 group() 建一个浅一档容器，里面装它的行；关闭/打开时按实际行数填计数徽章
    private final ArrayList<LinearLayout> groupCards = new ArrayList<>();
    private final ArrayList<LinearLayout> groupRows = new ArrayList<>();
    private LinearLayout rowsBox;
    protected void resetGroups() { groupCards.clear(); groupRows.clear(); rowsBox = null; }
    // 主题色板：随「深浅色」设置切换（light / dark；auto 跟随车机）
    public interface DarkK { float get(); }
    protected DarkK darkKProvider;
    protected boolean light;
    protected int TXT, SUB, MUTE, CARD_TOP, CARD_BOT, CARD_BORDER, GROUP_BG, GROUP_BORDER,
                 GROUP_TITLE, LINE, INFO, ICON, SEG_BG, SEG_OFF_TXT, SEG_ON_TXT, SEG_ON_BG, SW_OFF, TRACK_OFF,
                 CHIP_BG, CHIP_TXT, BTN_BORDER, BTN_OFF_BG, CHEV_STROKE, CHEV_FILL;
    /** 设置深色键（仅 auto 模式用于跟随车机当前明暗） */
    void setDarkKProvider(DarkK p) { darkKProvider = p; applyTheme(); }
    /** 按「深浅色」重算整套配色，并刷新面板背景 */
    void applyTheme() {
        String t = S.str("theme");
        boolean lt;
        if ("light".equals(t)) lt = true;
        else if ("dark".equals(t)) lt = false;
        else { float k = darkKProvider != null ? darkKProvider.get() : 1f; lt = k < 0.5f; }
        light = lt;
        if (lt) {
            TXT = 0xff14171c; SUB = 0x8014171c; MUTE = 0xb314171c;
            CARD_TOP = 0xfff2f2f7; CARD_BOT = 0xfff2f2f7; CARD_BORDER = 0x00000000;
            GROUP_BG = 0xffffffff; GROUP_BORDER = 0x00000000; GROUP_TITLE = 0xff6b7280;
            LINE = 0x20000000; INFO = 0xff7c8794; ICON = 0xff52525b; SEG_BG = 0x12000000; SEG_OFF_TXT = 0xff5b6675;
            SEG_ON_TXT = ACC; SEG_ON_BG = 0x1f5b8cff; SW_OFF = 0x26000000; TRACK_OFF = 0x26000000;
            CHIP_BG = 0xffe7eaf0; CHIP_TXT = 0xff14171c; BTN_BORDER = 0x29000000; BTN_OFF_BG = 0x00000000;
            CHEV_STROKE = 0x80000000; CHEV_FILL = 0x14000000;
        } else {
            TXT = 0xffeef1f6; SUB = 0xa6eef1f6; MUTE = 0xb3eef1f6;
            CARD_TOP = 0xff000000; CARD_BOT = 0xff000000; CARD_BORDER = 0x00000000;
            GROUP_BG = 0xff1c1c1f; GROUP_BORDER = 0x00000000; GROUP_TITLE = 0xff8e8e93;
            LINE = 0xff2c2c2e; INFO = 0xff8b97a8; ICON = 0xffd0d4da; SEG_BG = 0x0fffffff; SEG_OFF_TXT = 0x99eef1f6;
            SEG_ON_TXT = 0xffcfe0ff; SEG_ON_BG = 0x1f5b8cff; SW_OFF = 0x24ffffff; TRACK_OFF = 0x24ffffff;
            CHIP_BG = 0x15181e; CHIP_TXT = 0xffeef1f6; BTN_BORDER = 0x29ffffff; BTN_OFF_BG = 0x0fffffff;
            CHEV_STROKE = 0x80ffffff; CHEV_FILL = 0x14ffffff;
        }
        accentChanged();
        // 标题文字随主题换色（构造时 TXT 还是 0，必须在这里回写，否则深浅色下都看不见「设置」）
        if (title != null) title.setTextColor(TXT);
        // 关闭键（IconBtn）随主题换色：深色面板用白字，浅色面板用深色字
        if (close != null) { close.fg = TXT; close.bg = light ? 0x14000000 : 0x14ffffff; close.invalidate(); }
    }
    /** 设置页开着时切换「深浅色」：重算配色并重画整页 */
    void retheme() { applyTheme(); if (open) rows(); }

    Sheet(Context c, Settings s, String name, boolean modal) {
        super(c);
        S = s;
        this.modal = modal;
        dens = c.getResources().getDisplayMetrics().density;
        shade = new View(c);
        shade.setAlpha(0);
        if (modal) shade.setOnClickListener(v -> show(false));
        addView(shade, new LayoutParams(-1, -1));
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setAlpha(0);
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(c);
        title.setText(name);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false));
        title.setTextColor(TXT);
        title.setLetterSpacing(0.04f);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        close = new IconBtn(c, "M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12l5.6 5.6-1.4 1.4-5.6-5.6L6.4 19 5 17.6 10.6 12 5 6.4z");
        close.setOnClickListener(v -> show(false));
        head.addView(close);
        card.addView(head);
        scroll = new ScrollView(c);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollbarFadingEnabled(false);
        body = new LinearLayout(c);
        body.setClipChildren(false); // 标签的序号角标挂在标签外，别被裁掉
        body.setClipToPadding(false);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        addView(card);
        setVisibility(INVISIBLE);
    }
    /** 填内容（打开时、尺寸变了时） */
    protected abstract void rows();
    /** 关上了（点了关闭键、点了面板外面、外面叫关的都算） */
    protected abstract void closed();
    boolean isOpen() { return open; }

    void show(boolean on) {
        if (on == open) return;
        open = on;
        card.animate().cancel(); shade.animate().cancel();
        if (on) {
            setVisibility(VISIBLE);
            applyTheme();
            rows();
            scroll.scrollTo(0, 0);
            boolean narrow = W < H;
            card.setPivotX(narrow ? card.getWidth() / 2f : card.getWidth()); card.setPivotY(narrow ? card.getHeight() : card.getHeight() / 2f);
            if (narrow) { card.setTranslationY(H * 0.3f); card.setRotationX(18); } else { card.setTranslationX(W * 0.3f); card.setRotationY(-28); }
            card.setCameraDistance(14 * rem * 8);
            card.animate().alpha(1).translationX(0).translationY(0).rotationY(0).rotationX(0).setDuration(550).setInterpolator(ease).start();
            if (modal) shade.animate().alpha(1).setDuration(400).start();
            for (int i = 0; i < body.getChildCount(); i++) { // 一行行依次浮上来
                View v = body.getChildAt(i);
                v.setAlpha(0); v.setTranslationX(0.6f * rem);
                v.animate().alpha(1).translationX(0).setStartDelay(Math.round(Math.min(i * 35, 600))).setDuration(500).setInterpolator(ease).start();
            }
        } else {
            boolean narrow = W < H;
            card.animate().alpha(0).translationX(narrow ? 0 : W * 0.3f).translationY(narrow ? H * 0.3f : 0).rotationY(narrow ? 0 : -28).rotationX(narrow ? 18 : 0)
                .setDuration(450).setInterpolator(ease).withEndAction(() -> { if (!open) setVisibility(INVISIBLE); }).start();
            shade.animate().alpha(0).setDuration(400).start();
            closed();
        }
    }
    /** 不是 modal 的：面板外面的手势交给下面（车况页） */
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (!modal && e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && !hit(card, e.getX(), e.getY())) return false;
        return super.dispatchTouchEvent(e);
    }
    private static boolean hit(View v, float x, float y) { return x >= v.getLeft() && x < v.getRight() && y >= v.getTop() && y < v.getBottom(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        W = Math.max(1, w); H = Math.max(1, h);
        rem = H / 10.52f;
        post(this::relayout);
    }
    protected int px(float r) { return Math.round(r * rem); }
    private void relayout() {
        boolean narrow = W < H;
        LayoutParams lp = narrow ? new LayoutParams(-1, Math.round(H * 0.64f), Gravity.BOTTOM) : new LayoutParams(Math.round(W * 0.46f), -1, Gravity.END);
        lp.setMargins(px(narrow ? 0.16f : 0.2f), px(0.2f), px(narrow ? 0.16f : 0.2f), px(narrow ? 0.16f : 0.2f));
        card.setLayoutParams(lp);
        card.setBackground(new AccentBg());
        card.setClipToOutline(true);
        card.setElevation(px(0.3f));
        shade.setBackground(new GradientDrawable(narrow ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.LEFT_RIGHT, new int[] { 0x0005070a, 0x0005070a, 0x8c05070a }));
        View head = card.getChildAt(0);
        head.setPadding(px(0.32f), px(0.26f), px(0.2f), px(0.04f));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.34f));
        close.setLayoutParams(new LinearLayout.LayoutParams(px(0.56f), px(0.56f)));
        body.setPadding(px(0.36f), 0, px(0.36f), px(0.3f));
        applyTheme();
        if (open) rows();
    }
    /** 卡片背景 + 顶上一道强调色细线（同 .set-card::before）。底色用 160° 斜向渐变（同 .set-card） */
    private final class AccentBg extends android.graphics.drawable.Drawable {
        final Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        AccentBg() {}
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            // 160° 渐变：从左上偏上到右下，70% 处到深色（网页版 linear-gradient(160deg, ...)）
            float cx = b.left + b.width() * 0.5f, cy = b.top + b.height() * 0.5f;
            double rad = Math.toRadians(160 - 90); // CSS 0°=向上，顺时针
            float dx = (float) Math.cos(rad), dy = (float) Math.sin(rad);
            float half = Math.max(b.width(), b.height()) * 0.75f;
            lp.setShader(new LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                    new int[] { CARD_TOP, CARD_BOT }, new float[] { 0f, 0.7f }, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(b), px(0.32f), px(0.32f), lp);
            lp.setShader(null);
            // 边框
            bp.setStyle(Paint.Style.STROKE);
            bp.setStrokeWidth(Math.max(1, Math.round(dens)));
            bp.setColor(CARD_BORDER);
            c.drawRoundRect(new RectF(b), px(0.32f), px(0.32f), bp);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
    /** 强调色（同网页版设置面板的 --acc）：固定蓝 #5b8cff，跟车机音乐卡片（JS 版）一致 */
    protected int acc() { return ACC; }
    /** 面板强调色（蓝） */
    static final int ACC = 0xff5b8cff;
    protected void accentChanged() { if (card.getBackground() != null) card.getBackground().invalidateSelf(); }
    /** 改标题（二级页跳转时把「设置」换成子页名） */
    protected void setTitleText(String t) { title.setText(t); }
    /** 二次确认弹层（危险操作：清空成就 / 恢复默认）：点 Amber 按钮后弹，确认才执行 */
    protected void confirm(String title, String msg, Runnable yes) {
        try {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(getContext());
            b.setTitle(title); b.setMessage(msg);
            b.setNegativeButton("取消", (d, w) -> {});
            b.setPositiveButton("确认", (d, w) -> yes.run());
            b.show();
        } catch (Exception e) { yes.run(); }
    }

    // ---------------- 公用的几种行 ----------------
    /** 一个分组：标题在卡片「外面」上方（小灰字，照参考图），下面一张中性灰卡片装行 */
    protected LinearLayout group(String t) {
        // 分组标题：卡片外、上方、小灰字
        TextView g = new TextView(getContext());
        g.setText(t);
        g.setTextColor(GROUP_TITLE);
        g.setTypeface(Typeface.create(Typeface.DEFAULT, 400, false));
        g.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.2f));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.leftMargin = 0;
        glp.topMargin = groupCards.isEmpty() ? px(0.2f) : px(0.34f);
        glp.bottomMargin = px(0.14f);
        body.addView(g, glp);
        // 中性灰卡片（比面板亮一档）
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cd = new GradientDrawable();
        cd.setCornerRadius(px(0.3f));
        cd.setColor(GROUP_BG);
        card.setBackground(cd);
        card.setPadding(px(0.22f), px(0.16f), px(0.22f), px(0.16f));
        LinearLayout rows = new LinearLayout(getContext());
        rows.setOrientation(LinearLayout.VERTICAL);
        card.addView(rows);
        body.addView(card);
        groupCards.add(card); groupRows.add(rows);
        rowsBox = rows;
        return card;
    }
    protected TextView note(String s) {
        TextView t = new TextView(getContext());
        t.setText(s); t.setTextColor(SUB); t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.19f)); t.setLineSpacing(0, 1.5f);
        t.setPadding(0, px(0.04f), 0, 0);
        return t;
    }
    /** 一行底下的细线（同 .set-row 的 border-bottom）；组内行内缩，对齐文字列 */
    protected final class BottomLine extends android.graphics.drawable.Drawable {
        final Paint p = new Paint();
        final int color; int padL, padR;
        BottomLine(int color) { this.color = color; }
        void setPads(int l, int r) { padL = l; padR = r; }
        @Override public void draw(Canvas c) { p.setColor(color); c.drawRect(getBounds().left + padL, getBounds().bottom - Math.max(1, dens), getBounds().right - padR, getBounds().bottom, p); }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
    protected static void repaint(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) repaint(((ViewGroup) v).getChildAt(i));
    }

    // ---------------- 两个面板共用的控件（设置、调试面板） ----------------
    /** 滑块拖完了（设置面板拿它重画跟着颜色变的字） */
    protected void afterDrag() {}
    /** 手指正按着某个滑块（这时别重画面板，不然滑块被换掉、拖不动） */
    protected boolean dragging;
    protected LinearLayout row(boolean col) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(col ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        r.setGravity(col ? Gravity.NO_GRAVITY : Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(0.16f), 0, px(0.16f));
        if (col) r.setTag(Boolean.TRUE); // 标记「标题 + 控件」行，构建完由 applySmartRows 按宽度决定横竖
        BottomLine bl = new BottomLine(LINE);
        if (rowsBox != null) bl.setPads(px(0.52f), 0); // 组内分割线内缩：左起对齐文字列，右随卡片内边距留边（照 iOS 参考图）
        r.setBackground(bl);
        (rowsBox != null ? rowsBox : body).addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }
    /** 能放开才横向单行、放不开保持竖向：按实测宽度重排「标题 + 控件」两件的行。
     *  在 rows() 构建完之后调用；3 件以上（含子选项、展开内容）一律保持竖向。 */
    protected void applySmartRows() {
        if (H <= 1 || rem <= 0) return; // onSizeChanged 之前 rem 还是默认 40，先别算
        boolean narrow = W < H;
        float cardW = narrow ? (W - 2f * px(0.16f)) : (W * 0.46f - 2f * px(0.2f));
        int avail = Math.round(cardW) - 2 * px(0.36f) - 2 * px(0.22f); // 扣掉 body 与分组卡片的左右内边距
        if (avail <= 0) return;
        int gap = px(0.12f);
        int reserve = px(0.4f); // 预留控件 margin、内边距与测量误差：宁可判竖向换行，也不让横向挤到重叠
        int uns = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED);
        for (LinearLayout rows : groupRows) {
            for (int i = 0; i < rows.getChildCount(); i++) {
                View v = rows.getChildAt(i);
                if (!Boolean.TRUE.equals(v.getTag()) || !(v instanceof LinearLayout)) continue;
                LinearLayout r = (LinearLayout) v;
                if (r.getChildCount() != 2) continue;
                View a = r.getChildAt(0), b = r.getChildAt(1);
                a.measure(uns, uns);
                b.measure(uns, uns);
                int bw = b.getMeasuredWidth();
                // 分段控件一律竖向；其余：控件测得出宽度、不超过行宽 60%（给标题留足空间）、总宽加余量后仍放得开
                boolean horiz = bw > 0 && !"seg".equals(b.getTag()) && bw <= avail * 0.6f
                        && a.getMeasuredWidth() + gap + bw + reserve <= avail;
                r.setOrientation(horiz ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                r.setGravity(horiz ? Gravity.CENTER_VERTICAL : Gravity.NO_GRAVITY);
                if (a.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) a.getLayoutParams();
                    lp.width = horiz ? 0 : LinearLayout.LayoutParams.MATCH_PARENT;
                    lp.weight = horiz ? 1f : 0f; // 拿剩余宽度，标题不会换行
                    a.setLayoutParams(lp);
                }
                // ★关键：row(true) 在 VERTICAL 下给子项默认 width=MATCH_PARENT；切成 HORIZONTAL 后
                // 控件若仍占满整行会把标题挤成 0 宽（文字消失、TextView 逐字换行把行高撑大）。
                // 固定成实测自然宽（EXACT），标题有确定空间，分段控件内部 weight 均分也正常
                if (b.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams lp2 = (LinearLayout.LayoutParams) b.getLayoutParams();
                    lp2.width = horiz ? bw : LinearLayout.LayoutParams.MATCH_PARENT;
                    lp2.weight = 0f;
                    b.setLayoutParams(lp2);
                }
            }
        }
    }
    protected LinearLayout hbox() {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(0, px(0.12f), 0, 0);
        return l;
    }
    /** 名字（.set-lb）+ 后面强调色的当前值（em）+ 下面的小字（small） */
    protected View label(String name, String em, String small) { return label(name, null, em, small, null); }
    protected View label(String name, String em, String small, Integer emColor) { return label(name, null, em, small, emColor); }
    protected View label(String name, String icon, String em, String small) { return label(name, icon, em, small, null); }
    protected View label(String name, String icon, String em, String small, Integer emColor) {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(getContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // 左侧线性图标（参考图特征）：按名称取图标路径，主题色 ICON
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(0.36f), px(0.36f));
        ilp.rightMargin = px(0.16f);
        String path = ICONS.get(name);
        if (path != null) {
            IconView iv = new IconView(getContext(), path);
            iv.fg = ICON;
            iv.setLayoutParams(ilp);
            top.addView(iv);
        } else {
            top.addView(new View(getContext()), ilp);
        }
        TextView n = new TextView(getContext());
        n.setText(name); n.setTextColor(TXT); n.setTypeface(Typeface.create(Typeface.DEFAULT, 400, false)); n.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.26f));
        top.addView(n);
        if (em != null && !em.isEmpty()) {
            TextView e = new TextView(getContext());
            e.setText(em); e.setTextColor(emColor != null ? emColor : SUB); e.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = px(0.14f);
            top.addView(e, lp);
        }
        // ⓘ 说明触发：紧跟标题文字右侧，点击就地展开 small（方案 A）。用自绘「字母 i」，不依赖字体字形，保证车机清晰可见
        final InfoIcon info = new InfoIcon(getContext());
        LinearLayout.LayoutParams infolp = new LinearLayout.LayoutParams(px(0.28f), px(0.28f));
        infolp.leftMargin = px(0.1f);
        info.setLayoutParams(infolp);
        top.addView(info);
        l.addView(top);
        if (small != null && !small.isEmpty()) {
            final TextView ex = new TextView(getContext());
            ex.setText(small);
            ex.setTextColor(SUB);
            ex.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.2f));
            ex.setLineSpacing(0, 1.4f);
            ex.setPadding(0, px(0.08f), px(0.1f), 0);
            ex.setVisibility(View.GONE);
            l.addView(ex);
            info.setOnClickListener(v -> {
                boolean open = ex.getVisibility() != View.VISIBLE;
                ex.setVisibility(open ? View.VISIBLE : View.GONE);
                info.setOpen(open);
            });
        } else {
            info.setVisibility(View.GONE);
        }
        return l;
    }
    protected View actBtn(String text, boolean pri, Runnable on) {
        TextView b = new TextView(getContext());
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
        b.setTextColor(pri ? 0xffffffff : TXT);
        b.setPadding(px(0.24f), px(0.12f), px(0.24f), px(0.12f)); // 上下高度统一，避免强调/非强调按钮并排时一高一矮
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(px(0.3f));
        // 同 .act：描边风（1px 边框 + 半透明底）；pri 用强调色实心、无边框
        if (pri) { d.setColor(acc()); d.setStroke(0, 0); }
        else { d.setColor(BTN_OFF_BG); d.setStroke(Math.max(1, Math.round(dens)), BTN_BORDER); }
        b.setBackground(d);
        b.setOnClickListener(v -> on.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(0.14f);
        b.setLayoutParams(lp);
        return b;
    }
    protected interface Pick { void on(Object v); }
    /** 分段按钮（.seg）：第 i 个值显示 names[i]，cur 是现在选着的（数字 1 和 1.0 算一样）；perRow > 0 时一行放这么多个、排几行 */
    protected View segOf(List<?> vals, String[] names, Object cur, int perRow, Pick pick) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(0.05f), px(0.05f), px(0.05f), px(0.05f));
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(px(0.3f)); bg.setColor(SEG_BG);
        box.setBackground(bg);
        int n = perRow > 0 ? perRow : vals.size();
        LinearLayout line = null;
        for (int i = 0; i < vals.size(); i++) {
            if (i % n == 0) { line = new LinearLayout(getContext()); box.addView(line, new LinearLayout.LayoutParams(-1, -2)); }
            final Object v = vals.get(i);
            boolean on = DebugHost.same(v, cur);
            TextView b = new TextView(getContext());
            b.setText(names[i]); b.setGravity(Gravity.CENTER); b.setSingleLine(true);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.24f));
            // 文字自动缩放兜底：名字过长时在格子内缩小，避免裁切/重叠（minSdk 28 支持）
            b.setAutoSizeTextTypeUniformWithConfiguration(px(0.16f), px(0.24f), px(0.01f), TypedValue.COMPLEX_UNIT_PX);
            b.setTextColor(on ? SEG_ON_TXT : SEG_OFF_TXT);
            b.setPadding(0, px(0.08f), 0, px(0.08f));
            if (on) {
                // 选中态统一：12% 蓝底 + 蓝描边（与标签选中态一致）
                GradientDrawable d = new GradientDrawable();
                d.setCornerRadius(px(0.26f));
                d.setColor(SEG_ON_BG);
                d.setStroke(Math.max(1, Math.round(dens)), acc());
                b.setBackground(d);
                b.setElevation(px(0.02f));
            }
            b.setOnClickListener(x -> pick.on(v));
            line.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        }
        if (perRow > 0 && line != null) for (int i = line.getChildCount(); i < n; i++) line.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(0.14f);
        box.setLayoutParams(lp);
        box.setTag("seg"); // 分段项内部按 weight 均分，横向被压缩会挤到重叠，自适应时一律竖向
        return box;
    }
    /** 开关（.sw）：轨道 .78×.44rem，滑块 .34rem 白圆（带阴影），开时轨道走强调色、滑块右移 .34rem */
    protected final class Switch extends View {
        final boolean on; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Switch(Context c, boolean on, Runnable click) { super(c); this.on = on; setOnClickListener(v -> click.run()); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setColor(on ? acc() : SW_OFF);
            c.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
            float d = 0.34f * rem, x = 0.05f * rem + (on ? 0.34f * rem : 0);
            // 滑块投影（同 .sw i 的 box-shadow）
            p.setShadowLayer(px(0.06f), 0, px(0.02f), 0x66000000);
            p.setColor(0x00000000); c.drawCircle(x + d / 2, h / 2, d / 2, p);
            p.clearShadowLayer();
            p.setColor(0xffffffff); c.drawCircle(x + d / 2, h / 2, d / 2, p);
        }
    }
    protected interface Fmt { String of(float v); }
    protected interface OnVal { void on(float v); }
    protected final class Slider extends LinearLayout {
        final TextView val; final Track track; final Fmt fmt;
        Slider(Context c, String name, float min, float max, float step, float v, Fmt fmt, OnVal on, int[] grad) {
            super(c);
            this.fmt = fmt;
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, px(0.1f), 0, 0);
            // 图标槽：与 label 行统一，保证名字列左边缘对齐
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(0.36f), px(0.36f));
            ilp.rightMargin = px(0.16f);
            String sp = ICONS.get(name);
            if (sp != null) {
                IconView iv = new IconView(c, sp);
                iv.fg = ICON;
                iv.setLayoutParams(ilp);
                addView(iv);
            } else {
                addView(new View(c), ilp);
            }
            TextView n = new TextView(c);
            n.setText(name); n.setTextColor(TXT); n.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
            addView(n, new LayoutParams(px(0.9f), -2));
            track = new Track(c, min, max, step, v, on, grad);
            LayoutParams tp = new LayoutParams(0, px(0.44f), 1);
            tp.leftMargin = px(0.1f); tp.rightMargin = px(0.1f);
            addView(track, tp);
            val = new TextView(c);
            val.setText(fmt.of(v)); val.setTextColor(SUB); val.setTypeface(Typeface.create(Typeface.DEFAULT, 400, false)); val.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
            val.setGravity(Gravity.END);
            addView(val, new LayoutParams(px(1f), -2));
            track.label = val; track.fmt = fmt;
        }
    }
    /** 滑块的轨道：竖着划交给面板滚动，横着拖才改值（从按下时的值跟着手指挪，不跳），点一下不改 */
    protected final class Track extends View {
        final float min, max, step; float v; final OnVal on; final int[] grad;
        TextView label; Fmt fmt;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float x0, y0, v0; int mode;
        Track(Context c, float min, float max, float step, float v, OnVal on, int[] grad) { super(c); this.min = min; this.max = max; this.step = step; this.v = v; this.on = on; this.grad = grad; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), th = 0.06f * rem, k = (v - min) / (max - min), r = 0.17f * rem, x = r + (w - 2 * r) * k;
            if (grad != null) { p.setShader(new LinearGradient(r, 0, w - r, 0, grad, null, Shader.TileMode.CLAMP)); c.drawRoundRect(r, h / 2 - th, w - r, h / 2 + th, th, th, p); p.setShader(null); }
            else {
                p.setColor(TRACK_OFF); c.drawRoundRect(r, h / 2 - th / 2, w - r, h / 2 + th / 2, th / 2, th / 2, p);
                p.setColor(acc()); c.drawRoundRect(r, h / 2 - th / 2, x, h / 2 + th / 2, th / 2, th / 2, p);
            }
            p.setColor(acc()); c.drawCircle(x, h / 2, r + 0.04f * rem, p);
            // 拇指投影（同网页版 box-shadow）：先画一层带阴影的空心圆，再压上白色圆心
            p.setShadowLayer(px(0.08f), 0, px(0.02f), 0x66000000);
            p.setColor(0x00000000); c.drawCircle(x, h / 2, r, p);
            p.clearShadowLayer();
            p.setColor(0xffffffff); c.drawCircle(x, h / 2, r, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: x0 = e.getRawX(); y0 = e.getRawY(); v0 = v; mode = 0; dragging = true; getParent().requestDisallowInterceptTouchEvent(true); return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                    if (mode == 0 && (Math.abs(dx) > 8 * dens || Math.abs(dy) > 8 * dens)) {
                        mode = Math.abs(dx) > Math.abs(dy) ? 1 : 2;
                        if (mode == 2) { dragging = false; getParent().requestDisallowInterceptTouchEvent(false); scroll.scrollBy(0, Math.round(-dy)); return false; }
                    }
                    if (mode == 1) {
                        float w = Math.max(1, getWidth() - 0.34f * rem), nv = v0 + dx / w * (max - min);
                        nv = Math.max(min, Math.min(max, Math.round((nv - min) / step) * step + min));
                        if (nv != v) { v = nv; if (label != null) label.setText(fmt.of(v)); invalidate(); on.on(v); }
                    }
                    return true;
                }
                default: {
                    getParent().requestDisallowInterceptTouchEvent(false);
                    dragging = false;
                    if (mode == 1 && v != v0) post(Sheet.this::afterDrag); // 拖完：强调色这类跟着颜色变的字重画一遍
                    return true;
                }
            }
        }
    }

    /** 自动换行的一排（标签、分组名；分组名独占一行）。子 View 的 LayoutParams 右边距/下边距按 .tag 的 margin 生效 */
    protected final class Flow extends ViewGroup {
        Flow(Context c) { super(c); setClipChildren(false); setClipToPadding(false); }
        /** 子 View 的额外间距：右边距 + 下边距（负边距用来做角标溢出，这里按正边距算） */
        private int[] gaps(View v) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            int r = 0, b = 0;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                r = ((ViewGroup.MarginLayoutParams) lp).rightMargin;
                b = ((ViewGroup.MarginLayoutParams) lp).bottomMargin;
            }
            return new int[] { Math.max(0, r), Math.max(0, b) };
        }
        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), x = 0, y = 0, lh = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                // 手动 measure 会绕开子 View 的 LayoutParams：无文字的色块（只有背景色）内在尺寸是 0，
                // 不按 LayoutParams 给固定宽高的话量出来 0×0，整排看不见。所以固定尺寸的子项按 EXACTLY 量。
                int cw = -1, ch = -1;
                ViewGroup.LayoutParams vlp = v.getLayoutParams();
                if (vlp instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) vlp;
                    if (llp.width > 0) cw = llp.width;
                    if (llp.height > 0) ch = llp.height;
                }
                v.measure(
                    cw > 0 ? MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY) : MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
                    ch > 0 ? MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY) : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int[] g = gaps(v);
                boolean br = "break".equals(v.getTag());
                if (br || x + v.getMeasuredWidth() > w) { if (x > 0) { y += lh; x = 0; lh = 0; } }
                x += v.getMeasuredWidth() + g[0]; lh = Math.max(lh, v.getMeasuredHeight() + g[1]);
                if (br) { y += lh; x = 0; lh = 0; }
            }
            setMeasuredDimension(w, y + lh);
        }
        @Override protected void onLayout(boolean ch, int l, int t, int r, int b) {
            int w = r - l, x = 0, y = 0, lh = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                int[] g = gaps(v);
                boolean br = "break".equals(v.getTag());
                if (br || x + v.getMeasuredWidth() > w) { if (x > 0) { y += lh; x = 0; lh = 0; } }
                v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
                x += v.getMeasuredWidth() + g[0]; lh = Math.max(lh, v.getMeasuredHeight() + g[1]);
                if (br) { y += lh; x = 0; lh = 0; }
            }
        }
    }
    /** 标签（.tag）：描边风（1px 边框 + 透明底），选中的边框和底色走强调色；n>0 时右上角挂一个圆形序号角标（同 .tag.on::after） */
    protected View tag(String text, boolean on, int n, Runnable onClick) {
        TagBtn b = new TagBtn(getContext(), text, on, n);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }
    /** 圆角为半径的圆底（角标、圆点用） */
    private static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }
    /** 标签控件：内容一个 TextView（描边风），右上角可挂圆形序号角标 */
    protected final class TagBtn extends FrameLayout {
        final TextView text;
        TagBtn(Context c, String s, boolean on, int n) {
            super(c);
            setClipChildren(false); // 角标是负边距挂在标签外右上角，别被裁掉
            setClipToPadding(false);
            text = new TextView(c);
            text.setText(s);
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
            text.setTextColor(on ? SEG_ON_TXT : SUB);
            text.setPadding(px(0.22f), px(0.08f), px(0.22f), px(0.08f));
            text.setGravity(Gravity.CENTER);
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(px(0.3f));
            d.setColor(on ? SEG_ON_BG : BTN_OFF_BG);
            d.setStroke(Math.max(1, Math.round(dens)), on ? acc() : BTN_BORDER);
            text.setBackground(d);
            addView(text, new LayoutParams(-2, -2));
            if (n > 0) { // 序号角标：右上角一个强调色小圆（.tag.on::after）
                TextView badge = new TextView(getContext());
                badge.setText(String.valueOf(n));
                badge.setTextColor(0xffffffff);
                badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.18f));
                badge.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false));
                badge.setGravity(Gravity.CENTER);
                badge.setBackground(circle(acc()));
                int bs = px(0.3f);
                LayoutParams blp = new LayoutParams(bs, bs);
                blp.gravity = Gravity.TOP | Gravity.END;
                blp.topMargin = -px(0.12f); blp.rightMargin = -px(0.1f);
                addView(badge, blp);
            }
        }
        /** 当前是不是选中的（改了要重画） */
        void setOn(boolean on) {
            text.setTextColor(on ? SEG_ON_TXT : SUB);
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(px(0.3f));
            d.setColor(on ? SEG_ON_BG : BTN_OFF_BG);
            d.setStroke(Math.max(1, Math.round(dens)), on ? acc() : BTN_BORDER);
            text.setBackground(d);
        }
    }
    /** 说明触发图标：细线圆圈 + 实心 i（i 默认字重、不描粗），矢量绘制、比例正确，干净不拼凑 */
    private final class InfoIcon extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean open;
        final android.graphics.Path I = SvgPath.of("M11,7H13V9H11V7M11,11H13V17H11V11");
        InfoIcon(Context c) { super(c); setClickable(true); }
        void setOpen(boolean o) { open = o; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float pad = w * 0.08f, avail = w - pad * 2;
            float scale = avail / 20f;
            float cx = w / 2f, cy = h / 2f;
            p.setColor(open ? ACC : INFO);
            // 圆圈：细描边（不再用粗填充环）
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.6f * scale);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawCircle(cx, cy, 10f * scale, p);
            // i：实心点 + 竖，保持默认字重不变粗
            android.graphics.Path q = new android.graphics.Path();
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(scale, scale);
            m.postTranslate(cx - 12f * scale, cy - 12f * scale);
            I.transform(m, q);
            p.setStyle(Paint.Style.FILL);
            c.drawPath(q, p);
        }
    }
    /** 圆形图标键（关闭）：路径同网页版的 svg */
    static final class IconBtn extends View {
        final String d; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); int bg = 0x14ffffff, fg = 0xffeef1f6;
        IconBtn(Context c, String d) { super(c); this.d = d; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setColor(bg); c.drawCircle(w / 2, h / 2, Math.min(w, h) / 2, p);
            float pad = w * 0.25f, sz = w - pad * 2;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(sz / 24, sz / 24); m.postTranslate(pad, pad);
            android.graphics.Path q = new android.graphics.Path(); SvgPath.of(d).transform(m, q);
            p.setColor(fg); c.drawPath(q, p);
        }
    }
    /** 左侧线性图标（参考图）：按路径画描边图标，主题色 ICON（不画背景圆，区别于关闭键 IconBtn）。按包围盒居中缩放，保证所有图标视觉重心一致、与文字对齐 */
    static final class IconView extends View {
        final String d; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); int fg = 0xffcfcabf;
        IconView(Context c, String d) { super(c); this.d = d; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            android.graphics.Path raw = new android.graphics.Path(); SvgPath.of(d).transform(new android.graphics.Matrix(), raw);
            android.graphics.RectF b = new android.graphics.RectF();
            raw.computeBounds(b, true);
            if (b.isEmpty()) return;
            float pad = w * 0.16f, avail = w - pad * 2;
            // 按包围盒较长边统一缩放（描边宽度不计入包围盒，故单线图标也不会消失），再居中
            float scale = avail / Math.max(b.width(), b.height());
            float cx = w / 2f, cy = h / 2f;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(scale, scale);
            m.postTranslate(cx - b.centerX() * scale, cy - b.centerY() * scale);
            android.graphics.Path q = new android.graphics.Path(); raw.transform(m, q);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f * scale);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(fg);
            c.drawPath(q, p);
        }
    }
    /** 设置项名称 → 线性图标路径（24×24 描边，照参考图的左侧图标；动态名如车模/车牌不含，自动无图标） */
    static final Map<String,String> ICONS = new HashMap<>();
    static {
        ICONS.put("\u538b\u6697", "M12 3a9 9 0 1 0 0 18c1 0 1.4-.8 1.4-1.5 0-.4-.2-.7-.4-1-.3-.3-.4-.6-.4-1 0-.8.8-1.5 1.8-1.5H15a6 6 0 0 0 6-6c0-3.3-4-6-9-6z");
        ICONS.put("\u6c34\u5e73\u89d2\u5ea6", "M12 4v4M12 4l-3 3M12 4l3 3M20 12h-4M20 12l-3-3M20 12l-3 3M4 12h4M4 12l3-3M4 12l3 3M12 20v-4M12 20l-3-3M12 20l3-3");
        ICONS.put("\u4fef\u89c6\u89d2\u5ea6", "M12 5a7 7 0 1 1-6 4M6 9V5M6 9h4");
        ICONS.put("\u8f66\u7684\u5927\u5c0f", "M4 14l5-5M4 8v6h6M20 10l-5 5M20 16v-6h-6");
        ICONS.put("\u89c6\u91ce", "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6 3 3 0 1 0 0-6z");
        ICONS.put("主题模式", "M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8z");
        ICONS.put("24 小时制", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2");
        ICONS.put("显示秒", "M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16zM12 8v4l3 2M9 1h6");
        ICONS.put("窄屏自动隐藏秒", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2");
        ICONS.put("全屏时收起状态栏", "M4 9V5a1 1 0 0 1 1-1h4M20 9V5a1 1 0 0 0-1-1h-4M4 15v4a1 1 0 0 0 1 1h4M20 15v4a1 1 0 0 1-1 1h-4");
        ICONS.put("背景风格", "M3 5a1 1 0 0 1 1-1h16a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1zM3 16l5-5 4 4 3-3 6 6");
        ICONS.put("背景模式", "M3 5a1 1 0 0 1 1-1h16a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1zM3 16l5-5 4 4 3-3 6 6");
        ICONS.put("背景色", "M12 3a9 9 0 1 0 0 18c1 0 1.4-.8 1.4-1.5 0-.4-.2-.7-.4-1-.3-.3-.4-.6-.4-1 0-.8.8-1.5 1.8-1.5H15a6 6 0 0 0 6-6c0-3.3-4-6-9-6z");
        ICONS.put("封面风格", "M3 5a1 1 0 0 1 1-1h16a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1zM3 16l5-5 4 4 3-3 6 6");
        ICONS.put("车况图标", "M12 14l4-4M3 18a9 9 0 1 1 18 0");
        ICONS.put("底部数据", "M4 6h16M4 12h16M4 18h16");
        ICONS.put("竖屏歌词", "M5 7h14M5 12h14M5 17h9");
        ICONS.put("宽屏歌词", "M5 7h14M5 12h14M5 17h9");
        ICONS.put("3D 车模设置", "M4 13l2-5h12l2 5v4H4zM8 17m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0M16 17m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0");
        ICONS.put("成就墙", "M8 4h8v4a4 4 0 0 1-8 0zM8 6H5v2a3 3 0 0 0 3 3M16 6h3v2a3 3 0 0 1-3 3M12 12v4M9 20h6M10 16h4v4h-4z");
        ICONS.put("调试面板", "M4 7h10M18 7h2M4 12h2M10 12h10M4 17h7M15 17h5M14 5a2 2 0 1 0 0 4 2 2 0 1 0 0-4zM8 10a2 2 0 1 0 0 4 2 2 0 1 0 0-4zM12 15a2 2 0 1 0 0 4 2 2 0 1 0 0-4z");
        ICONS.put("当前版本", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v5M12 8h.01");
        ICONS.put("测试版更新", "M5 15c-1 1-1 4-1 4s3 0 4-1M14 4c3 0 5 2 5 5 0 4-4 8-9 9l-4-4c1-5 5-9 9-9zM14.5 9.5a1.5 1.5 0 1 0 .01 0");
        ICONS.put("车漆质感", "M12 3c3 4 6 7 6 10a6 6 0 0 1-12 0c0-3 3-6 6-10z");
        ICONS.put("默认视角", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 8v8M8 12h8");
        ICONS.put("挂 D 挡后的视角", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 8v8M8 12h8");
        ICONS.put("画质", "M4 18V9M10 18V5M16 18v-6M20 18v-9");
        ICONS.put("帧率", "M4 5h16v14H4zM4 9h16M4 15h16M9 5v14M15 5v14");
        ICONS.put("胎压标签", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 12m-3 0a3 3 0 1 0 6 0a3 3 0 1 0-6 0");
        ICONS.put("进场动画", "M5 4l14 8-14 8z");
        ICONS.put("抗锯齿", "M4 17l4-4 3 3 5-7 4 5");
        ICONS.put("车漆", "M12 3c3 4 6 7 6 10a6 6 0 0 1-12 0c0-3 3-6 6-10z");
        ICONS.put("轮毂", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 12m-3 0a3 3 0 1 0 6 0a3 3 0 1 0-6 0");
        ICONS.put("刹车卡钳", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 8v4M12 16h.01");
    }
}
