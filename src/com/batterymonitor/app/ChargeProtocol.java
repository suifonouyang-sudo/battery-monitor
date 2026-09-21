package com.batterymonitor.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 当前充电协议检测。
 *
 * 两级判定：
 *   1. 读到 sysfs 供电节点（需 Shizuku / root，或部分机型 App 直接可读）
 *      → 按 usb/real_type、type、各厂商私有节点精确识别 PD / QC / AFC / VOOC / PE …
 *   2. 读不到节点 → 用实测功率 + 电源类型粗判，并标注"（推测）"
 *
 * 结果缓存 20 秒，避免频繁执行 shell 命令。
 */
public class ChargeProtocol {

    private static final String PS = "/sys/class/power_supply/";

    private static final String[] NODES = {
            PS + "usb/real_type",
            PS + "usb/type",
            PS + "usb/voltage_max",
            PS + "usb/current_max",
            PS + "usb/voltage_now",
            PS + "usb/current_now",
            PS + "usb/online",
            PS + "ac/type",
            PS + "ac/voltage_max",
            PS + "ac/current_max",
            PS + "ac/online",
            PS + "wireless/online",
            PS + "wireless/type",
            PS + "battery/charge_type",
            PS + "battery/fast_charge_type",
            PS + "battery/quick_charge_type",
            PS + "battery/pd_authentication",
            PS + "battery/afc_result",
            PS + "battery/vooc_enabled",
            PS + "battery/super_vooc_enabled",
            PS + "battery/mtk_ta_current",
            PS + "charger/type",
            PS + "bc12/type",
    };

    public static class Info {
        public String name = "未知";
        public String detail = "";
        public String basis = "";
        public String raw = "";
        public boolean fast;
    }

    private static Info cached;
    private static long cachedAt;
    private static final long TTL = 20_000L;

    public static void invalidate() {
        cached = null;
        cachedAt = 0;
    }

    public static Info detect(Context ctx, BatteryData d) {
        if (d == null) return fallback(new Info(), null);
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < TTL) return cached;

