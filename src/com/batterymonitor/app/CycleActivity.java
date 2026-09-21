package com.batterymonitor.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 充放电循环记录专页。
 *
 * 数据来自 cycles 表（由 CycleRecorder 在采样时归档），**持久保存、只能手动删除**：
 *   - 每条记录标注是「第几次充电 / 第几次放电」，序号按类型独立累计；
 *   - 长按单条可删除该记录，顶部「清空全部」可一次清空（均需二次确认）；
 *   - 页面不做任何自动清理，记录不会因时间或数量被丢弃。
 */
public class CycleActivity extends Activity implements View.OnClickListener {
    private BatteryDbHelper db;
    private ListView lv;
    private TextView tvSummary, tvEmpty;
    private Button btnClear;
    CycleAdapter adapter;

    private Handler h;
    private volatile boolean busy;

    static final int COLOR_CHARGE = 0xFF2F7DF6;    // 充电：蓝
    static final int COLOR_DISCHARGE = 0xFFE8842C; // 放电：橙

    /** 命名适配器（不用匿名类：R8 8.2.2-dev 对匿名类转换会内部崩溃） */
    static class CycleAdapter extends BaseAdapter {
        private final Context ctx;
        private final List<Cycles.Cycle> data = new ArrayList<>();
        private final LayoutInflater inflater;

        CycleAdapter(Context c) {
            ctx = c;
            inflater = LayoutInflater.from(c);
        }

        /** 传入按时间升序（已编号）的列表，内部反转为倒序（最新在前） */
        void setData(List<Cycles.Cycle> src) {
            data.clear();
            for (int i = src.size() - 1; i >= 0; i--) data.add(src.get(i));
            notifyDataSetChanged();
        }

        @Override
        public int getCount() { return data.size(); }

        @Override
        public Object getItem(int position) { return data.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) v = inflater.inflate(R.layout.app_cycle_item, parent, false);
            Cycles.Cycle cy = data.get(position);
            boolean charge = Cycles.isCharge(cy);
            int color = charge ? COLOR_CHARGE : COLOR_DISCHARGE;

            TextView tvType = v.findViewById(R.id.tvType);
            TextView tvTitle = v.findViewById(R.id.tvTitle);
            TextView tvLevel = v.findViewById(R.id.tvLevel);
            TextView tvDetail = v.findViewById(R.id.tvDetail);

            tvType.setText(charge ? "充电" : "放电");
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(ctx, 4));
            bg.setColor(color);
            tvType.setBackground(bg);

