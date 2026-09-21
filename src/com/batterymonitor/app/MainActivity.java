package com.batterymonitor.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

import java.io.File;
import java.lang.ref.WeakReference;
import java.io.FileWriter;
import java.util.List;

public class MainActivity extends Activity {
    private BatteryDbHelper db;
    private Handler uiHandler;
    private RefreshTask refreshTask;
    private TextView tvStatus, tvLevel, tvHealth, tvFull, tvEstimate, tvDesign, tvEta, tvRate, tvProtocol, tvTemp, tvVoltage, tvLastFull, tvCycles;
    private TextView tvRemainCapacity, tvDevicePower, tvAvgPower, tvUsageTime, tvUseTime;
    private TextView tvSource;
    private Button btnShizuku;
    private EditText etDesign;
    private Button btnSaveDesign;
    private PermListener permListener;

    private static final int REQ_SHIZUKU = 1001;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        db = new BatteryDbHelper(this);
        uiHandler = new Handler(Looper.getMainLooper());
        refreshTask = new RefreshTask(this);

        tvStatus = findViewById(R.id.tvStatus);
        tvLevel = findViewById(R.id.tvLevel);
        tvHealth = findViewById(R.id.tvHealth);
        tvFull = findViewById(R.id.tvFull);
        tvEstimate = findViewById(R.id.tvEstimate);
        tvDesign = findViewById(R.id.tvDesign);
        tvEta = findViewById(R.id.tvEta);
        tvRate = findViewById(R.id.tvRate);
        tvProtocol = findViewById(R.id.tvProtocol);
        tvTemp = findViewById(R.id.tvTemp);
        tvVoltage = findViewById(R.id.tvVoltage);
        tvLastFull = findViewById(R.id.tvLastFull);
        tvCycles = findViewById(R.id.tvCycles);
        tvRemainCapacity = findViewById(R.id.tvRemainCapacity);
        tvDevicePower = findViewById(R.id.tvDevicePower);
        tvAvgPower = findViewById(R.id.tvAvgPower);
        tvUsageTime = findViewById(R.id.tvUsageTime);
        tvUseTime = findViewById(R.id.tvUseTime);
        tvSource = findViewById(R.id.tvSource);
        btnShizuku = findViewById(R.id.btnShizuku);
        etDesign = findViewById(R.id.etDesign);
        btnSaveDesign = findViewById(R.id.btnSaveDesign);
        // 回填已保存的设计容量
        long savedDesign = Settings.getDesignMah(this);
        if (savedDesign > 0) etDesign.setText(String.valueOf(savedDesign));
        btnSaveDesign.setOnClickListener(v -> saveDesign());

