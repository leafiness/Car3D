package com.ljone.car3d;

import android.content.Context;

/**
 * 红绿灯数据源（可插拔）。两种实现：
 *  - SimTraffic：模拟源，无需任何 SDK，直接看到顶栏倒计时跳动（默认，用于实验/演示）。
 *  - AmapTraffic：高德导航 SDK 派生源，用反射调用 com.amap.api.navi.*，
 *    未集成 AAR 时也能编译，运行时若真机带 SDK 且 AmapConfig.ENABLED=true 才生效。
 *
 * Hud 顶栏每秒（clockTick）拉一次 current() 渲染；调用方自行判 valid()。
 */
public interface TrafficLightSource {
    /** 挂载（拿到 Context，开始产生数据）。 */
    void attach(Context c);

    /** 卸载（停止定时器 / 停止导航）。 */
    void detach();

    /** 当前最新快照（可能无效，调用方需判 valid()）。 */
    LightInfo current();
}