            tvTitle.setText(Cycles.titleOf(cy));
            tvLevel.setText(cy.levelText);
            tvLevel.setTextColor(color);
            String detail = Cycles.detailOf(cy);
            if (cy.open) detail = detail + "  ·  记录中";
            tvDetail.setText(detail);
            return v;
        }
    }

    private static float dp(Context c, int v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    // ==================== 后台加载 ====================

    private static class LoadTask implements Runnable {
        private final WeakReference<CycleActivity> ref;

        LoadTask(CycleActivity a) { ref = new WeakReference<>(a); }

        @Override
        public void run() {
            CycleActivity a = ref.get();
            if (a == null) return;
            List<Cycles.Cycle> list = new ArrayList<>();
            try {
                // 首次升级到本版本时把历史采样折算成记录（幂等，跑完即打标）
                CycleRecorder.backfillIfNeeded(a, a.db);
                Cursor c = a.db.queryCycles();
                try {
                    while (c.moveToNext()) list.add(Cycles.fromCursor(c));
                } finally {
                    c.close();
                }
                Cycles.assignSeq(list);
            } catch (Throwable ignored) {
            }
            a.h.post(new ApplyTask(a, list));
        }
    }

    private static class ApplyTask implements Runnable {
        private final WeakReference<CycleActivity> ref;
        private final List<Cycles.Cycle> list;

        ApplyTask(CycleActivity a, List<Cycles.Cycle> l) {
            ref = new WeakReference<>(a);
            list = l;
        }

        @Override
        public void run() {
            CycleActivity a = ref.get();
            if (a == null) return;
            a.adapter.setData(list);
            a.tvSummary.setText(Cycles.summaryOf(list)
                    + "\n记录已永久保存，长按某条可删除");
            boolean empty = list.isEmpty();
            a.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            a.btnClear.setEnabled(!empty);
            a.busy = false;
        }
    }

    // ==================== 删除交互 ====================

    /** 长按单条：弹确认框 */
    private static class LongPressListener implements AdapterView.OnItemLongClickListener {
        private final WeakReference<CycleActivity> ref;

        LongPressListener(CycleActivity a) { ref = new WeakReference<>(a); }

        @Override
        public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
            CycleActivity a = ref.get();
            if (a == null) return false;
            Object o = a.adapter.getItem(position);
            if (o instanceof Cycles.Cycle) {
                a.confirmDelete((Cycles.Cycle) o);
                return true;
            }
            return false;
        }
    }

    private static class DeleteOneListener implements DialogInterface.OnClickListener {
        private final WeakReference<CycleActivity> ref;
        private final long id;
        private final int type;
        private final boolean open;

        DeleteOneListener(CycleActivity a, Cycles.Cycle cy) {
            ref = new WeakReference<>(a);
            id = cy.id;
            type = cy.type;
            open = cy.open;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            CycleActivity a = ref.get();
            if (a == null) return;
            try {
                // 删的是「进行中」那段：抑制该类型，否则 30 秒后采样器会把同样的段写回来
                if (open) CycleRecorder.suppress(a, type);
                a.db.deleteCycle(id);
            } catch (Throwable ignored) {
            }
            a.refresh();
        }
    }

    private static class DeleteAllListener implements DialogInterface.OnClickListener {
        private final WeakReference<CycleActivity> ref;

        DeleteAllListener(CycleActivity a) { ref = new WeakReference<>(a); }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            CycleActivity a = ref.get();
            if (a == null) return;
            try {
                BatteryDbHelper.OpenCycle o = a.db.getOpenCycle();
                if (o != null) CycleRecorder.suppress(a, o.type);
                a.db.deleteAllCycles();
            } catch (Throwable ignored) {
            }
            a.refresh();
        }
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cycles);
        h = new Handler(Looper.getMainLooper());
        lv = findViewById(R.id.lvCycles);
        tvSummary = findViewById(R.id.tvCycleSummary);
        tvEmpty = findViewById(R.id.tvCycleEmpty);
        btnClear = findViewById(R.id.btnClearCycles);
        Button btn = findViewById(R.id.btnRefreshCycles);
        btn.setOnClickListener(this);
        btnClear.setOnClickListener(this);
        db = new BatteryDbHelper(this);
        adapter = new CycleAdapter(this);
        lv.setAdapter(adapter);
        lv.setOnItemLongClickListener(new LongPressListener(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (db != null) {
            try { db.close(); } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btnClearCycles) confirmClearAll();
        else refresh();
    }

    private void refresh() {
        if (busy) return;
        busy = true;
        tvSummary.setText("统计中…");
        new Thread(new LoadTask(this)).start();
    }

    private void confirmDelete(Cycles.Cycle cy) {
        new AlertDialog.Builder(this)
                .setTitle("删除这条记录？")
                .setMessage(Cycles.titleOf(cy) + "\n\n删除后无法恢复。")
                .setPositiveButton("删除", new DeleteOneListener(this, cy))
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearAll() {
        int n = adapter.getCount();
        new AlertDialog.Builder(this)
                .setTitle("清空全部循环记录？")
                .setMessage("将删除全部 " + n + " 条充放电记录，且无法恢复。\n"
                        + "（记录平时不会被自动清理，只有在此处手动删除才会消失）")
                .setPositiveButton("清空", new DeleteAllListener(this))
                .setNegativeButton("取消", null)
                .show();
    }
}
