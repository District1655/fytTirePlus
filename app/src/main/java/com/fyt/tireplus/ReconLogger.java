package com.fyt.tireplus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 侦察日志：订阅 0~485 全部码，值变化时落盘，用于发现温度码与设置回读码。
 * 输出：{externalFilesDir}/tireplus/recon_yyyyMMdd_HHmmss.log
 */
public class ReconLogger {

    private static final int MAX_RECENT = 50;

    private static boolean running;
    private static Writer writer;
    private static File logFile;
    private static HandlerThreadSafe worker;
    private static final int[] lastVal = new int[TireDataCenter.MAX_CODE + 1];
    private static final LinkedList<String> recent = new LinkedList<>();
    private static final SimpleDateFormat TF = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** 轻量写线程：handler 队列避免频繁触盘 */
    private static class HandlerThreadSafe extends Thread {
        final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
        volatile boolean quit;

        @Override
        public void run() {
            while (!quit) {
                try {
                    String line = queue.take();
                    synchronized (ReconLogger.class) {
                        if (writer != null) {
                            writer.write(line);
                            writer.write('\n');
                            writer.flush();
                        }
                    }
                } catch (InterruptedException ie) {
                    if (quit) return;
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static synchronized boolean isRunning() {
        return running;
    }

    static synchronized String logPath() {
        return logFile != null ? logFile.getAbsolutePath() : "(未开始)";
    }

    static synchronized void start() {
        if (running) return;
        try {
            for (int i = 0; i < lastVal.length; i++) lastVal[i] = Integer.MIN_VALUE;
            File dir = ModuleConfig.dir();
            if (dir == null) {
                TireDataCenter.log("侦察启动失败：配置目录为空");
                return;
            }
            String name = "recon_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".log";
            logFile = new File(dir, name);
            writer = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8);
            worker = new HandlerThreadSafe();
            worker.start();
            for (int code = 0; code <= TireDataCenter.MAX_CODE; code++) {
                TireDataCenter.subscribe(code);
            }
            running = true;
            enqueue("=== recon start, canbusId=" + TireDataCenter.readRaw(1000) + " ===");
            snapshot();
            TireDataCenter.log("侦察开始: " + logFile.getAbsolutePath());
        } catch (Throwable t) {
            TireDataCenter.log("侦察启动失败: " + t);
            stop();
        }
    }

    static synchronized void stop() {
        if (!running) return;
        running = false;
        enqueue("=== recon stop ===");
        try {
            Thread.sleep(120);
        } catch (InterruptedException ignored) {
        }
        if (worker != null) {
            worker.quit = true;
            worker.interrupt();
            worker = null;
        }
        try {
            if (writer != null) writer.close();
        } catch (Throwable ignored) {
        }
        writer = null;
        TireDataCenter.log("侦察结束: " + logPath());
    }

    /** 全量快照：DATA[0..485] 落盘 */
    static synchronized void snapshot() {
        StringBuilder sb = new StringBuilder("--- snapshot ---");
        enqueue(sb.toString());
        for (int code = 0; code <= TireDataCenter.MAX_CODE; code++) {
            enqueue("snap " + code + "=" + TireDataCenter.readRaw(code));
        }
        enqueue("--- snapshot end ---");
    }

    /** 数据变化入口（主线程调用，仅做内存操作） */
    static void onCode(int code, int value) {
        if (!running || code < 0 || code > TireDataCenter.MAX_CODE) return;
        if (lastVal[code] == value) return;
        lastVal[code] = value;
        String line = TF.format(new Date()) + " " + code + "=" + value + CmdConsole.annotate();
        enqueue(line);
        synchronized (recent) {
            recent.addLast(line);
            while (recent.size() > MAX_RECENT) recent.removeFirst();
        }
    }

    static String recentText() {
        StringBuilder sb = new StringBuilder();
        synchronized (recent) {
            for (String s : recent) sb.append(s).append('\n');
        }
        return sb.length() == 0 ? "(暂无变化记录)" : sb.toString();
    }

    private static void enqueue(String line) {
        HandlerThreadSafe w = worker;
        if (w != null) w.queue.offer(line);
    }
}