        Info info;
        Map<String, String> m = readNodes(ctx);
        if (m.isEmpty()) {
            info = fromPower(d);
        } else {
            info = fromSysfs(m, d);
        }
        cached = info;
        cachedAt = now;
        return info;
    }

    // ---------- 节点读取 ----------

    private static Map<String, String> readNodes(Context ctx) {
        Map<String, String> map = new HashMap<>();
        // 1) Shizuku / root 批量读取
        StringBuilder cmd = new StringBuilder("for f in");
        for (String p : NODES) cmd.append(' ').append(p);
        cmd.append("; do printf '%s|%s\\n' \"$f\" \"$(cat $f 2>/dev/null | tr -d '\\n')\"; done");
        String out = ShizukuExec.exec(cmd.toString());
        parse(out, map);
        // 2) App 自身直读兜底（部分机型 sysfs 对 App 可读）
        if (map.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String p : NODES) {
                sb.append(p).append('|').append(readOne(p)).append('\n');
            }
            parse(sb.toString(), map);
        }
        return map;
    }

    private static void parse(String out, Map<String, String> map) {
        if (out == null || out.length() == 0) return;
        String[] lines = out.split("\n");
        for (String line : lines) {
            int i = line.indexOf('|');
            if (i <= 0) continue;
            String k = line.substring(0, i).trim();
            String v = line.substring(i + 1).trim();
            if (v.length() > 0) map.put(k, v);
        }
    }

    private static String readOne(String path) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(path));
            String s = br.readLine();
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---------- 判定 ----------

    private static Info fromSysfs(Map<String, String> m, BatteryData d) {
        Info r = new Info();
        if (d.status != 2 && d.status != 5) {
            r.name = "未充电";
            r.basis = "电池状态: " + BatteryData.statusText(d.status);
            r.raw = dumpRaw(m);
            return r;
        }

        String usbType = first(m, PS + "usb/real_type", PS + "usb/type");
        String bcType = first(m, PS + "charger/type", PS + "bc12/type", PS + "ac/type");
        String chargeType = first(m, PS + "battery/charge_type");
        long vmax = num(first(m, PS + "usb/voltage_max", PS + "ac/voltage_max"));
        long cmax = num(first(m, PS + "usb/current_max", PS + "ac/current_max"));
        boolean wireless = "1".equals(first(m, PS + "wireless/online"));

        String name = null;
        String basis = "";

        String t = (usbType != null && usbType.length() > 0) ? usbType : bcType;
        if (t != null && t.length() > 0) {
            String up = t.toUpperCase(Locale.US);
            basis = "sysfs type=" + t;
            if (up.contains("PPS")) name = "USB PD PPS";
            else if (up.contains("PD")) name = "USB PD";
            else if (up.contains("HVDCP_3") || up.contains("HVDCP3")) name = "QC 3.0 / 4+";
            else if (up.contains("HVDCP")) name = "QC 2.0";
            else if (up.contains("DCP")) name = "USB DCP 专用充电口";
            else if (up.contains("CDP")) name = "USB CDP 充电下行口";
            else if (up.contains("SDP")) name = "USB SDP 标准口";
            else if (up.contains("ACA")) name = "USB ACA 附件充电";
            else if (up.contains("USB_C")) name = "USB Type-C 默认电流";
            else name = t;
        }

        // 厂商私有快充协议
        if ("1".equals(m.get(PS + "battery/pd_authentication")) && name == null) {
            name = "USB PD";
            basis = "sysfs pd_authentication=1";
        }
        String afc = m.get(PS + "battery/afc_result");
        if (afc != null && afc.length() > 0 && !"0".equals(afc)) {
            name = "三星 AFC 快充";
            basis = "sysfs afc_result=" + afc;
        }
        if ("1".equals(m.get(PS + "battery/super_vooc_enabled"))) {
            name = "OPPO SuperVOOC";
            basis = "sysfs super_vooc_enabled=1";
        } else if ("1".equals(m.get(PS + "battery/vooc_enabled"))) {
            name = "OPPO VOOC";
            basis = "sysfs vooc_enabled=1";
        }
        long ta = num(m.get(PS + "battery/mtk_ta_current"));
        if (ta > 0 && name == null) {
            name = "MTK Pump Express";
            basis = "sysfs mtk_ta_current=" + ta;
        }
        String fastType = first(m, PS + "battery/fast_charge_type", PS + "battery/quick_charge_type");
        if (fastType != null && fastType.length() > 0 && !"0".equals(fastType) && !"N/A".equalsIgnoreCase(fastType)) {
            if (name == null) {
                name = "私有快充协议";
                basis = "sysfs fast_charge_type=" + fastType;
            } else {
                basis += " · fast_charge_type=" + fastType;
            }
        }

        // 充电阶段（Fast / Trickle / Taper）
        if (chargeType != null && chargeType.length() > 0) {
            String ct = chargeType.toLowerCase(Locale.US);
            if ("fast".equals(ct)) {
                r.fast = true;
                if (name == null) name = "快充";
                else if (!name.contains("快充")) name = name + "（快充中）";
            } else if ("trickle".equals(ct)) {
                name = "涓流充电";
            } else if ("taper".equals(ct) || "taper_linear".equals(ct)) {
                if ("USB PD PPS".equals(name) || "USB PD".equals(name)) name = "USB PD（恒压涓流段）";
                else if (name == null) name = "恒压充电段";
            } else if ("none".equals(ct) || "n/a".equals(ct)) {
                name = "未充电";
            }
            if (basis.length() > 0) basis += " · charge_type=" + chargeType;
            else basis = "sysfs charge_type=" + chargeType;
        }

        // 无线
        if (wireless) {
            name = "无线充电";
            basis = "sysfs wireless/online=1";
        }

        // 仍未知：按协商电压/电流推断
        if (name == null) {
            if (vmax >= 8_000_000) {
                name = "高压快充";
                basis = "协商电压 " + (vmax / 1e6) + "V";
            } else if (vmax > 0 && cmax > 0) {
                double w = (vmax / 1e6) * (cmax / 1e6);
                name = w >= 15 ? "快充" : "普通充电";
                basis = "协商 " + (vmax / 1e6) + "V × " + (cmax / 1e3) + "mA";
            }
        }

        if (name == null) {
            Info p = fromPower(d);
            p.raw = dumpRaw(m);
            return p;
        }

        r.name = name;
        if (vmax > 0 || cmax > 0) {
            r.detail = String.format(Locale.US, "协商 %.1fV / %.0fmA ≈ %.1fW",
                    vmax / 1e6, cmax / 1e3, (vmax / 1e6) * (cmax / 1e6));
        } else {
            Double pw = d.powerWatts();
            if (pw != null) {
                r.detail = String.format(Locale.US, "实测 %.2fW · %.2fV · %.0fmA",
                        Math.abs(pw), d.voltage / 1000.0, Math.abs(d.cn) / 1000.0);
            }
        }
        r.basis = basis;
        r.raw = dumpRaw(m);
        r.fast = r.fast || isFastName(name);
        return r;
    }

    /** 无 sysfs 权限时：用实测功率粗判 */
    private static Info fromPower(BatteryData d) {
        Info r = new Info();
        r.basis = "功率推测（读不到供电节点，授权 Shizuku 可精确识别）";
        Double p = d.powerWatts();
        double w = p != null ? Math.abs(p) : 0;
        if ((d.plugged & 4) != 0) {
            r.name = "无线充电";
        } else if (w >= 45) {
            r.name = "超快充（≥45W）";
        } else if (w >= 20) {
            r.name = "PD / VOOC 级快充（≥20W）";
        } else if (w >= 12) {
            r.name = "QC / PD 级快充（≥12W）";
        } else if (w >= 7.5) {
            r.name = "快充（≥7.5W）";
        } else if (w >= 2.5) {
            r.name = (d.plugged == 1) ? "普通充电（交流适配器）" : "普通充电（USB 5V）";
        } else {
            r.name = "慢充 / USB 标准口";
        }
        r.fast = w >= 7.5;
        r.detail = String.format(Locale.US, "实测 %.2fW · %.2fV · %.0fmA",
                w, d.voltage / 1000.0, Math.abs(d.cn) / 1000.0);
        return r;
    }

    private static Info fallback(Info r, BatteryData d) {
        r.name = "未知";
        r.basis = "无电池数据";
        return r;
    }

    private static boolean isFastName(String n) {
        return n.contains("PD") || n.contains("QC") || n.contains("AFC") || n.contains("VOOC")
                || n.contains("快充") || n.contains("Pump");
    }

    private static String dumpRaw(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (String p : NODES) {
            String v = m.get(p);
            if (v == null || v.length() == 0) continue;
            sb.append(p.replace(PS, "")).append('=').append(v).append('\n');
        }
        return sb.toString();
    }

    // ---------- 工具 ----------

    private static String first(Map<String, String> m, String... keys) {
        for (String k : keys) {
            String v = m.get(k);
            if (v != null && v.length() > 0) return v;
        }
        return null;
    }

    private static long num(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }
}
