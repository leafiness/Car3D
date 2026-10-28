package com.ljone.car3d;

/** 单个路口红绿灯快照（与高德/魔星等数据源解耦的统一模型）。 */
public final class LightInfo {
    public static final int UNKNOWN = 0, RED = 1, YELLOW = 2, GREEN = 3, FLASH_YELLOW = 4;

    public int state = UNKNOWN;     // 灯色，见上方常量
    public int remainSec = 0;       // 当前相位剩余秒（倒计时）
    public int distanceM = 0;       // 距路口距离（米）
    public int lightId = -1;        // 路口/灯组 ID（有的源提供）
    public long ts = 0;             // 数据时间戳（毫秒），用于判定是否过期

    /** 数据是否有效（有灯色且 8 秒内刷新过）。 */
    public boolean valid() {
        return state != UNKNOWN && ts > 0 && (System.currentTimeMillis() - ts) < 8000;
    }

    public void copyFrom(LightInfo o) {
        state = o.state; remainSec = o.remainSec; distanceM = o.distanceM; lightId = o.lightId; ts = o.ts;
    }
}
