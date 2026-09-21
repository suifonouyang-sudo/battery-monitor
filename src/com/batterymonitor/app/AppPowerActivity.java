package com.batterymonitor.app;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

import rikka.shizuku.Shizuku;

/**
 * 应用耗电排行：通过 Shizuku / root 执行 dumpsys batterystats，
 * 列出所有软件自上次充满以来的累计耗电（mAh），降序排列。
 *
 * 注意：本类刻意不使用任何匿名内部类（OnClickListener / Runnable / ArrayAdapter 均用命名类），
 * 以规避当前 R8 (8.2.2-dev) 对匿名类的已知转换崩溃（NPE: String.length()）。
 */
public class AppPowerActivity extends Activity implements View.OnClickListener {
    private TextView tvSummary;
    private ListView lv;
    private Button btnRefresh;
    private Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_app_power);
        tvSummary = findViewById(R.id.tvSummary);
        lv = findViewById(R.id.lvAppPower);
        btnRefresh = findViewById(R.id.btnRefresh);
        ShizukuExec.bind(this);
        btnRefresh.setOnClickListener(this);
        load();
    }

    @Override
    public void onClick(View v) {
        if (v == btnRefresh) load();
    }

    private void load() {
        tvSummary.setText("读取中…（通过 Shizuku / root 执行 dumpsys batterystats）");
        lv.setAdapter(null);
        new Thread(new LoadTask(this)).start();
    }

    private void apply(AppPowerReader.Result r) {
        if (r == null || r.items == null || r.items.isEmpty()) {
            String msg = (r != null && r.error != null) ? r.error : "未读到任何耗电数据";
            tvSummary.setText(msg);
            Toast.makeText(this, "未读到数据", Toast.LENGTH_LONG).show();
            return;
        }
        String cap = r.capacityMah > 0 ? AppPowerReader.fmt(r.capacityMah) : "?";
        String extra = r.foreignItems > 0
                ? " · 含 " + r.foreignItems + " 项来自 " + r.foreignUserDesc + "（无法解析名称）"
                : "";
        tvSummary.setText("电池标称 " + cap + " mAh · 自上次充满实际放电 "
                + AppPowerReader.fmt(r.totalDrainMah) + " mAh"
                + " · 共 " + r.items.size() + " 项，各项合计 " + AppPowerReader.fmt(r.appsSubtotalMah)
                + " mAh（百分比以此为基准）" + extra);
        lv.setAdapter(new PowerAdapter(this, r));
    }

    /** 命名静态任务：后台执行 dumpsys 抓取 */
    private static class LoadTask implements Runnable {
        private final WeakReference<AppPowerActivity> ref;
        LoadTask(AppPowerActivity a) { ref = new WeakReference<>(a); }
        @Override
        public void run() {
            AppPowerActivity a = ref.get();
            if (a == null) return;
            AppPowerReader.Result r;
            try {
                r = AppPowerReader.read(a);
            } catch (Throwable t) {
                // 后台线程未捕获异常会直接杀死整个进程（历史 bug：三星安全文件夹 uid 触发
                // SecurityException 导致点击即闪退），这里统一兜底为错误提示。
                r = new AppPowerReader.Result();
                r.error = "读取失败：" + t.getClass().getSimpleName()
                        + (t.getMessage() != null ? " — " + t.getMessage() : "");
            }
            a.h.post(new ApplyTask(a, r));
        }
    }

    /** 命名静态任务：切回主线程刷新列表 */
    private static class ApplyTask implements Runnable {
        private final WeakReference<AppPowerActivity> ref;
        private final AppPowerReader.Result r;
        ApplyTask(AppPowerActivity a, AppPowerReader.Result res) {
            ref = new WeakReference<>(a);
            r = res;
        }
        @Override
        public void run() {
            AppPowerActivity a = ref.get();
            if (a != null) a.apply(r);
        }
    }

    /** 命名静态适配器：避免 R8 对匿名 ArrayAdapter 子类的已知转换崩溃 */
    private static class PowerAdapter extends BaseAdapter {
        private final Context ctx;
        private final AppPowerReader.Result r;

        PowerAdapter(Context c, AppPowerReader.Result res) {
            ctx = c;
            r = res;
        }

        @Override
        public int getCount() { return r.items.size(); }

        @Override
        public Object getItem(int p) { return r.items.get(p); }

        @Override
        public long getItemId(int p) { return p; }

        @Override
        public View getView(int p, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(ctx).inflate(R.layout.app_power_item, parent, false);
            AppPowerReader.Item it = r.items.get(p);
            ((TextView) v.findViewById(R.id.tvLabel)).setText(
                    (p + 1) + ". " + it.label + "\nuid " + it.uid
                            + (it.pkg != null ? " · " + it.pkg : ""));
            ((TextView) v.findViewById(R.id.tvMah)).setText(AppPowerReader.fmt(it.mah) + " mAh");
            double pct = r.appsSubtotalMah > 0 ? it.mah / r.appsSubtotalMah * 100.0 : 0;
            ((TextView) v.findViewById(R.id.tvPct)).setText(String.format("%.1f%%", pct));
            return v;
        }
    }
}
