package com.fyt.tireplus;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 配置：/sdcard/Android/data/com.syu.canbus/files/tireplus/config.json
 * 温度码位、换算偏移、标准值、告警阈值、换位映射、下行 cmd 预设。
 */
public class ModuleConfig {

    /** 四轮温度码（-1=未配置），顺序 FL/FR/RL/RR */
    static int[] tempCodes = {-1, -1, -1, -1};
    /** 温度换算偏移：℃ = raw - offset */
    static int tempOffset = 40;
    /** 四轮标准胎压 kPa */
    static double[] stdKpa = {250, 250, 250, 250};
    /** 告警阈值（相对偏差比例） */
    static double warnPercent = 0.25;
    /** 显示位→数据位映射（换位），默认恒等 */
    static int[] swapMap = {0, 1, 2, 3};
    static boolean reconAutoStart = false;
    /** 设标准值下行码：-1 关闭；≥0 时按 PAJeep 风格发 {pos, 0/1} */
    static int setStdCmdCode = -1;
    /** 换位下行码开关与参数 */
    static boolean swapCmdEnabled = false;
    static int swapCmdCode = 8;
    static int[] swapCmdInts = {255, 2};
    /** CMD 调试预设 [{name, code, ints:[...]}] */
    static JSONArray cmdPresets;

    private static File configFile;

    static synchronized void load(Context ctx) {
        File dir = null;
        if (ctx != null) {
            File ext = ctx.getExternalFilesDir(null);
            dir = ext != null ? new File(ext, "tireplus") : new File(ctx.getFilesDir(), "tireplus");
        }
        if (dir == null) dir = new File("/sdcard/Android/data/com.syu.canbus/files/tireplus");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        configFile = new File(dir, "config.json");
        try {
            if (configFile.exists()) {
                byte[] buf = new byte[(int) configFile.length()];
                FileInputStream in = new FileInputStream(configFile);
                int n = in.read(buf);
                in.close();
                JSONObject o = new JSONObject(new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8));
                tempCodes = readIntArray(o, "tempCodes", tempCodes);
                tempOffset = o.optInt("tempOffset", tempOffset);
                stdKpa = readDoubleArray(o, "stdKpa", stdKpa);
                warnPercent = o.optDouble("warnPercent", warnPercent);
                swapMap = readIntArray(o, "swapMap", swapMap);
                reconAutoStart = o.optBoolean("reconAutoStart", false);
                setStdCmdCode = o.optInt("setStdCmdCode", -1);
                swapCmdEnabled = o.optBoolean("swapCmdEnabled", false);
                swapCmdCode = o.optInt("swapCmdCode", 8);
                swapCmdInts = readIntArray(o, "swapCmdInts", swapCmdInts);
                cmdPresets = o.optJSONArray("cmdPresets");
            }
        } catch (Throwable t) {
            TireDataCenter.log("配置读取失败: " + t);
        }
        if (cmdPresets == null) cmdPresets = defaultPresets();
        save();
        TireDataCenter.log("配置目录: " + dir.getAbsolutePath());
    }

    static synchronized void save() {
        if (configFile == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("tempCodes", new JSONArray(toBoxed(tempCodes)));
            o.put("tempOffset", tempOffset);
            o.put("stdKpa", new JSONArray(toBoxed(stdKpa)));
            o.put("warnPercent", warnPercent);
            o.put("swapMap", new JSONArray(toBoxed(swapMap)));
            o.put("reconAutoStart", reconAutoStart);
            o.put("setStdCmdCode", setStdCmdCode);
            o.put("swapCmdEnabled", swapCmdEnabled);
            o.put("swapCmdCode", swapCmdCode);
            o.put("swapCmdInts", new JSONArray(toBoxed(swapCmdInts)));
            o.put("cmdPresets", cmdPresets);
            FileOutputStream fos = new FileOutputStream(configFile);
            fos.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Throwable t) {
            TireDataCenter.log("配置保存失败: " + t);
        }
    }

    static File dir() {
        return configFile != null ? configFile.getParentFile() : null;
    }

    private static JSONArray defaultPresets() {
        JSONArray arr = new JSONArray();
        try {
            arr.put(preset("请求胎压", 98, new int[]{4}));
            arr.put(preset("请求全部", 98, new int[]{255}));
            arr.put(preset("换位尝试A", 8, new int[]{255, 2}));
            arr.put(preset("换位尝试B", 8, new int[]{255, 1}));
            arr.put(preset("标定尝试(cmd12+)", 12, new int[]{0, 1}));
            arr.put(preset("标定尝试(cmd12-)", 12, new int[]{0, 0}));
        } catch (Throwable ignored) {
        }
        return arr;
    }

    private static JSONObject preset(String name, int code, int[] ints) throws Exception {
        JSONObject p = new JSONObject();
        p.put("name", name);
        p.put("code", code);
        p.put("ints", new JSONArray(toBoxed(ints)));
        return p;
    }

    private static int[] readIntArray(JSONObject o, String key, int[] def) {
        try {
            JSONArray a = o.optJSONArray(key);
            if (a == null) return def;
            int[] r = new int[a.length()];
            for (int i = 0; i < r.length; i++) r[i] = a.getInt(i);
            return r;
        } catch (Throwable t) {
            return def;
        }
    }

    private static double[] readDoubleArray(JSONObject o, String key, double[] def) {
        try {
            JSONArray a = o.optJSONArray(key);
            if (a == null) return def;
            double[] r = new double[a.length()];
            for (int i = 0; i < r.length; i++) r[i] = a.getDouble(i);
            return r;
        } catch (Throwable t) {
            return def;
        }
    }

    private static Object[] toBoxed(int[] arr) {
        Object[] r = new Object[arr.length];
        for (int i = 0; i < arr.length; i++) r[i] = arr[i];
        return r;
    }

    private static Object[] toBoxed(double[] arr) {
        Object[] r = new Object[arr.length];
        for (int i = 0; i < arr.length; i++) r[i] = arr[i];
        return r;
    }
}
