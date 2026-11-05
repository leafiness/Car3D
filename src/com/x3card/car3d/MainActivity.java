package com.ljone.car3d;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

import com.ljone.car3d.ach.AchPage;
import com.ljone.car3d.ach.Trophies;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 3D 车况原生版（照网页版 demo3d 的车况页，没有唱片机页）：3D 画布（SceneView + Stage + CarPage）+ 上面一层界面（CarUi：字、图标、胎压标签、齿轮、设置面板）
 * + 最上面的说明（InfoView：连不上宿主、没授权时）。
 * 宿主的数据（CarData）在主线程收，交给 GL 线程里的 CarPage；CarPage 交出来的界面变化由 CarUi 切回主线程。
 * 全屏同琉光原生版：自己在主屏上开一个全屏页（FullActivity，单独的任务），在全屏页里点"恢复小屏"关掉它；全屏页开着时小窗口里停画。设置两边共用，回到前台重新读。
 * 成就（ach/）：看得见时记录（Trophies，自己订一组宿主数据）；新解锁的在右上角滑出提示（CarUi.achToast），奖杯键打开整页的成就墙（AchPage，盖在车上，开着时车况页停画）
 */
public class MainActivity extends Activity implements Uiplus.Listener, CarData.Listener, CarUi.Actions {
    private static WeakReference<MainActivity> pip = new WeakReference<>(null);
    private static final int PICK_MODEL = 1, PICK_BG = 2;

    private Host host;
    private CarData data;
    private Settings S;
    private FrameLayout root;
    private SceneView view;
    private Stage stage;
    private CarPage page;
    private CarUi ui;
    private InfoView info;
    private InfoPanel infoP;
    private DebugPanel dbgP;
    private PlatePanel plateP;
    private Trophies tro;
    private AchPage achP;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread io = new HandlerThread("io");
    private Handler ioH;
    private boolean started, slept;
    private int pageIdx; // 当前页：0 车况 / 1 音乐 / 2 琉光
    private com.x3card.car3d.lg.LgHost lgHost;
    private long fsAt;

