package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 编译期桩：运行时由 Xposed 框架提供。 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        return null;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return null;
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        return false;
    }

    public static int getIntField(Object obj, String fieldName) {
        return 0;
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        return null;
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) {
        return null;
    }
}
