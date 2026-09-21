package com.batterymonitor.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** 后台前台服务：每 30 秒采样一次电池数据并入库（即使 App 关闭也在记录） */
public class SamplingService extends Service {
    public static final String CHANNEL_ID = "battery_monitor";
    private static final int NOTIF_ID = 1;
    private static final long INTERVAL = 30_000;

    private BatteryDbHelper db;
    private Handler handler;
    private TickTask tick;
    /** 历史回填只跑一次（服务可能被反复启动） */
    private static volatile boolean sBackfillStarted = false;

    private static class TickTask implements Runnable {
        private final SamplingService svc;
        TickTask(SamplingService s) { svc = s; }
        @Override
        public void run() {
            // 单次采样失败不能中断整个采样循环
            try {
                BatteryData d = BatteryData.read(svc);
                svc.db.insert(d.ts, d.level, d.status, d.health, d.voltage, d.temp,
                        d.cc, d.cf, d.cfd, d.cn, d.present);
                // 同步归档充放电循环记录（持久化，仅手动删除）
                CycleRecorder.onSample(svc, svc.db, d.ts, d.level, d.cc, d.cf, d.status);
                svc.updateNotification(d);
            } catch (Throwable ignored) {
            }
            svc.handler.postDelayed(this, INTERVAL);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        db = new BatteryDbHelper(this);
        handler = new Handler(Looper.getMainLooper());
        tick = new TickTask(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        BatteryData d = null;
        try {
            createChannel();
            d = BatteryData.read(this);
        } catch (Throwable ignored) {
        }
        // startForeground 必须调用，否则系统会杀掉服务
        try {
            startForeground(NOTIF_ID, buildNotification(d != null ? d : new BatteryData()));
        } catch (Throwable ignored) {
        }
        // onStartCommand 可能被多次触发（启动/重启/授权后重进），
        // 先移除旧任务再排新，避免叠加出多条采样链导致同一时刻重复入库
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000);
        // 首次升级后把已有的采样历史折算成循环记录（幂等，跑完即打标）
        if (!sBackfillStarted) {
            sBackfillStarted = true;
            try {
                new Thread(new CycleRecorder.BackfillTask(this)).start();
            } catch (Throwable ignored) {
            }
        }
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "电池监控", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("后台记录电池数据");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(BatteryData d) {
        String txt = String.format("电量 %.0f%% · %s", d.level, BatteryData.statusText(d.status));
        Integer eta = d.etaMinutes();
        if (eta != null) txt += " · 约" + eta + "分钟满";
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("电池健康监控")
         .setContentText(txt)
         .setSmallIcon(android.R.drawable.ic_dialog_info)
         .setOngoing(true);
        return b.build();
    }

    private void updateNotification(BatteryData d) {
        // Android 13+ 未授予 POST_NOTIFICATIONS 时 notify() 会抛 SecurityException
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(d));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
