package com.ljone.car3d;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 车况数据（照网页版 demo3d/data.js）：宿主给外部应用的接口（Uiplus）订阅车况、顶栏要的系统数据、媒体（氛围灯音乐律动时才订）。
 * 新版宿主的数字编码按下面的表解码成和网页版一样的值（挡位 'P'、电源 'ready'、开关 true/false……），之后的推送只带变化的叶子（逐叶子合并）。
 * 只订要的：车况页常驻订阅 FIELDS；底部数据选了别的数值才另外加进来（extra）；字段先和宿主的能力目录对一遍（订阅里有一个宿主没有的字段整个订阅都会失败）。
 * 订阅失败：后端没好、超时这类一直试（2、4、8、16 秒，之后每 30 秒，同琉光原生版），别的错最多 6 次。全部在主线程上
 */
final class CarData {
    interface Listener { void car(Map<String, Object> car); void sys(Map<String, Object> sys); void media(JSONObject media); }

    static final List<String> FIELDS = Arrays.asList(
        "power.level", "driving.gear", "driving.speed", "display.dayNightMode",
        "energy.batteryPercent", "energy.fuelPercent", "energy.electricRange", "energy.fuelRange", "energy.chargingState",
        "ac.outdoorTemperature", "ac.indoorTemperature", "driving.totalDistance",
        "body.doors.frontLeft.state", "body.doors.frontRight.state", "body.doors.rearLeft.state", "body.doors.rearRight.state",
        "body.hood.state", "body.tailgate.state", "body.sunroof.position", "body.windows.anyOpen",
        "body.windows.frontLeft.position", "body.windows.frontRight.position", "body.windows.rearLeft.position", "body.windows.rearRight.position",
        "light.lowBeam", "light.highBeam", "light.turnSignal.left", "light.turnSignal.right", "light.hazard", "light.brake",
        "light.daytimeRunning", "light.side", "light.frontFog", "light.rearFog", "light.ambient.color", "light.ambient.brightness",
        "light.ambient.area", "light.ambient.musicMode", "light.dynamicColorful",
        "body.sunshade.position", "energy.connectorState",
        "tyre.frontLeft.pressure", "tyre.frontRight.pressure", "tyre.rearLeft.pressure", "tyre.rearRight.pressure",
        "tyre.frontLeft.state", "tyre.frontRight.state", "tyre.rearLeft.state", "tyre.rearRight.state", "tyre.displayUnit",
        "energy.charging.remainingTime", "warning.lowVoltageBatteryFault", "warning.keyBatteryLow",
        "ac.power", "ac.compressor", "ac.driverTemperature", "ac.fanLevel", "ac.circulation", "ac.frontDefrost", "ac.rearDefrost",
        "ac.controlMode", "ac.windMode", "ac.rear.power", "ac.rear.temperature", "ac.rear.fanLevel", "ac.rear.controlMode", "ac.rear.windMode",
        "seat.driver.heating", "seat.passenger.heating", "seat.driver.ventilation", "seat.passenger.ventilation",
        "seat.driver.beltFastened",
        "energy.operationMode", "energy.mode", "energy.regenerationLevel", "energy.simulator", "energy.socSave", "energy.socTarget",
        "energy.wirelessCharging", "energy.wirelessChargingState", "environment.pm25.indoor", "environment.pm25.indoorLevel", "environment.slope", "engine.rpm", "audio.muted",
        "tyre.frontLeft.temperature", "tyre.frontRight.temperature", "tyre.rearLeft.temperature", "tyre.rearRight.temperature");
    static final List<String> SYS = Arrays.asList("network.transport", "network.validated", "wifi.enabled", "wifi.connected", "wifi.signalLevel", "bluetooth.enabled", "bluetooth.connectionState",
        "cellular.displayType", "cellular.signalLevel", "cellular.simState", "cellular.dataState", "airplane.enabled", "hotspot.enabled", "gps.enabled", "gps.permissionGranted");

