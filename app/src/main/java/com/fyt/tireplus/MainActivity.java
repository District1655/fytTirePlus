package com.fyt.tireplus;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 模块入口：版本信息 + 检查更新 + 下载安装。
 * 实际功能（胎压增强）通过 Xposed 注入 com.syu.canbus 进程实现。
 */
public class MainActivity extends Activity {

    private TextView mStatusView;
    private TextView mInfoView;
    private Button mCheckBtn;
    private Button mDownloadBtn;
    private UpdateManager.ReleaseInfo mLatest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        sv.addView(root);
        setContentView(sv);

        // 标题
        TextView title = new TextView(this);
        title.setText("fytTirePlus 胎压增强");
        title.setTextSize(20f);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8), 0, dp(16));
        root.addView(title);

        // 版本信息
        mInfoView = new TextView(this);
        mInfoView.setTextSize(13f);
        mInfoView.setLineSpacing(dp(2), 1f);
        mInfoView.setText(buildInfoText());
        root.addView(mInfoView);

        // 分隔
        ViewUtil.addSpacer(root, dp(8));

        // 状态
        mStatusView = new TextView(this);
        mStatusView.setTextSize(13f);
        mStatusView.setLineSpacing(dp(2), 1f);
        mStatusView.setPadding(0, dp(8), 0, dp(8));
        mStatusView.setText("状态：尚未检查更新");
        root.addView(mStatusView);

        // 检查更新
        mCheckBtn = new Button(this);
        mCheckBtn.setText("检查更新");
        mCheckBtn.setOnClickListener(v -> checkUpdate());
        root.addView(mCheckBtn, new LinearLayout.LayoutParams(-1, -2));

        // 下载
        mDownloadBtn = new Button(this);
        mDownloadBtn.setText("下载并安装");
        mDownloadBtn.setEnabled(false);
        mDownloadBtn.setOnClickListener(v -> startDownload());
        root.addView(mDownloadBtn, new LinearLayout.LayoutParams(-1, -2));

        // 说明
        ViewUtil.addSpacer(root, dp(16));
        TextView help = new TextView(this);
        help.setTextSize(11f);
        help.setText("使用说明：\n"
                + "1. 安装后到 LSPosed 勾选作用域 com.syu.canbus\n"
                + "2. 强停「车辆信息」或重启车机生效\n"
                + "3. 打开车辆信息→胎压页面，点右上「增强」按钮\n"
                + "4. 用面板「侦察」页找温度码后填入配置\n\n"
                + "GitHub: https://github.com/" + UpdateManager.REPO);
        root.addView(help);
    }

    private String buildInfoText() {
        return "版本：v" + BuildConfig.VERSION_NAME + " (code " + BuildConfig.VERSION_CODE + ")\n"
                + "作用域：com.syu.canbus\n"
                + "车型：朗逸 Plus MQB（RZC canbusId=786592 / WC2 canbusId=1900561）\n"
                + "功能：胎压温度、标准值告警、轮胎换位、全码侦察、CMD 调试";
    }

    // ------------------------------------------------------------------
    // 更新逻辑
    // ------------------------------------------------------------------

    private void checkUpdate() {
        mStatusView.setText("状态：正在检查更新…");
        mDownloadBtn.setEnabled(false);
        new Thread(() -> {
            final UpdateManager.ReleaseInfo info = UpdateManager.checkLatest();
            runOnUiThread(() -> {
                mLatest = info;
                if (info == null) {
                    mStatusView.setText("状态：检查失败（网络不通或仓库无 Release）\n"
                            + "请确认车机可访问 api.github.com");
                    return;
                }
                String head = "状态：最新版本 v" + info.tagName
                        + "（code " + info.versionCode + "）\n";
                String body = (info.body == null || info.body.isEmpty())
                        ? "" : "更新内容：\n" + info.body + "\n";
                if (UpdateManager.isNewer(info, BuildConfig.VERSION_CODE)) {
                    mStatusView.setText(head + body + "→ 发现新版本，可下载安装");
                    mDownloadBtn.setEnabled(true);
                } else {
                    mStatusView.setText(head + body + "→ 当前已是最新版本");
                    mDownloadBtn.setEnabled(false);
                }
            });
        }).start();
    }

    private void startDownload() {
        if (mLatest == null || TextUtils.isEmpty(mLatest.downloadUrl)) {
            Toast.makeText(this, "请先检查更新", Toast.LENGTH_SHORT).show();
            return;
        }
        mStatusView.setText("状态：正在下载 " + mLatest.tagName + " …");
        long id = UpdateManager.download(this, mLatest.downloadUrl);
        if (id >= 0) {
            UpdateManager.registerDownloadReceiver(this, id);
        } else {
            mStatusView.setText("状态：下载启动失败");
        }
    }

    private int dp(int px) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (px * density + 0.5f);
    }
}
