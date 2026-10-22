package com.example.biliintl;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模块设置 / 自检界面。
 *
 * <p>这里不做配置项：模块是否生效由 LSPosed 的作用域决定，
 * 界面只负责把「环境是否就绪」讲清楚，并提供一键重新检测。
 */
public final class SettingsActivity extends Activity {

    private static final String LSPOSED_PKG = "org.lsposed.manager";

    private LinearLayout body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("跳转国际版b站");

        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        body.setPadding(pad, pad, pad, pad);
        scroll.addView(body);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        body.removeAllViews();

        TextView title = new TextView(this);
        title.setText("跳转国际版b站 v" + versionName());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(title);

        addText("把微信等应用发出的 B 站分享链接，强制交给国际版哔哩哔哩打开。", 0xFF666666, 14);
        addGap();

        addSection("环境");
        addText("国际版  " + BiliIntent.PKG_INTL + "：" + installedText(BiliIntent.PKG_INTL), 0xFF000000, 14);
        addText("国内版  " + BiliIntent.PKG_CN + "：" + installedText(BiliIntent.PKG_CN), 0xFF000000, 14);
        addText("LSPosed 管理器：" + installedText(LSPOSED_PKG), 0xFF000000, 14);
        addGap();

        addSection("系统当前的链接解析结果");
        probe("bilibili://video/BV1GJ411x7h7");
        probe("https://b23.tv/abc123");
        probe("https://www.bilibili.com/video/BV1GJ411x7h7");
        probe("https://m.bilibili.com/video/BV1GJ411x7h7");
        addText("以上是「未经过本模块」时系统的真实行为。若结果不是国际版，说明正是需要重定向的场景。", 0xFF666666, 13);
        addGap();

        addSection("使用步骤");
        addText("1. 在 LSPosed 中启用本模块；", 0xFF000000, 14);
        addText("2. 进入「作用域」，勾选微信（com.tencent.mm）；", 0xFF000000, 14);
        addText("3. 强制停止微信后重新打开（LSPosed 需要重新注入）；", 0xFF000000, 14);
        addText("4. 在微信里点开 B 站分享的视频，应直接进入国际版。", 0xFF000000, 14);
        addText("如果还从 QQ / TIM / 钉钉等点开，把对应应用一并勾进作用域即可。", 0xFF666666, 13);
        addGap();

        addSection("说明");
        addText("• 模块只改写「目标包名」，不改变链接内容，因此视频仍是原来那一期；", 0xFF444444, 13);
        addText("• 微信内网页仍会先正常显示，点页面上的「打开APP」时才会跳到国际版；", 0xFF444444, 13);
        addText("• 排查问题可在 LSPosed 管理器的日志里搜索 BiliIntlBridge。", 0xFF444444, 13);

        addGap();
        Button refresh = new Button(this);
        refresh.setText("重新检测");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                render();
            }
        });
        body.addView(refresh);

        Button openLsposed = new Button(this);
        openLsposed.setText("打开 LSPosed");
        openLsposed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent launch = getPackageManager().getLaunchIntentForPackage(LSPOSED_PKG);
                if (launch != null) {
                    try {
                        startActivity(launch);
                    } catch (Throwable ignored) {
                        // 忽略：部分 ROM 限制启动管理器
                    }
                }
            }
        });
        body.addView(openLsposed);
    }

    /** 用 PackageManager 真实查询某个链接会被谁接收。 */
    private void probe(String url) {
        String result;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            List<ResolveInfo> list = getPackageManager()
                    .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            Set<String> names = new LinkedHashSet<String>();
            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo != null) {
                        names.add(ri.activityInfo.packageName);
                    }
                }
            }
            if (names.isEmpty()) {
                result = "无应用可接收";
            } else {
                StringBuilder sb = new StringBuilder();
                int i = 0;
                for (String n : names) {
                    if (i++ > 0) {
                        sb.append("、");
                    }
                    sb.append(n);
                    if (i >= 4) {
                        sb.append(" 等");
                        break;
                    }
                }
                result = sb.toString();
            }
        } catch (Throwable t) {
            result = "查询失败：" + t;
        }
        addText("• " + url + "\n    → " + result, 0xFF0000CC, 13);
        addText("    钉死国内版包名时：" + pinnedByCnPackage(url), 0xFFB26A00, 13);
    }

    /**
     * 模拟微信的行为：把 Intent 的目标包固定成国内版。
     * 返回是否能解析——解析不到，正是「点了没反应」的根因。
     */
    private String pinnedByCnPackage(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setPackage(BiliIntent.PKG_CN);
            List<ResolveInfo> list = getPackageManager()
                    .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (list == null || list.isEmpty()) {
                return "无法解析（模块要修复的就是这里）";
            }
            return "可解析到 " + list.size() + " 个组件";
        } catch (Throwable t) {
            return "无法解析";
        }
    }

    private String installedText(String pkg) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(pkg, 0);
            if (info == null) {
                return "未安装";
            }
            String version = info.versionName == null ? "" : " (" + info.versionName + ")";
            return "已安装" + version;
        } catch (Throwable t) {
            return "未安装";
        }
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info != null && info.versionName != null ? info.versionName : "1.0";
        } catch (Throwable t) {
            return "1.0";
        }
    }

    private void addSection(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(0xFF1565C0);
        tv.setPadding(0, dp(12), 0, dp(6));
        body.addView(tv);
    }

    private void addText(String text, int color, int sp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setLineSpacing(0f, 1.15f);
        tv.setPadding(0, dp(3), 0, dp(3));
        body.addView(tv);
    }

    private void addGap() {
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(10)));
        body.addView(gap);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
