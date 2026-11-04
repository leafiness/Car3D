package com.ljone.car3d;

/**
 * 车况页 ↔ 音乐卡片页 的切换：一条时间线，两边共用。
 *
 * 主线程（画布层 Hud）起头：定好目标页和滑动方向，然后推进进度 t。
 * 画布层按 t 摆音乐卡片页的位置，GL 层按同一个 t 摆 3D 车模的位置
 * → 两页严丝合缝地并排滑动，不会一边快一边慢（那正是「不丝滑」的根源），
 * 也不会两层半透明叠在一起（那正是「残影」的根源）。
 *
 * t = 0 车况页完全显示，t = 1 音乐卡片页完全显示。
 * dir = +1 内容向右走，-1 向左走。
 */
final class Slide {
    /** 进度：0 = 车况页，1 = 音乐卡片页 */
    volatile float t;
    /** 滑动方向：+1 内容向右，-1 向左 */
    volatile int dir = -1;
    /** 目标页是音乐卡片页吗 */
    volatile boolean toMusic = true;

    /**
     * 音乐卡片页左边（页内 x=0）在屏幕上的位置，屏宽的比例：
     * +1 = 整页在右边（还没进来），0 = 就位，-1 = 整页在左边（已经走了）
     */
    float musicFrac() { return (toMusic ? -1f : 1f) * dir * (1f - t); }

    /** 车况页的横移，屏宽的比例：+1 = 往右挪了一整屏 */
    float carFrac() { return (toMusic ? 1f : -1f) * dir * t; }
}
