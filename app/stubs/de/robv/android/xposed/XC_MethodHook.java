package de.robv.android.xposed;

/** 编译期桩：运行时由 Xposed 框架提供。 */
public abstract class XC_MethodHook {

    public XC_MethodHook() {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam {
        public Object[] args;
        public Object thisObject;
        public Object method;

        public MethodHookParam() {
        }

        public Object getResult() {
            return null;
        }

        public void setResult(Object result) {
        }

        public Throwable getThrowable() {
            return null;
        }

        public boolean hasThrowable() {
            return false;
        }

        public void setThrowable(Throwable throwable) {
        }
    }

    public class Unhook {
        public void unhook() {
        }
    }
}
