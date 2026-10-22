package com.example.biliintl;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;

import java.util.Locale;

/**
 * B 站链接识别与目标包改写。
 *
 * <p>微信分享出来的视频链接，最终会以 {@code bilibili://video/...}、
 * {@code https://b23.tv/...} 或 {@code https://www.bilibili.com/video/...} 之一
 * 的形式交给系统。问题在于发起方往往已经用
 * {@code setPackage("tv.danmaku.bili")} 把目标钉死在国内版上，
 * 于是即使手机里装了国际版（com.bilibili.app.in）也永远不会被唤起。
 *
 * <p>这里只做一件事：把命中的 Intent 的目标包改写成国际版，
 * 其余 action / data / flags / extras 全部原样保留，让国际版按它自己的
 * Manifest 去解析对应页面。
 */
final class BiliIntent {

    /** 国际版（Bilibili 海外版）包名。 */
    static final String PKG_INTL = "com.bilibili.app.in";
    /** 国内版包名。 */
    static final String PKG_CN = "tv.danmaku.bili";

    private static final String[] CN_PACKAGE_PREFIXES = {
            "tv.danmaku.bili",
            "tv.danmaku.bilipro",
    };

    private BiliIntent() {
    }

    /** 目标包是否属于「B 站系」且需要被改写到国际版。 */
    static boolean isBiliPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.equals(PKG_INTL)) {
            return true;
        }
        for (String prefix : CN_PACKAGE_PREFIXES) {
            if (p.equals(prefix) || p.startsWith(prefix + ":")) {
                return true;
            }
        }
        return false;
    }

    /** 链接是否指向 B 站。 */
    static boolean isBiliUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        String s = scheme.toLowerCase(Locale.ROOT);
        if (s.equals("bilibili") || s.equals("bilibili-hd")
                || s.equals("bilibili-intl") || s.equals("bstar")
                || s.equals("bili")) {
            return true;
        }
        if (s.equals("http") || s.equals("https")) {
            return isBiliHost(uri.getHost());
        }
        return false;
    }

    /** 域名是否属于 B 站（含短链）。 */
    static boolean isBiliHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return matchesDomain(h, "bilibili.com")
                || matchesDomain(h, "bilibili.tv")
                || matchesDomain(h, "bilibili.cn")
                || matchesDomain(h, "b23.tv")
                || matchesDomain(h, "acg.tv")
                || h.equals("bili22.cn")
                || h.equals("bili23.cn")
                || h.equals("bili33.cn")
                || h.equals("bili2233.cn")
                || h.equals("bili22.tv")
                || h.equals("bili33.tv");
    }

    private static boolean matchesDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /** 是否是「去商店下载国内版」这类链接（H5 检测未安装后常跳这个）。 */
    static boolean isStoreIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        Uri data = intent.getData();
        if (data == null) {
            return false;
        }
        String raw = data.toString().toLowerCase(Locale.ROOT);
        if (!raw.contains("tv.danmaku.bili") && !raw.contains("tv%2edanmaku%2ebili")) {
            return false;
        }
        String scheme = data.getScheme();
        if (scheme == null) {
            return false;
        }
        String s = scheme.toLowerCase(Locale.ROOT);
        if (s.equals("market") || s.equals("smarket")) {
            return true;
        }
        String host = data.getHost();
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("a.app.qq.com") || h.endsWith(".app.qq.com");
    }

    /** 这个 Intent 是否值得改写（指向 B 站，且目标还不是国际版）。 */
    static boolean needsRedirect(Intent intent) {
        if (intent == null) {
            return false;
        }
        boolean bili = false;

        ComponentName component = intent.getComponent();
        if (component != null && isBiliPackage(component.getPackageName())) {
            bili = true;
        }
        if (!bili && isBiliPackage(intent.getPackage())) {
            bili = true;
        }
        if (!bili && isBiliUri(intent.getData())) {
            bili = true;
        }
        if (!bili && isStoreIntent(intent)) {
            bili = true;
        }
        // selector（用于 chooser / 显式转发）里也可能藏着真实目标
        if (!bili) {
            Intent selector = intent.getSelector();
            if (selector != null) {
                bili = needsRedirect(selector);
            }
        }
        if (!bili) {
            return false;
        }

        // 已经是国际版就不要再动，避免反复改写。
        if (component != null && PKG_INTL.equals(component.getPackageName())) {
            return false;
        }
        if (PKG_INTL.equals(intent.getPackage())) {
            return false;
        }
        Intent selector = intent.getSelector();
        if (selector != null && PKG_INTL.equals(selector.getPackage())) {
            return false;
        }
        return true;
    }

    /**
     * 生成改写后的 Intent（不修改原对象）。
     *
     * @return 无需改写时返回 null
     */
    static Intent redirect(Intent original) {
        if (!needsRedirect(original)) {
            return null;
        }
        // 商店下载链接没有视频信息，直接改成打开国际版首页，
        // 避免系统弹「前往软件商店安装国内版」。
        if (isStoreIntent(original)) {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.setPackage(PKG_INTL);
            home.addCategory(Intent.CATEGORY_LAUNCHER);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return home;
        }
        Intent out = new Intent(original);
        // 保留 selector 会让系统重新按 selector 解析，可能又回到国内版。
        out.setSelector(null);

        // 微信分享走的是 OpenSDK 的 AppMessage：组件直接指向
        // tv.danmaku.bili/.wxapi.WXEntryActivity，数据在 extras 里。
        // 这种情况下不能把组件清空，否则国际版不知道要处理哪条消息，
        // 应当换成国际版自己的微信入口，extras 原样带过去。
        String mapped = mapComponentClass(original.getComponent());
        if (mapped != null) {
            out.setComponent(new ComponentName(PKG_INTL, mapped));
        } else {
            out.setComponent(null);
            out.setPackage(PKG_INTL);
        }

        // 从非 Activity Context 启动时必须有 NEW_TASK，否则会抛异常。
        if ((out.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        normalizeToDeepLink(out);
        normalizeUnsupportedDeepLink(out);
        return out;
    }

    /**
     * 网页视频地址转成国际版唯一入口的深链。
     *
     * <p>{@code https://www.bilibili.com/video/BVxxx} 在国际版里有多个 Activity
     * 都能匹配，隐式启动会弹「打开方式」选择器；而 {@code bilibili://video/BVxxx}
     * 只有一个入口，直接进视频页。
     */
    private static void normalizeToDeepLink(Intent intent) {
        Uri data = intent.getData();
        if (data == null) {
            return;
        }
        String scheme = data.getScheme();
        if (scheme == null) {
            return;
        }
        String s = scheme.toLowerCase(Locale.ROOT);
        if (!s.equals("http") && !s.equals("https")) {
            return;
        }
        String host = data.getHost();
        if (host == null) {
            return;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (!h.equals("bilibili.com") && !h.endsWith(".bilibili.com")) {
            return;
        }
        String path = data.getPath();
        if (path == null) {
            return;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^/video/(BV[0-9A-Za-z]+)").matcher(path);
        if (matcher.find()) {
            intent.setData(Uri.parse("bilibili://video/" + matcher.group(1)));
        }
    }

    /** 国内版组件 -> 国际版同类入口；无法确定时返回 null，走包名重解析。 */
    private static String mapComponentClass(ComponentName component) {
        if (component == null) {
            return null;
        }
        String cls = component.getClassName();
        if (cls == null) {
            return null;
        }
        if (cls.endsWith(".wxapi.WXEntryActivity")) {
            return "com.bilibili.app.in.wxapi.WXEntryActivity";
        }
        if (cls.endsWith(".wxapi.WXPayEntryActivity")) {
            return "com.bilibili.app.in.wxapi.WXPayEntryActivity";
        }
        return null;
    }

    /**
     * 个别深链国际版没有注册对应 filter（实测 bilibili://comment/... 会解析失败），
     * 换成国际版一定认识的网页视频地址。
     */
    private static void normalizeUnsupportedDeepLink(Intent intent) {
        Uri data = intent.getData();
        if (data == null) {
            return;
        }
        String scheme = data.getScheme();
        if (scheme == null) {
            return;
        }
        if (!"bilibili".equalsIgnoreCase(scheme)) {
            return;
        }
        String host = data.getHost();
        if (host == null) {
            return;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (!h.equals("comment") && !h.equals("comment2")) {
            return;
        }
        String path = data.getPath();
        if (path == null) {
            return;
        }
        String id = path.replace("/", "").trim();
        if (id.isEmpty()) {
            return;
        }
        intent.setData(Uri.parse("https://www.bilibili.com/video/" + id));
    }

    /** 供设置界面显示用：把 Intent 描述成一行文本。 */
    static String describe(Intent intent) {
        if (intent == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        if (intent.getPackage() != null) {
            sb.append("pkg=").append(intent.getPackage()).append(' ');
        }
        ComponentName cn = intent.getComponent();
        if (cn != null) {
            sb.append("cmp=").append(cn.flattenToShortString()).append(' ');
        }
        sb.append("data=").append(intent.getData());
        return sb.toString();
    }
}
