package com.batterymonitor.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统计设备所有软件（含系统服务）的耗电情况。
 *
 * 数据来源：dumpsys batterystats --charged（自上次充满起累计），
 * 通过 Shizuku / root（shell 身份）执行——普通 App 没有 BATTERY_STATS 权限，
 * 但 shell/root 可以读，故不需要本 App 申请任何特殊权限。
 *
 * 解析 "Estimated power use (mAh):" 段里的逐 Uid 行：
 *     Uid u0a165: 0.0000153 ( cpu=0.0000153 ) Including smearing: ...
 *  数字即该 uid 自上次充满起的累计耗电（mAh）。
 * uid token 解码：
 *    u<user>a<app>  -> user*100000 + 10000 + app
 *    u<user>i<app>  -> user*100000 + 99000 + app   (instant app)
 *    纯数字           -> 原始 uid（如 1000=系统, 0=root）
 * 再用 PackageManager 把 uid 映射成应用显示名。
 */
public class AppPowerReader {

    public static class Item {
        public int uid;
        public String pkg;     // 主包名（可能为 null，如纯系统 uid）
        public String label;   // 显示名
        public double mah;     // 自上次充满起累计耗电 mAh
    }

    public static class Result {
        public List<Item> items = new ArrayList<>();
        public double totalDrainMah;   // Computed drain（实际累计放电，含屏幕等硬件）
        public double appsSubtotalMah; // 列表内所有应用/服务的耗电小计（百分比以此为基准）
        public double capacityMah;     // Capacity（电池标称容量）
        public int foreignItems;       // 来自其他 Android 用户（如三星安全文件夹 user 150）的条目数
        public String foreignUserDesc; // 其他用户的描述，如 "user 150"
        public String error;
    }

    /**
     * 单条逐 uid 耗电行，按行调用 find()（故不需要 MULTILINE）。
     * 捕获 3 组：前导缩进、uid token、数值。
     *
     * 缩进与大小写都因 ROM 而异，不能作为判别依据：
     *     S24(Android14) : "  UID u0a355: 113 fg: ..."     2 空格 + 大写
     *     R10D           : "    UID u0a271: 3007 ..."      4 空格 + 大写
     *     旧 ROM         : "    Uid u0a165: 0.0000153 ..." 4 空格 + 小写
     * 且三星把网络流量段也写成 "    Uid u0a355: 51.5 (8983 packets ...)"：
     * 数值是流量而非 mAh，误吞会导致同一应用出现两行、数值虚高。
     * 因此唯一可靠的判别是「段落边界」——只解析 "Estimated power use (mAh):" 段内的行，
     * 并用该段首条 UID 行的缩进作为条目缩进基准（明细行缩进更深，自动被排除）。
     */
    private static final Pattern UID_RE =
            Pattern.compile("^(\\s*)UID\\s+(\\S+):\\s*([0-9.eE+-]+)", Pattern.CASE_INSENSITIVE);
    private static final String SECTION_TAG = "Estimated power use";
    // 兼容两种 ROM 格式：
    //   标准: Capacity: 5000, Computed drain: 123
    //   三星: Capacity: 4050, Rated: 3950, Typical: 4050, Computed drain: 3687, actual drain: ...
    private static final Pattern CAP_RE =
            Pattern.compile("Capacity:\\s*([0-9.eE+-]+)[^\\n]*?Computed drain:\\s*([0-9.eE+-]+)");
    private static final Pattern UID_APP = Pattern.compile("^u(\\d+)a(\\d+)$");
    private static final Pattern UID_INST = Pattern.compile("^u(\\d+)i(\\d+)$");
    /** 系统/共享 uid：u<user>s<appId>，如 u150s5009 → 150*100000 + 5009 */
    private static final Pattern UID_SYS = Pattern.compile("^u(\\d+)s(\\d+)$");

