package com.fyt.tireplus;

/**
 * 下行命令发送记录：供侦察日志标注「cmd 后 5 秒内的码变化」。
 */
public class CmdConsole {

    private static final long WINDOW_MS = 5000;

    private static long lastSentAt;
    private static String lastDesc = "";

    static void markSent(int code, int[] ints) {
        lastSentAt = System.currentTimeMillis();
        lastDesc = "cmd(" + code + ", " + TireDataCenter.join(ints) + ")";
    }

    /** 若处于 cmd 后观察窗内，返回标注文本，否则返回空串 */
    static String annotate() {
        if (lastSentAt > 0 && System.currentTimeMillis() - lastSentAt <= WINDOW_MS) {
            return " <== " + lastDesc;
        }
        return "";
    }

    static void send(int code, int[] ints) {
        TireDataCenter.sendCmd(code, ints);
    }

    static void requestTireData() {
        send(98, new int[]{4});
    }
}
