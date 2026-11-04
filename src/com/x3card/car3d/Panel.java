package com.ljone.car3d;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
import java.util.List;
import java.util.Map;

/**
 * 设置面板（照网页版 index.html 的 #set 和 style.css 的 .set-card：深色卡片、顶上一道强调色细线、一行行分组），去掉唱片机、调整车模、贴纸、调试面板、新手引导那几项。
 * 车漆 / 轮毂 / 卡钳是带高光的小球，最后一个"自定义"展开色相 / 饱和度 / 亮度三个滑块；强调色跟着车漆。
 * 滑块自己接触摸：竖着划是翻面板，横着拖才改值（同网页版 main.js 里那段）。点面板外面关掉
 */
@SuppressLint("ViewConstructor")
final class Panel extends Sheet {
    interface Act { void act(String name); void closed(); }

    private final Act act;
    private boolean fsRow;
    private String modelName = "", modelCredit = "";
    private boolean resetOff = true;
    private String[] tips = { "", "", "" };
    private String plateState = "";
    /** 设置页当前层级："main" 主页 / "model" 3D 车模设置二级页 */
    private String page = "main";
    /** 底部数据设置是否展开（默认收起，只显示已选项名称） */
    private boolean statsExpanded;
    /** 车况图标设置是否展开（默认收起，只显示已开启项数） */
    private boolean tellsExpanded;

    Panel(Context c, Settings s, Act act) {
        super(c, s, "设置", true);
        this.act = act;
    }
    @Override protected void closed() { page = "main"; act.closed(); }
    void setFs(boolean on) { fsRow = on; if (open) rows(); }
    /** 调试宿主（没有车机时）：最下面一行打开调试面板 */
    void setDebug(boolean on) { dbgRow = on; if (open) rows(); }
    private boolean dbgRow;
    void setModel(String name, String credit, boolean off, String[] t) { modelName = name; modelCredit = credit; resetOff = off; tips = t; if (open) rows(); }
    /** 车牌那一行的状态（"后 · 粤B·12345"、"没挂"） */
    void setPlates(String s) { if (s.equals(plateState)) return; plateState = s; refresh(""); }
    /** 设置在别处改了：面板开着就重画（保持滚动位置）。拖滑块改的（视角、压暗、自定义颜色）不重画（正拖着的滑块会被换掉），只让各处按新值重画一遍 */
    void refresh(String k) {
        if (!open) return;
        if (Settings.NUM.containsKey(k) || Settings.HEX.containsKey(k)) { repaint(body); accentChanged(); return; }
        int y = scroll.getScrollY();
        rows();
        scroll.post(() -> scroll.scrollTo(0, y));
    }


