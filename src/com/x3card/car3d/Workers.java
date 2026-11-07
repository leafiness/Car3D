package com.ljone.car3d;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 后台短任务的公共线程池：io（读字体、读封面文件、binder 小活）与 net（检查更新、搜歌、语音命令）。
 * 原来各处 new Thread 各起一套，页切走了任务照跑、也没法限流；这里固定线程数 + daemon，进程退出即回收。
 * 只收短任务：常驻循环（渲染、监听）不要往这里塞，会占死线程。
 */
public final class Workers {
    private static final ExecutorService IO = pool("work-io", 1, Thread.NORM_PRIORITY - 1);
    private static final ExecutorService NET = pool("work-net", 2, Thread.NORM_PRIORITY);

    /** 磁盘/解码/本地 binder 这类不吃网络的活 */
    public static void io(Runnable r) { IO.execute(r); }
    /** 网络请求（超时由调用方自己控制） */
    public static void net(Runnable r) { NET.execute(r); }

    private static ExecutorService pool(final String name, int n, final int pri) {
        final ThreadFactory f = r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setPriority(pri);
            return t;
        };
        return Executors.newFixedThreadPool(n, f);
    }

    private Workers() { }
}
