package de.robv.android.xposed;
import java.lang.reflect.Member;
import java.util.Set;
public final class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static Set<Object> hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook cb) { return null; }
}
