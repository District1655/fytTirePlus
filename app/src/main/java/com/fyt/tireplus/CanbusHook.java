package com.fyt.tireplus;

import android.app.Activity;
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 入口：注入 com.syu.canbus 进程。
 * - ConnectionCanbus.onConnected 后置：PROXY 就绪，初始化数据层
 * - TheApp.onCreate 后置：兜底（onConnected 签名变动时仍能经轮询初始化）
 * - 两个 MQB 胎压 Activity onCreate 后置：挂载叠加层
 */
public class CanbusHook implements IXposedHookLoadPackage {

    private static final String PKG = "com.syu.canbus";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PKG.equals(lpparam.packageName)) return;
        TireDataCenter.log("注入成功 process=" + lpparam.processName);

        hookOnConnected(lpparam.classLoader);
        hookTheApp(lpparam.classLoader);
        hookTireActivity(lpparam.classLoader, "com.syu.carinfo.golf7.RzcGolfPressureTireAct");
        hookTireActivity(lpparam.classLoader, "com.syu.carinfo.golf7.GolfTireAct_TuAng_Wc");
    }

    private void hookOnConnected(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.syu.module.canbus.ConnectionCanbus", cl,
                    "onConnected", "com.syu.ipc.IRemoteToolkit",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            TireDataCenter.ensureInit(cl);
                        }
                    });
            TireDataCenter.log("hook ConnectionCanbus.onConnected 成功");
        } catch (Throwable t) {
            TireDataCenter.log("hook ConnectionCanbus.onConnected 失败: " + t);
        }
    }

    private void hookTheApp(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.syu.canbus.TheApp", cl, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            TireDataCenter.ensureInit(cl);
                        }
                    });
            TireDataCenter.log("hook TheApp.onCreate 成功");
        } catch (Throwable t) {
            TireDataCenter.log("hook TheApp.onCreate 失败: " + t);
        }
    }

    private void hookTireActivity(final ClassLoader cl, final String className) {
        try {
            XposedHelpers.findAndHookMethod(className, cl,
                    "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                new TireOverlay().attach((Activity) param.thisObject);
                                // 进胎压页时主动请求一次数据（与原厂行为一致）
                                CmdConsole.requestTireData();
                            } catch (Throwable t) {
                                TireDataCenter.log("挂载叠加层失败: " + t);
                            }
                        }
                    });
            TireDataCenter.log("hook " + className + ".onCreate 成功");
        } catch (Throwable t) {
            TireDataCenter.log("hook " + className + " 失败(该车型无此页面则忽略): " + t);
        }
    }
}
