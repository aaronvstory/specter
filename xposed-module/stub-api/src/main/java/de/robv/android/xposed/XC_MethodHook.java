package de.robv.android.xposed;
public abstract class XC_MethodHook {
    public static class MethodHookParam {
        public Object[] args;
        public Object thisObject;
        private Object result;
        private Throwable throwable;
        public Object getResult() { return result; }
        public void setResult(Object r) { this.result = r; }
        // Real Xposed: setting a result/throwable in beforeHookedMethod SKIPS the original method and makes it
        // return that value / throw that exception. A plain `throw` from a hook callback is CAUGHT and swallowed
        // by LSPosed (then the original runs) — so making a method "throw" MUST go through setThrowable.
        public Throwable getThrowable() { return throwable; }
        public boolean hasThrowable() { return throwable != null; }
        public void setThrowable(Throwable t) { this.throwable = t; }
    }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