    // ---------------- 新版宿主的数字编码 → 和旧版一样的值 ----------------
    private static final Map<String, Map<Integer, Object>> DEC = new HashMap<>();
    private static Map<Integer, Object> m(Object... kv) { Map<Integer, Object> o = new HashMap<>(); for (int i = 0; i < kv.length; i += 2) o.put((Integer) kv[i], kv[i + 1]); return o; }
    static {
        Map<Integer, Object> B01 = m(0, false, 1, true), B12 = m(1, true, 2, false), OC = m(0, "closed", 1, "open"), SEAT = m(1, 0.0, 2, 1.0, 3, 2.0);
        Map<Integer, Object> AUTO = m(0, "auto", 1, "manual"), WIND = m(1, "face", 2, "faceFeet", 3, "feet", 4, "feetScreen", 5, "screen", 6, "faceFeetScreen", 7, "faceScreen");
        DEC.put("driving.gear", m(0, "N", 1, "R", 2, "D", 3, "P"));
        DEC.put("power.level", m(0, "off", 1, "acc", 2, "on", 3, "ready", 4, "fakeReady"));
        DEC.put("energy.chargingState", m(1, "ready", 2, "charging", 3, "finished", 4, "terminated"));
        for (String k : new String[] { "body.doors.frontLeft.state", "body.doors.frontRight.state", "body.doors.rearLeft.state", "body.doors.rearRight.state", "body.hood.state", "body.tailgate.state" }) DEC.put(k, OC);
        for (String k : new String[] { "body.windows.anyOpen", "light.lowBeam", "light.highBeam", "light.turnSignal.left", "light.turnSignal.right", "light.side", "light.frontFog", "light.rearFog",
            "warning.lowVoltageBatteryFault", "seat.driver.beltFastened", "ac.power", "ac.compressor", "ac.frontDefrost", "ac.rearDefrost", "energy.simulator", "body.windows.ventilation",
            "body.windows.halfOpen", "audio.muted", "ac.rear.power" }) DEC.put(k, B01);
        for (String k : new String[] { "light.hazard", "light.daytimeRunning", "light.ambient.musicMode", "light.dynamicColorful", "warning.keyBatteryLow", "energy.wirelessCharging" }) DEC.put(k, B12);
        DEC.put("light.brake", m(1, "on", 2, "flash", 3, "off"));
        DEC.put("light.ambient.area", m(1, "front", 2, "rear", 3, "all"));
        DEC.put("light.ambient.color", m(1, "iceBlue", 2, "coldWhite", 3, "warmWhite", 4, "redOrange", 5, "red", 6, "redPurple", 7, "darkBlue"));
        DEC.put("energy.connectorState", m(1, "none", 2, "ac", 3, "dc", 4, "acDc", 5, "vtol"));
        DEC.put("tyre.displayUnit", m(1, "bar", 2, "psi", 3, "kPa"));
        DEC.put("display.dayNightMode", m(16, "day", 32, "night"));
        for (String k : new String[] { "seat.driver.heating", "seat.passenger.heating", "seat.driver.ventilation", "seat.passenger.ventilation" }) DEC.put(k, SEAT);
        DEC.put("ac.circulation", m(0, "outside", 1, "inside"));
        DEC.put("energy.operationMode", m(1, "eco", 2, "sport", 3, "comfort"));
        DEC.put("energy.mode", m(1, "ev", 3, "hev"));
        DEC.put("energy.regenerationLevel", m(1, "standard", 2, "high"));
        DEC.put("energy.socSave", m(1, false, 2, true));
        DEC.put("energy.wirelessChargingState", m(0, "charging", 1, "full", 2, "fault", 3, "standby"));
        DEC.put("energy.charging.type", m(1, "default", 2, "ac", 3, "vtog", 4, "gbDc", 5, "gbNonDc"));
        DEC.put("ac.controlMode", AUTO); DEC.put("ac.rear.controlMode", AUTO); DEC.put("ac.windMode", WIND); DEC.put("ac.rear.windMode", WIND);
    }
    private static final Map<String, Map<Integer, String>> UNIT_NAME = new HashMap<>();
    static {
        // 电耗 / 油耗单位（uiplus 接口规范：能耗字段单位经 unitOf 解析）
        Map<Integer, String> kwh = new HashMap<>(); kwh.put(1, "kWh"); kwh.put(2, "kWh"); kwh.put(3, "kWh"); kwh.put(4, "kWh");
        Map<Integer, String> ltr = new HashMap<>(); ltr.put(1, "L"); ltr.put(2, "L"); ltr.put(3, "L"); ltr.put(4, "L");
        UNIT_NAME.put("energy.consumption.averageElectric", kwh);
        UNIT_NAME.put("energy.consumption.recentElectric", kwh);
        UNIT_NAME.put("energy.consumption.instantElectric", kwh);
        UNIT_NAME.put("energy.consumption.averageFuel", ltr);
        UNIT_NAME.put("energy.consumption.recentFuel", ltr);
        UNIT_NAME.put("energy.consumption.instantFuel", ltr);
        Map<Integer, String> c = new HashMap<>(); c.put(1, "km"); c.put(2, "mi"); UNIT_NAME.put("driving.journeyDistance", c);
    }
    private static final double RANGE_BAD = 2046;

