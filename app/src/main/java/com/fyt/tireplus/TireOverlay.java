package com.fyt.tireplus;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 在原胎压 Activity 上叠加一层：四轮温度 + 标准值 + 增强按钮。
 * 不依赖宿主布局 ID，完全以绝对坐标浮在 decorView 上。
 */
public class TireOverlay implements TireDataCenter.TireListener {

    private static final int TAG_ID = 0x7F000000; // fytTirePlus overlay marker

    private Activity activity;
    private FrameLayout root;
    private TextView tvTitle;
    private TextView[] tvTemps;
    private TextView tvStd;
    private boolean attached;

    public void attach(Activity act) {
        if (attached) return;
        this.activity = act;
        // 优先挂到 android.R.id.content：起点在系统状态栏下方，
        // 避免 decorView 全屏坐标导致顶部按钮被车机系统栏遮住
        ViewGroup vg = act.findViewById(android.R.id.content);
        if (vg == null) {
            View decor = act.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            vg = (ViewGroup) decor;
        }
        // 兼容重启后重复 attach
        View old = vg.findViewById(TAG_ID);
        if (old != null) vg.removeView(old);
        build(vg);
        TireDataCenter.addListener(this);
        attached = true;
        TireDataCenter.log("TireOverlay attached: " + act.getClass().getName()
                + " parent=" + vg.getClass().getSimpleName());
    }

    private void build(ViewGroup parent) {
        root = new FrameLayout(activity);
        root.setId(TAG_ID);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部标题 + 增强按钮
        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(4), dp(12), dp(4));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        topLp.gravity = Gravity.TOP;
        top.setLayoutParams(topLp);

        tvTitle = new TextView(activity);
        tvTitle.setText("fytTirePlus 加载中...");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(14f);
        tvTitle.setShadowLayer(2, 0, 1, Color.BLACK);
        top.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView btnEnhance = new TextView(activity);
        btnEnhance.setText("增强");
        btnEnhance.setTextColor(Color.WHITE);
        btnEnhance.setTextSize(13f);
        btnEnhance.setGravity(Gravity.CENTER);
        btnEnhance.setPadding(dp(12), dp(4), dp(12), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(180, 30, 120, 180));
        bg.setCornerRadius(dp(6));
        btnEnhance.setBackground(bg);
        btnEnhance.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PanelWindow.show(activity);
            }
        });
        top.addView(btnEnhance, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        root.addView(top);

        // 四轮温度条（置于底部）
        LinearLayout bottom = new LinearLayout(activity);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(12), dp(4), dp(12), dp(4));
        FrameLayout.LayoutParams botLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        botLp.gravity = Gravity.BOTTOM;
        botLp.bottomMargin = dp(60); // 避开车载底栏
        bottom.setLayoutParams(botLp);

        tvTemps = new TextView[4];
        String[] labels = {"FL", "FR", "RL", "RR"};
        for (int i = 0; i < 4; i++) {
            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            col.setPadding(dp(8), 0, dp(8), 0);

            TextView label = new TextView(activity);
            label.setText(labels[i]);
            label.setTextColor(Color.argb(180, 255, 255, 255));
            label.setTextSize(11f);
            col.addView(label);

            tvTemps[i] = new TextView(activity);
            tvTemps[i].setText("--");
            tvTemps[i].setTextColor(Color.CYAN);
            tvTemps[i].setTextSize(13f);
            tvTemps[i].setShadowLayer(2, 0, 1, Color.BLACK);
            col.addView(tvTemps[i]);

            bottom.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        root.addView(bottom);

        // 标准值浮层（居中偏下）
        tvStd = new TextView(activity);
        tvStd.setText("");
        tvStd.setTextColor(Color.argb(180, 200, 200, 200));
        tvStd.setTextSize(11f);
        tvStd.setShadowLayer(2, 0, 1, Color.BLACK);
        FrameLayout.LayoutParams stdLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stdLp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        stdLp.bottomMargin = dp(32);
        tvStd.setLayoutParams(stdLp);
        root.addView(tvStd);

        // 全透明背景（让宿主 UI 透过）
        root.setBackgroundColor(Color.TRANSPARENT);
        parent.addView(root);
        onUpdate();
    }

    @Override
    public void onUpdate() {
        if (activity == null || tvTemps == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (tvTitle != null) {
                    tvTitle.setText("fytTirePlus | " + TireDataCenter.pressureText(0));
                }
                for (int i = 0; i < 4; i++) {
                    if (tvTemps[i] != null) {
                        tvTemps[i].setText(TireDataCenter.tempText(i));
                    }
                }
                StringBuilder sb = new StringBuilder("标准: ");
                for (int i = 0; i < 4; i++) {
                    double s = ModuleConfig.stdKpa[i];
                    sb.append(s <= 0 ? "--" : ((int) s) + "kPa");
                    if (i < 3) sb.append(" / ");
                }
                if (tvStd != null) tvStd.setText(sb.toString());
            }
        });
    }

    @Override
    public boolean isDead() {
        return activity == null || activity.isFinishing();
    }

    private int dp(int px) {
        float density = activity.getResources().getDisplayMetrics().density;
        return (int) (px * density + 0.5f);
    }

    static void detach(Activity act) {
        ViewGroup vg = act.findViewById(android.R.id.content);
        if (vg == null) {
            View decor = act.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            vg = (ViewGroup) decor;
        }
        View old = vg.findViewById(TAG_ID);
        if (old != null) vg.removeView(old);
    }
}