    public static Result read(Context ctx) {
        Result res = new Result();
        String out = ShizukuExec.exec("dumpsys batterystats --charged");
        if (out == null || out.trim().length() == 0) {
            res.error = "读取失败：Shizuku / root 不可用，或 dumpsys 无输出。\n请先授权 Shizuku（或确保设备已 root）。";
            return res;
        }

        Matcher cm = CAP_RE.matcher(out);
        if (cm.find()) {
            try { res.capacityMah = Double.parseDouble(cm.group(1)); } catch (Throwable ignored) {}
            try { res.totalDrainMah = Double.parseDouble(cm.group(2)); } catch (Throwable ignored) {}
        }

        PackageManager pm = ctx.getPackageManager();
        // 本应用所在的 Android 用户号。查询其他用户（多用户 / 三星安全文件夹）的 uid 时，
        // PackageManager.getPackagesForUid 会抛 SecurityException：
        //   "requires INTERACT_ACROSS_USERS_FULL ... to access user 150"
        // 第三方 App 拿不到 INTERACT_ACROSS_USERS*（连 adb shell 也被拒），故必须先判用户号再查。
        int myUser = userOf(Process.myUid());
        // 只解析 "Estimated power use (mAh):" 段；段落结束于：
        //   · 缩进小于条目缩进的非空行（下一段的顶层行），或
        //   · 缩进 ≤ 2 且以 ':' 结尾的行（下一段的标题行，兼容三星）
        // 找不到该段标题时退化为全文扫描（仍受条目缩进一致性约束）。
        boolean hasSection = out.contains(SECTION_TAG);
        boolean inSection = !hasSection;
        int entryIndent = -1;
        for (String line : out.split("\n")) {
            if (!inSection) {
                if (line.contains(SECTION_TAG)) inSection = true;
                continue;
            }
            Matcher um = UID_RE.matcher(line);
            if (um.find()) {
                int ind = um.group(1).length();
                if (entryIndent < 0) entryIndent = ind;
                if (ind != entryIndent) continue;   // 缩进更深的是明细行，跳过
                String tok = um.group(2);
                double mah;
                try { mah = Double.parseDouble(um.group(3)); } catch (Throwable ignored) { continue; }
                if (mah <= 0) continue;
                int uid = parseUid(tok);
                if (uid < 0) continue;
                addItem(res, pm, myUser, uid, mah);
                continue;
            }
            if (line.trim().length() == 0) continue;   // 段内可能夹空行，不作为结束标志
            int ind = indentOf(line);
            if (entryIndent >= 0 && ind < entryIndent) break;
            if (hasSection && ind <= 2 && line.trim().endsWith(":")) break;
        }
        Collections.sort(res.items, (a, b) -> Double.compare(b.mah, a.mah));
        return res;
    }

    /** 行首空格数（dumpsys 用空格缩进，无制表符） */
    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    /** 由一条 uid 耗电记录构造列表项（跨用户条目不做包名解析） */
    private static void addItem(Result res, PackageManager pm, int myUser, int uid, double mah) {
        Item it = new Item();
        it.uid = uid;
        it.mah = mah;
        int uidUser = userOf(uid);
        if (uidUser != myUser) {
            // 跨用户条目（多用户 / 三星安全文件夹）：不做包名解析，统一标注来源用户
            it.pkg = null;
            it.label = foreignLabel(uidUser);
            res.foreignItems++;
            if (res.foreignUserDesc == null) res.foreignUserDesc = "user " + uidUser;
        } else {
            String[] pkgs = null;
            // 再兜一层 try：个别 ROM（改造过的 PM）仍可能对合法 uid 抛异常
            try { pkgs = pm.getPackagesForUid(uid); } catch (Throwable ignored) {}
            if (pkgs != null && pkgs.length > 0) {
                it.pkg = pkgs[0];
                it.label = labelFor(pm, pkgs[0]);
                if (pkgs.length > 1) it.label += "（+" + (pkgs.length - 1) + "）";
            } else {
                it.pkg = null;
                it.label = (uid == 0) ? "系统 (root)" : "系统服务 (uid " + uid + ")";
            }
        }
        res.items.add(it);
        res.appsSubtotalMah += mah;
    }

    /**
     * 由 uid 反推 Android 用户号，等价于隐藏 API UserHandle.getUserId(uid)：
     *     uid = userId * PER_USER_RANGE(100000) + appId
     * 公开 SDK 未导出该方法（@UnsupportedAppUsage），直接调用会编译失败，故自行计算。
     */
    private static int userOf(int uid) {
        return uid / 100000;
    }

    /**
     * 跨用户应用的显示名。已知 user 150 在三星 ROM 上是安全文件夹（Knox Secure Folder），
     * 其余情况给出通用的多用户标注。名称无法解析（跨用户拿不到 PackageManager），
     * 但耗电量本身是真实数据，保留展示意义。
     */
    private static String foreignLabel(int uidUser) {
        if (uidUser == 150) return "安全文件夹 / 其他用户应用 (user 150)";
        return "其他用户应用 (user " + uidUser + ")";
    }

    private static String labelFor(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = ai.loadLabel(pm);
            return l != null ? l.toString() : pkg;
        } catch (Throwable t) {
            return pkg;
        }
    }

    private static int parseUid(String tok) {
        try {
            if (tok.equals("0")) return 0;
            Matcher m = UID_APP.matcher(tok);
            if (m.matches()) {
                int user = Integer.parseInt(m.group(1));
                int app = Integer.parseInt(m.group(2));
                return user * 100000 + 10000 + app;
            }
            Matcher m2 = UID_INST.matcher(tok);
            if (m2.matches()) {
                int user = Integer.parseInt(m2.group(1));
                int inst = Integer.parseInt(m2.group(2));
                return user * 100000 + 99000 + inst;
            }
            Matcher m3 = UID_SYS.matcher(tok);
            if (m3.matches()) {
                int user = Integer.parseInt(m3.group(1));
                int app = Integer.parseInt(m3.group(2));
                return user * 100000 + app;
            }
            return Integer.parseInt(tok);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 按量级自适应格式化 mAh（<0.01 用科学计数法，避免显示成 0.000000） */
    static String fmt(double m) {
        if (m >= 1) return String.format("%.2f", m);
        if (m >= 0.01) return String.format("%.4f", m);
        if (m > 0) return String.format("%.2e", m);
        return "0";
    }
}