    private final Host host;
    private final Listener out;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Object> car = new LinkedHashMap<>(), sys = new LinkedHashMap<>();
    private final Map<String, String> units = new HashMap<>();
    private final JSONObject tree = new JSONObject(), stree = new JSONObject();
    private JSONObject media;
    // 已上屏的曲目号与时间戳：换歌（trackId 变了）必须立即上屏，同一首歌才按 updatedAt 去重，
    // 避免旧快照（上一首的进度回灌、或新歌首帧时间戳略小）被误丢或误覆盖
    private String appliedTid = "";
    private long appliedUa = -1;
    private Map<String, String> caps;
    private List<String> fields = FIELDS, extra = new ArrayList<>();
    private boolean wantCar, wantMedia;
    private String session, carId, sysId, mediaId;
    private int seq, tries, gen;

    CarData(Host host, Listener out) { this.host = host; this.out = out; }
    synchronized String unit(String k) { return units.get(k); }
    List<String> fields() { return new ArrayList<>(fields); }

    /** 只订这些（同 use）：car 车况 + 系统数据、media 媒体；不要了的退订 */
    void use(boolean c, boolean m) {
        if (c == wantCar && m == wantMedia) return;
        if (!c) { unsub("vehicle", carId); unsub("system", sysId); carId = sysId = null; }
        if (!m) { unsub("media", mediaId); mediaId = null; }
        wantCar = c; wantMedia = m;
        gen++;
        main.removeCallbacks(retry);
        start();
    }
    /** 底部数据选了的字段：和常驻的一起订（变了才重订车况） */
    void extra(List<String> keys) {
        List<String> k = new ArrayList<>();
        for (String x : keys) if (x != null && !k.contains(x)) k.add(x);
        Collections.sort(k);
        if (k.equals(extra)) return;
        extra = k;
        if (!wantCar) return;
        unsub("vehicle", carId);
        carId = null;
        gen++;
        main.removeCallbacks(retry);
        start();
    }
    /** 退订（同 SDK 的 handle.unsubscribe：方法名把 .subscribe 换成 .unsubscribe）；不要结果 */
    private void unsub(String ns, String id) {
        if (id == null || session == null || !session.equals(host.session())) return;
        try { host.request(ns + ".unsubscribe", new JSONObject().put("subscriptionId", id), null); } catch (Exception ignored) { }
    }
    /** 宿主能做的原生操作（能力目录的 actions）；还没拿到时是空的 */
    Set<String> actions() { return new HashSet<>(actions); }
    private final Set<String> actions = new HashSet<>();
    Runnable onActions;

