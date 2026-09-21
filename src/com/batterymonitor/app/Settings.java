package com.batterymonitor.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 用户设置：出厂设计容量（mAh）。
 * 很多厂商（三星、瑞芯微方案等）不向系统暴露 charge_full_design，
 * 此时由用户手动填写出厂标称容量，用于计算电池健康度。
 */
public class Settings {

    private static final String PREF = "battery_settings";
    private static final String KEY_DESIGN_MAH = "design_mah";
    private static final String KEY_LAST_FULL_UAH = "last_full_counter_uah";

    /** 返回手动设置的设计容量（µAh），未设置返回 0 */
    public static long getDesignUah(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return sp.getLong(KEY_DESIGN_MAH, 0);
    }

    public static long getDesignMah(Context ctx) {
        return getDesignUah(ctx) / 1000;
    }

    public static void setDesignMah(Context ctx, long mah) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putLong(KEY_DESIGN_MAH, mah * 1000).apply();
    }

    public static void clearDesign(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_DESIGN_MAH).apply();
    }

    /**
     * 最近一次「充满」时记录的电荷量（µAh），作为满电容量的可信来源。
     * 用途：Android 16 等机型读不到 charge_full（sysfs 对 shell 不可读、BatteryManager 不暴露），
     * 此时用「充满瞬间 charge_counter」替代满电容量，用于读取容量显示与健康度计算。无需 root。
     * 未记录过返回 0。
     */
    public static long getLastFullCounterUah(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return sp.getLong(KEY_LAST_FULL_UAH, 0);
    }

    /** 记录满电电荷量（µAh）。prev 已有值时仅接受 0.5~2 倍范围内的新值，避免异常尖峰覆盖 */
    public static void setLastFullCounterUah(Context ctx, long uah) {
        if (uah <= 0) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long prev = sp.getLong(KEY_LAST_FULL_UAH, 0);
        if (prev > 0 && (uah < prev * 0.5 || uah > prev * 2.0)) return;
        sp.edit().putLong(KEY_LAST_FULL_UAH, uah).apply();
    }
}
