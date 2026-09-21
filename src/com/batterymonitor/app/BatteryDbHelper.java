package com.batterymonitor.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BatteryDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "battery.db";
    private static final int DB_VERSION = 2;

    public static final String T_SAMPLES = "samples";
    public static final String C_ID = "id";
    public static final String C_TS = "ts";
    public static final String C_LEVEL = "level";
    public static final String C_STATUS = "status";
    public static final String C_HEALTH = "health_code";
    public static final String C_VOLTAGE = "voltage";
    public static final String C_TEMP = "temperature";
    public static final String C_CC = "charge_counter";
    public static final String C_CF = "charge_full";
    public static final String C_CFD = "charge_full_design";
    public static final String C_CN = "current_now";
    public static final String C_PRESENT = "present";

    /** 充放电循环记录表：与 samples 解耦，落库后永久保存，只能由用户手动删除 */
    public static final String T_CYCLES = "cycles";
    public static final String Y_ID = "id";
    public static final String Y_TYPE = "type";              // 1=充电 2=放电
    public static final String Y_START_TS = "start_ts";
    public static final String Y_LAST_TS = "last_ts";        // 该段最后一条采样的时间
    public static final String Y_START_LEVEL = "start_level";
    public static final String Y_LAST_LEVEL = "last_level";
    public static final String Y_START_CC = "start_cc";
    public static final String Y_LAST_CC = "last_cc";
    public static final String Y_MAX_CF = "max_cf";
    public static final String Y_DONE = "done";              // 0=进行中 1=已结束

    public BatteryDbHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createSamples(db);
        createCycles(db);
    }

    /**
     * 升级路径必须**保数据**：samples 是不可再生的历史来源，
     * 老实现无条件 DROP 会清空用户积累的全部采样，这里改为按版本增量建表。
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 1) createSamples(db);
        if (oldV < 2) createCycles(db);
    }

    private static void createSamples(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_SAMPLES + " (" +
                C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                C_TS + " INTEGER NOT NULL, " +
                C_LEVEL + " REAL, " +
                C_STATUS + " INTEGER, " +
                C_HEALTH + " INTEGER, " +
                C_VOLTAGE + " INTEGER, " +
                C_TEMP + " REAL, " +
                C_CC + " INTEGER, " +
                C_CF + " INTEGER, " +
                C_CFD + " INTEGER, " +
                C_CN + " INTEGER, " +
                C_PRESENT + " INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ts ON " + T_SAMPLES + "(" + C_TS + ")");
    }

    private static void createCycles(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_CYCLES + " (" +
                Y_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                Y_TYPE + " INTEGER NOT NULL, " +
                Y_START_TS + " INTEGER NOT NULL, " +
                Y_LAST_TS + " INTEGER NOT NULL DEFAULT 0, " +
                Y_START_LEVEL + " REAL NOT NULL DEFAULT 0, " +
                Y_LAST_LEVEL + " REAL NOT NULL DEFAULT 0, " +
                Y_START_CC + " INTEGER NOT NULL DEFAULT 0, " +
                Y_LAST_CC + " INTEGER NOT NULL DEFAULT 0, " +
                Y_MAX_CF + " INTEGER NOT NULL DEFAULT 0, " +
                Y_DONE + " INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cy_start ON " + T_CYCLES + "(" + Y_START_TS + ")");
    }

    public long insert(long ts, double level, int status, int health, int voltage,
                       double temp, long cc, long cf, long cfd, long cn, int present) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(C_TS, ts);
        v.put(C_LEVEL, level);
        v.put(C_STATUS, status);
        v.put(C_HEALTH, health);
        v.put(C_VOLTAGE, voltage);
        v.put(C_TEMP, temp);
        v.put(C_CC, cc);
        v.put(C_CF, cf);
        v.put(C_CFD, cfd);
        v.put(C_CN, cn);
        v.put(C_PRESENT, present);
        long id = db.insert(T_SAMPLES, null, v);
        db.close();
        return id;
    }

    /** 返回最近的采样（按时间升序），字段见 projection。
     *  注意必须先取最新 limit 条再正序——直接 ASC+LIMIT 会拿到最旧的。 */
    public Cursor queryRecent(int limit) {
        SQLiteDatabase db = getReadableDatabase();
        return db.query(T_SAMPLES,
                new String[]{C_TS, C_LEVEL, C_CC, C_CF, C_STATUS, C_TEMP, C_CN, C_CFD},
                C_TS + " IN (SELECT " + C_TS + " FROM " + T_SAMPLES +
                        " ORDER BY " + C_TS + " DESC LIMIT " + limit + ")",
                null, null, null,
                C_TS + " ASC", null);
    }

    /** 健康度 = 最近一次 charge_full / charge_full_design */
    public double[] getCapacities() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_SAMPLES,
                new String[]{C_CF, C_CFD},
                null, null, null, null,
                C_TS + " DESC", "1");
        double[] r = {0, 0};
        if (c.moveToFirst()) {
            r[0] = c.getLong(0); // charge_full
            r[1] = c.getLong(1); // charge_full_design
        }
        c.close();
        db.close();
        return r;
    }

    /**
     * 预估容量（不依赖厂商上报的 charge_full，独立估算电池实际总容量）。
     * 原理：一段连续充电/放电内，累计流过的电量 ΔmAh 与电量百分比变化 Δ% 之比 → 满电总容量。
     *   ΔmAh 优先取库仑计 charge_counter 差值，没有则对 current_now 做时间积分。
     * 取最近若干段的中位数，抑制单段误差。
     *
     * @return {预估容量mAh, 有效段数}；样本不足时 {0,0}
     */
    public double[] estimateCapacity() {
        double[] out = {0, 0};
        List<Double> caps = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            long since = System.currentTimeMillis() / 1000 - 7 * 24 * 3600L;
            // 先取最近 3000 条，再按时间升序，避免长时间运行后全表扫描
            c = db.rawQuery("SELECT " + C_TS + "," + C_LEVEL + "," + C_STATUS + "," + C_CC + "," + C_CN +
                            " FROM (SELECT " + C_TS + "," + C_LEVEL + "," + C_STATUS + "," + C_CC + "," + C_CN +
                            " FROM " + T_SAMPLES + " WHERE " + C_TS + ">=? ORDER BY " + C_TS + " DESC LIMIT 3000)" +
                            " ORDER BY " + C_TS + " ASC",
                    new String[]{String.valueOf(since)});
            Seg seg = null;
            while (c.moveToNext()) {
                long ts = c.getLong(0);
                double level = c.getDouble(1);
                int status = c.getInt(2);
                long cc = c.getLong(3);
                long cn = c.getLong(4);
                int t = (status == 2 || status == 5) ? 1 : (status == 3 ? 2 : 0);
                if (t == 0) {
                    flushSeg(seg, caps);
                    seg = null;
                    continue;
                }
                if (seg == null || seg.type != t) {
                    flushSeg(seg, caps);
                    seg = new Seg();
                    seg.type = t;
                    seg.startTs = ts;
                    seg.startLevel = level;
                    seg.startCc = cc;
                    seg.prevTs = ts;
                    seg.prevCn = cn;
                } else {
                    if (ts > seg.prevTs) {
                        seg.sumMah += Math.abs(seg.prevCn) / 1000.0 * (ts - seg.prevTs) / 3600.0;
                    }
                    seg.prevTs = ts;
                    seg.prevCn = cn;
                }
                seg.endTs = ts;
                seg.endLevel = level;
                seg.endCc = cc;
            }
            flushSeg(seg, caps);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
            db.close();
        }
        if (caps.isEmpty()) return out;
        int m = Math.min(5, caps.size());
        List<Double> recent = new ArrayList<>(caps.subList(caps.size() - m, caps.size()));
        Collections.sort(recent);
        out[0] = recent.get(recent.size() / 2);
        out[1] = caps.size();
        return out;
    }

    private static class Seg {
        int type;
        long startTs, endTs, prevTs;
        double startLevel, endLevel;
        long startCc, endCc, prevCn;
        double sumMah;
    }

    private static void flushSeg(Seg s, List<Double> caps) {
        if (s == null || s.type == 0) return;
        double dl = Math.abs(s.endLevel - s.startLevel);
        if (dl < 2.0) return;                      // 电量变化太小，估算不可靠
        if (s.prevTs > 0 && s.endTs > s.prevTs) {
            s.sumMah += Math.abs(s.prevCn) / 1000.0 * (s.endTs - s.prevTs) / 3600.0;
        }
        double mah;
        if (s.startCc > 0 && s.endCc > 0 && s.endCc != s.startCc) {
            mah = Math.abs(s.endCc - s.startCc) / 1000.0;   // 库仑计（更准）
        } else {
            mah = s.sumMah;                                  // 电流积分兜底
        }
        if (mah <= 0) return;
        double cap = mah / (dl / 100.0);
        if (cap < 200 || cap > 200000) return;     // 明显异常丢弃
        caps.add(cap);
    }

    /**
     * 近 24 小时内放电状态的平均电流（mA），用于估算续航。
     * 只统计明显放电（<-10mA）的采样点；无数据返回 0。
     */
    public double avgDischargeMa() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            long since = System.currentTimeMillis() / 1000 - 24 * 3600L;
            c = db.rawQuery("SELECT AVG(-" + C_CN + ") FROM " + T_SAMPLES +
                            " WHERE " + C_CN + " < -10000 AND " + C_TS + " >= ?",
                    new String[]{String.valueOf(since)});
            if (c.moveToFirst() && !c.isNull(0)) return c.getDouble(0) / 1000.0;
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
            db.close();
        }
        return 0;
    }

    /**
     * 近 7 天记录中的最大放电电流（mA），代表「重度使用时的功耗」，
     * 用于充电状态下推算参考续航。
     *
     * 自适应过滤：不再写死 5A 这类阈值（会误杀 30W 级高功耗机型），
     * 而是基于设备自身历史放电分布（中位数 + MAD 鲁棒统计）算出动态上限，
     * 只丢弃明显的离群尖峰（单位错误 / 数据损坏级），保留真实高功耗。
     * 物理硬上限 20A（20000mA）兜底。无可用数据返回 0。
     */
    public double maxDischargeMa() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        List<Double> vals = new ArrayList<>();
        try {
            long since = System.currentTimeMillis() / 1000 - 7 * 24 * 3600L;
            c = db.rawQuery("SELECT -" + C_CN + " FROM " + T_SAMPLES +
                            " WHERE " + C_CN + " < -10000 AND " + C_TS + " >= ?",
                    new String[]{String.valueOf(since)});
            while (c.moveToNext()) {
                double ma = c.getDouble(0) / 1000.0;   // µA → mA
                if (ma > 0 && ma < 20000) vals.add(ma); // 物理硬上限 20A，先剔除明显损坏数据
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
            db.close();
        }
        if (vals.isEmpty()) return 0;
        Collections.sort(vals);
        int n = vals.size();
        double median = vals.get(n / 2);
        // MAD（中位数绝对偏差），对离群稳健
        List<Double> devs = new ArrayList<>(n);
        for (double v : vals) devs.add(Math.abs(v - median));
        Collections.sort(devs);
        double mad = devs.get(devs.size() / 2);
        double robust = 1.4826 * mad;
        // 自适应上限：中位数 + 8×稳健标准差；同时至少允许 4×中位数（低功耗机型也保留正常峰值）
        double limit = Math.max(median + 8 * robust, median * 4);
        limit = Math.min(limit, 20000);
        double maxv = 0;
        for (double v : vals) {
            if (v <= limit && v > maxv) maxv = v;
        }
        return maxv;
    }

    public int countRows() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + T_SAMPLES, null);
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        db.close();
        return n;
    }

    // ==================== 充放电循环记录（持久化，仅手动删除） ====================

    /** 一条「进行中」的循环段，供归档器续写用 */
    public static class OpenCycle {
        public long id;
        public int type;
        public long startTs, lastTs;
        public double startLevel, lastLevel;
        public long startCc, lastCc, maxCf;
    }

    /** 取当前进行中的段（done=0 且开始时间最早的那条会被先收尾，故取最新一条即可） */
    public OpenCycle getOpenCycle() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query(T_CYCLES,
                    new String[]{Y_ID, Y_TYPE, Y_START_TS, Y_LAST_TS,
                            Y_START_LEVEL, Y_LAST_LEVEL, Y_START_CC, Y_LAST_CC, Y_MAX_CF},
                    Y_DONE + "=0", null, null, null, Y_ID + " DESC", "1");
            if (c.moveToFirst()) {
                OpenCycle o = new OpenCycle();
                o.id = c.getLong(0);
                o.type = c.getInt(1);
                o.startTs = c.getLong(2);
                o.lastTs = c.getLong(3);
                o.startLevel = c.getDouble(4);
                o.lastLevel = c.getDouble(5);
                o.startCc = c.getLong(6);
                o.lastCc = c.getLong(7);
                o.maxCf = c.getLong(8);
                return o;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
            db.close();
        }
        return null;
    }

    /** 开启一段新循环（done=0），返回自增主键（= 记录的全局流水号） */
    public long startCycle(int type, long ts, double level, long cc, long cf) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(Y_TYPE, type);
        v.put(Y_START_TS, ts);
        v.put(Y_LAST_TS, ts);
        v.put(Y_START_LEVEL, level);
        v.put(Y_LAST_LEVEL, level);
        v.put(Y_START_CC, cc);
        v.put(Y_LAST_CC, cc);
        v.put(Y_MAX_CF, cf);
        v.put(Y_DONE, 0);
        long id = db.insert(T_CYCLES, null, v);
        db.close();
        return id;
    }

    /** 续写进行中的段（只更新末端量与满电容量峰值，不动起点） */
    public void touchCycle(long id, long ts, double level, long cc, long maxCf) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(Y_LAST_TS, ts);
        v.put(Y_LAST_LEVEL, level);
        v.put(Y_LAST_CC, cc);
        v.put(Y_MAX_CF, maxCf);
        db.update(T_CYCLES, v, Y_ID + "=?", new String[]{String.valueOf(id)});
        db.close();
    }

    /** 收尾一段循环：写入末端量并置 done=1（此后永久保留，不会被自动清理） */
    public void finishCycle(long id, long ts, double level, long cc, long maxCf) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(Y_LAST_TS, ts);
        v.put(Y_LAST_LEVEL, level);
        v.put(Y_LAST_CC, cc);
        v.put(Y_MAX_CF, maxCf);
        v.put(Y_DONE, 1);
        db.update(T_CYCLES, v, Y_ID + "=?", new String[]{String.valueOf(id)});
        db.close();
    }

    /** 删除单条记录（仅由用户手动触发） */
    public void deleteCycle(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(T_CYCLES, Y_ID + "=?", new String[]{String.valueOf(id)});
        db.close();
    }

    /** 清空全部记录（仅由用户手动触发） */
    public void deleteAllCycles() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(T_CYCLES, null, null);
        db.close();
    }

    /** 全部循环记录，按开始时间升序（序号由调用方按类型累计） */
    public Cursor queryCycles() {
        SQLiteDatabase db = getReadableDatabase();
        return db.query(T_CYCLES,
                new String[]{Y_ID, Y_TYPE, Y_START_TS, Y_LAST_TS,
                        Y_START_LEVEL, Y_LAST_LEVEL, Y_START_CC, Y_LAST_CC, Y_MAX_CF, Y_DONE},
                null, null, null, null, Y_START_TS + " ASC");
    }

    public int countCycles() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        int n = 0;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM " + T_CYCLES, null);
            if (c.moveToFirst()) n = c.getInt(0);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
            db.close();
        }
        return n;
    }

    /** @return {充电段数, 放电段数} */
    public int[] countCyclesByType() {
        int[] out = {0, 0};
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT " + Y_TYPE + ", COUNT(*) FROM " + T_CYCLES +
                    " GROUP BY " + Y_TYPE, null);
            while (c.moveToNext()) {
                int t = c.getInt(0);
                int n = c.getInt(1);
                if (t == 1) out[0] = n;
                else if (t == 2) out[1] = n;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
            db.close();
        }
        return out;
    }

    /** 回填历史用：按 samples 全量取数（升序），上限 limit 条（约 7 天 @30s） */
    public Cursor queryAllForBackfill(int limit) {
        String cols = C_TS + "," + C_LEVEL + "," + C_CC + "," + C_CF + "," +
                C_STATUS + "," + C_TEMP + "," + C_CN + "," + C_CFD;
        SQLiteDatabase db = getReadableDatabase();
        return db.rawQuery("SELECT " + cols +
                        " FROM (SELECT " + cols + " FROM " + T_SAMPLES +
                        " ORDER BY " + C_TS + " DESC LIMIT " + limit + ")" +
                        " ORDER BY " + C_TS + " ASC",
                null);
    }

    public void exportCsv(Writer w) throws IOException {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_SAMPLES,
                new String[]{C_TS, C_LEVEL, C_STATUS, C_VOLTAGE, C_TEMP, C_CC, C_CF, C_CFD, C_CN},
                null, null, null, null, C_TS + " ASC");
        w.write("ts,level,status,voltage_mv,temp_c,charge_counter_uah,charge_full_uah,charge_full_design_uah,current_now_ua\n");
        while (c.moveToNext()) {
            w.write(c.getLong(0) + "," + c.getDouble(1) + "," + c.getInt(2) + "," +
                    c.getInt(3) + "," + c.getDouble(4) + "," + c.getLong(5) + "," +
                    c.getLong(6) + "," + c.getLong(7) + "," + c.getLong(8) + "\n");
        }
        c.close();
        db.close();
    }
}
