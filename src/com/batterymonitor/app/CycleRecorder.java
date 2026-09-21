package com.batterymonitor.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

/**
 * 充放电循环记录的**归档器**。
 *
 * 与旧实现（每次打开页面从最近 600 条采样现算）相比，这里把每一段充/放电过程
 * 在采样时就写进 cycles 表，因此：
 *   - 记录是持久的：采样被覆盖/表被裁剪都不会影响已归档的循环；
 *   - 只有用户手动删除才会消失（本类不存在任何自动清理）。
 *
 * 归档状态机（每次采样调用一次 onSample）：
 *   类型不变且未断层 → 续写当前段
 *   类型切换 / 状态变为「无充放电」/ 断层超过 10 分钟 → 收尾当前段，必要时开新段
 * 收尾时用与页面展示一致的规则丢弃伪段（不足 1 分钟且电量几乎没变化）。
 */
public final class CycleRecorder {

    /** 采样断层阈值（秒）：超过视为两次独立过程，不再续写，避免把熄屏停机拉成一条巨段 */
    private static final long GAP_MAX_SEC = 10 * 60;
    private static final String PREF = "bm_prefs";
    private static final String KEY_BACKFILL = "cycle_backfill_v2";
    private static final String KEY_SUPPRESS = "cycle_suppress_type";
    private static final int BACKFILL_LIMIT = 20000;   // 约 7 天 @30s

    private CycleRecorder() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /**
     * 用户手动删除了「进行中」的那一段时调用：抑制该类型的新段创建，
     * 否则下一次采样（30 秒后）会立刻把同样的段再写回来，删除就形同无效。
     * 抑制一直持续到充/放电状态本身发生变化为止。
     */
    public static void suppress(Context ctx, int type) {
        try {
            prefs(ctx).edit().putInt(KEY_SUPPRESS, type).apply();
        } catch (Throwable ignored) {
        }
    }

    private static int suppressedType(Context ctx) {
        try {
            return prefs(ctx).getInt(KEY_SUPPRESS, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 每次采样入库后调用一次；任何异常都不能影响采样主流程 */
    public static void onSample(Context ctx, BatteryDbHelper db, long ts, double level,
                                long cc, long cf, int status) {
        try {
            int type = Cycles.typeOf(status);
            int sup = suppressedType(ctx);
            if (sup != 0 && type != sup) {
                // 该类型已结束（拔电 / 重新充电），解除抑制，恢复正常记录
                prefs(ctx).edit().putInt(KEY_SUPPRESS, 0).apply();
                sup = 0;
            }
            BatteryDbHelper.OpenCycle open = db.getOpenCycle();

            // 长时间断层：旧段就此封口（用它自己最后一次采样的数据），下面重新开段
            if (open != null && ts - open.lastTs > GAP_MAX_SEC) {
                closeSegment(db, open);
                open = null;
            }
            if (type == 0) {
                if (open != null) closeSegment(db, open);
                return;
            }
            if (open == null || open.type != type) {
                if (open != null) closeSegment(db, open);
                if (sup == type) return;          // 这一段被用户删过，本次不再新建
                db.startCycle(type, ts, level, cc, cf);
                return;
            }
            db.touchCycle(open.id, ts, level, cc, Math.max(open.maxCf, cf));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 收尾一段：伪段直接丢弃（删行），有效段置 done=1 永久保留。
     * 判据与 Cycles.finalizeCycle 保持一致。
     */
    private static void closeSegment(BatteryDbHelper db, BatteryDbHelper.OpenCycle o) {
        int durationMin = (int) ((o.lastTs - o.startTs) / 60);
        double dLevel = o.lastLevel - o.startLevel;
        long dCc = o.lastCc - o.startCc;

        boolean trivial = durationMin < 1 && Math.abs(dLevel) < 1;
        boolean empty = Math.abs(dLevel) < 1 && Math.abs(dCc) < 1000;
        if (trivial || empty) {
            db.deleteCycle(o.id);
            return;
        }
        db.finishCycle(o.id, o.lastTs, o.lastLevel, o.lastCc, o.maxCf);
    }

    /**
     * 首次升级到本版本时，把 samples 里已有的历史一次性折算成循环记录，
     * 避免老用户打开页面看到「暂无记录」。只执行一次（SharedPreferences 打标）。
     */
    public static void backfillIfNeeded(Context ctx, BatteryDbHelper db) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            if (sp.getBoolean(KEY_BACKFILL, false)) return;
            if (db.countCycles() == 0) {
                doBackfill(db);
            }
            sp.edit().putBoolean(KEY_BACKFILL, true).commit();
        } catch (Throwable ignored) {
        }
    }

    private static void doBackfill(BatteryDbHelper db) {
        List<Cycles.Sample> samples = new ArrayList<>();
        long lastSampleTs = 0;
        Cursor c = null;
        try {
            c = db.queryAllForBackfill(BACKFILL_LIMIT);
            while (c.moveToNext()) {
                Cycles.Sample s = new Cycles.Sample();
                s.ts = c.getLong(0);
                s.level = c.getDouble(1);
                s.cc = c.getLong(2);
                s.cf = c.getLong(3);
                s.status = c.getInt(4);
                s.temp = c.getDouble(5);
                s.cn = c.getLong(6);
                s.cfd = c.getLong(7);
                samples.add(s);
                if (s.ts > lastSampleTs) lastSampleTs = s.ts;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (samples.isEmpty()) return;

        List<Cycles.Cycle> hist = Cycles.compute(samples);
        int n = hist.size();
        for (int i = 0; i < n; i++) {
            Cycles.Cycle cy = hist.get(i);
            long id = db.startCycle(cy.type, cy.startTs, cy.startLevel, cy.startCc, cy.maxCf);
            // 最后一段若紧贴最新采样，说明它可能仍在进行，留给归档器续写（done 保持 0）
            boolean stillRunning = (i == n - 1) && (lastSampleTs - cy.endTs <= GAP_MAX_SEC);
            if (stillRunning) {
                db.touchCycle(id, cy.endTs, cy.endLevel, cy.endCc, cy.maxCf);
            } else {
                db.finishCycle(id, cy.endTs, cy.endLevel, cy.endCc, cy.maxCf);
            }
        }
    }

    /** 后台回填任务（R8 环境禁用匿名类，故用命名类） */
    public static class BackfillTask implements Runnable {
        private final Context ctx;

        public BackfillTask(Context c) {
            ctx = c.getApplicationContext();
        }

        @Override
        public void run() {
            BatteryDbHelper db = null;
            try {
                db = new BatteryDbHelper(ctx);
                backfillIfNeeded(ctx, db);
            } catch (Throwable ignored) {
            } finally {
                if (db != null) {
                    try { db.close(); } catch (Throwable ignored) { }
                }
            }
        }
    }
}
