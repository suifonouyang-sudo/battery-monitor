package com.batterymonitor.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后自动启动采样服务，持续记录电池数据 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent s = new Intent(context, SamplingService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(s);
            } else {
                context.startService(s);
            }
        }
    }
}