    /** 宿主连上、授权变了、后端好了：重订 */
    void hostChanged() {
        String s = host.ready() && host.canRead() ? host.session() : null;
        if (s == null) { session = null; return; }
        if (!s.equals(session)) { session = s; caps = null; carId = sysId = mediaId = null; tries = 0; actions.clear(); if (onActions != null) onActions.run(); }
        if (host.backendReady()) start();
    }
    private final Runnable retry = this::start;
    /** 过一会儿就会好的错（后端没好、超时、宿主内部出错）；没权限、参数不对、字段不认识、宿主不支持、订阅太多这类再试也没用 */
    private static boolean transientErr(String code) {
        return !Arrays.asList("PERMISSION_DENIED", "INVALID_ARGUMENT", "UNKNOWN_FIELD", "UNSUPPORTED", "RESOURCE_LIMIT").contains(code);
    }
    private void fail(int g, String code) {
        if (g != gen || (!transientErr(code) && tries >= 6)) return;
        main.removeCallbacks(retry);
        main.postDelayed(retry, Math.min(30000, 2000L << Math.min(tries, 4)));
        tries++;
    }
    private void start() {
        final String s = session;
        if (s == null || !host.backendReady() || !s.equals(host.session())) return;
        final int g = gen;
        if (caps == null) {
            host.request("capabilities", null, (r, code, msg) -> {
                if (g != gen || !s.equals(host.session())) return;
                if (code != null) { fail(g, code); return; }
                caps = new HashMap<>();
                JSONArray f = r.optJSONArray("fields");
                for (int i = 0; f != null && i < f.length(); i++) { JSONObject o = f.optJSONObject(i); if (o != null && o.has("name")) caps.put(o.optString("name"), o.optString("subscriptionMode", "native")); }
                JSONArray a = r.optJSONArray("actions");
                actions.clear();
                for (int i = 0; a != null && i < a.length(); i++) { Object o = a.opt(i); String n = o instanceof JSONObject ? ((JSONObject) o).optString("method") : String.valueOf(o); if (!n.isEmpty()) actions.add(n); }
                if (onActions != null) onActions.run();
                start();
            });
            return;
        }
        List<String> fl = new ArrayList<>();
        List<String> all = new ArrayList<>(FIELDS);
        for (String k : extra) if (!all.contains(k)) all.add(k);
        for (String k : all) { String m = caps.get(k); if (m != null && !"none".equals(m)) fl.add(k); }
        fields = fl;
        if (wantCar && carId == null && !fl.isEmpty()) {
            final String id = carId = "car" + (++seq);
            try {
                host.request("vehicle.subscribe", new JSONObject().put("subscriptionId", id).put("fields", new JSONArray(fl)), (r, code, msg) -> {
                    if (!id.equals(carId)) return;
                    if (code != null) { Log.w(Uiplus.TAG, "订阅车况失败：" + code + " " + msg); carId = null; fail(g, code); return; }
                    tries = 0;
                });
            } catch (Exception ignored) { }
        }
        if (wantCar && sysId == null) { // 系统数据订不上不影响车况
            final String id = sysId = "sys" + (++seq);
            try { host.request("system.subscribe", new JSONObject().put("subscriptionId", id).put("fields", new JSONArray(SYS)), (r, code, msg) -> { if (code != null) Log.w(Uiplus.TAG, "订阅系统数据失败：" + code + " " + msg); }); } catch (Exception ignored) { }
        }
        if (wantMedia && mediaId == null) {
            final String id = mediaId = "media" + (++seq);
            try {
                host.request("media.subscribe", new JSONObject().put("subscriptionId", id), (r, code, msg) -> {
                    if (!id.equals(mediaId)) return;
                    if (code != null) { mediaId = null; fail(g, code); return; }
                    host.request("media.get", null, (m, c2, m2) -> { if (m != null) onMedia(m); });
                });
            } catch (Exception ignored) { }
        }
    }
    /** 订阅推送（主线程） */
    void onEvent(JSONObject msg) {
        String id = msg.optString("subscriptionId");
        JSONObject d = msg.optJSONObject("data");
        if (d == null) return;
        if (id.equals(carId)) onVehicle(d);
        else if (id.equals(sysId)) onSys(d);
        else if (id.equals(mediaId)) onMedia(d);
    }
    /** 发一个媒体控制命令（play/pause/next/previous/togglePlayPause） */
    void mediaCmd(String action) { try { host.request("media." + action, new JSONObject(), null); } catch (Exception ignored) { } }
    /** 主动问一次媒体快照（订阅推送不可靠时的兜底：推送一停，界面上的歌名、进度、播放态都不再更新） */
    void mediaNow() { try { host.request("media.get", null, (m, c, msg) -> { if (m != null) onMedia(m); }); } catch (Exception ignored) { } }
    /** 拉整首歌词（行列表）；拿不到返回空列表。歌词可能迟到：结果带的 trackId 对不上当前曲目就不采用，空/过期结果间隔重试几次 */
    /** 一行歌词：文本 + 这句在整首里的开始时间（timeMs，宿主没给是 -1）。
     *  照琉光 MediaState：有了每句时间才能「下一句和这句一模一样时按时间自己往下走」、以及按播放位置兜底定位。 */
    static final class LyLine {
        final String t; final long at;
        LyLine(String t, long at) { this.t = t; this.at = at; }
    }
    interface LyricsCb { void on(List<LyLine> lines); }
    /** 一行可能是 {"text":"...","timeMs":...} 的对象，也可能直接就是歌词字符串（时间未知 -1） */
    private static LyLine lineOf(JSONArray a, int i) {
        JSONObject o = a == null ? null : a.optJSONObject(i);
        if (o != null) {
            String t = o.optString("text", "");
            if (t.isEmpty()) t = o.optString("content", "");
            if (t.isEmpty()) t = o.optString("words", "");
            if (t.isEmpty()) t = o.optString("lyric", "");
            return new LyLine(t, o.optLong("timeMs", -1));
        }
        return new LyLine(a == null ? "" : a.optString(i), -1);
    }
    private int lyricsGen = 0; // 换歌/再次拉取会让旧的重试链作废
    void mediaLyrics(String expectTid, LyricsCb cb) {
        int gen = ++lyricsGen;
        fetchLyrics(expectTid == null ? "" : expectTid, cb, gen, 0);
    }
    private void fetchLyrics(final String expectTid, final LyricsCb cb, final int gen, final int attempt) {
        try { host.request("media.getLyrics", new JSONObject(), (r, code, msg) -> {
            if (gen != lyricsGen) return; // 已有更新的拉取，这条作废
            String rtid = r == null ? "" : r.optString("trackId", "");
            if (rtid.equals("null")) rtid = ""; // JSONObject.NULL（宿主没给）
            JSONArray a = r == null ? null : r.optJSONArray("lines");
            if (a == null && r != null) { JSONObject d = r.optJSONObject("data"); if (d != null) a = d.optJSONArray("lines"); } // 有的宿主包一层 data
            List<LyLine> l = new ArrayList<>();
            for (int i = 0; a != null && i < a.length(); i++) l.add(lineOf(a, i));
            boolean stale = !rtid.isEmpty() && !expectTid.isEmpty() && !rtid.equals(expectTid); // 迟到的那份带着自己的曲目号，对不上就不用
            if ((l.isEmpty() || stale) && attempt < 4) { // 歌词可能迟到，空结果不是永久失败：间隔重试
                main.postDelayed(() -> fetchLyrics(expectTid, cb, gen, attempt + 1), 1200L * (attempt + 1));
                return;
            }
            if (cb != null) cb.on(stale ? new ArrayList<>() : l);
        }); } catch (Exception ignored) { if (gen == lyricsGen && cb != null) cb.on(new ArrayList<>()); }
    }

