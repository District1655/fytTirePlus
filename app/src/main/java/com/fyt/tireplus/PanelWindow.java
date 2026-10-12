package com.fyt.tireplus;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 悬浮增强面板：胎压总览 / 标准值设置 / 轮胎换位 / 侦察 / CMD 调试。
 * TYPE_APPLICATION_OVERLAY，运行在 com.syu.canbus 进程（宿主持有 SYSTEM_ALERT_WINDOW）。
 */
public class PanelWindow {

    private static final String[] WHEELS = {"左前", "右前", "左后", "右后"};
    private static final int[] BTN_SWAP_MAPS = {
            0x2301, // 前后对调: {2,3,0,1}
            0x3210, // 交叉对调: {3,2,1,0}
            0x1032, // 左右对调: {1,0,3,2}
    };

    private static View windowView;
    private static WindowManager wm;
    private static FrameLayout[] pages;
    private static TextView[] tabBtns;
    private static TextView[][] tireCards;
    private static TextView reconStatus;
    private static TextView reconRecent;
    private static TextView cmdResult;

    static synchronized void show(Activity act) {
        if (windowView != null) {
            dismiss();
            return;
        }
        try {
            Context ctx = act.getApplicationContext();
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            android.graphics.Point size = new android.graphics.Point();
            d.getSize(size);

            windowView = build(act);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    (int) (size.x * 0.62), (int) (size.y * 0.82),
                    android.os.Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            wm.addView(windowView, lp);
            refreshTireCards();
        } catch (Throwable t) {
            TireDataCenter.log("面板打开失败: " + t);
            Toast.makeText(act, "fytTirePlus 面板打开失败", Toast.LENGTH_SHORT).show();
            windowView = null;
        }
    }

    static synchronized void dismiss() {
        if (windowView != null && wm != null) {
            try {
                wm.removeView(windowView);
            } catch (Throwable ignored) {
            }
        }
        windowView = null;
    }

    // ---------- UI 构建 ----------

