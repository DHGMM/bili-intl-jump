package com.example.biliintl;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed / Xposed 模块入口。
 *
 * <p>模块不挑宿主：只要在 LSPosed 里给它勾选了某个应用，就会在该应用的
 * 进程里安装重定向 hook。默认建议勾选微信；QQ、TIM、钉钉等同样适用。
 */
public final class XposedInit implements IXposedHookLoadPackage {

    private static final String SELF = "com.example.biliintl";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        String pkg = lpparam.packageName;
        if (SELF.equals(pkg)) {
            return;
        }
        // 不在 B 站自身进程里装 hook：避免误勾选作用域时劫持它自己的内部导航。
        if (BiliIntent.PKG_INTL.equals(pkg) || BiliIntent.PKG_CN.equals(pkg)) {
            return;
        }
        try {
            Hooks.install(pkg, lpparam.classLoader);
        } catch (Throwable t) {
            // 任何异常都必须吞掉，避免宿主启动失败。
            Hooks.log("install failed in " + pkg + ": " + t);
        }
    }
}
