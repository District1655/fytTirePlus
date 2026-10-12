package com.fyt.tireplus;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * 数据层：反射接入宿主 com.syu.canbus 的 DataCanbus（DATA[] / NOTIFY_EVENTS[] / PROXY）。
 * 所有订阅均为进程内 UiNotifyEvent.addNotify(IUiNotify 动态代理)，不向服务注册新 Binder 回调。
 */
public class TireDataCenter {

    public interface TireListener {
        void onUpdate();
        boolean isDead();
    }

    static final String TAG = "[fytTirePlus] ";

    /** 四轮气压码（FL/FR/RL/RR），RZC 与 WC2 协议一致 */
    static final int[] PRESSURE_CODES = {184, 185, 186, 187};
    static final int CODE_DISPLAY = 251;
    static final int CODE_UNIT = 252;
    static final int MAX_CODE = 485;

    private static boolean resolving;
    private static boolean ready;

    private static int[] DATA;
    private static Object[] events;
    private static Object proxyObj;
    private static Method cmd4;
    private static Method addNotify;
    private static Object uiNotifyProxy;
    private static Context appCtx;

    private static final List<TireListener> listeners = new ArrayList<>();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static int retryCount;

    /** 幂等入口：解析宿主类并等待 PROXY 就绪后完成订阅 */
    static void ensureInit(final ClassLoader cl) {
        if (ready || resolving) return;
        resolving = true;
        try {
            Class<?> dc = Class.forName("com.syu.module.canbus.DataCanbus", true, cl);
            DATA = (int[]) dc.getField("DATA").get(null);
            events = (Object[]) dc.getField("NOTIFY_EVENTS").get(null);
            proxyObj = dc.getField("PROXY").get(null);
            cmd4 = proxyObj.getClass().getMethod("cmd",
                    int.class, int[].class, float[].class, String[].class);
            Class<?> iui = Class.forName("com.syu.module.IUiNotify", false, cl);
            addNotify = events[0].getClass().getMethod("addNotify", iui);
            uiNotifyProxy = Proxy.newProxyInstance(cl, new Class<?>[]{iui}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("onNotify".equals(method.getName()) && args != null && args.length >= 1
                            && args[0] instanceof Integer) {
                        handleUpdate((Integer) args[0]);
                    }
                    return null;
                }
            });
            try {
                Class<?> theApp = Class.forName("com.syu.canbus.TheApp", false, cl);
                appCtx = (Context) theApp.getMethod("getInstance").invoke(null);
            } catch (Throwable t) {
                log("TheApp.getInstance 失败: " + t);
            }
            waitProxyReady(cl);
        } catch (Throwable t) {
            resolving = false;
            log("ensureInit 解析失败: " + t);
        }
    }

    private static void waitProxyReady(final ClassLoader cl) {
        UI.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Object remote = proxyObj.getClass().getMethod("getRemoteModule").invoke(proxyObj);
                    if (remote != null) {
                        ready = true;
                        resolving = false;
                        onReady();
                        return;
                    }
                } catch (Throwable ignored) {
                }
                if (++retryCount < 60) {
                    waitProxyReady(cl);
                } else {
                    resolving = false;
                    log("等待 PROXY 就绪超时");
                }
            }
        }, retryCount == 0 ? 0 : 500);
    }

    private static void onReady() {
        log("PROXY 就绪, canbusId=" + readRaw(1000));
        ModuleConfig.load(appCtx);
        for (int code : PRESSURE_CODES) subscribe(code);
        subscribe(CODE_DISPLAY);
        subscribe(CODE_UNIT);
        if (ModuleConfig.reconAutoStart) {
            ReconLogger.start();
        }
        dispatch();
    }

    static void subscribe(int code) {
        if (code < 0 || code >= events.length || events[code] == null) return;
        try {
            addNotify.invoke(events[code], uiNotifyProxy);
        } catch (Throwable t) {
            log("订阅 " + code + " 失败: " + t);
        }
    }

    private static void handleUpdate(final int code) {
        // UiNotifyEvent 已在主线程分发
        ReconLogger.onCode(code, readRaw(code));
        if (isTireCode(code)) dispatch();
    }

    private static boolean isTireCode(int code) {
        if (code == CODE_DISPLAY || code == CODE_UNIT) return true;
        for (int c : PRESSURE_CODES) if (c == code) return true;
        for (int c : ModuleConfig.tempCodes) if (c == code) return true;
        return false;
    }

    static void addListener(TireListener l) {
        synchronized (listeners) {
            listeners.add(l);
        }
    }

    private static void dispatch() {
        synchronized (listeners) {
            for (int i = listeners.size() - 1; i >= 0; i--) {
                TireListener l = listeners.get(i);
                if (l.isDead()) {
                    listeners.remove(i);
                } else {
                    l.onUpdate();
                }
            }
        }
    }

    static int readRaw(int code) {
        if (DATA == null || code < 0 || code >= DATA.length) return 0;
        return DATA[code];
    }

    /** 显示位 → 数据轮位（换位映射） */
    static int mapWheel(int displayPos) {
        int[] map = ModuleConfig.swapMap;
        return (map != null && displayPos < map.length) ? map[displayPos] : displayPos;
    }

    /** 当前单位码：0=bar 1=psi 2=kpa（与原厂页一致） */
    static int unitCode() {
        return readRaw(CODE_UNIT);
    }

    /** 气压显示值（与原厂页换算一致：bar=raw/10, psi=raw/2, kpa=raw*10） */
    static String pressureText(int displayPos) {
        int raw = readRaw(PRESSURE_CODES[mapWheel(displayPos)]);
        int unit = unitCode();
        if (unit == 0) return String.format("%.1f bar", raw / 10.0f);
        if (unit == 1) return String.format("%.1f psi", raw / 2.0f);
        return (raw * 10) + " kpa";
    }

    /** 气压换算为 kPa（用于标准值比较） */
    static double pressureKpa(int displayPos) {
        int raw = readRaw(PRESSURE_CODES[mapWheel(displayPos)]);
        switch (unitCode()) {
            case 0: return raw * 10.0;      // raw/10 bar → kPa
            case 1: return raw * 0.5 * 6.894757;
            default: return raw * 10.0;
        }
    }

    /** 胎温显示；未配置温度码或 0xFF 无效时返回 "--" */
    static String tempText(int displayPos) {
        int code = ModuleConfig.tempCodes[mapWheel(displayPos)];
        if (code < 0) return "--";
        int raw = readRaw(code) & 0xFF;
        if (raw == 0xFF) return "--";
        return (raw - ModuleConfig.tempOffset) + "℃";
    }

    /** 是否偏离标准值超过阈值 */
    static boolean isWarn(int displayPos) {
        double std = ModuleConfig.stdKpa[displayPos];
        if (std <= 0) return false;
        double cur = pressureKpa(displayPos);
        return Math.abs(cur - std) / std > ModuleConfig.warnPercent;
    }

    /** 下行命令透传 */
    static void sendCmd(int cmdCode, int[] ints) {
        if (!ready || proxyObj == null) {
            log("cmd 丢弃(未就绪): " + cmdCode);
            return;
        }
        try {
            cmd4.invoke(proxyObj, cmdCode, ints, null, null);
            CmdConsole.markSent(cmdCode, ints);
            log("cmd 已发: code=" + cmdCode + " ints=" + join(ints));
        } catch (Throwable t) {
            log("cmd 失败: " + t);
        }
    }

    static String join(int[] arr) {
        if (arr == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        return sb.append(']').toString();
    }

    static Context context() {
        return appCtx;
    }

    static boolean isReady() {
        return ready;
    }

    static void log(String msg) {
        XposedBridge.log(TAG + msg);
    }
}