    // ================= 面板里的一行行 =================
    @Override protected void afterDrag() { refresh(""); }
    @Override protected void rows() {
        body.removeAllViews();
        resetGroups();
        accentChanged();
        if ("model".equals(page)) { rowsModel(); } else { rowsMain(); applySmartRows(); }
    }
    /** 主设置页分组（从上到下）：顶栏 → 车模页 → 音乐页 → 成就 → 调试 → 关于。3D 车模设置并入车模页，细节在二级页 */
    private void rowsMain() {
        setTitleText("设置");
        // 顶栏：主题模式（全局主题）+ 时钟格式 + 全屏收起，全部作用在顶部栏
        group("顶栏");
        LinearLayout r = row(true);
        r.addView(label("主题模式", null, "全局主题，影响整张卡片的明暗"));
        r.addView(seg("theme", Settings.THEMES, new String[] { "跟随车机", "浅色", "深色" }, 0));
        swRow("hour24", "24 小时制", "关闭则显示 12 小时制（带 AM/PM）");
        swRow("showSec", "显示秒", "时间分钟后显示秒数");
        if (S.bool("showSec")) swRow("secHideNarrow", "窄屏自动隐藏秒", "竖屏时隐藏秒，避免顶栏拥挤");
        if (fsRow) swRow("fsBar", "全屏时收起状态栏", "只在全屏页生效；每次进全屏先显示，这里开着会自动再收起");
        // 车模页：背景、图标、底部数据、3D 车模设置
        group("车模页");
        r = row(true);
        r.addView(label("背景风格", null, "只影响车况显示"));
        r.addView(seg("bg", Settings.BGS, new String[] { "影棚", "暖调", "冷调", "图片" }, 0));
        if ("image".equals(S.str("bg"))) {
            LinearLayout a2 = hbox();
            a2.addView(actBtn("换一张图片", false, () -> act.act("bgImage")));
            r.addView(a2);
            r.addView(slider("bgDim", "压暗", v -> Math.round(v * 100) + "%"));
        }
        // 车况图标：默认收起，点下拉图标才展开全部图标开关；收起时只显示已开启项数
        {
            LinearLayout tr = row(true);
            LinearLayout hdr = new LinearLayout(getContext());
            hdr.setOrientation(LinearLayout.HORIZONTAL);
            hdr.setGravity(Gravity.CENTER_VERTICAL);
            hdr.addView(label("车况图标", null, "选想在车况页看到的图标，只影响车况显示"), new LinearLayout.LayoutParams(0, -2, 1));
            hdr.addView(chevron(tellsExpanded));
            hdr.setOnClickListener(v -> { tellsExpanded = !tellsExpanded; rows(); });
            tr.addView(hdr);
            if (tellsExpanded) {
                tr.addView(tells());
            } else {
                TextView sum = new TextView(getContext());
                int n = S.tells().size();
                sum.setText(n > 0 ? "已开启 " + n + " 项" : "未开启");
                sum.setTextColor(SUB); sum.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
                sum.setPadding(0, px(0.08f), 0, 0);
                tr.addView(sum);
            }
        }
        // 底部数据：默认收起，点下拉图标才展开全部数据项；收起时只显示已选数据项名称（按显示顺序）
        {
            LinearLayout br = row(true);
            LinearLayout hdr = new LinearLayout(getContext());
            hdr.setOrientation(LinearLayout.HORIZONTAL);
            hdr.setGravity(Gravity.CENTER_VERTICAL);
            hdr.addView(label("底部数据", null, "选 1～6 项，按点选顺序排，只影响车况显示"), new LinearLayout.LayoutParams(0, -2, 1));
            hdr.addView(chevron(statsExpanded));
            hdr.setOnClickListener(v -> { statsExpanded = !statsExpanded; rows(); });
            br.addView(hdr);
            if (statsExpanded) {
                br.addView(tags());
            } else {
                TextView sum = new TextView(getContext());
                StringBuilder sb = new StringBuilder();
                for (String id : S.stats()) { String[] s = CarStat.byId(id); if (s != null) { if (sb.length() > 0) sb.append(" · "); sb.append(s[1]); } }
                sum.setText(sb.length() > 0 ? sb.toString() : "未选择");
                sum.setTextColor(SUB); sum.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
                sum.setPadding(0, px(0.08f), 0, 0);
                br.addView(sum);
            }
        }
        // 3D 车模设置：与车模页其它项同组
        r = row(true);
        r.addView(label("3D 车模设置", null, "车模、车漆、轮毂、卡钳、车况页视角都在这"));
        LinearLayout acts = hbox();
        acts.addView(actBtn("进入 ›", true, () -> { page = "model"; rows(); }));
        r.addView(acts);
        // 音乐页：背景 + 歌词
        group("音乐页");
        r = row(true);
        r.addView(label("背景模式", null, "深浅模式 / 固定色 / 跟随封面，只影响音乐卡片"));
        r.addView(segOf(Settings.MBG_MODE, Settings.MBG_MODE_NAMES, S.str("mBgMode"), 0, v -> { S.set("mBgMode", (String) v); refresh(""); }));
        if ("fixed".equals(S.str("mBgMode"))) {
            r.addView(subLabel("背景色", "选一个固定底色"));
            r.addView(colorRow("mBgColor", Settings.MBG_COLORS));
        }
        if ("cover".equals(S.str("mBgMode"))) {
            // 与「固定色」子项一致：都作为背景模式这一行内的子项，不另起独立行
            r.addView(subLabel("封面风格", "跟随当前歌曲封面的配色"));
            View st = segOf(Settings.MBG_COVER, Settings.MBG_COVER_NAMES, S.str("mBgCover"), 3, v -> S.set("mBgCover", (String) v));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.topMargin = px(0.08f); slp.leftMargin = px(0.08f); // 同 colorRow 的外边距
            st.setLayoutParams(slp);
            r.addView(st);
        }
        // 歌词（对齐「背景模式」式排布：竖屏 / 宽屏各占一条竖排，标题一行、提示词一行、行数 / 字号步进器一行）
        r = row(true);
        r.addView(label("竖屏歌词", null, "行数 1～15 · 字号 14～52，越大字越大"));
        r.addView(lyricPair(Settings.LYR_ROWS, "lyricLinesP", Settings.LYR_SIZE, "lyricSizeP"));
        r = row(true);
        r.addView(label("宽屏歌词", null, "全屏 / 七分屏用这一组"));
        r.addView(lyricPair(Settings.LYR_ROWS, "lyricLinesL", Settings.LYR_SIZE, "lyricSizeL"));
        // 成就
        group("成就");
        r = row(true);
        r.addView(label("成就墙", null, "按里程、行程、充电、足迹等解锁勋章"));
        LinearLayout a6 = hbox();
        a6.addView(actBtn("打开成就墙", true, () -> act.act("achOpen")));
        a6.addView(actBtnAmber("清空成就", () -> confirm("清空成就", "确定清空所有成就记录？此操作不可撤销。", () -> act.act("achReset"))));
        r.addView(a6);
        if (dbgRow) {
            group("调试");
            r = row(true);
            r.addView(label("调试面板", null, "没有车机时才有：可模拟车况、音乐等数据"));
            LinearLayout a5 = hbox();
            a5.addView(actBtn("打开调试面板", false, () -> act.act("debug")));
            r.addView(a5);
        }
        group("关于");
        r = row(true);
        r.addView(label("当前版本", appVersion(), "点一下检查更新", acc()));
        r.setClickable(true);
        r.setOnClickListener(v -> act.act("checkUpdate"));
        swRow("testBuild", "测试版更新", "打开后才会接收测试版更新（默认只收正式版）");
    }
    /** 3D 车模设置二级页：车模、车漆、轮毂、卡钳、车况页视角 */
    private void rowsModel() {
        setTitleText("3D 车模设置");
        LinearLayout back = row(false);
        back.addView(actBtn("‹ 返回", false, () -> { page = "main"; rows(); }));
        group("车模");
        LinearLayout r = row(true);
        r.addView(label("当前车模", modelName, modelCredit));
        LinearLayout acts = hbox();
        acts.addView(actBtn("导入车模包", true, () -> act.act("import")));
        View rs = actBtnAmber("恢复默认", () -> confirm("恢复默认", "确定恢复车模默认设置？", () -> act.act("modelReset")));
        rs.setEnabled(!resetOff); rs.setAlpha(resetOff ? 0.35f : 1);
        acts.addView(rs);
        r.addView(acts);
        r.addView(note("车模在网页版的「调整车模」里调好（车门、车窗、灯、贴纸都在那里调），点「导出为 GLB」得到一个 .glb 文件，拷到车机上再在这里导入。"));
        r.addView(note("网页版用电脑浏览器打开：" + CarModel.WEB));
        r.addView(note("打不开时（GitHub 在国内有时连不上）：换个网络或过一会儿再试；也可以把网页版下载下来（github.com/Cat-bl/car3d-web 页面上的 Code → Download ZIP，或者找能打开的人要一份），解压后用电脑浏览器打开里面的 index.html，用法一样。"));
        r = row(true);
        r.addView(label("车牌", plateState, "装饰项，跟车况不联动；位置按车模分开记"));
        LinearLayout ap = hbox();
        ap.addView(actBtn("调整车牌", false, () -> act.act("plates")));
        r.addView(ap);
        group("外观");
        balls("paint", "车漆", tips[0]);
        r = row(true);
        r.addView(label("车漆质感", null, null));
        r.addView(seg("finish", Settings.FINISHES, Settings.FINISH_NAMES, 5));
        balls("rim", "轮毂", tips[1]);
        balls("caliper", "刹车卡钳", tips[2]);
        group("视角");
        r = row(true);
        LinearLayout sub = new LinearLayout(getContext());
        sub.setGravity(Gravity.TOP);
        sub.addView(label("默认视角", null, "拖动可预览；挂 D 挡视角单独设"), new LinearLayout.LayoutParams(0, -2, 1));
        sub.addView(smallBtn("恢复默认", () -> { for (String k : Settings.VIEW) S.set(k, Settings.NUM.get(k)[3]); }));
        r.addView(sub);
        r.addView(slider("viewAz", "水平角度", v -> Math.round(v * 180 / Math.PI) + "°"));
        r.addView(slider("viewEl", "俯视角", v -> Math.round(v * 180 / Math.PI) + "°"));
        r.addView(slider("viewZoom", "车的大小", v -> Math.round(v * 100) + "%"));
        r.addView(slider("viewFov", "视野", v -> Math.round(v) + "°"));
        LinearLayout a3 = hbox();
        a3.addView(actBtn("把打开设置前的角度设为默认", false, () -> act.act("viewSave")));
        r.addView(a3);
        r = row(true);
        LinearLayout dsub = new LinearLayout(getContext());
        dsub.setGravity(Gravity.TOP);
        dsub.addView(label("挂 D 挡后的视角", null, "挂 D 挡时镜头转到此角度，拖动可预览"), new LinearLayout.LayoutParams(0, -2, 1));
        dsub.addView(smallBtn("恢复默认", () -> { for (String k : Settings.DVIEW) S.set(k, Settings.NUM.get(k)[3]); }));
        r.addView(dsub);
        r.addView(slider("dAz", "水平角度", v -> Math.round(v * 180 / Math.PI) + "°"));
        r.addView(slider("dEl", "俯视角", v -> Math.round(v * 180 / Math.PI) + "°"));
        r.addView(slider("dZoom", "车的大小", v -> Math.round(v * 100) + "%"));
        LinearLayout a4 = hbox();
        a4.addView(actBtn("把打开设置前的角度设为 D 挡视角", false, () -> act.act("dSave")));
        r.addView(a4);
        group("显示");
        swRow("labels", "胎压标签", "轮子旁边的胎压");
        swRow("intro", "进场动画", "镜头转进来、迎宾灯");
        group("性能");
        r = row(true);
        r.addView(label("画质", null, S.fx().note));
        r.addView(seg("quality", new ArrayList<>(Settings.QUAL.keySet()), new String[] { "低", "标准", "高", "极致" }, 0));
        r = row(true);
        r.addView(label("帧率", null, "转动、开门等动画时的每秒帧数"));
        r.addView(seg("fps", Settings.FPS, new String[] { "30 帧", "60 帧（流畅）" }, 0));
        swRow("aa", "抗锯齿", "车和字的边缘更平滑，更费显卡；切换后画面重建一次");
    }

