package com.batterymonitor.app;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

import java.util.Locale;

public class BatteryData {
    // BatteryManager 中的隐藏整型常量（运行时存在，编译桩里不可见）
    private static final int PROP_CHARGE_FULL = 5;
    private static final int PROP_CHARGE_FULL_DESIGN = 6;

    public long ts;
    public double level;     // %
    public int status;       // 1未知 2充电 3放电 4未充 5满
    public int plugged;      // 电源类型位掩码：1=AC 2=USB 4=无线
    public int health;       // 2良好 ...
    public int voltage;      // mV
    public double temp;      // ℃
    public long cc;          // 当前电量 µAh
    public long cf;          // 当前满电容量 µAh
    public long cfd;         // 设计容量 µAh
    public long cn;          // 实时电流 µA（正=充电，负=放电）
    public int present;
    public long ttf;         // 厂商-reported 距充满秒数（sysfs time_to_full_now，0=无）
    public String source = "BatteryManager";  // 容量数据来源

    public static BatteryData read(Context ctx) {
        BatteryData d = new BatteryData();
        d.ts = System.currentTimeMillis() / 1000;
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        Intent intent = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent != null) {
            int lvl = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scl = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            d.level = (lvl >= 0 && scl > 0) ? lvl * 100.0 / scl : -1;
            d.status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 1);
            d.plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            d.health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 2);
            d.voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            int t = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            d.temp = t / 10.0;
            // 部分厂商（如瑞芯微平板）把 EXTRA_PRESENT 以 Boolean 广播，
            // getIntExtra 会抛 ClassCastException 并回退默认值，这里按实际类型读
            d.present = 1;
            android.os.Bundle ex = intent.getExtras();
            if (ex != null) {
                Object pv = ex.get(BatteryManager.EXTRA_PRESENT);
                if (pv instanceof Boolean) d.present = ((Boolean) pv) ? 1 : 0;
                else if (pv instanceof Integer) d.present = (Integer) pv;
            }
        } else {
            d.status = 1; d.health = 2; d.present = 1;
        }
        if (bm != null) {
            long v = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            d.cc = (v == Long.MIN_VALUE) ? 0 : v;
            v = bm.getLongProperty(PROP_CHARGE_FULL);
            d.cf = (v == Long.MIN_VALUE) ? 0 : v;
            v = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            d.cn = (v == Long.MIN_VALUE) ? 0 : v;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                v = bm.getLongProperty(PROP_CHARGE_FULL_DESIGN);
                d.cfd = (v == Long.MIN_VALUE) ? 0 : v;
            } else {
                d.cfd = 0;
            }
        }
        // 厂商屏蔽了 CHARGE_FULL / CHARGE_FULL_DESIGN（三星常见）时，
        // 通过 Shizuku / root / 直读 sysfs 补齐容量数据
        if (d.cf <= 0 || d.cfd <= 0) {
            try {
                Privileged.Result r = Privileged.read(ctx);
                if (r != null) {
                    if (d.cf <= 0) d.cf = r.cf;
                    if (d.cfd <= 0) d.cfd = r.cfd;
                    if (d.cc <= 0 && r.cc > 0) d.cc = r.cc;
                    d.ttf = r.ttf;
                    d.source = r.mode;
                }
            } catch (Throwable ignored) {
            }
        }
        // 三星已知 bug：BATTERY_PROPERTY_CURRENT_NOW / sysfs current_now 返回 mA
        // 而标准单位是 µA（充电时仅几百"µA"显然异常）。数值可疑时按 mA 修正。
        try {
            String man = Build.MANUFACTURER;
            if (man != null && man.toLowerCase(Locale.US).contains("samsung")
                    && Math.abs(d.cn) > 0 && Math.abs(d.cn) < 50_000) {
                d.cn *= 1000;
            }
        } catch (Throwable ignored) {
        }
        // 用户手动填写的出厂设计容量优先 —— 健康度 = 预估容量 ÷ 设计容量
        long manualDesign = Settings.getDesignUah(ctx);
        if (manualDesign > 0) {
            d.cfd = manualDesign;
            if (d.cf <= 0) {
                // 充满容量也没有时，健康度无法算，但设计容量仍可用于容量图参考线
                d.source = (d.source == null ? "" : d.source) + "+设计容量(手动)";
            } else {
                d.source = (d.source == null || "BatteryManager".equals(d.source)
                        ? "" : d.source + " + ") + "设计容量(手动)";
            }
        }
        // 充满时把当前电荷量记为「满电容量」基准（Android 16 等读不到 charge_full 的机型用，无需 root）
        try {
            if (d.status == 5 && d.cc > 0) {
                Settings.setLastFullCounterUah(ctx, d.cc);
            }
        } catch (Throwable ignored) {
        }
        return d;
    }

    public static String statusText(int s) {
        switch (s) {
            case 2: return "充电中";
            case 3: return "放电中";
            case 4: return "未充电";
            case 5: return "已充满";
            default: return "未知";
        }
    }

    public static String healthText(int h) {
        switch (h) {
            case 2: return "良好";
            case 3: return "过热";
            case 4: return "失效";
            case 5: return "过压";
            case 7: return "过冷";
            default: return "未知";
        }
    }

    /** 预计充满(分钟)；非充电返回 null。优先用厂商 time_to_full_now，其次电流推算 */
    public Integer etaMinutes() {
        if (status != 2 || cf <= 0 || cn <= 0) return null;
        if (ttf > 0) return (int) Math.max(1, ttf / 60);
        double remainMah = (cf - cc) / 1000.0;
        double rateMah = cn / 1000.0;
        if (rateMah < 30) return null;   // 电流读数异常/极小，推算无意义
        return (int) (remainMah / rateMah * 60);
    }

    /**
     * 当前电池预估剩余容量（mAh）。
     * 以库仑计 CHARGE_COUNTER 为基础，用「预估容量 ÷ 读取容量」做老化修正；
     * 没有库仑计时退化为 预估容量 × 当前电量百分比。
     */
    /**
     * 校验库仑计读数是否可信：由「当前剩余电荷 ÷ 电量百分比」反推满电容量，
     * 需与参考总容量处于同一量级（0.4~2.5 倍）。
     * 部分机型（如某些平板/瑞芯微方案）上报的 charge_counter 单位或量级不对，
     * 直接采信会算出 2 mAh 这种离谱值，因此必须先过滤。
     */
    public static boolean ccLooksValid(long cc, double level, double refFullMah) {
        if (cc <= 0 || level <= 0 || refFullMah <= 0) return false;
        double impliedFull = (cc / 1000.0) / (level / 100.0);
        return impliedFull > refFullMah * 0.4 && impliedFull < refFullMah * 2.5;
    }

    public Double remainMah(double estMah) {
        double ref = estMah > 0 ? estMah : (cf > 0 ? cf / 1000.0 : 0);
        if (ccLooksValid(cc, level, ref)) {
            double raw = cc / 1000.0;
            if (estMah > 0 && cf > 0) raw *= estMah / (cf / 1000.0);   // 老化修正
            return raw;
        }
        if (estMah > 0 && level > 0) return estMah * level / 100.0;    // 按百分比折算
        if (cc > 0) return cc / 1000.0;
        return null;
    }

    /**
     * 按当前功率还能用多久（分钟）。
     * 用「可用能量 ÷ 功率」：Wh = 可用mAh × 电压 / 1000，再除以功率绝对值（W）。
     * 充电时取充电功率折算，因此充电中也能给出参考续航。
     *
     * @param remainMah 剩余可用容量 mAh
     * @param watts     当前功率绝对值 W（充电为正、放电为负，这里传绝对值）
     * @param voltageV  当前电压 V
     * @return 分钟数；结果不靠谱返回 null
     */
    public static Integer usageMinutes(double remainMah, double watts, double voltageV) {
        if (remainMah <= 0 || watts <= 0.05 || voltageV <= 0) return null;
        double wh = remainMah * voltageV / 1000.0;
        double hours = wh / watts;
        if (hours > 100) return null;   // 接近待机级，估算不可靠
        return (int) Math.max(1, Math.round(hours * 60));
    }

    /** 把分钟数格式化成「X 小时 Y 分」 */
    public static String fmtMinutes(int min) {
        int h = min / 60;
        int m = min % 60;
        return h > 0 ? h + " 小时 " + m + " 分" : m + " 分钟";
    }

    /**
     * 实时充放电功率（W）= 电压 × 电流。
     * 充电为正（+），放电为负（-）；电压或电流缺失时返回 null。
     */
    public Double powerWatts() {
        if (voltage <= 0 || cn == 0) return null;
        // mV × µA = 1e-3 V × 1e-6 A = 1e-9 W
        return voltage * cn / 1e9;
    }

    /** 电池健康度 %（读取容量 ÷ 设计容量）。UI 现以「预估容量 ÷ 设计容量」为主口径，
     *  本方法保留为读取容量口径 / 兜底用。 */
    public Double healthPct() {
        if (cf > 0 && cfd > 0) return cf * 100.0 / cfd;
        return null;
    }
}
