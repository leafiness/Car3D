package com.x3card.car3d.lg;

import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.view.View;

import javax.microedition.khronos.opengles.GL10;

/**
 * 把琉光 3D 场景接进 car3d 的 Stage。
 * Stage 在第三页（琉光）把整帧 GL 委托给这里；车况页仍走 CarPage，音乐页停 GL。
 * 数据由 Hud 的媒体快照桥接成 MediaState.Snap，不另开宿主会话。
 */
public final class LgHost implements Canvas3D, Scene.Host {
    private final GLSurfaceView glView;
    private final Scene scene;
    private boolean created;
    private int lastW = -1, lastH = -1;
    private int rev;
    private float cssW = 1, cssH = 1, resK = 1;

    public LgHost(GLSurfaceView v, float density) {
        glView = v;
        scene = new Scene(this, this, density);
        scene.setDay(true);
    }

    // ---------- Canvas3D ----------
    @Override public Scene scene() { return scene; }
    @Override public View view() { return glView; }
    @Override public void onPause() { }
    @Override public void onResume() { }
    @Override public void requestRender() { glView.requestRender(); }
    @Override public void queueEvent(Runnable r) { glView.queueEvent(r); }
    @Override public float cssW() { return cssW; }
    @Override public float cssH() { return cssH; }
    @Override public float res() { return resK; }
    @Override public void setRes(float k) { resK = k; }

    /** MainActivity 量到窗口尺寸后告诉场景（CSS 像素） */
    public void size(float w, float h) { cssW = w; cssH = h; }

    // ---------- Scene.Host：car3d 已有自己的播控，这里只留空实现 ----------
    @Override public void press(String cmd) { }
    @Override public void seek(long ms) { }
    @Override public void btnWait(boolean w) { }
    @Override public void playIndex(int i) { }
    @Override public void shelfState(boolean open) { }
    @Override public void locate(int dir, float y) { }
    @Override public void coverAt(float l, float t, float r, float b) { }

    // ---------- Stage 调用 ----------
    /** 第一次进第三页时补一次 surface 创建；之后每帧直接委托 */
    public void draw(GL10 gl, int w, int h) {
        if (!created) {
            scene.onSurfaceCreated(gl, null);
            created = true;
            lastW = -1; // 建完 GL 资源后必须补一次尺寸
        }
        if (w != lastW || h != lastH) { // 只在尺寸变化时重置，不能每帧调 onSurfaceChanged
            lastW = w; lastH = h;
            scene.onSurfaceChanged(gl, w, h);
        }
        scene.onDrawFrame(gl);
    }

    /** 画布尺寸变化（分屏/旋屏）：打个标记，让下一帧在 GL 线程里补尺寸 */
    public void surfaceChanged(int w, int h) { lastW = -1; lastH = -1; }

    /** 离开页面时清 GL 缓存，避免和车况页的 VAO/FBO/program 串味 */
    public void leave() { G.resetCache(); }

    // ---------- 媒体数据桥接 ----------
    public void media(boolean on, boolean playing, String title, String artist, String track,
                      String[] lines, long[] times, int cur, long dur, long pos, Bitmap cover) {
        MediaState.Snap s = new MediaState.Snap();
        s.on = on;
        s.playing = playing;
        s.full = lines != null && lines.length > 0;
        s.rawPlaying = playing;
        s.canPlay = true;
        s.track = track == null ? "" : track;
        s.title = title == null ? "" : title;
        s.artist = artist == null ? "" : artist;
        s.trackCmd = "";
        s.lines = lines == null ? new String[0] : lines;
        s.times = times;
        s.cur = cur;
        s.revTrack = s.revLines = s.revCover = s.revCur = ++rev;
        s.dur = dur;
        s.posBase = pos;
        s.posAt = SystemClock.elapsedRealtime();
        s.cover = cover;
        s.coverTrack = s.track;
        s.heart = BydMedia.HIDE;
        scene.setSnap(s);
    }
}