    private static View build(final Activity act) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2141517);
        bg.setCornerRadius(dp(act, 10));
        root.setBackground(bg);
        root.setPadding(dp(act, 12), dp(act, 8), dp(act, 12), dp(act, 12));

        // 标题栏
        LinearLayout titleBar = new LinearLayout(act);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(act);
        title.setText("fytTirePlus 胎压增强  v" + BuildConfig.VERSION_NAME);
        title.setTextColor(Color.WHITE);
        title.setTextSize(15f);
        titleBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = smallBtn(act, "关闭");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        titleBar.addView(close);
        root.addView(titleBar, new LinearLayout.LayoutParams(-1, dp(act, 36)));

        // 标签行
        String[] tabs = {"胎压", "标准值", "换位", "侦察", "CMD"};
        LinearLayout tabRow = new LinearLayout(act);
        tabRow.setGravity(Gravity.CENTER);
        pages = new FrameLayout[tabs.length];
        tabBtns = new TextView[tabs.length];
        final FrameLayout content = new FrameLayout(act);
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            TextView tb = new TextView(act);
            tb.setText(tabs[i]);
            tb.setTextSize(13f);
            tb.setGravity(Gravity.CENTER);
            tb.setPadding(0, dp(act, 6), 0, dp(act, 6));
            tb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab(idx);
                }
            });
            tabBtns[i] = tb;
            tabRow.addView(tb, new LinearLayout.LayoutParams(0, -2, 1));

            FrameLayout page = new FrameLayout(act);
            page.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            pages[i] = page;
            content.addView(page, new FrameLayout.LayoutParams(-1, -1));
        }
        root.addView(tabRow, new LinearLayout.LayoutParams(-1, -2));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        buildTirePage(act, pages[0]);
        buildStdPage(act, pages[1]);
        buildSwapPage(act, pages[2]);
        buildReconPage(act, pages[3]);
        buildCmdPage(act, pages[4]);
        switchTab(0);
        return root;
    }

    private static void switchTab(int idx) {
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabBtns[i].setTextColor(i == idx ? 0xFF4CAF50 : 0xFFAAAAAA);
        }
        if (idx == 0) refreshTireCards();
        if (idx == 3) refreshRecon();
    }

    // 胎压页
    private static void buildTirePage(Activity act, FrameLayout page) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        tireCards = new TextView[4][1];
        LinearLayout rowTop = new LinearLayout(act);
        LinearLayout rowBottom = new LinearLayout(act);
        for (int i = 0; i < 4; i++) {
            TextView card = new TextView(act);
            card.setGravity(Gravity.CENTER);
            card.setTextSize(16f);
            card.setPadding(dp(act, 8), dp(act, 16), dp(act, 8), dp(act, 16));
            tireCards[i][0] = card;
            (i < 2 ? rowTop : rowBottom).addView(card, new LinearLayout.LayoutParams(0, -2, 1));
        }
        wrap.addView(rowTop, new LinearLayout.LayoutParams(-1, -2));
        wrap.addView(rowBottom, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = new TextView(act);
        hint.setText("本车 RZC 协议(cAN 184~187=胎压 0.1bar)不含温度数据，"
                + "温度列恒为 -- 属正常；标准值仅本地告警用");
        hint.setTextColor(0xFF888888);
        hint.setTextSize(11f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(act, 8), 0, 0);
        wrap.addView(hint);
        page.addView(wrap, new FrameLayout.LayoutParams(-1, -1));
    }

    private static void refreshTireCards() {
        if (tireCards == null) return;
        for (int i = 0; i < 4; i++) {
            TextView c = tireCards[i][0];
            if (c == null) continue;
            String text = WHEELS[i] + "\n" + TireDataCenter.pressureText(i)
                    + "\n" + TireDataCenter.tempText(i)
                    + "\n标准 " + ((int) ModuleConfig.stdKpa[i]) + "kPa";
            c.setText(text);
            c.setTextColor(TireDataCenter.isWarn(i) ? Color.RED : Color.WHITE);
        }
    }

    // 标准值设置页（含温度码配置）
    private static void buildStdPage(Activity act, FrameLayout page) {
        ScrollView sv = new ScrollView(act);
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(act, 8), 0, 0);

        wrap.addView(sectionTitle(act, "标准胎压（kPa，本地告警基准 + 可选下行）"));
        final EditText[] stdEt = new EditText[4];
        for (int i = 0; i < 4; i++) {
            final int pos = i;
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(act);
            label.setText(WHEELS[i]);
            label.setTextColor(Color.WHITE);
            label.setTextSize(13f);
            row.addView(label, new LinearLayout.LayoutParams(dp(act, 44), -2));

            Button minus = smallBtn(act, "-");
            minus.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    adjStd(pos, -5);
                    if (ModuleConfig.setStdCmdCode >= 0) {
                        TireDataCenter.sendCmd(ModuleConfig.setStdCmdCode, new int[]{pos, 0});
                    }
                }
            });
            row.addView(minus);

            stdEt[i] = new EditText(act);
            stdEt[i].setText(String.valueOf((int) ModuleConfig.stdKpa[i]));
            styleEditText(stdEt[i]);
            stdEt[i].setInputType(InputType.TYPE_CLASS_NUMBER);
            row.addView(stdEt[i], new LinearLayout.LayoutParams(0, -2, 1));

            Button plus = smallBtn(act, "+");
            plus.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    adjStd(pos, 5);
                    if (ModuleConfig.setStdCmdCode >= 0) {
                        TireDataCenter.sendCmd(ModuleConfig.setStdCmdCode, new int[]{pos, 1});
                    }
                }
            });
            row.addView(plus);
            wrap.addView(row, new LinearLayout.LayoutParams(-1, dp(act, 40)));
        }

        wrap.addView(sectionTitle(act, "温度码（-1=无；侦察确认后填写）"));
        final EditText[] tempEt = new EditText[4];
        for (int i = 0; i < 4; i++) {
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(act);
            label.setText(WHEELS[i] + " 温度码");
            label.setTextColor(Color.WHITE);
            label.setTextSize(13f);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            tempEt[i] = new EditText(act);
            tempEt[i].setText(String.valueOf(ModuleConfig.tempCodes[i]));
            styleEditText(tempEt[i]);
            tempEt[i].setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
            row.addView(tempEt[i], new LinearLayout.LayoutParams(dp(act, 90), -2));
            wrap.addView(row, new LinearLayout.LayoutParams(-1, dp(act, 38)));
        }

        LinearLayout row2 = new LinearLayout(act);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        TextView offLabel = new TextView(act);
        offLabel.setText("温度偏移(默认40)");
        offLabel.setTextColor(Color.WHITE);
        offLabel.setTextSize(13f);
        row2.addView(offLabel, new LinearLayout.LayoutParams(0, -2, 1));
        final EditText offEt = new EditText(act);
        offEt.setText(String.valueOf(ModuleConfig.tempOffset));
        styleEditText(offEt);
        offEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        row2.addView(offEt, new LinearLayout.LayoutParams(dp(act, 90), -2));
        wrap.addView(row2, new LinearLayout.LayoutParams(-1, dp(act, 38)));

        // 全轮快捷应用 + RZC 协议说明
        LinearLayout rowAll = new LinearLayout(act);
        rowAll.setGravity(Gravity.CENTER_VERTICAL);
        Button applyAll = smallBtn(act, "把左前值应用到全部四轮");
        applyAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                double v0 = parseDouble(stdEt[0].getText().toString(), ModuleConfig.stdKpa[0]);
                for (int i = 0; i < 4; i++) {
                    ModuleConfig.stdKpa[i] = v0;
                    stdEt[i].setText(String.valueOf((int) v0));
                }
                ModuleConfig.save();
                TireDataCenter.requestUiRefresh();
                refreshTireCards();
                Toast.makeText(v.getContext(), "四轮标准值已设为 " + ((int) v0) + "kPa", Toast.LENGTH_SHORT).show();
            }
        });
        rowAll.addView(applyAll, new LinearLayout.LayoutParams(0, dp(act, 38), 1));
        wrap.addView(rowAll);
        TextView note = new TextView(act);
        note.setText("说明: 本车 RZC 协议无下行设置命令，标准值仅用于"
                + "本模块显示与偏离告警，不会改变原车仪表。");
        note.setTextColor(0xFF888888);
        note.setTextSize(11f);
        note.setPadding(0, dp(act, 4), 0, 0);
        wrap.addView(note);

        Button save = smallBtn(act, "保存配置");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (int i = 0; i < 4; i++) {
                    ModuleConfig.stdKpa[i] = parseDouble(stdEt[i].getText().toString(), ModuleConfig.stdKpa[i]);
                    ModuleConfig.tempCodes[i] = parseInt(tempEt[i].getText().toString(), -1);
                    if (ModuleConfig.tempCodes[i] >= 0) {
                        TireDataCenter.subscribe(ModuleConfig.tempCodes[i]);
                    }
                }
                ModuleConfig.tempOffset = parseInt(offEt.getText().toString(), 40);
                ModuleConfig.save();
                TireDataCenter.requestUiRefresh();
                refreshTireCards();
                Toast.makeText(v.getContext(), "已保存", Toast.LENGTH_SHORT).show();
            }
        });
        wrap.addView(save, new LinearLayout.LayoutParams(-1, dp(act, 40)));
        sv.addView(wrap);
        page.addView(sv, new FrameLayout.LayoutParams(-1, -1));
    }

    private static void adjStd(int pos, int delta) {
        ModuleConfig.stdKpa[pos] = Math.max(100, ModuleConfig.stdKpa[pos] + delta);
        ModuleConfig.save();
    }

    // 换位页
    private static void buildSwapPage(final Activity act, FrameLayout page) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setPadding(0, dp(act, 12), 0, 0);

        wrap.addView(sectionTitle(act, "换位后保持显示与真实轮位一致（显示层映射）"));
        String[] names = {"前后对调", "交叉对调", "左右对调", "还原"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            Button b = smallBtn(act, names[i]);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (idx == 3) {
                        ModuleConfig.swapMap = new int[]{0, 1, 2, 3};
                    } else {
                        int packed = BTN_SWAP_MAPS[idx];
                        ModuleConfig.swapMap = new int[]{
                                (packed >> 12) & 0xF, (packed >> 8) & 0xF,
                                (packed >> 4) & 0xF, packed & 0xF};
                    }
                    ModuleConfig.save();
                    if (ModuleConfig.swapCmdEnabled) {
                        TireDataCenter.sendCmd(ModuleConfig.swapCmdCode, ModuleConfig.swapCmdInts);
                    }
                    refreshTireCards();
                    Toast.makeText(act, "换位映射: " + TireDataCenter.join(ModuleConfig.swapMap),
                            Toast.LENGTH_SHORT).show();
                }
            });
            wrap.addView(b, new LinearLayout.LayoutParams(-1, dp(act, 42)));
        }
        TextView cur = new TextView(act);
        cur.setText("当前映射: " + TireDataCenter.join(ModuleConfig.swapMap)
                + "\n下行换位命令: " + (ModuleConfig.swapCmdEnabled ? "已启用" : "未启用(仅UI映射)"));
        cur.setTextColor(0xFF888888);
        cur.setTextSize(11f);
        cur.setGravity(Gravity.CENTER);
        cur.setPadding(0, dp(act, 10), 0, 0);
        wrap.addView(cur);
        page.addView(wrap, new FrameLayout.LayoutParams(-1, -1));
    }

    // 侦察页
    private static void buildReconPage(Activity act, FrameLayout page) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(act, 8), 0, 0);

        LinearLayout btns = new LinearLayout(act);
        Button toggle = smallBtn(act, "开始/停止侦察");
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ReconLogger.isRunning()) {
                    ReconLogger.stop();
                } else {
                    ReconLogger.start();
                }
                refreshRecon();
            }
        });
        btns.addView(toggle, new LinearLayout.LayoutParams(0, dp(act, 40), 1));
        Button snap = smallBtn(act, "导出 DATA 快照");
        snap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ReconLogger.isRunning()) {
                    ReconLogger.snapshot();
                    Toast.makeText(v.getContext(), "快照已写入", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(v.getContext(), "先开始侦察", Toast.LENGTH_SHORT).show();
                }
            }
        });
        btns.addView(snap, new LinearLayout.LayoutParams(0, dp(act, 40), 1));
        wrap.addView(btns);

        reconStatus = new TextView(act);
        reconStatus.setTextColor(0xFFAAAAAA);
        reconStatus.setTextSize(11f);
        wrap.addView(reconStatus);

        ScrollView sv = new ScrollView(act);
        reconRecent = new TextView(act);
        reconRecent.setTextColor(0xFF9CCC65);
        reconRecent.setTextSize(11f);
        reconRecent.setTypeface(android.graphics.Typeface.MONOSPACE);
        sv.addView(reconRecent);
        wrap.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(wrap, new FrameLayout.LayoutParams(-1, -1));
        refreshRecon();
    }

    private static void refreshRecon() {
        if (reconStatus != null) {
            reconStatus.setText("状态: " + (ReconLogger.isRunning() ? "记录中" : "停止")
                    + "\n日志: " + ReconLogger.logPath());
        }
        if (reconRecent != null) {
            reconRecent.setText(ReconLogger.recentText());
        }
    }

    // CMD 调试页
    private static void buildCmdPage(Activity act, FrameLayout page) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(act, 8), 0, 0);

        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final EditText codeEt = new EditText(act);
        codeEt.setHint("cmdCode");
        styleEditText(codeEt);
        codeEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        row.addView(codeEt, new LinearLayout.LayoutParams(dp(act, 90), -2));
        final EditText intsEt = new EditText(act);
        intsEt.setHint("参数 逗号分隔 如 4 或 255,2");
        styleEditText(intsEt);
        row.addView(intsEt, new LinearLayout.LayoutParams(0, -2, 1));
        Button send = smallBtn(act, "发送");
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int code = parseInt(codeEt.getText().toString(), -1);
                if (code < 0) return;
                int[] ints = parseInts(intsEt.getText().toString());
                TireDataCenter.sendCmd(code, ints);
                if (cmdResult != null) {
                    cmdResult.setText("已发: cmd(" + code + ", " + TireDataCenter.join(ints)
                            + ")\n随后5秒内码变化将在侦察页标注");
                }
            }
        });
        row.addView(send);
        wrap.addView(row, new LinearLayout.LayoutParams(-1, dp(act, 44)));

        // 预设
        ScrollView presetSv = new ScrollView(act);
        LinearLayout presetBox = new LinearLayout(act);
        presetBox.setOrientation(LinearLayout.VERTICAL);
        try {
            JSONArray presets = ModuleConfig.cmdPresets;
            if (presets != null) {
                for (int i = 0; i < presets.length(); i++) {
                    final JSONObject p = presets.getJSONObject(i);
                    final String name = p.optString("name", "预设");
                    final int code = p.getInt("code");
                    JSONArray ia = p.getJSONArray("ints");
                    final int[] ints = new int[ia.length()];
                    for (int j = 0; j < ints.length; j++) ints[j] = ia.getInt(j);
                    Button b = smallBtn(act, name + "  → cmd(" + code + "," + TireDataCenter.join(ints) + ")");
                    b.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            TireDataCenter.sendCmd(code, ints);
                            if (cmdResult != null) {
                                cmdResult.setText("已发预设「" + name + "」cmd(" + code + ", "
                                        + TireDataCenter.join(ints) + ")");
                            }
                        }
                    });
                    presetBox.addView(b, new LinearLayout.LayoutParams(-1, dp(act, 38)));
                }
            }
        } catch (Throwable t) {
            TireDataCenter.log("预设加载失败: " + t);
        }
        presetSv.addView(presetBox);
        wrap.addView(presetSv, new LinearLayout.LayoutParams(-1, 0, 1));

        cmdResult = new TextView(act);
        cmdResult.setTextColor(0xFFAAAAAA);
        cmdResult.setTextSize(11f);
        wrap.addView(cmdResult);
        page.addView(wrap, new FrameLayout.LayoutParams(-1, -1));
    }

    // ---------- 小部件工具 ----------

    private static TextView sectionTitle(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFF4CAF50);
        tv.setTextSize(12f);
        tv.setPadding(0, dp(ctx, 6), 0, dp(ctx, 4));
        return tv;
    }

    /** 统一输入框样式：深色圆角底 + 白字（修复宿主浅色主题下白字白底看不清） */
    private static void styleEditText(EditText et) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF23272C);
        bg.setCornerRadius(dp(et.getContext(), 4));
        bg.setStroke(dp(et.getContext(), 1), 0xFF4A5158);
        et.setBackground(bg);
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(0xFF777777);
        et.setTextSize(14f);
        et.setGravity(Gravity.CENTER);
        et.setPadding(dp(et.getContext(), 6), 0, dp(et.getContext(), 6), 0);
    }

    private static Button smallBtn(Context ctx, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(12f);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0);
        // 统一深色圆角底，避免宿主主题默认按钮底色与白字冲突
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF32373D);
        bg.setCornerRadius(dp(ctx, 4));
        b.setBackground(bg);
        return b;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static int[] parseInts(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        String[] parts = s.split(",");
        int[] r = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            r[i] = parseInt(parts[i], 0);
        }
        return r;
    }

    private static int dp(Context ctx, int px) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return (int) (px * density + 0.5f);
    }
}
