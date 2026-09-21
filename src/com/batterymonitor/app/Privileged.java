package com.batterymonitor.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * 电池容量（满电容量 / 设计容量 / 当前电量）的特权读取层。
 *
 * 三星等厂商不向普通 App 暴露 BatteryManager 的 BATTERY_PROPERTY_CHARGE_FULL，
 * 因此需要更高权限。读取来源按权限由高到低分三档：
 *   1. root su      —— 最高权威性，读到的字段绝不被更低权限来源覆盖
 *   2. Shizuku       —— UserService / newProcess（adb 或 root 身份）
 *   3. App 自身 sysfs 直读 —— 最低优先级（SELinux 常拒绝）
 *
 * 合并规则（root 优先）：先以 root su 读数为基准，仅当某字段 root 缺失/无效时，
 * 才允许 Shizuku 或 sysfs 直读补位。即「root 读取的数据不许被 Shizuku 权限覆盖」。
 * 结果缓存 5 分钟，避免频繁执行命令。
 */
public class Privileged {

    private static final String DIR = "/sys/class/power_supply/battery/";
    private static final String CMD =
            "cat " + DIR + "charge_full " + DIR + "charge_full_design " + DIR + "charge_counter "
                    + DIR + "time_to_full_now 2>/dev/null";

    private static final long TTL = 5 * 60 * 1000L;

    /**
     * 枚举除 battery 之外的供电源（usb / ac / wireless / *-charger 等），
     * 读取各自的 current_now(µA) 与 voltage_now(µV)，得到线端输入功率 W。
     * 用于「设备功耗 = 线端输入功率 − 充入电池的净功率」。
     * 注意：不少机型（含三星、部分平板）不暴露这些节点，此时返回 0，调用方需走兜底。
     */
    private static final String INPUT_CMD =
            "for p in /sys/class/power_supply/*; do case \"$p\" in */battery) continue;; esac; " +
                    "if [ -f \"$p/current_now\" ]; then " +
                    "echo \"$(cat $p/current_now 2>/dev/null) $(cat $p/voltage_now 2>/dev/null)\"; fi; done";

    private static double cachedInputW = -1;
    private static long cachedInputAt = 0;

    public static class Result {
        public long cf;      // 满电容量 µAh
        public long cfd;     // 设计容量 µAh
        public long cc;      // 当前电量 µAh
        public long ttf;     // 距充满秒数（time_to_full_now，0=不可用）
        public String mode;  // 数据来源
        public boolean root; // 是否含 root su 读数（root 读到的字段绝不被更低权限覆盖）
    }

    private static Result cached;
    private static long cachedAt;