    /** 运行期从 PackageManager 读 versionName（格式 Vx.x.x），versionCode 单给小字用 */
    private String appVersion() {
        try {
            Context c = getContext();
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            String v = pi.versionName;
            if (v == null || v.isEmpty()) v = "—";
            return "V" + v + (S.bool("testBuild") ? "（测试版）" : "");
        } catch (Exception e) { return "V—"; }
    }
    /** 给颜色加透明度（同 Hud.withA） */
    private static int withAlpha(int c, float a) { return (Math.round((c >>> 24) * a) << 24) | (c & 0xffffff); }
    /** 车漆的强调色：小球光晕用，跟车漆走（同网页版 --acc 跟车漆），跟设置页主题色无关 */
    private int paintAcc() {
        String a = S.pick("paint").acc;
        try { return Color.parseColor(a != null ? a : "#ff4b55"); } catch (Exception e) { return 0xffff4b55; }
    }
    /** 标题右边的小描边键（.set-mini，"恢复默认"）：危险操作，改用琥珀色 */    private View smallBtn(String text, Runnable on) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(0xfff5a623);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.2f));
        t.setPadding(px(0.16f), px(0.12f), px(0.16f), px(0.12f));
        GradientDrawable rb = new GradientDrawable(); rb.setCornerRadius(px(0.2f)); rb.setColor(0x1ff5a623); rb.setStroke(Math.max(1, Math.round(dens)), 0x80f5a623);
        t.setBackground(rb);
        t.setOnClickListener(v -> on.run());
        return t;
    }
    /** 琥珀色按钮（危险操作：清空成就 / 恢复默认），点一下先弹二次确认 */
    private View actBtnAmber(String text, Runnable on) {
        TextView b = (TextView) actBtn(text, false, on);
        b.setTextColor(0xfff5a623);
        Drawable bg = b.getBackground();
        if (bg instanceof GradientDrawable) { ((GradientDrawable) bg).setColor(0x1ff5a623); ((GradientDrawable) bg).setStroke(Math.max(1, Math.round(dens)), 0x80f5a623); }
        return b;
    }
    /** 分段按钮（.seg）；perRow > 0 时一行放这么多个、排几行 */
    private View seg(String k, List<String> vals, String[] names, int perRow) {
        return segOf(vals, names, S.str(k), perRow, v -> {
            if ("theme".equals(k)) { S.set(k, (String) v); retheme(); }
            else if ("bg".equals(k) && "image".equals(v)) act.act("bgUse");
            else S.set(k, (String) v);
        });
    }
    private void swRow(String k, String name, String small) {
        LinearLayout r = row(false);
        r.addView(label(name, null, small), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(px(0.78f), px(0.44f));
        slp.leftMargin = px(0.2f); // 同 .sw 的 margin-left
        r.addView(new Switch(getContext(), S.bool(k), () -> S.set(k, !S.bool(k))), slp);
    }
    /** 滑块（.rng）：名字、轨道（已选部分强调色）、当前值 */
    private View slider(String k, String name, Fmt fmt) {
        float[] lim = Settings.NUM.get(k);
        return new Slider(getContext(), name, lim[0], lim[1], lim[2], S.num(k), fmt, v -> S.set(k, v), null);
    }

    /** 步进器：左右加减号、中间当前值（行数/字号这类小档位用）。点 ± 走 Settings 的档位列表，改动后 refresh() 整行重画 */
    private View stepper(String k, List<String> vals, boolean font) { return stepper(k, vals, font, " 行"); }
    private View stepper(String k, List<String> vals, boolean font, String unit) {
        LinearLayout box = new LinearLayout(getContext());
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(stepBtn("−", () -> { int i = vals.indexOf(S.str(k)); if (i > 0) { flashKey = k + "−"; S.set(k, vals.get(i - 1)); } }, k + "−"));
        TextView val = new TextView(getContext());
        String v = S.str(k);
        if (font) {
            String t = v.endsWith(".0") ? v.substring(0, v.length() - 2) : v;
            val.setText(t + "×");
        } else val.setText(v + unit);
        val.setTextColor(TXT);
        val.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.24f));
        val.setGravity(Gravity.CENTER);
        val.setMinWidth(px(0.8f));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
        vlp.leftMargin = px(0.12f); vlp.rightMargin = px(0.12f);
        val.setLayoutParams(vlp);
        box.addView(val);
        box.addView(stepBtn("+", () -> { int i = vals.indexOf(S.str(k)); if (i < vals.size() - 1) { flashKey = k + "+"; S.set(k, vals.get(i + 1)); } }, k + "+"));
        return box;
    }
    /** 步进器按钮：点击后让对应符号（−/+）亮一下表示点击成功。由于 refresh 会整行重画，这里在重画后的新按钮上触发闪烁（flashKey 标记刚点过的键） */
    private String flashKey;
    private TextView stepBtn(String sym, Runnable on, String key) {
        TextView b = (TextView) actBtn(sym, false, on);
        b.setPadding(px(0.16f), px(0.08f), px(0.16f), px(0.08f)); // 步进器按钮更紧凑，圆环更小
        if (key.equals(flashKey)) { flashKey = null; flash(b); }
        return b;
    }
    /** 控件短暂高亮一下（约 150ms 后还原）：文字转白、背景填强调色，强调「点到了」 */
    private void flash(View v) {
        if (!(v instanceof TextView)) return;
        TextView t = (TextView) v;
        Drawable bg = t.getBackground();
        t.setTextColor(0xffffffff);
        if (bg instanceof GradientDrawable) ((GradientDrawable) bg).setColor(acc());
        v.postDelayed(() -> {
            t.setTextColor(TXT);
            if (bg instanceof GradientDrawable) ((GradientDrawable) bg).setColor(BTN_OFF_BG);
        }, 150);
    }
    /** 歌词设置里「行数 −/+」「字号 −/+」两个步进器并排成一行：对齐网页版 .nums 里两个 .num 横排（竖屏/宽屏各一行） */
    private View lyricPair(List<String> rowsV, String rowsK, List<String> sizeV, String sizeK) {
        LinearLayout pair = new LinearLayout(getContext());
        pair.setOrientation(LinearLayout.HORIZONTAL);
        pair.setGravity(Gravity.CENTER_VERTICAL);
        pair.addView(stepper(rowsK, rowsV, false, "")); // 行数也只显示数字，不再带「 行」
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = px(0.3f);
        pair.addView(stepper(sizeK, sizeV, false, ""), sp); // 字号无单位，纯数字，对齐网页版
        return pair;
    }
    /** 折叠指示：单纯矢量「∨」形箭头（收起朝下、展开朝上），细线不粗、尺寸小巧；点所属分组可展开/收起 */
    private View chevron(boolean up) {
        final int sz = px(0.34f); // 缩小，仅作指示、不抢视觉
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        View caret = new View(getContext()) {
            final Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                float pad = w * 0.22f, avail = w - pad * 2;
                float scale = avail / 12f; // 12 网格
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setScale(scale, scale);
                m.postTranslate((w - 12f * scale) / 2f, (h - 12f * scale) / 2f);
                String d = up ? "M2,8 L6,4 L10,8" : "M2,4 L6,8 L10,4";
                android.graphics.Path q = new android.graphics.Path();
                SvgPath.of(d).transform(m, q);
                pp.setStyle(Paint.Style.STROKE);
                pp.setStrokeWidth(2f * scale);
                pp.setStrokeJoin(Paint.Join.ROUND);
                pp.setStrokeCap(Paint.Cap.ROUND);
                pp.setColor(TXT);
                c.drawPath(q, pp);
            }
        };
        caret.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        box.addView(caret);
        return box;
    }
    // ---------------- 音乐卡片固定背景色板 ----------------
    /** 子项的小标题：缩进一点、紧跟上面的父项（不另起一行分组），让层级关系一眼看清 */
    private View subLabel(String name, String small) {
        View v = label(name, null, small);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(0.1f);
        v.setLayoutParams(lp);
        return v;
    }

    /** 固定背景色的色板（同网页版）：点一下选一个底色。每个色块下面带颜色名，深色块在深色面板上也看得清 */
    private View colorRow(String k, List<String[]> cols) {
        Flow box = new Flow(getContext());
        String cur = S.str(k);
        for (String[] o : cols) {
            final String hex = o[0];
            boolean on = hex.equalsIgnoreCase(cur);
            View chip = colorChip(hex, on, () -> { S.set(k, hex); refresh(""); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = px(0.14f); lp.bottomMargin = px(0.12f);
            box.addView(chip, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(0.08f);
        lp.leftMargin = px(0.08f);
        box.setLayoutParams(lp);
        return box;
    }
    /** 一个色块：上面是色块（带细描边），下面是小字颜色名；选中的色块描边加粗并显示对勾 */
    private View colorChip(final String hex, boolean on, Runnable onClick) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView b = new TextView(getContext());
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(px(0.1f));
        d.setColor(Color.parseColor(hex));
        // 描边：深色块在深色面板上必须有边框才看得见；选中的用强调色加粗
        d.setStroke(Math.max(1, Math.round(dens)), on ? acc() : (light ? 0x33000000 : 0x33ffffff));
        b.setBackground(d);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.2f));
        b.setTextColor(on ? 0xffffffff : 0x00000000);
        b.setText(on ? "✓" : ""); // 选中打勾（浅色块上用白字看不清时靠描边区分）
        col.addView(b, new LinearLayout.LayoutParams(px(0.56f), px(0.44f)));
        TextView nm = new TextView(getContext());
        nm.setText(nameOf(hex));
        nm.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.17f));
        nm.setTextColor(on ? acc() : SUB);
        nm.setGravity(Gravity.CENTER);
        nm.setSingleLine(true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = px(0.05f);
        col.addView(nm, nlp);
        col.setOnClickListener(v -> onClick.run());
        return col;
    }
    /** 找色板里对应的颜色名（找不到就显示空） */
    private String nameOf(String hex) {
        for (String[] o : Settings.MBG_COLORS) if (o[0].equalsIgnoreCase(hex)) return o[1];
        return "";
    }

    // ---------------- 车漆 / 轮毂 / 卡钳的小球 ----------------
    private void balls(String k, String name, String tip) {
        LinearLayout r = row(true);
        Settings.Pick pick = S.pick(k);
        r.addView(label(name, pick.name + ("caliper".equals(k) && !"custom".equals(S.str(k)) && !"model".equals(S.str(k)) ? "色" : ""), tip));
        LinearLayout box = new LinearLayout(getContext());
        box.setPadding(0, px(0.14f), 0, 0);
        for (Settings.Opt o : Settings.OPTS.get(k)) {
            int color;
            boolean ring = false, enabled = true;
            if (o.model) {
                String css = "paint".equals(k) ? S.modelPaintCss() : S.modelPartCss(k);
                color = Color.parseColor(css != null ? css : "paint".equals(k) ? "#9a0c13" : "#9a9da3");
                enabled = css != null; ring = true;
            } else color = o.hex != null ? 0 : Color.parseColor(o.color);
            Ball b = new Ball(getContext(), color, o.id.equals(S.str(k)), o.hex, ring);
            b.setEnabled(enabled);
            b.setAlpha(enabled ? 1 : 0.35f);
            b.setOnClickListener(v -> S.set(k, o.id));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(0.5f), px(0.5f));
            lp.rightMargin = px(0.2f); lp.bottomMargin = px(0.06f);
            box.addView(b, lp);
        }
        r.addView(box);
        if ("custom".equals(S.str(k))) hsl(r, k);
    }
    /** 自定义颜色：色相 / 饱和度 / 亮度（滑轨画成当前色相的渐变；白、灰、黑拖色相时把饱和度、亮度拉到看得出颜色的地方） */
    private void hsl(LinearLayout r, String k) {
        String hx = Settings.OPTS.get(k).get(Settings.OPTS.get(k).size() - 1).hex;
        int[] a = Settings.hex2hsl(S.str(hx));
        if (a[1] < 3 && S.hueKeep.containsKey(hx)) a[0] = Math.round(S.hueKeep.get(hx));
        final float[] cur = { a[0], a[1], a[2] };
        int[] hues = new int[7];
        for (int i = 0; i < 7; i++) hues[i] = Color.parseColor(Settings.hsl2hex(i * 60 % 360, 100, 50));
        r.addView(new Slider(getContext(), "色相", 0, 360, 1, cur[0], v -> Math.round(v) + "°", v -> {
            cur[0] = v;
            if (cur[1] < 25) cur[1] = 70;
            if (cur[2] > 85 || cur[2] < 15) cur[2] = 50;
            S.hueKeep.put(hx, v);
            S.set(hx, Settings.hsl2hex(cur[0], cur[1], cur[2]));
        }, hues));
        r.addView(new Slider(getContext(), "饱和度", 0, 100, 1, cur[1], v -> Math.round(v) + "%", v -> { cur[1] = v; S.hueKeep.put(hx, cur[0]); S.set(hx, Settings.hsl2hex(cur[0], cur[1], cur[2])); },
            new int[] { Color.parseColor(Settings.hsl2hex(cur[0], 0, 50)), Color.parseColor(Settings.hsl2hex(cur[0], 100, 50)) }));
        r.addView(new Slider(getContext(), "亮度", 0, 100, 1, cur[2], v -> Math.round(v) + "%", v -> { cur[2] = v; S.hueKeep.put(hx, cur[0]); S.set(hx, Settings.hsl2hex(cur[0], cur[1], cur[2])); },
            new int[] { 0xff000000, Color.parseColor(Settings.hsl2hex(cur[0], cur[1], 50)), 0xffffffff }));
    }
    /** 小球：底色 + 高光 + 暗部（同 .ball），选中的放大一点、外圈一道强调色的光；自定义的是彩虹圈；原车颜色外面一圈虚线 */
    private final class Ball extends View {
        final int color0; final boolean on, custom, ring; final String hex; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Ball(Context c, int color, boolean on, String hex, boolean ring) { super(c); color0 = color; this.on = on; this.hex = hex; custom = hex != null; this.ring = ring; setScaleX(on ? 1.14f : 1); setScaleY(on ? 1.14f : 1); }
        @Override protected void onDraw(Canvas c) {
            int color = hex != null ? Color.parseColor(S.str(hex)) : color0; // 自定义颜色拖滑块时跟着变
            float w = getWidth(), cx = w / 2, cy = w / 2, r = 0.25f * rem;
            if (on) {
                // 小球是"设置车模"用的：光晕跟车漆色走，跟设置页主题色无关（同网页版 .ball.on 用 --acc = 车漆强调色）
                int pa = paintAcc();
                p.setColor(withAlpha(pa, 0.35f)); c.drawCircle(cx, cy, r + 0.12f * rem, p);
                p.setColor(pa); c.drawCircle(cx, cy, r + 0.07f * rem, p);
                p.setColor(0xff16191f); c.drawCircle(cx, cy, r + 0.035f * rem, p);
            }
            if (custom) { p.setShader(new android.graphics.SweepGradient(cx, cy, new int[] { 0xffff3b30, 0xffffcc00, 0xff34c759, 0xff00c7be, 0xff007aff, 0xffaf52de, 0xffff3b30 }, null)); c.drawCircle(cx, cy, r, p); p.setShader(null); p.setColor(color); c.drawCircle(cx, cy, r * 0.62f, p); }
            else { p.setColor(color); c.drawCircle(cx, cy, r, p); }
            p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, 0x1affffff, 0x61000000, Shader.TileMode.CLAMP)); c.drawCircle(cx, cy, r, p);
            p.setShader(new RadialGradient(cx - 0.32f * r, cy - 0.44f * r, r * 0.64f, new int[] { 0xd9ffffff, 0x40ffffff, 0x00ffffff }, new float[] { 0, 0.4f, 1 }, Shader.TileMode.CLAMP)); c.drawCircle(cx, cy, r, p);
            p.setShader(null);
            if (ring) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, 0.02f * rem)); p.setColor(0x80ffffff); p.setPathEffect(new android.graphics.DashPathEffect(new float[] { 0.05f * rem, 0.04f * rem }, 0)); c.drawCircle(cx, cy, r + 0.045f * rem, p); p.setPathEffect(null); p.setStyle(Paint.Style.FILL); }
        }
    }

    // ---------------- 车况图标（可自由开关） ----------------
    private View tells() {
        Flow box = new Flow(getContext());
        List<String> cur = S.tells();
        for (String[] o : CarStat.TELLS) {
            final String id = o[0];
            boolean on = cur.contains(id);
            View b = tag(o[1], on, 0, () -> {
                List<String> l = S.tells();
                if (l.contains(id)) l.remove(id); else l.add(id);
                S.set("tells", l);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = px(0.14f); lp.bottomMargin = px(0.14f);
            box.addView(b, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(0.14f);
        box.setLayoutParams(lp);
        return box;
    }

    // ---------------- 底部数据的标签 ----------------
    private View tags() {
        Flow box = new Flow(getContext());
        List<String> cur = S.stats();
        String g = "";
        for (String[] o : CarStat.STATS) {
            if (!o[2].equals(g)) {
                g = o[2];
                TextView h = new TextView(getContext());
                h.setText(g); h.setTextColor(0x73eef1f6); h.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.19f)); h.setLetterSpacing(0.1f);
                h.setTag("break");
                box.addView(h);
            }
            final String id = o[0];
            int n = cur.indexOf(id);
            View b = tag(o[1], n >= 0, n >= 0 ? n + 1 : 0, () -> {
                List<String> l = S.stats();
                int i = l.indexOf(id);
                if (i >= 0) { if (l.size() > 1) l.remove(i); } else { if (l.size() >= 6) l.remove(l.size() - 1); l.add(id); }
                S.set("stats", l);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = px(0.14f); lp.bottomMargin = px(0.14f);
            box.addView(b, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(0.14f);
        box.setLayoutParams(lp);
        return box;
    }
}
