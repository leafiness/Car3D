package com.ljone.car3d;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用内检查更新 / 下载 / 安装：
 *  - 拉远端 version.txt（与网页版同一份，格式：第1行=版本名，第2行=versionCode）和本地 versionCode 比；
 *  - 下载 latest.apk 到缓存，边下边回调进度；
 *  - 优先用 PackageInstaller 安装（能拿到安装完成回调，用来删缓存包），被限制时回退 FileProvider + ACTION_VIEW。
 */
final class Updater {
    static final String BASE = "https://car3d-apk.app.workbuddy.host/";
    // 部署站点启用了分享码保护：直接访问域名返回 403，必须带 sharecode 才能拉到文件
    static final String SHARE = "?sharecode=AY7S4GPRRP";
    // 更新通道：测试版开关（Settings.testBuild）打开后走测试通道，否则走正式通道
    static final String VER_URL = BASE + "version.txt" + SHARE;        // 正式通道版本号
    static final String APK_URL = BASE + "latest.apk" + SHARE;          // 正式通道安装包
    static final String LOG_URL = BASE + "changelog.txt" + SHARE;       // 正式通道更新日志
    static final String VER_TEST = BASE + "version_test.txt" + SHARE;   // 测试通道版本号
    static final String APK_TEST = BASE + "latest_test.apk" + SHARE;     // 测试通道安装包
    static final String LOG_TEST = BASE + "changelog_test.txt" + SHARE; // 测试通道更新日志
    static final String PKG = "com.ljone.car3d";
    /** 下载上限：正常包 7MB 左右，给足余量；服务端异常返回超大流时不至于把车机存储写满 */
    private static final int MAX_APK = 256 * 1024 * 1024;
    /** 文本响应上限：version.txt / changelog.txt 都只有几 KB */
    private static final int MAX_TEXT = 256 * 1024;
    /** 是否开启测试通道：读 Settings 的生效值（含默认值）。
     *  不能直接读 prefs 原始 JSON——缺这个键时 optBoolean 给 false，
     *  测试版 APK（默认开）覆盖安装后就会误走正式通道、显示正式日志。 */
    static boolean testBuild(Context c) {
        Settings s = Settings.inst();
        if (s != null) return s.bool("testBuild");
        try { // Settings 还没建好时的兜底
            android.content.SharedPreferences p = c.getSharedPreferences("d3", Context.MODE_PRIVATE);
            org.json.JSONObject o = new org.json.JSONObject(p.getString("settings", "{}"));
            return o.optBoolean("testBuild", false);
        } catch (Exception e) { return false; }
    }

    /** 缓存安装包（下载中 / 安装中都在这里，安装成功后再删） */
    static File cacheApk(Context c) { return new File(c.getCacheDir(), "update.apk"); }

    interface CheckCb { void onLatest(); void onUpdate(String remoteName, String notes); void onError(String msg); }
    interface DownCb { void onProgress(int percent); void onDone(); void onError(String msg); }

    /** 拉远端版本，和本地 versionCode 比：远端更大=有更新；同时拉取更新日志（拉不到则为空，不阻断） */
    static void check(final Context c, final CheckCb cb) {
        new Thread(() -> {
            try {
                String txt = get(testBuild(c) ? VER_TEST : VER_URL, 8000);
                String[] lines = txt.split("\n");
                String remoteName = lines.length >= 1 ? lines[0].trim() : "";
                int remote = -1;
                if (lines.length >= 2) { try { remote = Integer.parseInt(lines[1].trim()); } catch (NumberFormatException ignored) { } }
                int local = localCode(c);
                // 日志要在工作线程拉：main() 里做网络会抛 NetworkOnMainThreadException（异常被吞掉就成了"没有日志"）
                if (remote > local) { final String notes = fetchNotes(c); main(() -> cb.onUpdate(remoteName, notes)); }
                else if (remote < 0) { // 没拿到 code：退化比对版本名
                    if (!remoteName.isEmpty() && !remoteName.equals(localName(c))) { final String notes = fetchNotes(c); main(() -> cb.onUpdate(remoteName, notes)); }
                    else main(cb::onLatest);
                } else main(cb::onLatest);
            } catch (Exception e) {
                main(() -> cb.onError("检查更新失败：" + e.getMessage()));
            }
        }, "upd-check").start();
    }