    /**
     * 线端输入功率（W）：所有非 battery 供电源的输入功率之和。
     * 结果缓存 60 秒。读不到（机型不暴露节点）返回 0。
     */
    public static double inputWatts() {
        if (cachedInputW >= 0 && (System.currentTimeMillis() - cachedInputAt) < 60 * 1000L) {
            return cachedInputW;
        }
        double sum = 0;
        String out = ShizukuExec.exec(INPUT_CMD);
        if (out != null) {
            for (String line : out.split("\\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                String[] a = t.split("\\s+");
                if (a.length < 2) continue;
                try {
                    double cur = Double.parseDouble(a[0]);   // µA
                    double vol = Double.parseDouble(a[1]);   // µV
                    double w = (cur / 1e6) * (vol / 1e6);
                    if (w > 0 && w < 500) sum += w;          // 排除异常量级
                } catch (Throwable ignored) {
                }
            }
        }
        cachedInputW = sum;
        cachedInputAt = System.currentTimeMillis();
        return sum;
    }

    public static void invalidate() {
        cached = null;
        cachedAt = 0;
        cachedInputW = -1;
        cachedInputAt = 0;
    }

    public static Result read(Context ctx) {
        if (cached != null && (System.currentTimeMillis() - cachedAt) < TTL) {
            return cached;
        }
        if (ctx != null) {
            try {
                ShizukuExec.bind(ctx.getApplicationContext());
            } catch (Throwable ignored) {
            }
        }

        // 分别读取 root su 与 Shizuku 两路，再按 root 优先合并：
        // root 读到的字段绝不被 Shizuku / sysfs 直读覆盖，仅用于补位。
        Result root = ShizukuExec.hasSu() ? readByRoot() : null;
        Result shiz = readByShizuku();
        Result merged = mergeRootFirst(root, shiz);
        if (merged == null) {
            merged = readDirect();          // App 自身 sysfs 直读（最低优先级兜底）
        } else {
            fillFromDirect(merged);         // 用直读补齐仍缺失的字段，且不覆盖特权值
        }
        if (merged != null) {
            cached = merged;
            cachedAt = System.currentTimeMillis();
        }
        return merged;
    }

    /** 仅通过 root su 读取容量节点（最高权威性来源） */
    private static Result readByRoot() {
        String out = ShizukuExec.execRootOnly(CMD);
        return parse(out, "root su", true);
    }

    /** 仅通过 Shizuku（UserService / newProcess）读取容量节点 */
    private static Result readByShizuku() {
        String out = ShizukuExec.execShizukuOnly(CMD);
        return parse(out, ShizukuExec.isShizukuReady() ? ShizukuExec.modeText() : "Shizuku 不可用", false);
    }

    /** 解析 cat 输出为 Result；解析失败或关键字段全缺返回 null */
    private static Result parse(String out, String mode, boolean root) {
        if (out == null) return null;
        out = out.trim();
        if (out.length() == 0 || out.startsWith("ERR:")) return null;
        String[] parts = out.split("\\s+");
        long[] nums = new long[4];
        int n = 0;
        for (int i = 0; i < parts.length && n < 4; i++) {
            try {
                nums[n] = Long.parseLong(parts[i].trim());
                n++;
            } catch (Throwable ignored) {
            }
        }
        android.util.Log.d("BM-Priv", "parsed(" + mode + "): cf=" + nums[0] + " cfd=" + nums[1]
                + " cc=" + nums[2] + " ttf=" + nums[3] + " n=" + n);
        if (n < 2) return null;
        Result r = new Result();
        r.cf = nums[0];
        r.cfd = nums[1];
        r.cc = (n > 2) ? nums[2] : 0;
        // time_to_full_now：非充电时可能返回负数/异常值，仅接受合理范围
        long ttf = (n > 3) ? nums[3] : 0;
        r.ttf = (ttf > 0 && ttf < 24 * 3600) ? ttf : 0;
        r.mode = mode;
        r.root = root;
        if (r.cf <= 0 && r.cfd <= 0) return null;
        return r;
    }

    /** root 优先合并：root 读到的字段绝不被 Shizuku / sysfs 直读覆盖；
     *  仅当 root 缺失该字段时，才允许更低权限来源补位。 */
    private static Result mergeRootFirst(Result root, Result shiz) {
        Result m = new Result();
        if (root != null) {
            m.cf = root.cf; m.cfd = root.cfd; m.cc = root.cc; m.ttf = root.ttf;
            m.mode = root.mode; m.root = true;
        }
        if (shiz != null) {
            if (m.cf <= 0) m.cf = shiz.cf;
            if (m.cfd <= 0) m.cfd = shiz.cfd;
            if (m.cc <= 0) m.cc = shiz.cc;
            if (m.ttf <= 0) m.ttf = shiz.ttf;
            if (!m.root) {
                m.mode = shiz.mode;
            } else if (m.cf > 0 || m.cfd > 0) {
                m.mode = m.mode + " + Shizuku兜底";
            }
        }
        if (m.cf <= 0 && m.cfd <= 0) return null;
        return m;
    }

    /** 用 App 自身 sysfs 直读补齐 m 中仍为 0 的字段（最低优先级，绝不覆盖特权值） */
    private static void fillFromDirect(Result m) {
        Result d = readDirect();
        if (d == null) return;
        if (m.cf <= 0) m.cf = d.cf;
        if (m.cfd <= 0) m.cfd = d.cfd;
        if (m.cc <= 0) m.cc = d.cc;
        if (m.ttf <= 0) m.ttf = d.ttf;
        if (!m.root) m.mode = (m.mode == null ? "" : m.mode + " + ") + "sysfs直读";
    }

    /** App 自身权限直接读 sysfs（SELinux 可能拒绝，失败返回 null） */
    private static Result readDirect() {
        long cf = readOne("charge_full");
        long cfd = readOne("charge_full_design");
        long cc = readOne("charge_counter");
        if (cf <= 0 && cfd <= 0) return null;
        Result r = new Result();
        r.cf = cf;
        r.cfd = cfd;
        r.cc = cc;
        long ttf = readOne("time_to_full_now");
        r.ttf = (ttf > 0 && ttf < 24 * 3600) ? ttf : 0;
        r.mode = "sysfs 直读";
        return r;
    }

    private static long readOne(String name) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(DIR + name));
            String s = br.readLine();
            if (s == null) return 0;
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
