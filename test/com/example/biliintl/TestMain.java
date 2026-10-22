package com.example.biliintl;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;

/**
 * 逻辑自测：在真机 Android 运行时里跑（app_process），
 * 用真实的 Intent / Uri 验证识别与改写规则。
 *
 * 不参与正式 APK 构建。
 */
public final class TestMain {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // 微信典型场景：目标包被钉死在国内版
        expectRedirect("b23 短链", "https://b23.tv/abc123", BiliIntent.PKG_CN);
        expectRedirect("www 视频页", "https://www.bilibili.com/video/BV1GJ411x7h7", BiliIntent.PKG_CN);
        expectRedirect("m 站视频页", "https://m.bilibili.com/video/BV1GJ411x7h7", BiliIntent.PKG_CN);
        expectRedirect("深链 video", "bilibili://video/BV1GJ411x7h7", BiliIntent.PKG_CN);
        expectRedirect("深链 pgc", "bilibili://pgc/season/12345", BiliIntent.PKG_CN);
        expectRedirect("直播页", "https://live.bilibili.com/12345", BiliIntent.PKG_CN);
        expectRedirect("个人空间", "https://space.bilibili.com/12345", BiliIntent.PKG_CN);
        expectRedirect("无 data 仅包名", null, BiliIntent.PKG_CN);
        expectRedirect("旧短链 bili23", "https://bili23.cn/abc", BiliIntent.PKG_CN);

        // 不该被改写的（真正打开普通网页时，发起方不会带 B 站包名）
        expectIgnore("普通网站", "https://example.com/video/1", null);
        expectIgnore("仿冒域名", "https://www.bilibili.com.evil.com/video/1", null);
        expectIgnore("子域仿冒", "https://bilibili.com.example.org/x", null);
        expectIgnore("无关链接", "https://github.com/bilibili/x", null);
        expectIgnore("普通网站无包名", "https://example.com/", null);

        // 只要目标是国内版包名就改写：微信会用这种方式发起跳转，data 可能为空
        expectRedirect("包名命中但无 data", null, BiliIntent.PKG_CN);

        // 已经是国际版则不再改写，避免死循环
        expectIgnore("已指向国际版", "https://b23.tv/abc123", BiliIntent.PKG_INTL);

        // 国际版没有注册的 comment 深链要换成网页视频地址
        expectCommentRewrite();

        // 网页视频地址转深链，避免国际版弹「打开方式」选择器
        expectDeepLinkConversion();

        // 微信 AppMessage 的组件要映射到国际版自己的微信入口
        expectWxEntryMapping();

        // 商店下载链接改成打开国际版首页，避免弹「去安装国内版」
        expectStoreIntent();

        System.out.println("----");
        System.out.println("PASS=" + passed + " FAIL=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void expectRedirect(String name, String url, String srcPkg) {
        Intent src = build(url, srcPkg);
        Intent out = BiliIntent.redirect(src);
        if (out == null) {
            fail(name, "期望改写，实际返回 null");
            return;
        }
        if (!BiliIntent.PKG_INTL.equals(out.getPackage()) && out.getComponent() == null) {
            fail(name, "目标包=" + out.getPackage());
            return;
        }
        if ((out.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            fail(name, "缺少 NEW_TASK");
            return;
        }
        if (src.getComponent() != null && BiliIntent.PKG_INTL.equals(src.getComponent().getPackageName())) {
            fail(name, "原 Intent 被就地修改");
            return;
        }
        if (BiliIntent.PKG_INTL.equals(src.getPackage())) {
            fail(name, "原 Intent 的 package 被就地修改");
            return;
        }
        ok(name);
    }

    private static void expectIgnore(String name, String url, String srcPkg) {
        Intent out = BiliIntent.redirect(build(url, srcPkg));
        if (out != null) {
            fail(name, "不该改写，却改成了 " + out.getPackage());
            return;
        }
        ok(name);
    }

    private static void expectCommentRewrite() {
        Intent out = BiliIntent.redirect(build("bilibili://comment/BV1GJ411x7h7", BiliIntent.PKG_CN));
        if (out == null) {
            fail("comment 深链改写", "返回 null");
            return;
        }
        String data = out.getData() == null ? "" : out.getData().toString();
        if (!"https://www.bilibili.com/video/BV1GJ411x7h7".equals(data)) {
            fail("comment 深链改写", "data=" + data);
            return;
        }
        ok("comment 深链改写");
    }

    private static void expectDeepLinkConversion() {
        Intent out = BiliIntent.redirect(
                build("https://www.bilibili.com/video/BV1GJ411x7h7?p=2&t=10", BiliIntent.PKG_CN));
        if (out == null) {
            fail("视频页转深链", "返回 null");
            return;
        }
        String data = out.getData() == null ? "" : out.getData().toString();
        if (!"bilibili://video/BV1GJ411x7h7".equals(data)) {
            fail("视频页转深链", "data=" + data);
            return;
        }
        ok("视频页转深链");
    }

    private static void expectWxEntryMapping() {
        Intent src = new Intent(Intent.ACTION_VIEW);
        src.setComponent(new ComponentName(BiliIntent.PKG_CN, "tv.danmaku.bili.wxapi.WXEntryActivity"));
        Intent out = BiliIntent.redirect(src);
        if (out == null) {
            fail("WXEntry 组件映射", "返回 null");
            return;
        }
        ComponentName cn = out.getComponent();
        if (cn == null) {
            fail("WXEntry 组件映射", "component 为空");
            return;
        }
        if (!BiliIntent.PKG_INTL.equals(cn.getPackageName())
                || !"com.bilibili.app.in.wxapi.WXEntryActivity".equals(cn.getClassName())) {
            fail("WXEntry 组件映射", "cmp=" + cn.flattenToShortString());
            return;
        }
        ok("WXEntry 组件映射");
    }

    private static void expectStoreIntent() {
        Intent out = BiliIntent.redirect(build("market://details?id=tv.danmaku.bili", null));
        if (out == null) {
            fail("商店链接接管", "返回 null");
            return;
        }
        if (!BiliIntent.PKG_INTL.equals(out.getPackage())
                || !Intent.ACTION_MAIN.equals(out.getAction())) {
            fail("商店链接接管", "act=" + out.getAction() + " pkg=" + out.getPackage());
            return;
        }
        ok("商店链接接管");
    }

    private static Intent build(String url, String pkg) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (url != null) {
            intent.setData(Uri.parse(url));
        }
        if (pkg != null) {
            intent.setPackage(pkg);
        }
        return intent;
    }

    private static void ok(String name) {
        passed++;
        System.out.println("PASS  " + name);
    }

    private static void fail(String name, String why) {
        failed++;
        System.out.println("FAIL  " + name + " -> " + why);
    }
}