    /** 全屏页（FullActivity）是真；多窗口里、从桌面直接打开的是假 */
    boolean isFull() { return false; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!isFull()) pip = new WeakReference<>(this);
        io.start();
        com.ljone.car3d.gl.Prog.cacheDir(new java.io.File(getCacheDir(), "shaders"));
        ioH = new Handler(io.getLooper());
        S = new Settings(this);
        host = DebugHost.wanted(this) ? new DebugHost(this, this) : new Uiplus(this, this); // 不是车机：调试宿主（车况是模拟的，调试面板里改）
        data = new CarData(host, this);
        data.onActions = this::acOk;
        tro = new Trophies(this, link(), host instanceof DebugHost);
        root = new FrameLayout(this);
        ui = new CarUi(this, S, this);
        info = new InfoView(this, w -> { if ("auth".equals(w)) authorize(); });
        newScene();
        root.addView(info, new FrameLayout.LayoutParams(-1, -1));
        root.addView(ui, new FrameLayout.LayoutParams(-1, -1));
        infoP = new InfoPanel(this, S, host, this::sheetClosed);
        ui.addView(infoP, new FrameLayout.LayoutParams(-1, -1));
        if (host instanceof DebugHost) {
            dbgP = new DebugPanel(this, S, (DebugHost) host, this::sheetClosed);
            ui.addView(dbgP, new FrameLayout.LayoutParams(-1, -1));
            ui.hud.setDemo(true);
            ui.panel.setDebug(true);
        }
        plateP = new PlatePanel(this, S, new PlatePanel.Act() {
            @Override public void plates(JSONObject st) { pushPlates(st); }
            @Override public void edit(String side) { final CarPage p = page; view.queueEvent(() -> p.plateEdit(side)); }
            @Override public void closed() {
                final CarPage p = page;
                view.queueEvent(() -> p.plateEdit(null));
                ui.panel.setPlates(Plates.state(plateP.current()));
                sheetClosed();
            }
        });
        ui.addView(plateP, new FrameLayout.LayoutParams(-1, -1));
        achP = new AchPage(this);
        root.addView(achP, new FrameLayout.LayoutParams(-1, -1));
        ui.onTheme = (d, k) -> achP.setDark(d, k);
        tro.listen(new Trophies.Listener() {
            @Override public void unlocked(List<com.ljone.car3d.ach.Ach> g) { if (achP.isOpen()) achP.unlocked(g); else ui.achToast.show(g); }
            @Override public void pending(int n) { ui.setBadge(n); }
            @Override public void changed() { achP.changed(); }
        });
        ui.setBadge(tro.pendingCount());
        setContentView(root);
        ui.darkNow(page.isDark());
        ui.hud.setHint(page.hintShown());
        ui.setBlocked(info.covering());
        S.on(k -> { if (Looper.myLooper() == Looper.getMainLooper()) setting(k); else main.post(() -> setting(k)); });
        data.extra(statFields());
        applyBar();
    }

    /** 新的一套画布（第一次、换抗锯齿时）：Stage、CarPage 跟着画布走（GL 的东西都在画布的上下文里） */
    private void newScene() {
        stage = new Stage(getAssets(), S.bool("aa"));
        stage.fpsCap = "30".equals(S.str("fps")) ? 30 : 60;
        stage.fx = S.fx();
        page = new CarPage(this, S, ui);
        page.needs = () -> main.post(this::wants);
        page.plateOut = plateOut;
        page.unitOf = data::unit;
        view = new SceneView(this, stage, S.bool("aa"));
        view.setRatio(S.fx().ratio);
        root.addView(view, 0, new FrameLayout.LayoutParams(-1, -1));
        final CarPage p = page;
        final Map<String, Object> car = data.car(), sys = data.sys();
        final float tm = ui.hud.msgTop / getResources().getDisplayMetrics().density;
        view.queueEvent(() -> { p.setCar(car); p.setSys(sys); if (tm > 0) p.setTagMax(tm); });
        stage.setPage(page);
        lgHost = new com.x3card.car3d.lg.LgHost(view, getResources().getDisplayMetrics().density);
        stage.setLg(lgHost);
        ui.hud.setLgHost(lgHost);
        // 同级页面：Hud 的 pageOffset 变化时，只把车模本身往左滑出/滑回（车况背景 Bg 保持满屏固定），
        // 这样音乐背景半透明淡入时下方始终是车况背景、不会露出黑底亮暗缝；GL 停画按 pageOffset 实时决定——
        // 只有完全停在音乐页（且非拖拽）才停，拖拽/滑动途中保持渲染，车模跟着平移进出
        ui.hud.setOnPageOffset(() -> {
            float ph = ui.hud.pagePhase();
            int idx = ui.hud.pageIndex();
            pageIdx = idx;
            p.setPageOffset(ph);
            boolean onMusic = idx == 1 && Math.abs(ph - 1f) < 0.012f && !ui.hud.dragging(); // 完全停在音乐页才停 GL
            boolean onLg = idx == 2; // 第三页：GL 帧交给琉光 3D 场景
            if (lgHost != null) {
                float d = getResources().getDisplayMetrics().density;
                lgHost.size(view.getWidth() / d, view.getHeight() / d);
            }
            view.queueEvent(() -> {
                stage.setLgMode(onLg);
                stage.setVisible(started && !slept && !onMusic);
                stage.invalidate();
            });
            ui.setPagePhase(idx == 0 ? ph : 1f); // 非车况页藏掉胎压标签
        });
        acOk();
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        File f = new File(getCacheDir(), "update.apk"); if (f.exists()) f.delete(); // 清掉上一次更新残留的缓存包
        reload();
        ui.setFsMode(isFull() ? "small" : onMainScreen() ? "" : "full");
        if (!slept) wake(true);
        host.start();
        if (isFull()) { MainActivity p = pip.get(); if (p != null && p != this) p.sleep(true); }
        autoCheckUpdate();
    }

    /** 启动时静默查一次远端版本：有新版就直接弹更新框。车机不会自动重拉 APK（这是独立安装的应用），所以得让用户一眼看到「有新版本」。查不到不打扰。 */
    private boolean autoChecked;
    private void autoCheckUpdate() {
        if (autoChecked || isFull()) return; // 全屏页不打扰；一次进程只查一次
        autoChecked = true;
        main.postDelayed(() -> {
            if (!started) return;
            Updater.check(this, new Updater.CheckCb() {
                @Override public void onLatest() { }
                @Override public void onUpdate(String remoteName, String notes) {
                    ui.toast("发现新版本 V" + remoteName + "，点击可更新", 4000);
                    showUpdateDialog(remoteName, notes);
                }
                @Override public void onError(String msg) { } // 网络不通就当没这回事，别弹错误
            });
        }, 2500);
    }
    @Override protected void onStop() {
        started = false;
        wake(false);
        host.background(); // 不马上断开：宿主的授权确认页会把这里盖住，授权申请挂在这个会话上
        if (isFull()) { MainActivity p = pip.get(); if (p != null && p != this) p.sleep(false); }
        super.onStop();
    }
    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        host.quit();
        io.quitSafely();
        super.onDestroy();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
    @Override public void onBackPressed() {
        // 更新弹窗：返回键也能关（只认返回键和「取消」按钮，点外面不关，避免误关装更新的确认框）
        if (updateDlg != null && updateDlg.isShowing()) { updateDlg.dismiss(); return; }
        if (achP.back()) return;
        if (ui.panelOpen()) ui.panel(false);
        else if (plateP.isOpen()) plateP.show(false);
        else if (infoP.isOpen()) infoP.show(false);
        else if (dbgP != null && dbgP.isOpen()) dbgP.show(false);
        else super.onBackPressed();
    }

    /** GL 该不该画：看得见、没睡、不在音乐页。
     *  音乐页时整个 GL 停画（车模和车况背景都在 Stage 里，setVisible 掐的是渲染帧入口，两者一起停），
     *  两个页面互不干扰：车况背景再也影响不到音乐页，音乐背景也不压在车模上。 */
    private boolean glWanted() { return started && !slept && pageIdx != 1; } // 音乐页停 GL；车况页与琉光页都要画
    /** 看得见 / 看不见：画布停画、车况退订（同网页版：页面藏起来取消订阅，回来重订） */
    private void wake(boolean on) {
        if (on) {
            view.onResume(); view.queueEvent(() -> stage.setVisible(glWanted()));
            ui.setBadge(tro.pendingCount());
        } else { achP.closeNow(); view.queueEvent(() -> stage.setVisible(false)); view.onPause(); }
        wants(on);
        tro.setVisible(on);
    }
    private void wants() { wants(started && !slept); }
    private void wants(boolean on) {
        final CarPage p = page;
        data.use(on, on && p.wantsMedia());
    }
    /** 全屏页开着：小窗口里停画；关了接着画，顺便读一下全屏页里改过的设置 */
    void sleep(boolean s) {
        if (s == slept) return;
        slept = s;
        if (!started) return;
        if (s) wake(false); else { reload(); wake(true); }
    }
    private boolean onMainScreen() {
        Display d = getWindowManager().getDefaultDisplay();
        return d == null || d.getDisplayId() == Display.DEFAULT_DISPLAY;
    }

    // ================= 设置 =================
    /** 回到前台：全屏页和小窗口共用一份设置，另一边改过的跟上 */
    private void reload() {
        Map<String, Object> before = new java.util.LinkedHashMap<>();
        for (String k : KEYS) before.put(k, S.get(k));
        S.load();
        for (String k : KEYS) if (!String.valueOf(before.get(k)).equals(String.valueOf(S.get(k)))) S.emit(k);
        final CarPage p = page;
        view.queueEvent(p::platesFromStore); // 车牌：全屏页、小窗口另一边可能改过
    }
    private static final String[] KEYS = { "paint", "rim", "caliper", "finish", "paintHex", "rimHex", "calHex", "theme", "bg", "bgDim", "viewAz", "viewEl", "viewZoom", "viewFov",
        "stats", "labels", "intro", "quality", "fps", "aa", "fsBar",
        "mBgMode", "mBgCover", "mBgColor" };

    /** 一项设置变了（主线程） */
    private void setting(String k) {
        final CarPage p = page;
        switch (k) {
            case "aa": rebuild(); break;
            case "quality": view.setRatio(S.fx().ratio); stage.fx = S.fx(); view.queueEvent(() -> stage.setPage(p)); break; // 画质一变车况页重建（材质按有没有阴影重新编译）
            case "fps": final int f = "30".equals(S.str("fps")) ? 30 : 60; view.queueEvent(() -> stage.fpsCap = f); break;
            case "fsBar": applyBar(); break;
            case "lyricLinesP": case "lyricLinesL": case "lyricSizeP": case "lyricSizeL": ui.hud.invalidate(); break; // 歌词行数/字号变了马上重画
            case "mBgMode": case "mBgCover": case "mBgColor": ui.hud.invalidate(); break; // 音乐卡片背景变了马上重画
            case "model": break;
            case "stats": data.extra(statFields()); view.queueEvent(() -> p.setting(k)); break;
            case "tells": if (ui.hud != null) ui.hud.refilterTells(); break; // 车况图标勾选后立即重画
            default: view.queueEvent(() -> p.setting(k));
        }
        ui.panel.refresh(k);
    }
    /** 底部数据选了的那几项要的字段（常驻订阅里没有的另外订） */
    private List<String> statFields() {
        List<String> l = new ArrayList<>();
        for (String id : S.stats()) { String f = CarStat.field(id); if (f != null) l.add(f); }
        return l;
    }
    /** 抗锯齿只能在建画布时定：换一块新画布（车况页重建） */
    private void rebuild() {
        SceneView old = view;
        old.onPause();
        root.removeView(old);
        newScene();
        if (!started || slept) view.onPause();
        else view.queueEvent(() -> stage.setVisible(glWanted()));
        ui.panel.refresh("");
    }
    private void applyBar() {
        if (!isFull()) return;
        if (S.bool("fsBar")) getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    // ================= 面板里的按钮 =================
    @Override public void act(String name) {
        final CarPage p = page;
        switch (name) {
            case "import": pick(PICK_MODEL, "*/*"); break;
            case "modelReset":
                ui.panel(false);
                CarModel.reset(this);
                view.queueEvent(p::reload);
                ui.toast("换回默认车模了", 2600);
                break;
            case "bgImage": pick(PICK_BG, "image/*"); break;
            case "debug": debug(); break;
            case "achOpen": trophies(); break;
            case "plates": plates(); break;
            case "achReset": tro.reset(); ui.toast("成就记录清空了", 2600); break;
            case "bgUse": if (Background.has(this)) S.set("bg", "image"); else pick(PICK_BG, "image/*"); break; // 没存过图片：先选一张
            case "checkUpdate": checkUpdate(); break; // 设置页「当前版本」行：检查更新
            default: view.queueEvent(() -> p.setting("act:" + name));
        }
    }
    /** 设置页「当前版本」：检查远端是否有新版本 */
    private void checkUpdate() {
        ui.toast("正在检查更新…", 1500);
        Updater.check(this, new Updater.CheckCb() {
            @Override public void onLatest() { ui.toast("已是最新版本", 2200); }
            @Override public void onUpdate(String remoteName, String notes) { showUpdateDialog(remoteName, notes); }
            @Override public void onError(String msg) { ui.toast(msg, 3500); }
        });
    }
    private AlertDialog updateDlg;
    /** 发现新版本：圆角弹窗（和设置页卡片同款圆角），左取消 / 右更新，显示更新日志；点更新后显示下载进度；下完先自动弹一次安装界面，未装返回后可用「安装」按钮手动再装 */
    private void showUpdateDialog(String remoteName, String notes) {
        if (updateDlg != null && updateDlg.isShowing()) return;
        // 圆角卡片背景（和设置页卡片一致：160° 深色渐变 + 细描边 + 0.32rem 圆角）
        GradientDrawable cardBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xf5262a34, 0xf70f1116});
        cardBg.setCornerRadius(px(0.32f));
        cardBg.setStroke(Math.max(1, Math.round(dens())), 0x14ffffff);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(16));

        TextView title = new TextView(this);
        title.setText("发现新版本");
        title.setTextColor(0xffeef1f6); title.setTextSize(18);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false));
        title.setPadding(0, 0, 0, dp(8));
        content.addView(title);

        TextView msg = new TextView(this);
        msg.setText("当前 V" + Updater.localName(this) + " → 新版本 V" + remoteName + "\n是否现在更新？");
        msg.setTextColor(0xffeef1f6); msg.setTextSize(16);
        content.addView(msg);

        // 更新日志（有内容才显示）：当前版本高亮，历史版本灰色小字；超出可在日志区内滚动
        String[] seg = Updater.splitNotes(notes, remoteName);
        String cur = seg[0], hist = seg[1];
        {
            TextView ltitle = new TextView(this);
            ltitle.setText("更新日志");
            ltitle.setTextColor(0xff8fd3ff); ltitle.setTextSize(13);
            ltitle.setPadding(0, dp(10), 0, dp(4));
            ltitle.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false));
            content.addView(ltitle);
            ScrollView sv = new ScrollView(this);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            if (!cur.isEmpty()) {
                TextView lt = new TextView(this);
                lt.setText(cur);
                lt.setTextColor(0xffeef1f6); lt.setTextSize(14);
                lt.setLineSpacing(dp(3), 1f);
                box.addView(lt);
            }
            if (!hist.isEmpty()) {
                TextView ht = new TextView(this);
                ht.setText("历史版本\n" + hist);
                ht.setTextColor(0x88aab4c4); ht.setTextSize(12);
                ht.setLineSpacing(dp(2), 1f);
                ht.setPadding(0, dp(8), 0, 0);
                box.addView(ht);
            }
            if (cur.isEmpty() && hist.isEmpty()) { // 没拉到日志：说清楚，别留一片空白让人以为没有日志
                TextView nt = new TextView(this);
                nt.setText("暂时没取到更新日志（网络不通时会出现，联网后重新点「当前版本」即可）");
                nt.setTextColor(0x88aab4c4); nt.setTextSize(12);
                nt.setLineSpacing(dp(2), 1f);
                box.addView(nt);
            }
            sv.addView(box);
            // 高度按行数估算并封顶（超出可在日志区内滚动）
            int lines = ((cur.isEmpty() ? 0 : cur.split("\n").length) + (hist.isEmpty() ? 0 : hist.split("\n").length)) + 2;
            int hpx = Math.min(dp(200), Math.max(dp(46), lines * dp(21)));
            sv.setLayoutParams(new LinearLayout.LayoutParams(-1, hpx));
            content.addView(sv);
        }
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        pb.setMax(100); pb.setProgress(0); pb.setVisibility(View.GONE);
        content.addView(pb);
        TextView ptxt = new TextView(this);
        ptxt.setText(""); ptxt.setTextColor(0x99eef1f6); ptxt.setTextSize(13); ptxt.setPadding(0, dp(6), 0, 0);
        ptxt.setVisibility(View.GONE);
        content.addView(ptxt);

        // 按钮栏（和设置页 .act 同款：取消=描边，更新=蓝底实心）
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END);
        bar.setPadding(0, dp(12), 0, 0);
        TextView cancelBtn = makeDlgBtn("取消", false);
        TextView actionBtn = makeDlgBtn("更新", true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.leftMargin = dp(12);
        bar.addView(cancelBtn);
        bar.addView(actionBtn, alp);
        content.addView(bar);

        AlertDialog d = new AlertDialog.Builder(this)
            .setView(content)
            .create();
        d.setCanceledOnTouchOutside(false); // 只在「取消」按钮或返回键关闭，点外面不关，避免误关更新确认框
        d.show();
        // 透明窗口 + 清掉对话框自带面板背景，只露我们的圆角卡片
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            stripBg(d.getWindow().getDecorView());
        }
        content.setBackground(cardBg);

        cancelBtn.setOnClickListener(v -> d.dismiss());
        final TextView fab = actionBtn;
        actionBtn.setOnClickListener(v -> {
            fab.setEnabled(false);
            pb.setVisibility(View.VISIBLE); ptxt.setVisibility(View.VISIBLE); ptxt.setText("下载中…");
            Updater.downloadAndInstall(MainActivity.this, new Updater.DownCb() {
                @Override public void onProgress(int p) {
                    if (p >= 0) { if (pb.isIndeterminate()) { pb.setIndeterminate(false); } pb.setProgress(p); ptxt.setText(p + "%"); }
                    else { pb.setIndeterminate(true); ptxt.setText("下载中…"); }
                }
                @Override public void onDone() {
                    // 下载完成：先自动弹一次系统安装界面；若用户没装就返回，按钮变「安装」可手动再装
                    ptxt.setText("下载完成，正在弹出安装…");
                    fab.setText("安装");
                    fab.setEnabled(true);
                    fab.setOnClickListener(iv -> Updater.install(MainActivity.this));
                    Updater.install(MainActivity.this);  // 第一次自动安装
                }
                @Override public void onError(String m) { ptxt.setText(m); }
            });
        });
        updateDlg = d;
    }
    /** 对话框按钮（.act 风格）：pri=蓝底实心，否则描边 */
    private TextView makeDlgBtn(String text, boolean pri) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(15);
        b.setTextColor(pri ? 0xffffffff : 0xffeef1f6);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable gb = new GradientDrawable();
        gb.setCornerRadius(px(0.3f));
        if (pri) gb.setColor(0xff5b8cff);
        else { gb.setColor(0x0fffffff); gb.setStroke(Math.max(1, Math.round(dens())), 0x29ffffff); }
        b.setBackground(gb);
        return b;
    }
    /** 递归把视图背景清空（透明窗口下只留我们自己的圆角卡片） */
    private static void stripBg(View v) {
        if (v == null) return;
        v.setBackgroundDrawable(null);
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) stripBg(g.getChildAt(i)); }
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private float px(float f) { return f * getResources().getDisplayMetrics().heightPixels / 10.52f; }
    private float dens() { return getResources().getDisplayMetrics().density; }

    private void pick(int code, String type) {
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type), code); }
        catch (Exception e) { ui.toast("这台车机打不开选文件的界面", 3500); }
    }
    @Override protected void onActivityResult(int code, int res, Intent d) {
        super.onActivityResult(code, res, d);
        if (res != RESULT_OK || d == null || d.getData() == null) return;
        final Uri uri = d.getData();
        if (code == PICK_MODEL) {
            ui.panel(false);
            ui.busy("正在导入车模");
            ui.step("读取文件", 0.01f);
            ioH.post(() -> {
                String err = null;
                long t0 = SystemClock.uptimeMillis();
                // 读文件 0.01～0.12、检查 0.13、存下来 0.14～0.16；后面的（解析、建车、第一次画）在 CarPage.reload 里接着走
                try { CarModel.importPack(this, uri, (s, f) -> main.post(() -> ui.step(s, f))); }
                catch (Exception e) { err = e instanceof IllegalArgumentException || e instanceof IllegalStateException ? e.getMessage() : "导入失败：" + e.getMessage(); Log.w(Uiplus.TAG, "导入车模包", e); }
                Log.w("car3d", "导入：读文件、检查、保存 " + (SystemClock.uptimeMillis() - t0) + " 毫秒");
                final String er = err;
                main.post(() -> {
                    if (er != null) { ui.busy(null); ui.toast(er, 5000); return; }
                    // 换了一台车：车漆、轮毂、卡钳、质感都回到车模自己的样子
                    for (String k : new String[] { "paint", "rim", "caliper" }) S.set(k, "model");
                    S.set("finish", "preset");
                    final CarPage p = page;
                    view.queueEvent(() -> p.reload(true));
                });
            });
        } else if (code == PICK_BG) {
            ioH.post(() -> {
                String err = null;
                try { Background.save(this, uri); } catch (Exception e) { err = e instanceof IllegalArgumentException ? e.getMessage() : "这张图片用不了"; }
                final String er = err;
                main.post(() -> {
                    if (er != null) { ui.toast(er, 3500); return; }
                    final CarPage p = page;
                    if ("image".equals(S.str("bg"))) view.queueEvent(p::bgChanged); else S.set("bg", "image");
                });
            });
        }
    }

    // ================= 界面交过来的 =================
    @Override public void statusTap() { final CarPage p = page; view.queueEvent(p::statusTap); }
    @Override public void statusPick(int i) { final CarPage p = page; view.queueEvent(() -> p.statusPick(i)); }
    @Override public void statusClose() { final CarPage p = page; view.queueEvent(p::statusClose); }
    /** 全部车况：设置开着先关掉；车让到旁边 */
    @Override public void allStatus() {
        if (ui.panelOpen()) ui.panel(false);
        if (plateP.isOpen()) plateP.show(false);
        if (dbgP != null && dbgP.isOpen()) dbgP.show(false);
        infoP.open();
        ui.infoOpen(true);
        final CarPage p = page;
        view.queueEvent(() -> p.aside(true));
    }
    /** 调试面板（"调试数据"、设置里那一行）：再点一下关上；设置、全部车况开着先关掉；车让到旁边（同全部车况） */
    @Override public void debug() {
        if (dbgP == null) return;
        if (dbgP.isOpen()) { dbgP.show(false); return; }
        if (ui.panelOpen()) ui.panel(false);
        if (infoP.isOpen()) infoP.show(false);
        if (plateP.isOpen()) plateP.show(false);
        dbgP.show(true);
        ui.infoOpen(true);
        final CarPage p = page;
        view.queueEvent(() -> p.aside(true));
    }
    /** 车牌（设置里"调整车牌"）：设置、全部车况、调试面板先关掉；车让到旁边，镜头转过去看着这块车牌 */
    private void plates() {
        if (ui.panelOpen()) ui.panel(false);
        if (infoP.isOpen()) infoP.show(false);
        if (dbgP != null && dbgP.isOpen()) dbgP.show(false);
        if (!plateP.ready()) { ui.toast("车模还在载入，等一下再打开", 2600); return; }
        plateP.open();
        ui.infoOpen(true);
        final CarPage p = page;
        view.queueEvent(() -> p.aside(true));
    }
    /** 车上交回来的车牌（建好车时的配置、自动摆的 / 拖完的位置）：已经在主线程 */
    private final CarPage.PlateOut plateOut = new CarPage.PlateOut() {
        @Override public void plates(JSONObject st, float[] dims, String key) {
            if (plateP == null) return;
            plateP.setData(st, dims, key);
            ui.panel.setPlates(Plates.state(st));
        }
        @Override public void plateAt(String which, JSONArray at, boolean drag) {
            if (plateP == null) return;
            plateP.plateAt(which, at, drag);
            ui.panel.setPlates(Plates.state(plateP.current()));
        }
    };
    /** 车牌改了交给车：拖滑块时一帧只交最新的一份 */
    private final Object plateLock = new Object();
    private JSONObject plateNext;
    private boolean platePosted;
    private void pushPlates(JSONObject st) {
        synchronized (plateLock) { plateNext = st; if (platePosted) return; platePosted = true; }
        final CarPage p = page;
        view.queueEvent(() -> {
            JSONObject s;
            synchronized (plateLock) { s = plateNext; plateNext = null; platePosted = false; }
            if (s != null) p.setPlates(s);
        });
    }
    /** 全部车况、调试面板、车牌关上了：都关着才把车挪回来 */
    private void sheetClosed() {
        boolean any = infoP.isOpen() || dbgP != null && dbgP.isOpen() || plateP != null && plateP.isOpen();
        ui.infoOpen(any);
        if (any) return;
        final CarPage p = page;
        view.queueEvent(() -> p.aside(false));
    }
    /** 成就墙（奖杯键、弹出的提示、设置里那一行）：设置、全部车况这些先关掉；字和键淡出、车况页停画，关上以后恢复 */
    @Override public void trophies() {
        if (achP.isOpen() || !started || slept) return;
        if (ui.panelOpen()) ui.panel(false);
        if (infoP.isOpen()) infoP.show(false);
        if (dbgP != null && dbgP.isOpen()) dbgP.show(false);
        if (plateP.isOpen()) plateP.show(false);
        ui.achToast.clear();
        String b = S.str("bg");
        achP.setDark(ui.darkTo(), ui.hud.darkK());
        achP.setAccent(accent());
        achP.setBg("warm".equals(b) ? 1 : "cool".equals(b) ? 2 : "image".equals(b) ? 3 : 0, S.num("bgDim"), () -> Background.load(this));
        achP.open(tro, S.bool("aa"), "30".equals(S.str("fps")) ? 30 : 60, () -> {
            ui.animate().cancel();
            ui.setVisibility(View.VISIBLE);
            ui.animate().alpha(1).setDuration(250).start();
            final Stage st = stage;
            view.queueEvent(() -> st.setVisible(glWanted()));
        });
        ui.animate().alpha(0).setDuration(200).withEndAction(() -> { if (achP.isOpen()) ui.setVisibility(View.INVISIBLE); }).start();
        final Stage st = stage;
        view.queueEvent(() -> st.setVisible(false));
    }
    /** 强调色（同设置面板：跟着车漆） */
    private int accent() {
        String a = S.pick("paint").acc;
        try { if (a != null) return android.graphics.Color.parseColor(a); } catch (Exception ignored) { }
        return 0xffff4b55;
    }
    @Override public void roofTap() { final CarPage p = page; view.queueEvent(p::roofTap); }
    @Override public void tagMax(float y) { final CarPage p = page; view.queueEvent(() -> p.setTagMax(y)); }
    @Override public void panel(boolean open) { final CarPage p = page; view.queueEvent(() -> p.focus(open)); }
    /** 音乐卡片开关：开着也订阅媒体（调试宿主下才有歌），关了恢复按氛围灯模式决定。
     *  同级页面：车模页与音乐页左右交叉滑动——Hud 把音乐层右进、整层 GL 表面左出（pageOffset 驱动），
     *  停在音乐页时整个 GL 停画（车模和车况背景一起不渲染）。 */
    @Override public void musicCard(boolean on, int dir) {
        final CarPage p = page;
        p.setCardMusic(on, dir);
        pageIdx = on ? 1 : 0;
        // GL 停画交给 Hud.onPageOffset 按 pageOffset 实时决定（拖拽/滑动途中保持渲染，停在音乐页才停），
        // 不再用「进音乐页 300ms 后停画」的延时器——否则拖拽回车模页时会被它把 GL 又关掉、车模拖回途中空白
        boolean vis = started && !slept;
        data.use(vis, vis && p.wantsMedia());
    }
    /** 播放控制命令（play/pause/next/previous/togglePlayPause） */
    @Override public void music(String action) { data.mediaCmd(action); }
    /** 封面：coverUrl 是会话绑定的资源标识，走 openCover（不是 HTTP 下载），解码完回调（由调用方用序号丢弃过期结果） */
    @Override public void loadCover(String url, CarData.CoverCb cb) { data.loadCover(url, cb); }
    /** 换歌了拉整首歌词 */
    @Override public void musicLyrics() { data.mediaLyrics(ui.hud.trackId(), l -> ui.setLyrics(l)); }

    // ================= 点封面：打开正在放歌的那个 App =================
    /** 点封面打开音源 App：宿主原生动作 apps.launch（参数是包名）。
      按宿主的挂法逐级回退：① apps.launch（规范方式）；② 通用动作通道 actions.runSequence；
      ③ Android 原生 PackageManager 启动（车机上最直接的一条路，宿主不给用时兜底）。
      这三条都没有才算失败，提示说清为什么。 */
    @Override public void launchApp() {
        final String pkg = ui.hud.mediaPkg();
        if (pkg == null || pkg.isEmpty()) { ui.toast("还不知道这首是从哪个 App 放的", 2500); return; }
        final String name = Hud.srcName(pkg);
        if (!host.canExec()) { authorize(); ui.toast("要先在宿主里允许「执行操作」", 3000); return; }
        try {
            host.request("apps.launch", new JSONObject().put("packageName", pkg), (r, code, msg) -> {
                if (code == null) { ui.toast("正在打开" + name, 2000); return; }
                launchSeq(pkg, name, code, msg);
            });
        } catch (Exception e) { launchNative(pkg, name); }
    }
    /** 回退②：通用动作通道 actions.runSequence（里面只放 apps.launch 一步） */
    private void launchSeq(final String pkg, final String name, String c1, String m1) {
        try {
            JSONObject step = new JSONObject().put("method", "apps.launch").put("params", new JSONObject().put("packageName", pkg));
            JSONObject steps = new JSONObject().put("steps", new JSONArray().put(step));
            host.request("actions.runSequence", steps, (r, code, msg) -> {
                if (code == null) { ui.toast("正在打开" + name, 2000); return; }
                launchNative(pkg, name);
            });
        } catch (Exception e) { launchNative(pkg, name); }
    }
    /** 回退③：Android 原生启动（宿主两条路都不认时用；这台车机上装了就能起） */
    private void launchNative(String pkg, String name) {
        try {
            android.content.Intent it = getPackageManager().getLaunchIntentForPackage(pkg);
            if (it != null) {
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
                ui.toast("正在打开" + name, 2000);
                return;
            }
        } catch (Exception ignored) { }
        ui.toast("打不开" + name, 3000);
    }
    /** 车顶空调键：宿主的原生操作 vehicle.ac.setPower（不用车主确认）；失败交回车况页（变回去、说原因） */
    @Override public void acSet(boolean on) {
        final CarPage p = page;
        if (!host.canExec()) { view.queueEvent(() -> p.roofFail("要先在宿主里允许「执行操作」")); authorize(); return; }
        try {
            host.request("vehicle.ac.setPower", new JSONObject().put("enabled", on), (r, code, msg) -> {
                if (code == null) return;
                final String m = "PERMISSION_DENIED".equals(code) ? "没有执行操作的授权：到宿主的「应用授权」里给 3D 车况打开" : "空调没" + (on ? "开" : "关") + "成" + (msg != null && !msg.isEmpty() ? "：" + msg : "");
                view.queueEvent(() -> p.roofFail(m));
            });
        } catch (Exception ignored) { }
    }
    /** 宿主能不能开关空调（能力目录里有这个操作）：没有就不显示车顶空调键 */
    private void acOk() {
        final boolean ok = data.actions().contains("vehicle.ac.setPower");
        final CarPage p = page;
        view.queueEvent(() -> { p.acOk = ok; if (stage.renderer != null) stage.invalidate(); });
    }

    // ================= 全屏 =================
    @Override public void fullscreen() {
        long t = SystemClock.uptimeMillis();
        if (t - fsAt < 2500) return; // 点一次 2.5 秒内不再切
        fsAt = t;
        if (isFull()) { finishAndRemoveTask(); return; }
        try {
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
            startActivity(new Intent(this, FullActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), o.toBundle());
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "全屏打开失败", e);
            fsAt = 0;
            ui.toast("这台车机不让全屏打开", 3000);
        }
    }

    // ================= 宿主 =================
    @Override public void onState(String state, String detail) {
        info.setStatus(detail, "error".equals(state));
        info.setPerms(host.canRead(), host.ready());
        ui.setBlocked(info.covering());
        data.hostChanged();
        tro.hostChanged();
    }
    @Override public void onPermissions(boolean read, boolean exec) {
        info.setPerms(read, host.ready());
        ui.setBlocked(info.covering());
        data.hostChanged();
        tro.hostChanged();
    }
    @Override public void onEvent(JSONObject m) { if (!tro.onEvent(m) && !infoP.onEvent(m)) data.onEvent(m); }
    /** 成就用的宿主（同 Host） */
    private Trophies.Link link() {
        return new Trophies.Link() {
            @Override public void request(String m, JSONObject p, Uiplus.Reply r) { host.request(m, p, r); }
            @Override public String session() { return host.session(); }
            @Override public boolean ready() { return host.ready(); }
            @Override public boolean canRead() { return host.canRead(); }
            @Override public boolean backendReady() { return host.backendReady(); }
        };
    }
    void authorize() {
        try {
            host.authorize(new JSONObject().put("readData", true).put("executeActions", true), (r, code, msg) -> { if (code != null) ui.toast("授权没成：" + msg, 3500); });
        } catch (Exception ignored) { }
    }

    // ================= 车况数据（主线程）→ GL 线程 =================
    @Override public void car(Map<String, Object> c) { final CarPage p = page; view.queueEvent(() -> p.setCar(c)); }
    @Override public void sys(Map<String, Object> s) { final CarPage p = page; view.queueEvent(() -> p.setSys(s)); }
    @Override public void media(JSONObject m) { final CarPage p = page; view.queueEvent(() -> p.setMedia(m)); }
}
