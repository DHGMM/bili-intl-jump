package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 编译期桩：真实实现由 LSPosed / Xposed 在运行时提供。
 * 该文件仅参与 javac 编译，不会打进 APK。
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
