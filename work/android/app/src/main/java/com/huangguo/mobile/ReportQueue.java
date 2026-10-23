package com.huangguo.mobile;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * 上报的底层队列：落盘、攒批、发送、退避。
 *
 * ── 为什么落盘而不是纯内存 ──────────────────────────────────────────
 * 播放失败最集中的时刻，往往就是接下来 App 被划掉、被系统杀掉、或者用户断网的
 * 时刻。纯内存队列在这三种情况下全白报。所以每条事件先 append 到
 * files/reports/YYYY-MM-DD.jsonl，发成功了才删文件 —— 杀进程不丢，断网等下次。
 *
 * ── 队列就是文件本身 ───────────────────────────────────────────────
 * 发送时把文件改名成 `<名字>.sending`，成功后删、失败改回来。没有「内存里的一份
 * 副本」，也就没有重发时把事件写两遍的问题。`.sending` 是进程被杀的残留证据，
 * 下次 install 时会改回来。
 *
 * ── 为什么不走 AiiHttp / 不走代理 ──────────────────────────────────
 * 源挂了、代理挂了的时候恰恰最需要上报。这条通道必须直连、不共享源的 header
 * 和路由 —— 否则它跟被观测的东西一起死。见 TODO.md 2026-10-23 那节。
 *
 * ── 签名挡不住谁 ───────────────────────────────────────────────────
 * 密钥在 APK 里，反编译就拿得到，它只挡「随手 curl 刷库」。真正的防线是服务端
 * 单 IP 每分 60 次、单次 200 条上限。别把它当鉴权。
 */
final class ReportQueue {

