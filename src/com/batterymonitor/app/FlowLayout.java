package com.batterymonitor.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * 极简流式布局：子 View 按自身实际宽度依次排列，放不下自动换行。
 * 用于让指标卡片「按实际内容框选」，且行/列之间只保留很小的间距。
 */
public class FlowLayout extends ViewGroup {

    private int hSpace;
    private int vSpace;

    public FlowLayout(Context c) {
        this(c, null);
    }

    public FlowLayout(Context c, AttributeSet a) {
        super(c, a);
        hSpace = dp(5);
        vSpace = dp(5);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int total = MeasureSpec.getSize(widthMeasureSpec);
        int inner = Math.max(0, total - getPaddingLeft() - getPaddingRight());
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChild(child,
                    MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST),
                    heightMeasureSpec);
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            if (x > getPaddingLeft() && x + cw > getPaddingLeft() + inner) {
                x = getPaddingLeft();
                y += rowH + vSpace;
                rowH = 0;
            }
            x += cw + hSpace;
            if (ch > rowH) rowH = ch;
        }
        setMeasuredDimension(resolveSize(total, widthMeasureSpec),
                resolveSize(y + rowH + getPaddingBottom(), heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int inner = getWidth() - getPaddingLeft() - getPaddingRight();
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            if (x > getPaddingLeft() && x + cw > getPaddingLeft() + inner) {
                x = getPaddingLeft();
                y += rowH + vSpace;
                rowH = 0;
            }
            child.layout(x, y, x + cw, y + ch);
            x += cw + hSpace;
            if (ch > rowH) rowH = ch;
        }
    }
}