    /** 拉远端更新日志文本（纯文本，多行）；失败返回空串。必须在工作线程调用（主线程做网络会被系统抛异常） */
    private static String fetchNotes(Context c) {
        for (int i = 0; i < 3; i++) { // 重试两次：车机网络偶尔不稳，别让日志就这么没了
            try {
                String t = get(testBuild(c) ? LOG_TEST : LOG_URL, 6000);
                if (t != null && !t.trim().isEmpty()) return t.trim();
            } catch (Exception e) {
                Log.w(Uiplus.TAG, "拉更新日志失败（第 " + (i + 1) + " 次）：" + e.getMessage());
                if (i < 2) { try { Thread.sleep(600); } catch (InterruptedException ignored) { } }
            }
        }
        return "";
    }

    /** 更新日志分段：{当前版本那一段, 历史版本各段}。按空行分段，第一段是当前版本（对不上版本号就取第一段） */
    static String[] splitNotes(String notes, String remoteName) {
        String[] out = { "", "" };
        if (notes == null) return out;
        List<String> blocks = new ArrayList<>();
        for (String b : notes.trim().split("\\n\\s*\\n")) {
            String t = b.trim();
            if (!t.isEmpty()) blocks.add(t);
        }
        if (blocks.isEmpty()) return out;
        int cur = -1;
        if (remoteName != null && !remoteName.isEmpty()) {
            for (int i = 0; i < blocks.size(); i++) {
                String head = blocks.get(i).split("\\n")[0].trim();
                if (head.equalsIgnoreCase("v" + remoteName) || head.equalsIgnoreCase(remoteName)) { cur = i; break; }
            }
        }
        if (cur < 0) cur = 0; // 版本号对不上：日志是最新的在最前，就当第一段是当前版本
        StringBuilder rest = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            if (i == cur) continue;
            if (rest.length() > 0) rest.append("\n\n");
            rest.append(blocks.get(i));
        }
        out[0] = blocks.get(cur);
        out[1] = rest.toString();
        return out;
    }

    /** 下载 latest.apk 到缓存（带进度回调），下完只回调 onDone，是否安装由 UI 按钮决定 */
    static void downloadAndInstall(final Activity a, final DownCb cb) {
        File out = cacheApk(a);
        try { File p = out.getParentFile(); if (p != null) p.mkdirs(); } catch (Exception ignored) { }
        new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(testBuild(a) ? APK_TEST : APK_URL).openConnection();
                h.setConnectTimeout(10000); h.setReadTimeout(30000);
                int rc = h.getResponseCode();
                if (rc < 200 || rc >= 300) throw new IOException("HTTP " + rc);
                int total = h.getContentLength();
                if (total > MAX_APK) throw new IOException("安装包过大：" + total + " 字节");
                InputStream in = h.getInputStream();
                OutputStream os = new FileOutputStream(out);
                byte[] buf = new byte[65536]; long got = 0; int n, lastP = -2;
                try {
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n); got += n;
                        if (got > MAX_APK) throw new IOException("下载内容超出上限，已中止");
                        // 进度只在百分比变化时回调：原来每 64KB 就往主线程 post 一次，7MB 的包会刷上百条消息
                        int p = total > 0 ? (int) (got * 100 / total) : -1;
                        if (p != lastP) { lastP = p; main(() -> cb.onProgress(p)); }
                    }
                } finally { in.close(); os.close(); h.disconnect(); }
                // 纵深防御：先确认下下来的包和当前装的是同一把签名，再交给系统安装器
                if (!sameSigner(a, out)) { out.delete(); main(() -> cb.onError("安装包签名与当前应用不一致，已放弃安装")); return; }
                main(cb::onDone);
            } catch (Exception e) {
                main(() -> cb.onError("下载失败：" + e.getMessage()));
            }
        }, "upd-dl").start();
    }

    /** 用缓存里的 update.apk 触发安装（下载完成后由弹窗「安装」按钮调用） */
    static void install(Activity a) { install(a, cacheApk(a)); }

    /** 下载完成后触发安装：先在主线程弹系统安装器（最通用、必弹确认界面），失败再回退 PackageInstaller，再不行引导手动 */
    private static void install(final Activity a, File apk) {
        main(() -> {
            // 1) 主路径：系统安装器（ACTION_VIEW + FileProvider）——车机/各 ROM 最通用，会弹出安装确认界面
            try {
                Uri uri = UpdateFileProvider.uri(a);
                Intent v = new Intent(Intent.ACTION_VIEW);
                v.setDataAndType(uri, "application/vnd.android.package-archive");
                v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(v);
                return;
            } catch (Exception e) {
                Log.w("car3d", "ACTION_VIEW 安装失败，回退 PackageInstaller", e);
            }
            // 2) 回退：PackageInstaller 会话安装（能拿到安装完成回调删缓存）
            try {
                PackageManager pm = a.getPackageManager();
                PackageInstaller installer = pm.getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(PKG);
                int id = installer.createSession(sp);
                PackageInstaller.Session session = installer.openSession(id);
                try (OutputStream os = session.openWrite("update", 0, apk.length());
                     InputStream is = new FileInputStream(apk)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    session.fsync(os);
                }
                Intent intent = new Intent(a, InstallDoneReceiver.class);
                intent.setAction(InstallDoneReceiver.ACTION);
                PendingIntent pi = PendingIntent.getBroadcast(a, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
                session.commit(pi.getIntentSender());
            } catch (Exception e) {
                Log.e("car3d", "PackageInstaller 安装也失败", e);
                // 多半是没授权「未知来源安装」：引导去开，再不行提示手动
                try {
                    if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
                        a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + PKG)));
                        Toast.makeText(a, "请在设置中允许「安装未知应用」后重新点击更新", Toast.LENGTH_LONG).show();
                        return;
                    }
                } catch (Exception ignored) { }
                Toast.makeText(a, "自动安装失败，请在文件管理器中手动安装 update.apk", Toast.LENGTH_LONG).show();
            }
        });
    }

    static int localCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; } catch (Exception e) { return -1; }
    }
    static String localName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }
    /** 取文本：限制响应体大小（原来用 Scanner "\\A" 一把全读，服务端异常返回超大内容会直接吃掉内存） */
    private static String get(String url, int timeout) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(timeout); h.setReadTimeout(timeout);
        int rc = h.getResponseCode();
        if (rc < 200 || rc >= 300) { h.disconnect(); throw new IOException("HTTP " + rc); }
        InputStream in = h.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n, sum = 0;
        try {
            while ((n = in.read(b)) > 0) {
                sum += n;
                if (sum > MAX_TEXT) throw new IOException("响应过大（超过 " + MAX_TEXT + " 字节）");
                bos.write(b, 0, n);
            }
        } finally { in.close(); h.disconnect(); }
        return new String(bos.toByteArray(), "UTF-8");
    }
    /** 主线程 Handler 复用一份：原来每次 post 都 new 一个 Handler，下载时每块数据回一次进度 → 白造上百个对象 */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static void main(Runnable r) { MAIN.post(r); }

    /** 下完先比对签名：CDN/传输被换包时签名和当前装的对不上就直接放弃（系统安装器本身也会校验，这里提前拦一道） */
    private static boolean sameSigner(Context c, File apk) {
        try {
            PackageManager pm = c.getPackageManager();
            PackageInfo arc = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            if (arc == null) return false;                 // 包打不开 / 下载损坏
            PackageInfo inst = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
            if (inst == null) return true;                 // 取不到已装包信息就不拦，交给系统安装器
            String a = firstSig(inst), b = firstSig(arc);
            return a != null && a.equals(b);
        } catch (Exception e) { return true; }
    }
    private static String firstSig(PackageInfo pi) {
        if (pi == null || pi.signatures == null || pi.signatures.length == 0) return null;
        return pi.signatures[0].toCharsString();
    }
}