    /** 封面回调：b 是位图（取不到为 null），hash 是内容指纹（取不到为 0），供调用方判断"是不是和上一首那张一模一样" */
    interface CoverCb { void on(android.graphics.Bitmap b, long hash); }

    /** 封面：coverUrl 是会话绑定的资源标识（形如 https://media.uiplus.invalid/<token>），按规范不能发起 HTTP 下载，
     *  要在工作线程走 openCover 拿只读文件描述符解码；读取设 8MB 上限、用完关描述符 */
    void loadCover(String url, CoverCb cb) {
        if (url == null || url.isEmpty()) { main.post(() -> cb.on(null, 0)); return; }
        Workers.io(() -> {
            android.graphics.Bitmap b = null;
            long h = 0;
            android.os.ParcelFileDescriptor pfd = null;
            try {
                pfd = host.openCover(url);
                if (pfd != null) {
                    java.io.InputStream is = new java.io.FileInputStream(pfd.getFileDescriptor());
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n, total = 0;
                    while ((n = is.read(buf)) > 0 && total < 8 * 1024 * 1024) { bo.write(buf, 0, n); total += n; }
                    is.close();
                    byte[] d = bo.toByteArray();
                    if (d.length > 0) { b = android.graphics.BitmapFactory.decodeByteArray(d, 0, d.length); h = hashOf(d); }
                }
            } catch (Exception ignored) {
            } finally { if (pfd != null) try { pfd.close(); } catch (Exception ignored) { } }
            final android.graphics.Bitmap bm = b;
            final long fh = h;
            main.post(() -> cb.on(bm, fh));
        });
    }

    /** 封面内容指纹：照琉光音乐的做法，CRC32 + 长度，用来判断"换歌后取到的还是不是上一首那张" */
    private static long hashOf(byte[] b) {
        if (b == null || b.length == 0) return 0;
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(b);
        return (c.getValue() << 24) ^ b.length;
    }