    private static final String TAG = "AIIvideo.Report";
    private static final String DIR = "reports";
    private static final String EXT = ".jsonl";
    private static final String SENDING = ".sending";
    private static final int MAX_BATCH = 200;      // 服务端上限
    private static final int MAX_BACKLOG = 600;    // 攒到这个数就不收新的，别把磁盘撑爆
    // 事件量级很小（一次会话几十条），攒批没有意义，反而让「打开 App 看一眼就划掉」
    // 的那批事件压在盘里。3 秒的窗口只是把连续的几条合成一个请求。
    private static final long FLUSH_AGE_MS = 3_000L;
    private static final long BACKOFF_BASE_MS = 60_000L;
    private static final long BACKOFF_MAX_MS = 30L * 60_000L;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "report");
        thread.setDaemon(true);
        return thread;
    });

    private final Context context;
    private final File dir;
    /**
     * 只有主进程负责发送。spider 是独立进程，它要是也 claim 同一批文件，两边会
     * 抢着 rename、重发、互相删 —— 谁先谁后没法保证。所以 :spider 只写不发，
     * 它产生的崩溃由主进程的 ticker 顺路带走。
     */
    private final boolean canFlush;
    private long lastFlushAt = 0L;
    private int failures = 0;
    private long nextAttemptAt = 0L;
    private int backlog = 0;

    ReportQueue(Context context, boolean canFlush) {
        this.context = context.getApplicationContext();
        this.canFlush = canFlush;
        this.dir = new File(this.context.getFilesDir(), DIR);
        if (!this.dir.isDirectory() && !this.dir.mkdirs()) {
            Log.w(TAG, "建不了上报目录，这次的事件会丢");
        }
        if (canFlush) {
            recoverSending();
        }
        this.backlog = countEvents();
        if (canFlush) {
            startTicker();
        }
    }

    /** 落盘一条。失败就算了 —— 上报坏掉不该影响任何用户路径。 */
    void enqueue(JSONObject event) {
        if (this.backlog >= MAX_BACKLOG) {
            return;
        }
        byte[] line = (event.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (this) {
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(fileOfToday(), true);
                out.write(line);
                this.backlog += 1;
            } catch (IOException error) {
                Log.w(TAG, "写队列失败：" + error.getMessage());
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        // 写完了得有人踢一脚，否则这批事件要等到下一次入队才动
        if (this.canFlush) {
            maybeFlush(false);
        }
    }

    /**
     * 兜底节拍器。上面那次 maybeFlush 只在「又有新事件入队」时发生 —— 用户发完一条就
     * 把 App 划掉的话，那条就再也不动了。15 秒一次很便宜，换的是「离线事件不留过夜」。
     */
    private void startTicker() {
        // 必须是自己的线程。放到 WORKER 上就是一个 while(true) 把单线程占死，
        // 后面 every 一个 flush 任务都排不上 —— 事件会静静地躺在盘里。
        Thread ticker = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(15_000L);
                } catch (InterruptedException stop) {
                    return;
                }
                maybeFlush(false);
            }
        }, "report-tick");
        ticker.setDaemon(true);
        ticker.start();
    }

    /** 够一批、够一分钟、或者被叫醒（切后台 / 退出前）就发一次。 */
    void maybeFlush(boolean force) {
        if (!this.canFlush) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean due = force
                || this.backlog >= MAX_BATCH
                || (this.backlog > 0 && now - this.lastFlushAt >= FLUSH_AGE_MS);
        if (!due || now < this.nextAttemptAt || this.backlog <= 0) {
            return;
        }
        this.lastFlushAt = now;
        WORKER.execute(this::flushOnWorker);
    }

    private void flushOnWorker() {
        List<File> sending = claimFiles();
        if (sending.isEmpty()) {
            return;
        }
        List<JSONObject> events = new ArrayList<JSONObject>();
        for (File file : sending) {
            events.addAll(readLines(file));
            if (events.size() >= MAX_BATCH) {
                break;
            }
        }
        if (events.isEmpty()) {
            for (File file : sending) {
                release(file);
            }
            return;
        }
        try {
            byte[] body = new JSONObject()
                    .put("app", Report.appInfo(this.context))
                    .put("device", Report.deviceId())
                    .put("events", new JSONArray(events.subList(0, Math.min(events.size(), MAX_BATCH))))
                    .toString().getBytes(StandardCharsets.UTF_8);
            int status = post(body);
            if (status >= 200 && status < 300) {
                acknowledge(sending);
                this.failures = 0;
                this.nextAttemptAt = 0L;
                return;
            }
            if (status == 429 || status >= 500) {
                // 限流和临时故障都能重试：文件改回去，等退避
                for (File file : sending) {
                    release(file);
                }
                backoff();
                return;
            }
            // 400/401/413：这批本身有问题，重发一万次也一样，丢掉并留痕
            Log.w(TAG, "服务端拒收（HTTP " + status + "），丢 " + events.size() + " 条");
            acknowledge(sending);
            this.failures = 0;
        } catch (Exception error) {
            for (File file : sending) {
                release(file);
            }
            backoff();
            Log.w(TAG, "发送失败：" + error.getMessage());
        } finally {
            maybeFlush(false);
        }
    }

    private int post(byte[] body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(Endpoints.TRACK_EVENTS).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Content-Encoding", "gzip");
            connection.setRequestProperty("X-Sig", Report.sign(body));
            connection.setRequestProperty("User-Agent", "AIIvideo/" + Report.versionName(this.context));
            byte[] gzipped = gzip(body);
            connection.setFixedLengthStreamingMode(gzipped.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(gzipped);
            } finally {
                out.close();
            }
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length / 4 + 64);
        GZIPOutputStream gzip = new GZIPOutputStream(buffer);
        try {
            gzip.write(body);
        } finally {
            gzip.close();
        }
        return buffer.toByteArray();
    }

    /** 把待发文件认领成 `.sending`，同一时刻只有一个线程拿着它们。 */
    private List<File> claimFiles() {
        List<File> claimed = new ArrayList<File>();
        synchronized (this) {
            File[] files = this.dir.listFiles();
            if (files == null) {
                return claimed;
            }
            Arrays.sort(files);                     // 文件名带日期，旧的先发
            for (File file : files) {
                if (!file.getName().endsWith(EXT)) {
                    continue;
                }
                File staging = new File(this.dir, file.getName() + SENDING);
                if (!file.renameTo(staging)) {
                    continue;
                }
                claimed.add(staging);
            }
        }
        return claimed;
    }

    /** 发成功：删文件，把条数从积压里减掉。 */
    private void acknowledge(List<File> files) {
        synchronized (this) {
            for (File file : files) {
                int count = readLines(file).size();
                if (file.delete()) {
                    this.backlog = Math.max(0, this.backlog - count);
                } else {
                    Log.w(TAG, "删不掉 " + file.getName() + "，下次会重发");
                }
            }
        }
    }

    /** 没发出去：改回普通队列文件。 */
    private void release(File file) {
        String name = file.getName();
        if (!name.endsWith(SENDING)) {
            return;
        }
        File target = new File(this.dir, name.substring(0, name.length() - SENDING.length()));
        if (!file.renameTo(target)) {
            Log.w(TAG, "改名失败 " + name);
        }
    }

    /** 进程被杀留下的 `.sending`：改回来，接着发。 */
    private void recoverSending() {
        File[] files = this.dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.getName().endsWith(SENDING)) {
                release(file);
            }
        }
    }

    private List<JSONObject> readLines(File file) {
        List<JSONObject> items = new ArrayList<JSONObject>();
        // ── 这里必须显式指定 UTF-8 ───────────────────────────────────────
        // 原来用 RandomAccessFile.readLine()，它把每个字节原样当成一个 char
        // （等于 ISO-8859-1）：「中」的 E4B8AD 回来就变成 "ä¸­" 三个字符，
        // 再按 UTF-8 写出去就是 C3A4C2B8C2AD —— 服务端存进去全是乱码，而
        // ASCII 部分完全正常，所以看起来「只有中文坏了」。
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    items.add(new JSONObject(line));
                } catch (Exception ignored) {
                    // 半行（写到一半被杀）跳过
                }
            }
        } catch (IOException error) {
            Log.w(TAG, "读队列失败：" + error.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return items;
    }

    /** 还压在盘里的条数。设置页显示用。 */
    int pending() {
        synchronized (this) {
            return this.backlog;
        }
    }

    private void backoff() {
        this.failures = Math.min(this.failures + 1, 6);
        long delay = Math.min(BACKOFF_BASE_MS << (this.failures - 1), BACKOFF_MAX_MS);
        this.nextAttemptAt = System.currentTimeMillis() + delay;
    }

    private File fileOfToday() {
        return new File(this.dir, Report.dayKey(System.currentTimeMillis()) + EXT);
    }

    private int countEvents() {
        int total = 0;
        File[] files = this.dir.listFiles();
        if (files == null) {
            return 0;
        }
        for (File file : files) {
            if (file.getName().endsWith(EXT)) {
                total += readLines(file).size();
            }
        }
        return Math.min(total, MAX_BACKLOG);
    }

    static String hmacHex(String secret, byte[] body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception error) {
            return "";
        }
    }
}