        findViewById(R.id.btnRefresh).setOnClickListener(v -> safeRefresh());
        findViewById(R.id.btnExport).setOnClickListener(v -> exportCsv());
        btnShizuku.setOnClickListener(v -> requestShizuku());
        findViewById(R.id.btnCharts).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, ChartActivity.class)));
        findViewById(R.id.btnAppPower).setOnClickListener(v ->
                startActivity(new Intent(this, AppPowerActivity.class)));
        findViewById(R.id.btnCycles).setOnClickListener(v ->
                startActivity(new Intent(this, CycleActivity.class)));

        // Shizuku：绑定 UserService + 监听授权结果
        ShizukuExec.bind(this);
        permListener = new PermListener(this);
        try {
            Shizuku.addRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }

        // 打开 App 即开始后台记录
        Intent s = new Intent(this, SamplingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s);
        else startService(s);

        safeRefresh();
        uiHandler.postDelayed(refreshTask, 5000);
    }

    /** 刷新异常不应导致界面闪退：捕获后提示即可 */
    private void safeRefresh() {
        try {
            refresh();
        } catch (Throwable t) {
            try {
                Toast.makeText(this, "刷新出错: " + t, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private void saveDesign() {
        String s = etDesign.getText().toString().trim();
        if (s.length() == 0) {
            // 清空并保存 = 恢复自动读取
            Settings.clearDesign(this);
            Privileged.invalidate();
            Toast.makeText(this, "已清除手动设计容量，恢复自动读取", Toast.LENGTH_SHORT).show();
            safeRefresh();
            return;
        }
        try {
            long mah = Long.parseLong(s);
            if (mah <= 0 || mah > 1000000) throw new NumberFormatException();
            Settings.setDesignMah(this, mah);
            Privileged.invalidate();
            Toast.makeText(this, "设计容量已保存: " + mah + " mAh，健康度将按 预估容量÷设计容量 计算",
                    Toast.LENGTH_LONG).show();
            safeRefresh();
        } catch (Throwable t) {
            Toast.makeText(this, "请输入有效的设计容量（mAh）", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestShizuku() {
        try {
            if (ShizukuExec.isShizukuReady()) {
                Privileged.invalidate();
                ChargeProtocol.invalidate();
                ShizukuExec.bind(this);
                Toast.makeText(this, "Shizuku 已授权，重新读取容量…", Toast.LENGTH_SHORT).show();
                safeRefresh();
                return;
            }
            if (Shizuku.pingBinder()) {
                ShizukuExec.requestPermission(REQ_SHIZUKU);
                Toast.makeText(this, "请在 Shizuku 弹窗中允许本应用", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "未检测到运行中的 Shizuku。请先打开 Shizuku 并启动服务，再点此按钮。",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Shizuku 不可用: " + t, Toast.LENGTH_LONG).show();
        }
    }

    private static class PermListener implements Shizuku.OnRequestPermissionResultListener {
        private final WeakReference<MainActivity> ref;

        PermListener(MainActivity a) {
            ref = new WeakReference<>(a);
        }

        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            MainActivity a = ref.get();
            if (a == null) return;
            boolean granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted) {
                Privileged.invalidate();
                ChargeProtocol.invalidate();
                ShizukuExec.bind(a);
            }
            a.uiHandler.post(new PostRefresh(a));
            final String msg = granted ? "Shizuku 授权成功" : "Shizuku 授权被拒绝";
            a.uiHandler.post(new ShowToast(a, msg));
        }
    }

    private static class PostRefresh implements Runnable {
        private final WeakReference<MainActivity> ref;

        PostRefresh(MainActivity a) {
            ref = new WeakReference<>(a);
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a != null) a.safeRefresh();
        }
    }

    private static class ShowToast implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final String msg;

        ShowToast(MainActivity a, String m) {
            ref = new WeakReference<>(a);
            msg = m;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a != null) Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
        }
    }

    private static class RefreshTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        RefreshTask(MainActivity a) { ref = new WeakReference<>(a); }
        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a != null) { a.safeRefresh(); a.uiHandler.postDelayed(a.refreshTask, 5000); }
        }
    }

    private void refresh() {
        BatteryData d = BatteryData.read(this);
        String src = d.source;
        if (ShizukuExec.isShizukuInstalledButNoPermission()) src = src + "（Shizuku 未授权）";
        tvSource.setText("容量数据来源: " + src);
        btnShizuku.setText(ShizukuExec.isShizukuReady() ? "Shizuku已授权" : "Shizuku授权");

        // 预估容量：库仑计/电流积分独立估算（不依赖厂商上报的 charge_full）
        double[] est = db.estimateCapacity();
        double estMah = est[0];
        int estSegs = (int) est[1];

        // 健康度 = 预估容量 ÷ 设计容量（用户明确要求口径）。
        // 预估容量由库仑计/电流积分独立估算，不依赖厂商上报的 charge_full；
        // 仅当预估容量尚未积累出来（app 刚装、采样不足）时，才退回读到的满电容量 / 记录满电。
        Double hp = null;
        String hpTag;
        if (d.cfd > 0 && estMah > 0) {
            hp = estMah / (d.cfd / 1000.0) * 100.0;          // 预估容量 ÷ 设计容量
            hpTag = " [预估容量]";
        } else if (d.cfd > 0) {
            // 预估容量未积累时回退：sysfs/root 读到的 charge_full，读不到再用「记录满电」
            long fullUah = d.cf > 0 ? d.cf : Settings.getLastFullCounterUah(this);
            boolean hpFromRecord = (fullUah > 0 && d.cf <= 0);
            if (fullUah > 0) {
                hp = fullUah / 1000.0 / (d.cfd / 1000.0) * 100.0;
                hpTag = hpFromRecord ? " [记录满电]" : " [读取容量]";
            } else {
                hpTag = "";
            }
        } else {
            hpTag = "";
        }
        // 数值合理性收敛：允许新电池略高于 100%，超过 150% 判定为异常数据
        if (hp != null) {
            if (hp < 0) hp = 0.0;
            if (hp > 150) hp = 150.0;
        }

        tvStatus.setText("状态: " + BatteryData.statusText(d.status) +
                (hp != null ? "  · 健康 " + String.format("%.0f%%", hp) : ""));

        tvLevel.setText("当前电量: " + String.format("%.0f%%", d.level));
        tvHealth.setText("电池健康度: " + (hp != null ? String.format("%.1f%%", hp) +
                " (" + BatteryData.healthText(d.health) + ")" + hpTag : "未知"));
        long fullForShow = d.cf > 0 ? d.cf : Settings.getLastFullCounterUah(this);
        tvFull.setText("读取容量: " + (fullForShow > 0 ?
                (fullForShow / 1000) + " mAh" + (d.cf > 0 ? "" : " (记录)") : "-"));
        tvEstimate.setText("预估容量: " + (estMah > 0 ?
                String.format("%.0f mAh", estMah) + "（" + estSegs + "段估算）" : "积累中…"));
        tvDesign.setText("设计容量: " + (d.cfd > 0 ? (d.cfd / 1000) + " mAh" : "未知(需Android11+)"));
        Integer eta = d.etaMinutes();
        tvEta.setText("预计充满: " + (eta != null ? eta + " 分钟" : (d.status == 5 ? "已充满" : "—")));
        Double pw = d.powerWatts();
        tvRate.setText("充放电功率: " + (pw != null ? (pw > 0 ? "+" : "") + String.format("%.2f W", pw) : "-"));
        // 当前剩余容量 + 预计可用时长
        Double remain = d.remainMah(estMah);
        double refFullMah = estMah > 0 ? estMah : (d.cf > 0 ? d.cf / 1000.0 : 0);
        boolean remainEstimated = !BatteryData.ccLooksValid(d.cc, d.level, refFullMah);
        boolean charging = (d.status == 2 || d.status == 5);
        tvRemainCapacity.setText("当前可用容量: " + (remain != null ?
                String.format("%.0f mAh", remain) + (remainEstimated ? "(估)" : "") +
                        String.format(" · %.0f%%", d.level) : "-"));
        // 设备功耗：
        //   放电/未充电 → 电池输出全部给机身，直接等于 |电池功率|
        //   充电中      → 需要「线端输入功率 − 充入电池的净功率」；读不到线端时
        //                 退回近 24h 平均放电功耗作为「典型功耗」
        double battW = pw != null ? pw : 0;
        double deviceW;
        String deviceTag;
        if (!charging) {
            deviceW = Math.abs(battW);
            deviceTag = "";
        } else {
            double inputW = Privileged.inputWatts();
            if (inputW > 0.05) {
                deviceW = Math.max(0, inputW - battW);
                deviceTag = "";
            } else {
                // 峰值优先：取记录里的最大放电功耗（重度使用），读不到再退回平均功耗
                double ma = db.maxDischargeMa();
                boolean peak = ma > 0;
                if (ma <= 0) ma = db.avgDischargeMa();
                if (ma > 20000) ma = 0;                              // >20A 属异常数据
                deviceW = ma * (d.voltage / 1000.0) / 1000.0;         // mA × V ÷ 1000 = W
                deviceTag = deviceW > 0.05 ? (peak ? "(峰值)" : "(典型)") : "";
            }
        }
        tvDevicePower.setText("设备功耗: " + (deviceW > 0.05 ?
                String.format("%.2f W", deviceW) + deviceTag : "-（未测得）"));

        // 平均功耗：近 24h 放电平均（独立一格，作为设备功耗的参照片）
        double avgMa = db.avgDischargeMa();                     // mA
        if (avgMa > 20000) avgMa = 0;                            // >20A 属异常数据
        double avgW = avgMa * (d.voltage / 1000.0) / 1000.0;     // mA × V ÷ 1000 = W
        tvAvgPower.setText("平均功耗: " + (avgW > 0.05 ?
                String.format("%.2f W", avgW) + "（24h）" : "-（积累中）"));

        // 峰值功耗：历史最大放电功耗换算（重度使用场景），用于「预计使用」保守续航参考
        double peakMa = db.maxDischargeMa();                    // mA
        if (peakMa > 20000) peakMa = 0;                          // >20A 属异常数据
        double peakW = peakMa * (d.voltage / 1000.0) / 1000.0;   // mA × V ÷ 1000 = W
        boolean peakFallback = (peakW <= 0.05);                  // 取不到历史峰值 → 退回平均功耗
        if (peakFallback) peakW = avgW;

        // 预计使用 = 可用能量 ÷ 峰值功耗（始终按峰值，保留为保守续航参考）
        Integer usagePk = (remain != null && peakW > 0.05 && d.voltage > 0)
                ? BatteryData.usageMinutes(remain, peakW, d.voltage / 1000.0) : null;
        boolean pkTooLong = (remain != null && remain > 0 && peakW > 0.05 && d.voltage > 0 && usagePk == null);
        tvUsageTime.setText("预计使用: " + (usagePk != null ?
                BatteryData.fmtMinutes(usagePk) + (peakFallback ? "(按均值兜底)" : "(按峰值功耗)")
                : (pkTooLong ? ">100 小时" : (d.voltage <= 0 ? "-（需电压）" : "积累中…"))));

        // 使用时间 = 可用容量 ÷ (放电时当前实时功耗 或 平均功耗)
        double useW = (!charging && Math.abs(battW) > 0.05) ? Math.abs(battW) : avgW;
        boolean useCurrent = (!charging && Math.abs(battW) > 0.05);
        Integer usageAvg = (remain != null && useW > 0.05 && d.voltage > 0)
                ? BatteryData.usageMinutes(remain, useW, d.voltage / 1000.0) : null;
        boolean avgTooLong = (remain != null && remain > 0 && useW > 0.05 && d.voltage > 0 && usageAvg == null);
        tvUseTime.setText("使用时间: " + (usageAvg != null ?
                BatteryData.fmtMinutes(usageAvg) + (useCurrent ? "(当前)" : "(均值)")
                : (avgTooLong ? ">100 小时" : (d.voltage <= 0 ? "-（需电压）" : "积累中…"))));

        final ChargeProtocol.Info proto = ChargeProtocol.detect(this, d);
        tvProtocol.setText("充电协议: " + proto.name);
        tvProtocol.setTextColor(proto.fast ? 0xFF2E7D32 : 0xFF616161);
        tvProtocol.setOnClickListener(v -> showProtocol(proto));
        tvTemp.setText("温度: " + String.format("%.1f ℃", d.temp));
        tvVoltage.setText("电压: " + (d.voltage > 0 ? String.format("%.2f V", d.voltage / 1000.0) : "-"));
        tvLastFull.setText("采样数: " + db.countRows());

        // 充放电循环记录（持久保存，明细见「充放电循环记录」专页）
        // 序号不分充放电统一编号，这里只报总次数，细分仅作参考
        int[] cc = db.countCyclesByType();
        int total = cc[0] + cc[1];
        tvCycles.setText(total > 0
                ? "循环记录: 共 " + total + " 次（充电 " + cc[0] + " · 放电 " + cc[1] + "）"
                : "循环记录: 暂无");
    }

    /** 点击协议卡片：显示判定依据与读到的 sysfs 原始值 */
    private void showProtocol(ChargeProtocol.Info p) {
        StringBuilder sb = new StringBuilder();
        if (p.detail.length() > 0) sb.append(p.detail).append('\n');
        if (p.basis.length() > 0) sb.append("\n依据: ").append(p.basis);
        if (p.raw.length() > 0) sb.append("\n\n节点原始值:\n").append(p.raw);
        if (sb.length() == 0) sb.append("暂无明细");
        new android.app.AlertDialog.Builder(this)
                .setTitle("充电协议: " + p.name)
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    private void exportCsv() {
        try {
            File dir = getExternalFilesDir(null);
            File f = new File(dir, "battery_export.csv");
            FileWriter w = new FileWriter(f);
            db.exportCsv(w);
            w.close();
            Toast.makeText(this, "已导出: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
