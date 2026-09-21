package com.batterymonitor.app;

import android.database.Cursor;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 充放电循环的共用逻辑：采样读取 + 分段计算 + 文案生成。
 * 主页（循环次数指标）与「充放电循环记录」专页都从这里取数，避免逻辑分叉。
 */
public final class Cycles {

    private Cycles() {
    }

    /** 采样点（与 samples 表列顺序一一对应） */
    public static class Sample {
        public long ts;
        public double level;
        public long cc;
        public long cf;
        public int status;
        public double temp;
        public long cn;
        public long cfd;
    }

    /** 一段连续的充电或放电过程 */
    public static class Cycle {
        public long id;                  // 数据库主键（0 = 尚未落库的实时计算段）
        public int seq;                  // 总第几次（按时间升序，不分充放电；1 起；0 = 未编号）
        public boolean open;             // true = 仍在进行中
        public int type;                 // 1=充电 2=放电
        public long startTs, endTs;
        public float startLevel, endLevel;
        public long startCc, endCc, maxCf;
        public int durationMin;
        public String levelText = "";    // 如 "+17%" / "-96%"
        public String capText = "";      // 如 "+639mAh" / "-3200mAh"，无可信库仑计时为空
        public int fullCap;              // 充电段中出现过的最大满电容量(mAh)，0=无
    }

    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("MM-dd HH:mm", Locale.US);

    /** 系统电池状态位 → 段类型（0=无明确充放电） */
    public static int typeOf(int status) {
        if (status == 2 || status == 5) return 1;   // CHARGING / FULL
        if (status == 3) return 2;                  // DISCHARGING
        return 0;
    }

    /** 把 cycles 表的一行读成 Cycle。
     *  列顺序必须与 BatteryDbHelper.queryCycles() 的 projection 一致。 */
    public static Cycle fromCursor(Cursor c) {
        Cycle cy = new Cycle();
        cy.id = c.getLong(0);
        cy.type = c.getInt(1);
        cy.startTs = c.getLong(2);
        cy.endTs = c.getLong(3);
        cy.startLevel = (float) c.getDouble(4);
        cy.endLevel = (float) c.getDouble(5);
        cy.startCc = c.getLong(6);
        cy.endCc = c.getLong(7);
        cy.maxCf = c.getLong(8);
        // done=0 且仍在采样节奏内（10 分钟，与归档器断层阈值一致）才算「进行中」；
        // 服务停掉后遗留的 open 段不会再被续写，显示时按已结束处理
        cy.open = c.getInt(9) == 0
                && (System.currentTimeMillis() / 1000 - cy.endTs) <= 600;
        finalizeCycle(cy);
        return cy;
    }

    /** 读取最近 limit 条采样（按时间升序） */
    public static List<Sample> readSamples(BatteryDbHelper db, int limit) {
        List<Sample> list = new ArrayList<>();
        Cursor c = db.queryRecent(limit);
        while (c.moveToNext()) {
            Sample s = new Sample();
            s.ts = c.getLong(0);
            s.level = c.getDouble(1);
            s.cc = c.getLong(2);
            s.cf = c.getLong(3);
            s.status = c.getInt(4);
            s.temp = c.getDouble(5);
            s.cn = c.getLong(6);
            s.cfd = c.getLong(7);
            list.add(s);
        }
        c.close();
        return list;
    }

    /** 从采样序列切分出充/放电循环（按时间升序返回） */
    public static List<Cycle> compute(List<Sample> list) {
        List<Cycle> out = new ArrayList<>();
        Cycle run = null;
        for (Sample s : list) {
            int type = typeOf(s.status);
            if (type == 0) {
                if (run != null) { addIfValid(out, finalizeCycle(run)); run = null; }
                continue;
            }
            if (run == null || run.type != type) {
                if (run != null) addIfValid(out, finalizeCycle(run));
                run = new Cycle();
                run.type = type;
                run.startTs = s.ts;
                run.startLevel = (float) s.level;
                run.startCc = s.cc;
                run.maxCf = s.cf;
            } else {
                run.endTs = s.ts;
                run.endLevel = (float) s.level;
                run.endCc = s.cc;
                if (s.cf > run.maxCf) run.maxCf = s.cf;
            }
        }
        if (run != null) addIfValid(out, finalizeCycle(run));
        return out;
    }

