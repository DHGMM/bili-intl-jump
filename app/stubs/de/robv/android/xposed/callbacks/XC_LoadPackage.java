package de.robv.android.xposed.callbacks;

/** 编译期桩：运行时由 Xposed 框架提供。 */
public abstract class XC_LoadPackage {

    public XC_LoadPackage() {
    }

    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;

        public LoadPackageParam() {
        }
    }
}
