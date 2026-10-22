package io.github.dhgmm.biliintl;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.view.View;

import java.util.List;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 在宿主进程里安装 Intent 重定向 hook。
 *
 * <p>有些应用（例如微信）并不是直接 {@code startActivity}，而是先检查
 * 「国内版装没装」，查不到就自己弹「未找到相关应用」然后放弃。
 * 所以除了改写启动参数，还要让它在查询阶段就能看到国际版。
 */
final class Hooks {

    private static final String TAG = "BiliIntlBridge";
    /** 日志上限，避免刷爆 LSPosed 日志。 */
    private static final int MAX_LOG = 300;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicInteger LOG_COUNT = new AtomicInteger(0);

    private Hooks() {
    }

    static void install(String packageName, ClassLoader classLoader) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        hookIntentArgs(Instrumentation.class, "execStartActivity");
        hookIntentArgs(ContextWrapper.class, "startActivity");
        hookIntentArgs(ContextWrapper.class, "startActivityForResult");
        hookIntentArgs(ContextWrapper.class, "startActivities");
        hookIntentArgs(Activity.class, "startActivity");
        hookIntentArgs(Activity.class, "startActivityForResult");
        // 最底层实现：ContextWrapper 最终都委托给它；JNI / 内部调用也可能直接走这里。
        hookIntentArgsByClassName("android.app.ContextImpl");
        hookPackageManager();
        hookWebViewUrlLoaders(classLoader);
        log("hooks installed in " + packageName);
    }

    private static void hookIntentArgsByClassName(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            hookIntentArgs(clazz, "startActivity");
            hookIntentArgs(clazz, "startActivityForResult");
            hookIntentArgs(clazz, "startActivities");
        } catch (Throwable t) {
            log("hook " + className + " skipped: " + t);
        }
    }

    /** hook 一个方法的所有重载，把参数里的 Intent 换成指向国际版的 Intent。 */
    private static void hookIntentArgs(Class<?> clazz, String methodName) {
        try {
            XposedBridge.hookAllMethods(clazz, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    patchArgs(param, methodName);
                }
            });
        } catch (Throwable t) {
            log("hook " + clazz.getSimpleName() + "." + methodName + " failed: " + t);
        }
    }

    private static void patchArgs(XC_MethodHook.MethodHookParam param, String methodName) {
        Object[] args = param.args;
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof Intent) {
                Intent original = (Intent) arg;
                if (BiliIntent.isStoreIntent(original)) {
                    log("store-intent: " + BiliIntent.describe(original));
                }
                Intent fixed = BiliIntent.redirect(original);
                if (fixed != null) {
                    Context ctx = findContext(param);
                    if (ctx != null) {
                        fixed = pinSingleTarget(ctx, fixed);
                    }
                    args[i] = fixed;
                    log("redirect(" + methodName + "): " + BiliIntent.describe(fixed));
                }
            } else if (arg instanceof Intent[]) {
                Intent[] array = (Intent[]) arg;
                for (int j = 0; j < array.length; j++) {
                    Intent fixed = BiliIntent.redirect(array[j]);
                    if (fixed != null) {
                        Context ctx = findContext(param);
                        if (ctx != null) {
                            fixed = pinSingleTarget(ctx, fixed);
                        }
                        array[j] = fixed;
                        log("redirect(" + methodName + "[]): " + BiliIntent.describe(fixed));
                    }
                }
            }
        }
    }

    /**
     * 国际版有多个 activity 注册了同一类链接，隐式启动会弹「打开方式」选择器。
     * 这里先在国际版包内选出系统排序最靠前的那个，改成显式组件，只留一个入口。
     */
    private static Intent pinSingleTarget(Context ctx, Intent intent) {
        if (intent.getComponent() != null) {
            return intent;
        }
        try {
            List<ResolveInfo> list = ctx.getPackageManager()
                    .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (list == null || list.isEmpty()) {
                return intent;
            }
            ResolveInfo best = list.get(0);
            if (best.activityInfo == null || best.activityInfo.packageName == null
                    || best.activityInfo.name == null) {
                return intent;
            }
            Intent pinned = new Intent(intent);
            pinned.setComponent(new ComponentName(best.activityInfo.packageName, best.activityInfo.name));
            return pinned;
        } catch (Throwable t) {
            return intent;
        }
    }

    private static Context findContext(XC_MethodHook.MethodHookParam param) {
        Object self = param.thisObject;
        if (self instanceof Context) {
            return (Context) self;
        }
        if (self instanceof View) {
            return ((View) self).getContext();
        }
        if (param.args != null) {
            for (Object a : param.args) {
                if (a instanceof Context) {
                    return (Context) a;
                }
            }
        }
        return null;
    }

    private static void hookPackageManager() {
        Class<?> apm;
        try {
            apm = Class.forName("android.app.ApplicationPackageManager");
        } catch (Throwable t) {
            log("ApplicationPackageManager not found: " + t);
            return;
        }

        // 1) 查询阶段就返回国际版：让「先查再跳」的应用不会查到空。
        XC_MethodHook queryHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object[] args = param.args;
                if (args == null || args.length == 0 || !(args[0] instanceof Intent)) {
                    return;
                }
                Intent intent = (Intent) args[0];
                Intent fixed = BiliIntent.redirect(intent);
                if (fixed != null) {
                    args[0] = fixed;
                    log("query-rewrite: " + BiliIntent.describe(intent));
                }
            }
        };
        safeHook(apm, "resolveActivity", queryHook);
        safeHook(apm, "queryIntentActivities", queryHook);

        // 2) 记录：应用到底是怎么判断「国内版装没装」的。
        XC_MethodHook probe = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object a = param.args != null && param.args.length > 0 ? param.args[0] : null;
                if (a instanceof String && isBiliName((String) a)) {
                    log("check-package: " + a);
                }
            }
        };
        safeHook(apm, "getPackageInfo", probe);
        safeHook(apm, "getApplicationInfo", probe);
        safeHook(apm, "getLaunchIntentForPackage", probe);

        // 3) 存在性兜底：只针对国内版这一个包名。
        XC_MethodHook fakeCn = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable()) {
                    return;
                }
                Throwable t = param.getThrowable();
                if (!(t instanceof PackageManager.NameNotFoundException)) {
                    return;
                }
                Object a = param.args != null && param.args.length > 0 ? param.args[0] : null;
                if (!(a instanceof String) || !BiliIntent.PKG_CN.equals(a)) {
                    return;
                }
                PackageInfo fake = buildFakePackageInfo(param.thisObject);
                if (fake != null) {
                    param.setResult(fake);
                    log("pretend-installed: " + a);
                }
            }
        };
        safeHook(apm, "getPackageInfo", fakeCn);
        safeHook(apm, "getApplicationInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable()) {
                    return;
                }
                Object a = param.args != null && param.args.length > 0 ? param.args[0] : null;
                if (!(a instanceof String) || !BiliIntent.PKG_CN.equals(a)) {
                    return;
                }
                PackageInfo fake = buildFakePackageInfo(param.thisObject);
                if (fake != null) {
                    param.setResult(fake.applicationInfo);
                    log("pretend-installed(info): " + a);
                }
            }
        });
        // 有的应用用 getLaunchIntentForPackage 既判断又拿启动意图，直接换成国际版的。
        safeHook(apm, "getLaunchIntentForPackage", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object a = param.args != null && param.args.length > 0 ? param.args[0] : null;
                if (!(a instanceof String) || !BiliIntent.PKG_CN.equals(a)) {
                    return;
                }
                if (param.getResult() != null || !(param.thisObject instanceof PackageManager)) {
                    return;
                }
                try {
                    PackageManager pm = (PackageManager) param.thisObject;
                    Intent launch = pm.getLaunchIntentForPackage(BiliIntent.PKG_INTL);
                    if (launch != null) {
                        launch.setPackage(BiliIntent.PKG_INTL);
                        param.setResult(launch);
                        log("pretend-launch-intent: " + a);
                    }
                } catch (Throwable ignored) {
                    // 查不到就保持原样
                }
            }
        });
    }

    /** 用国际版的信息拼一个「国内版」的 PackageInfo，只用于通过存在性检查。 */
    private static PackageInfo buildFakePackageInfo(Object self) {
        if (!(self instanceof PackageManager)) {
            return null;
        }
        try {
            PackageManager pm = (PackageManager) self;
            PackageInfo intl = pm.getPackageInfo(BiliIntent.PKG_INTL, 0);
            if (intl == null) {
                return null;
            }
            PackageInfo fake = new PackageInfo();
            fake.packageName = BiliIntent.PKG_CN;
            fake.versionName = intl.versionName;
            fake.versionCode = intl.versionCode;
            fake.firstInstallTime = intl.firstInstallTime;
            fake.lastUpdateTime = intl.lastUpdateTime;
            fake.signatures = intl.signatures;
            fake.requestedPermissions = intl.requestedPermissions;
            if (intl.applicationInfo != null) {
                ApplicationInfo ai = new ApplicationInfo(intl.applicationInfo);
                ai.packageName = BiliIntent.PKG_CN;
                fake.applicationInfo = ai;
            }
            return fake;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void safeHook(Class<?> clazz, String methodName, XC_MethodHook callback) {
        try {
            XposedBridge.hookAllMethods(clazz, methodName, callback);
        } catch (Throwable t) {
            log("hook " + methodName + " failed: " + t);
        }
    }

    private static boolean isBiliName(String s) {
        String v = s.toLowerCase(Locale.ROOT);
        return v.contains("bili") || v.contains("danmaku");
    }

    private static volatile String lastOpen = null;
    private static volatile long lastOpenAt = 0;

    /**
     * 微信这类应用用自研 XWeb 内核加载网页，H5 的「打开APP」走的是
     * 应用宝中间页：{@code a.app.qq.com/o/simple.jsp?pkgname=tv.danmaku.bili&schema=bilibili://...}
     * 这里的跳转在 native 内核里完成，Java 的 startActivity 完全拦不到。
     *
     * <p>所以在网页加载这一层截住：只要 URL 里带着 {@code schema=bilibili://...}
     * 就直接把国际版叫起来，不再让中间页去做那套「国内版装没装」的判断。
     */
    private static void hookWebViewUrlLoaders(ClassLoader cl) {
        String[] webViewClasses = {
                "com.tencent.xweb.WebView",
                "com.tencent.mm.ui.widget.MMWebView",
                "android.webkit.WebView",
        };
        for (String cn : webViewClasses) {
            try {
                Class<?> c = cl.loadClass(cn);
                XposedBridge.hookAllMethods(c, "loadUrl", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object a = param.args != null && param.args.length > 0 ? param.args[0] : null;
                        if (a instanceof String) {
                            onWebUrl(param, (String) a);
                        }
                    }
                });
                log("hook " + cn + ".loadUrl");
            } catch (Throwable t) {
                log("hook " + cn + ".loadUrl skipped: " + t);
            }
        }

        String[] clientClasses = {
                "com.tencent.xweb.WebViewClient",
                "com.tencent.xweb.pinus.sdk.WebViewClient",
                "android.webkit.WebViewClient",
        };
        for (String cn : clientClasses) {
            try {
                Class<?> c = cl.loadClass(cn);
                XposedBridge.hookAllMethods(c, "shouldOverrideUrlLoading", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String url = null;
                        if (param.args != null) {
                            for (Object a : param.args) {
                                if (a instanceof String) {
                                    url = (String) a;
                                    break;
                                }
                            }
                        }
                        if (url != null && onWebUrl(param, url)) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                });
                log("hook " + cn + ".shouldOverrideUrlLoading");
            } catch (Throwable t) {
                log("hook " + cn + " skipped: " + t);
            }
        }
    }

    /** @return 是否已经接管这个 URL */
    private static boolean onWebUrl(XC_MethodHook.MethodHookParam param, String url) {
        if (url == null || url.length() < 12) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);

        // 1) 用户点开的就是 B 站视频页 / 短链：直接交给国际版，不再让微信渲染网页。
        if (lower.startsWith("https://b23.tv/") || lower.startsWith("http://b23.tv/")
                || lower.startsWith("https://m.bilibili.com/video/")
                || lower.startsWith("https://www.bilibili.com/video/")) {
            log("web-url(video): " + url);
            return launchIntl(param, url);
        }

        // 2) 应用宝中间页，URL 里带着 schema=bilibili://...
        String schema = null;
        if (lower.contains("pkgname=tv.danmaku.bili")
                || lower.contains("pkgname%3dtv.danmaku.bili")
                || lower.contains("a.app.qq.com")) {
            schema = extractSchema(url);
            log("web-url(middle): " + url);
        } else if (lower.startsWith("bilibili://")) {
            schema = url;
        }
        if (schema == null || !schema.toLowerCase(Locale.ROOT).startsWith("bilibili://")) {
            return false;
        }
        return launchIntl(param, schema);
    }

    private static boolean launchIntl(XC_MethodHook.MethodHookParam param, String target) {
        long now = System.currentTimeMillis();
        if (target.equals(lastOpen) && now - lastOpenAt < 3000) {
            return true;
        }
        lastOpen = target;
        lastOpenAt = now;

        Context ctx = contextOf(param.thisObject);
        if (ctx == null) {
            log("web-open: no context for " + target);
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
            intent.setPackage(BiliIntent.PKG_INTL);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            log("web-open-intl: " + target);
            return true;
        } catch (Throwable t) {
            log("web-open failed: " + t);
            return false;
        }
    }

    private static String extractSchema(String url) {
        try {
            String s = Uri.parse(url).getQueryParameter("schema");
            if (s != null) {
                return s;
            }
        } catch (Throwable ignored) {
            // 落到下面的手工解析
        }
        int i = url.indexOf("schema=");
        if (i < 0) {
            i = url.indexOf("schema%3D");
        }
        if (i < 0) {
            return null;
        }
        int start = i + 7;
        int end = url.indexOf('&', start);
        String raw = end > 0 ? url.substring(start, end) : url.substring(start);
        try {
            return Uri.decode(raw);
        } catch (Throwable t) {
            return raw;
        }
    }

    private static Context contextOf(Object o) {
        if (o instanceof View) {
            return ((View) o).getContext();
        }
        if (o instanceof Context) {
            return (Context) o;
        }
        return null;
    }

    static void log(String message) {
        if (LOG_COUNT.incrementAndGet() > MAX_LOG) {
            return;
        }
        try {
            XposedBridge.log(TAG + ": " + message);
        } catch (Throwable ignored) {
            // 框架日志不可用时静默，绝不能影响宿主。
        }
    }
}
