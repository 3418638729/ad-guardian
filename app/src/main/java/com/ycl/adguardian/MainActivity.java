package com.ycl.adguardian;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 去广告管家主界面。
 *
 * 三个按钮对应三种意图：
 *   开启 —— 按当前检测结果，把没开的那一段拉起来
 *   关闭 —— 分别跳到 GKD 和 AdAway，让你亲手关掉
 *   修复 —— 重新体检一遍，只处理出问题的那一段
 *
 * 普通 App 没有权限直接改系统的无障碍开关，也不能替别的 App 启停 VPN，
 * 所以这里的"修复"是精准跳转：把你送到该点的那一页，而不是假装已经修好。
 */
public class MainActivity extends Activity {

    private static final int COLOR_OK = 0xFF2E7D32;
    private static final int COLOR_BAD = 0xFFC62828;
    private static final int COLOR_TEXT = 0xFF333333;
    private static final int COLOR_HINT = 0xFF666666;

    private TextView summary;
    private TextView checklist;
    private Button enableBtn;
    private Button disableBtn;
    private Button repairBtn;

    private Checks.Report report;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(48), dp(28), dp(48));

        TextView title = new TextView(this);
        title.setText("去广告管家");
        title.setTextSize(26);
        title.setTextColor(COLOR_TEXT);
        title.setGravity(Gravity.CENTER);

        TextView subtitle = new TextView(this);
        subtitle.setText("一键检查 GKD 与 AdAway 是否都在工作");
        subtitle.setTextSize(13);
        subtitle.setTextColor(COLOR_HINT);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, dp(28));

        summary = new TextView(this);
        summary.setTextSize(20);
        summary.setGravity(Gravity.CENTER);
        summary.setPadding(0, 0, 0, dp(16));

        checklist = new TextView(this);
        checklist.setTextSize(14);
        checklist.setTextColor(COLOR_TEXT);
        checklist.setLineSpacing(dp(4), 1f);
        checklist.setPadding(dp(4), 0, dp(4), dp(24));

        enableBtn = new Button(this);
        enableBtn.setText("开启");
        enableBtn.setOnClickListener(v -> doEnable());

        disableBtn = new Button(this);
        disableBtn.setText("关闭");
        disableBtn.setOnClickListener(v -> doDisable());

        repairBtn = new Button(this);
        repairBtn.setText("修复");
        repairBtn.setOnClickListener(v -> doRepair());

        root.addView(title);
        root.addView(subtitle);
        root.addView(summary);
        root.addView(checklist);
        root.addView(enableBtn, lp());
        root.addView(disableBtn, lp());
        root.addView(repairBtn, lp());

        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置页/AdAway 返回后自动复检，状态立刻反映出来
        refresh();
    }

    // ---------- 检测 ----------

    private void refresh() {
        if (busy) return;
        busy = true;
        summary.setText("检测中…");
        summary.setTextColor(COLOR_HINT);

        new Thread(() -> {
            Checks.Report rep = Checks.run(this);
            runOnUiThread(() -> {
                report = rep;
                render(rep);
                busy = false;
            });
        }, "ad-check").start();
    }

    private void render(Checks.Report rep) {
        if (rep.allOk()) {
            summary.setText("✓ 一切正常");
            summary.setTextColor(COLOR_OK);
        } else {
            summary.setText("✗ 发现 " + rep.problemCount() + " 个问题");
            summary.setTextColor(COLOR_BAD);
        }

        StringBuilder sb = new StringBuilder();
        for (Checks.Item item : rep.items) {
            switch (item.state) {
                case OK:
                    sb.append("✓ ");
                    break;
                case PROBLEM:
                    sb.append("✗ ");
                    break;
                default:
                    sb.append("· ");
                    break;
            }
            sb.append(item.title).append('\n');
            if (!item.detail.isEmpty()) {
                sb.append("    ").append(item.detail).append('\n');
            }
        }
        checklist.setText(sb.toString().trim());
    }

    // ---------- 三个按钮 ----------

    /** 开启：用现有检测结果，把第一个没开的东西拉起来。 */
    private void doEnable() {
        if (report == null) {
            toast("正在检测，稍候再点一次");
            refresh();
            return;
        }
        Checks.Item problem = report.firstProblem();
        if (problem == null) {
            toast("已经全部开着，不用再开");
            return;
        }
        handle(problem);
    }

    /** 关闭：两段机制要分别关，交给用户选。 */
    private void doDisable() {
        new AlertDialog.Builder(this)
                .setTitle("关闭去广告")
                .setMessage("两段机制要分别关：\n\n"
                        + "GKD：无障碍 → 已下载的应用 → 关掉开关\n"
                        + "AdAway：打开后点停止")
                .setPositiveButton("关 GKD", (d, w) -> openA11y())
                .setNegativeButton("关 AdAway", (d, w) -> openAdAway())
                .setNeutralButton("取消", null)
                .show();
    }

    /** 修复：重新体检，只处理出问题的那一段。 */
    private void doRepair() {
        if (busy) {
            toast("正在检测，稍候");
            return;
        }
        busy = true;
        summary.setText("正在重新检测…");
        summary.setTextColor(COLOR_HINT);

        new Thread(() -> {
            Checks.Report rep = Checks.run(this);
            runOnUiThread(() -> {
                report = rep;
                render(rep);
                busy = false;

                Checks.Item problem = rep.firstProblem();
                if (problem == null) {
                    toast("检查完，没发现问题");
                } else {
                    handle(problem);
                }
            });
        }, "ad-repair").start();
    }

    // ---------- 处理动作 ----------

    private void handle(Checks.Item item) {
        switch (item.action) {
            case OPEN_A11Y:
                toast("点「已下载的应用」→ 找到 GKD → 打开开关");
                openA11y();
                break;

            case OPEN_ADAWAY:
                toast("打开 AdAway 后点「启动」");
                openAdAway();
                break;

            default:
                new AlertDialog.Builder(this)
                        .setTitle(item.title)
                        .setMessage(item.detail.isEmpty()
                                ? "这一项需要手动处理。"
                                : item.detail)
                        .setPositiveButton("知道了", null)
                        .show();
                break;
        }
    }

    private void openA11y() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            toast("打不开无障碍设置，请手动进：设置 → 无障碍");
        }
    }

    private void openAdAway() {
        Intent i = getPackageManager().getLaunchIntentForPackage(Checks.PKG_ADAWAY);
        if (i == null) {
            toast("没找到 AdAway，可能没装");
            return;
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("打不开 AdAway");
        }
    }

    // ---------- 小工具 ----------

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        p.bottomMargin = dp(8);
        return p;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
