package com.ljone.car3d;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 高德导航 SDK 红绿灯数据适配器（实验分支）。
 *
 * 设计要点：
 *  1) 全部通过【反射】调用 com.amap.api.navi.*，因此本文件在【未集成高德 AAR 时也能编译通过】，
 *     运行时仅当真机带 SDK、且 AmapConfig.ENABLED=true 才真正工作；否则静默降级（无数据）。
 *  2) 红绿灯倒计时是高德【导航引导派生数据】：必须在导航（或模拟导航）中、且前方路口在高德数据
 *     覆盖内，才会通过 AMapNaviListener 的灯相关回调吐出（灯色 + 剩余秒 + 距路口距离）。
 *     所以要拿到真实数据，还得先规划一条路线并 startNavi（见 setRoute / startNavi 注释）。
 *  3) 灯回调方法名随 SDK 版本变动，这里用「方法名含 trafficlight/lightinfo」宽匹配 + 反射读字段，
 *     对版本有容忍度；真集成时请按引入版本的 AMapNaviListener / TrafficLightInfo JavaDoc 校准。
 */
public final class AmapTraffic implements TrafficLightSource {
    private static final String TAG = "AmapTraffic";
    private final LightInfo cur = new LightInfo();
    private Context ctx;
    private Object navi;        // AMapNavi 实例
    private Object listener;    // AMapNaviListener 动态代理

    @Override public void attach(Context c) {
        this.ctx = c.getApplicationContext();
        try {
            Class<?> naviCls = Class.forName("com.amap.api.navi.AMapNavi");
            // 设 Key（两种常见写法都试一次）
            try { naviCls.getMethod("setApiKey", Context.class, String.class).invoke(null, ctx, AmapConfig.KEY); }
            catch (Throwable e) { try { naviCls.getMethod("setApiKey", String.class).invoke(null, AmapConfig.KEY); } catch (Throwable ignored) {} }
            // 取单例
            navi = naviCls.getMethod("getInstance", Context.class).invoke(null, ctx);
            if (navi == null) { Log.w(TAG, "AMapNavi.getInstance 返回 null（SDK 可能未集成）"); return; }
            // 动态代理 AMapNaviListener
            Class<?> lisCls = Class.forName("com.amap.api.navi.AMapNaviListener");
            listener = Proxy.newProxyInstance(lisCls.getClassLoader(), new Class[]{ lisCls }, (InvocationHandler) this::onListener);
            naviCls.getMethod("addAMapNaviListener", lisCls).invoke(navi, listener);
            // 尝试进入 GPS 导航（真实灯数据需要处于导航中；无路线时不会出灯，需先 setRoute）
            try { naviCls.getMethod("startNavi", int.class).invoke(navi, 1); Log.i(TAG, "已尝试 startNavi(GPS)"); }
            catch (Throwable e) { Log.w(TAG, "startNavi 失败（需先规划路线才有灯数据）: " + e.getMessage()); }
            Log.i(TAG, "高德红绿灯适配器已挂载");
        } catch (Throwable e) {
            Log.w(TAG, "高德 SDK 未集成或不可用，红绿灯走空: " + e.getMessage());
        }
    }

    /** AMapNaviListener 代理：拦截灯相关回调，转成 LightInfo。 */
    private Object onListener(Object proxy, Method method, Object[] args) {
        String name = method.getName().toLowerCase();
        if (name.contains("trafficlight") || name.contains("lightinfo")) {
            try { parseLight(args); } catch (Throwable t) { Log.w(TAG, "解析灯回调异常: " + t.getMessage()); }
        }
        return null; // AMapNaviListener 方法是 void，统一返回 null 安全
    }

    private void parseLight(Object[] args) {
        if (args == null) return;
        int state = LightInfo.UNKNOWN, remain = 0, dist = 0, id = -1;
        for (Object a : args) {
            if (a == null) continue;
            String cn = a.getClass().getName().toLowerCase();
            if (cn.contains("trafficlight")) {           // 形如 TrafficLightInfo 对象
                state  = getInt(a, "getStatus",      "status",      state);
                remain = getInt(a, "getRemainTime",  "remainTime",  remain);
                dist   = getInt(a, "getDistance",    "distance",    dist);
                id     = getInt(a, "getLightId",     "lightId",     id);
            } else if (a instanceof Integer) {            // 旧版直接传基本类型参数
                int v = (Integer) a;
                if (state == LightInfo.UNKNOWN && (v >= 1 && v <= 4)) state = v;
                else if (remain == 0 && v > 0 && v < 600) remain = v;
                else if (dist == 0 && v > 0) dist = v;
            }
        }
        cur.state = (state >= 1 && state <= 4) ? state : LightInfo.UNKNOWN;
        cur.remainSec = Math.max(0, remain);
        cur.distanceM = Math.max(0, dist);
        cur.lightId = id;
        cur.ts = System.currentTimeMillis();
    }

    private static int getInt(Object obj, String m1, String m2, int def) {
        try { return (Integer) obj.getClass().getMethod(m1).invoke(obj); }
        catch (Throwable e) { try { return obj.getClass().getField(m2).getInt(obj); } catch (Throwable e2) { return def; } }
    }

    /** 规划一条路线（反射调用 calculateDriveRouteAsyn），有路线 startNavi 后才会出灯数据。实验用，传起终点经纬度即可。 */
    public void setRoute(double slat, double slon, double elat, double elon) {
        if (navi == null) return;
        try {
            Class<?> ptCls = Class.forName("com.amap.api.navi.model.AMapNaviPoint");
            Object sp = ptCls.getMethod("builder", double.class, double.class).invoke(null, slat, slon);
            Object ep = ptCls.getMethod("builder", double.class, double.class).invoke(null, elat, elon);
            Class<?> strat = Class.forName("com.amap.api.navi.enums.PathPlanningStrategy");
            Object strategy = java.lang.reflect.Array.get(strat.getEnumConstants(), 0);
            navi.getClass().getMethod("calculateDriveRouteAsyn", ptCls, ptCls, strat).invoke(navi, sp, ep, strategy);
        } catch (Throwable e) { Log.w(TAG, "规划路线失败: " + e.getMessage()); }
    }

    @Override public void detach() {
        try { if (navi != null && listener != null)
            navi.getClass().getMethod("removeAMapNaviListener", Class.forName("com.amap.api.navi.AMapNaviListener")).invoke(navi, listener); } catch (Throwable ignored) {}
        try { if (navi != null) navi.getClass().getMethod("stopNavi").invoke(navi); } catch (Throwable ignored) {}
    }

    @Override public LightInfo current() { return cur; }
}