    private static void merge(JSONObject t, JSONObject p) {
        for (Iterator<String> it = p.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = p.opt(k);
            try {
                if (v instanceof JSONObject) { JSONObject sub = t.optJSONObject(k); if (sub == null) { sub = new JSONObject(); t.put(k, sub); } merge(sub, (JSONObject) v); }
                else t.put(k, v);
            } catch (Exception ignored) { }
        }
    }
    /** 字段名 → 路径段：每推一次车况要对上百个字段各 split 一次，按名字缓存（覆盖运行时 extra，不能只做静态表） */
    private static final Map<String, String[]> PATHS = new HashMap<>();
    private static String[] path(String k) {
        synchronized (PATHS) {
            String[] p = PATHS.get(k);
            if (p == null) { p = k.split("\\."); PATHS.put(k, p); }
            return p;
        }
    }
    /** 从合并后的业务树里取一个字段、解码；没有有效数据返回 null（同 fromTree） */
    private Object fromTree(String k) {
        String[] parts = path(k);
        JSONObject node = tree;
        for (int i = 0; i < parts.length - 1 && node != null; i++) node = node.optJSONObject(parts[i]);
        if (node == null) return null;
        String leaf = parts[parts.length - 1];
        Object v = node.opt(leaf);
        if (v == null || v == JSONObject.NULL) return null;
        Object u = node.opt(leaf + "Unit");
        Integer ui = u instanceof Number ? ((Number) u).intValue() : null;
        if (DEC.containsKey(k)) return v instanceof Number && ((Number) v).doubleValue() % 1 == 0 ? DEC.get(k).get(((Number) v).intValue()) : null;
        if (v instanceof JSONArray) { JSONArray a = (JSONArray) v; double[] d = new double[a.length()]; for (int i = 0; i < d.length; i++) d[i] = a.optDouble(i); return d; }
        if (!(v instanceof Number)) return v;
        double d = ((Number) v).doubleValue();
        if ((k.equals("energy.electricRange") || k.equals("energy.fuelRange")) && d >= RANGE_BAD) return null;
        if (UNIT_NAME.containsKey(k)) { String n = ui == null ? null : UNIT_NAME.get(k).get(ui); synchronized (this) { if (n != null) units.put(k, n); else units.remove(k); } }
        if (k.equals("ac.outdoorTemperature") || k.equals("ac.indoorTemperature") || k.equals("ac.driverTemperature")) return ui != null && ui == 0 ? (d - 32) * 5 / 9 : d;
        if (k.equals("ac.rear.temperature")) return ui == null ? null : ui == 0 ? (d - 32) * 5 / 9 : ui == 1 ? d : null;
        if (k.equals("driving.totalDistance")) return "m".equals(u) ? d / 1000 : "0.1km".equals(u) ? d / 10 : d;
        if (k.startsWith("tyre.") && k.endsWith(".pressure")) {
            if (d == 4092) return "burst";
            if (d == 4093) return "fault";
            return ui != null && ui == 1 ? d * 10 : ui != null && ui == 2 ? d * 0.6894757 : d;
        }
        if (k.startsWith("tyre.") && k.endsWith(".state")) {
            String src = node.optString("stateSource");
            int i = (int) d;
            if ("instrument".equals(src)) return i == 1 ? "normal" : i == 2 ? "warning" : i == 3 ? "critical" : null;
            if ("tyre".equals(src)) return i == 0 ? "normal" : i == 1 ? "high" : i == 2 ? "low" : null;
            return d == 0 ? "normal" : null;
        }
        return d;
    }
    private void onVehicle(JSONObject d) {
        merge(tree, d);
        for (String k : fields) { Object v = fromTree(k); if (v == null) car.remove(k); else car.put(k, v); }
        tries = 0;
        out.car(new LinkedHashMap<>(car));
    }
    private void onSys(JSONObject d) {
        merge(stree, d);
        for (String k : SYS) {
            String[] p = path(k);
            JSONObject n = stree.optJSONObject(p[0]);
            Object v = n == null ? null : n.opt(p[1]);
            if (v == null || v == JSONObject.NULL) sys.remove(k); else sys.put(k, v instanceof Number ? (Object) ((Number) v).doubleValue() : v);
        }
        out.sys(new LinkedHashMap<>(sys));
    }
    private void onMedia(JSONObject m) {
        if (!"ok".equals(m.optString("status"))) { media = null; appliedTid = ""; appliedUa = -1; out.media(null); return; }
        String tid = m.optString("trackId", "");
        long ua = m.optLong("updatedAt");
        // 换歌（trackId 变了）立即上屏，不被 updatedAt 比较误丢；同一首歌才按时间戳去重，拦掉迟到的旧进度/重复包
        if (tid.equals(appliedTid) && ua > 0 && appliedUa > 0 && ua <= appliedUa) return;
        media = m; appliedTid = tid; appliedUa = ua;
        out.media(media);
    }
    Map<String, Object> car() { return new LinkedHashMap<>(car); }
    Map<String, Object> sys() { return new LinkedHashMap<>(sys); }
}
