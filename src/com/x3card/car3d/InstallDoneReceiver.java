package com.ljone.car3d;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import java.io.File;

/**
 * 安装结果广播（清单注册，跨进程也能收到）：PackageInstaller 提交后系统安装完成会回调到这里，
 * 成功后删掉缓存的安装包，并提示已清除。
 */
public class InstallDoneReceiver extends BroadcastReceiver {
    static final String ACTION = "com.ljone.car3d.UPDATER_DONE";

    @Override public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_SUCCESS) {
            File f = Updater.cacheApk(c);
            if (f.exists()) f.delete();
            if (!(c instanceof Activity)) { // Activity 场景下由界面自己提示，避免重复
                Toast.makeText(c, "更新完成，已清除安装包", Toast.LENGTH_LONG).show();
            }
        }
    }
}
