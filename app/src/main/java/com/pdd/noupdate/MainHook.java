package com.pdd.noupdate;

import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[PddNoUpdate] ";

    private static final String FAKE_JSON =
            "{\"server_time\":0," +
            "\"upgrade_type\":\"NoUpgrade\"," +
            "\"build_no\":\"\"," +
            "\"version\":\"\"," +
            "\"url\":\"\"," +
            "\"title\":\"\"," +
            "\"silence\":\"Always\"," +
            "\"second_detect\":false," +
            "\"alert_period\":999999," +
            "\"result\":null," +
            "\"success\":true}";

    private static final String[] TARGET_PKGS = {
            "com.xunmeng.pinduoduo",
            "com.xunmeng.merchant",
            "com.xunmeng.pinduoduo.lifestyle"
    };

    private static boolean shouldBlock(String url) {
        if (url == null) return false;
        if (url.contains("meta.pinduoduo.com") && url.contains("upgrade")) return true;
        if (url.contains("/api/app/checkupdate")) return true;
        if (url.contains("upgrade.pinduoduo.com")) return true;
        // 拼多多 volantis 升级平台
        if (url.contains("volantis") && url.contains("upgrade")) return true;
        return false;
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        boolean match = false;
        for (String p : TARGET_PKGS) if (p.equals(pkg)) { match = true; break; }
        if (!match) return;

        XposedBridge.log(TAG + "loaded into " + pkg);

        ClassLoader cl = lpparam.classLoader;
        hookOkHttp(cl);
        hookUpgradeDialog(cl);   // 兜底：直接干掉弹窗
    }

    // ===================== 1) OkHttp 拦截 =====================
    private void hookOkHttp(final ClassLoader cl) {
        Class<?> realCall = XposedHelpers.findClassIfExists(
                "okhttp3.internal.connection.RealCall", cl);
        if (realCall == null)
            realCall = XposedHelpers.findClassIfExists("okhttp3.RealCall", cl);
        if (realCall == null) {
            XposedBridge.log(TAG + "RealCall not found");
            return;
        }

        Method target = null;
        for (Method m : realCall.getDeclaredMethods()) {
            if ("getResponseWithInterceptorChain".equals(m.getName())) {
                target = m; break;
            }
        }
        if (target == null) {
            XposedBridge.log(TAG + "getResponseWithInterceptorChain not found");
            return;
        }

        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object resp = param.getResult();
                    if (resp == null) return;
                    Object req = XposedHelpers.callMethod(resp, "request");
                    String url = XposedHelpers.callMethod(req, "url").toString();
                    if (!shouldBlock(url)) return;

                    XposedBridge.log(TAG + "intercept: " + url);
                    Object fake = buildFakeResponse(cl, resp);
                    param.setResult(fake);
                    XposedBridge.log(TAG + "response replaced (len=" + FAKE_JSON.length() + ")");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "okhttp err: " + t);
                }
            }
        });
        XposedBridge.log(TAG + "okhttp hook installed on " + realCall.getName());
    }

    private Object buildFakeResponse(ClassLoader cl, Object original) throws Throwable {
        Class<?> mediaTypeCls    = XposedHelpers.findClass("okhttp3.MediaType", cl);
        Class<?> responseBodyCls = XposedHelpers.findClass("okhttp3.ResponseBody", cl);

        Object mt;
        try {
            mt = XposedHelpers.callStaticMethod(mediaTypeCls, "parse",
                    "application/json; charset=utf-8");
        } catch (Throwable e) {
            mt = XposedHelpers.callStaticMethod(mediaTypeCls, "get",
                    "application/json; charset=utf-8");
        }

        Object body;
        try {
            body = XposedHelpers.callStaticMethod(responseBodyCls, "create", mt, FAKE_JSON);
        } catch (Throwable e) {
            Method m = responseBodyCls.getMethod("create", String.class, mediaTypeCls);
            body = m.invoke(null, FAKE_JSON, mt);
        }

        Object builder = XposedHelpers.callMethod(original, "newBuilder");
        XposedHelpers.callMethod(builder, "code", 200);
        XposedHelpers.callMethod(builder, "message", "OK");
        XposedHelpers.callMethod(builder, "body", body);
        return XposedHelpers.callMethod(builder, "build");
    }

    // ===================== 2) 兜底：直接干掉升级弹窗 =====================
    private void hookUpgradeDialog(ClassLoader cl) {
        String[] candidates = {
                "com.xunmeng.pinduoduo.volantis.upgrade.UpgradeManager",
                "com.xunmeng.pinduoduo.upgrade.UpgradeManager",
                "com.xunmeng.merchant.upgrade.UpgradeDialog",
                "com.xunmeng.pinduoduo.volantis.VolantisCenter",
                "com.xunmeng.pinduoduo.volantis.Volantis",
        };
        for (String name : candidates) {
            Class<?> cls = XposedHelpers.findClassIfExists(name, cl);
            if (cls == null) continue;
            XposedBridge.log(TAG + "found upgrade class: " + name);
            // 把所有 show/checkUpdate/notifyUpgrade 类的方法全替换成 no-op
            for (Method m : cls.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("show") || n.contains("check") || n.contains("notify")
                        || n.contains("upgrade") || n.contains("update")) {
                    try {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                        XposedBridge.log(TAG + "neutralized: " + name + "#" + m.getName());
                    } catch (Throwable ignore) {}
                }
            }
        }
    }
}