    /** finalize() 返回 null 表示该段无意义（电量几乎没变化），不加入列表 */
    private static void addIfValid(List<Cycle> out, Cycle c) {
        if (c != null) out.add(c);
    }

    private static Cycle finalizeCycle(Cycle r) {
        r.endTs = r.endTs == 0 ? r.startTs : r.endTs;
        r.endLevel = r.endLevel == 0 ? r.startLevel : r.endLevel;
        r.durationMin = (int) ((r.endTs - r.startTs) / 60);
        double dLevel = r.endLevel - r.startLevel;
        long dCc = r.endCc - r.startCc;                 // µAh
        r.levelText = pctText(dLevel);
        // 容量变化：库仑计读数可能跳变，仅在可信量级(>1mAh)时展示，符号取真实增减
        if (Math.abs(dCc) >= 1000) {
            long mah = Math.abs(dCc) / 1000;
            r.capText = (dCc > 0 ? "+" : "-") + mah + "mAh";
        } else {
            r.capText = "";
        }
        r.fullCap = (r.type == 1) ? (int) (r.maxCf / 1000) : 0;
        // 过滤无意义段：不足 1 分钟且电量几乎没变化的瞬时抖动（状态位翻转造成）
        if (r.durationMin < 1 && Math.abs(dLevel) < 1) return null;
        // 过滤空段：既无电量变化也无可信容量变化
        if (Math.abs(dLevel) < 1 && r.capText.length() == 0) return null;
        return r;
    }

    /** 电量变化文案：带真实符号，避免出现 "-0%" */
    private static String pctText(double d) {
        if (d >= 0.5) return String.format(Locale.US, "+%.0f%%", d);
        if (d <= -0.5) return String.format(Locale.US, "%.0f%%", d);
        return "0%";
    }

    public static boolean isCharge(Cycle c) {
        return c != null && c.type == 1;
    }

    /**
     * 按时间升序给每条记录编「第几次」：不分充电/放电，统一流水号。
     * 每当有新记录落库或用户删除记录后都会重算，序号始终连续可读。
     */
    public static void assignSeq(List<Cycle> ascList) {
        int n = 0;
        for (Cycle c : ascList) {
            c.seq = ++n;
        }
    }

    /** 主行：总第几次 + 时间段（序号充放电统一编号；充放电由列表项自身的标签与配色区分） */
    public static String titleOf(Cycle cy) {
        String head = cy.seq > 0
                ? "第 " + cy.seq + " 次"
                : (isCharge(cy) ? "充电" : "放电");   // 极少数未编号场景退回标类型
        String t = head + "  " + SDF.format(new Date(cy.startTs * 1000L));
        if (cy.open) return t + " → 进行中";
        return t + " → " + SDF.format(new Date(cy.endTs * 1000L));
    }

    /** 副行：电量变化 + 容量变化 + 时长（+ 满电容量） */
    public static String detailOf(Cycle cy) {
        StringBuilder sb = new StringBuilder();
        sb.append(cy.levelText);
        if (cy.capText != null && cy.capText.length() > 0) sb.append("  ").append(cy.capText);
        sb.append("  ·  用时 ").append(durationText(cy.durationMin));
        if (cy.type == 1 && cy.fullCap > 0) sb.append("  ·  满电 ").append(cy.fullCap).append("mAh");
        return sb.toString();
    }

    /** 分钟数转「x小时y分」 */
    public static String durationText(int min) {
        if (min <= 0) return "不足1分";
        if (min < 60) return min + " 分钟";
        int h = min / 60, m = min % 60;
        return m == 0 ? h + " 小时" : h + " 小时 " + m + " 分";
    }

    /** 全部循环统计摘要（充放电段数、累计充电时长、平均充电时长） */
    public static String summaryOf(List<Cycle> cycles) {
        int cCount = 0, dCount = 0, cMin = 0, dMin = 0;
        for (Cycle c : cycles) {
            if (isCharge(c)) { cCount++; cMin += c.durationMin; }
            else { dCount++; dMin += c.durationMin; }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(cycles.size()).append(" 次（充电 ").append(cCount)
                .append(" · 放电 ").append(dCount).append("）");
        if (cCount > 0) {
            sb.append("　平均充电 ").append(durationText(cMin / cCount));
        }
        return sb.toString();
    }
}
