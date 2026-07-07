package de.robv.android.xposed;
public abstract class XC_MethodReplacement extends XC_MethodHook {
    public static XC_MethodReplacement returnConstant(final Object value) {
        return new XC_MethodReplacement() {
            protected Object replaceHookedMethod(MethodHookParam param) { return value; }
        };
    }
    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
