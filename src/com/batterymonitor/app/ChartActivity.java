package com.batterymonitor.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import java.util.List;

/**
 * 曲线专页：电量(%) 与 实时容量(mAh) 两个图表。
 * 支持单指拖动平移、双指捏合缩放、双击复位，以及顶部快捷时间范围按钮；
 * 两图共用同一时间窗口（拖动其一另一图跟随）。
 */
public class ChartActivity extends Activity implements View.OnClickListener {
    private BatteryChart chart, chartCap;
    private BatteryDbHelper db;

    /** 视口联动器：把 A 图的视口应用到 B 图（命名类，避免匿名类触发 R8 内部错误） */
    private static class ViewportBinder implements BatteryChart.ViewportListener {
        private final BatteryChart other;
        private boolean busy;

        ViewportBinder(BatteryChart other) { this.other = other; }

        @Override
        public void onViewportChanged(float scale, float offsetSec) {
            if (busy) return;
            busy = true;
            other.setViewport(scale, offsetSec);
            busy = false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_charts);
        chart = findViewById(R.id.chart);
        chartCap = findViewById(R.id.chartCap);
        chart.setViewportListener(new ViewportBinder(chartCap));
        chartCap.setViewportListener(new ViewportBinder(chart));

        int[] ids = {R.id.btnRefreshCharts, R.id.btnRangeAll, R.id.btnRange24h,
                R.id.btnRange6h, R.id.btnRange1h, R.id.btnZoomIn, R.id.btnZoomOut};
        for (int id : ids) {
            Button b = findViewById(id);
            if (b != null) b.setOnClickListener(this);
        }
        db = new BatteryDbHelper(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (db != null) db.close();
        super.onDestroy();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btnRefreshCharts) {
            refresh();
        } else if (id == R.id.btnRangeAll) {
            chart.showRange(0);                 // 全部数据（视口变化会同步到另一图）
        } else if (id == R.id.btnRange24h) {
            chart.showRange(24 * 3600f);
        } else if (id == R.id.btnRange6h) {
            chart.showRange(6 * 3600f);
        } else if (id == R.id.btnRange1h) {
            chart.showRange(3600f);
        } else if (id == R.id.btnZoomIn) {
            chart.zoomBy(1.6f);                 // 拉宽时间线
        } else if (id == R.id.btnZoomOut) {
            chart.zoomBy(1f / 1.6f);
        }
    }

    private void refresh() {
        List<Cycles.Sample> list = Cycles.readSamples(db, 600);

        int n = list.size();
        long[] tsArr = new long[n];
        float[] lvlArr = new float[n];
        float[] capArr = new float[n];
        // 参考容量：手填设计容量 > 最近读取满电容量
        long lastCf = 0, refUah = 0;
        for (int i = n - 1; i >= 0; i--) {
            Cycles.Sample s = list.get(i);
            if (lastCf == 0 && s.cf > 0) lastCf = s.cf;
            if (refUah == 0 && s.cfd > 0) refUah = s.cfd;
        }
        long manual = Settings.getDesignUah(this);
        if (manual > 0) refUah = manual;
        float refMah = refUah > 0 ? refUah / 1000f : (lastCf > 0 ? lastCf / 1000f : 0);

        for (int i = 0; i < n; i++) {
            Cycles.Sample s = list.get(i);
            tsArr[i] = s.ts;
            lvlArr[i] = (float) s.level;
            // 库仑计读数不可信的机（瑞芯微等），曲线不画，避免一条贴底的假直线
            capArr[i] = (s.cc > 0 && refMah > 0 && BatteryData.ccLooksValid(s.cc, s.level, refMah))
                    ? s.cc / 1000f : Float.NaN;
        }
        chart.setData(tsArr, lvlArr, null);
        chartCap.setData(tsArr, capArr, refMah > 0 ? refMah : null);
    }
}
