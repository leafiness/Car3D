package com.ljone.car3d;

/** 高德接入配置（实验分支专用）。正式版默认不启用。 */
public final class AmapConfig {
    /** 高德开放平台 Key：包名 com.ljone.car3d + release.keystore 的 SHA1 已绑定。 */
    public static final String KEY = "c0c78b271e5a7f0a76aa703eeda547bf";

    /**
     * 是否启用高德真实红绿灯数据。
     *  false（默认）= 用 SimTraffic 模拟源，无需 SDK 即可在顶栏看到倒计时跳动。
     *  改为 true 前，必须先把高德【导航 SDK】的 AAR / .so / 依赖打进构建（见下方说明），
     *  否则运行时静默降级、拿不到数据。
     *
     * 接入真实数据还需：
     *  1) 高德控制台为 Key 开启「导航 SDK」且配额足够（车载/商用需购买技术服务许可）；
     *  2) 把 SDK 集成进本工程的自定义 build.mjs（aapt 资源、dex 化 classes.jar、拷入 armeabi-v7a/arm64-v8a 的 .so）；
     *  3) 实际导航中才有灯数据——用 AmapTraffic.setRoute(lat,lon,lat,lon) 规划路线并 startNavi，
     *     或在宿主已导航时由高德回调驱动；
     *  4) 按引入的 SDK 版本，核对 AMapNaviListener 灯回调方法名（AmapTraffic.parseLight 已做宽匹配）。
     */
    public static final boolean ENABLED = false;

    private AmapConfig() {}
}
