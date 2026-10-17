package com.huangguo.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 内置播放器（libVLC）。
 *
 * ── 为什么要有这个 Activity ───────────────────────────────────────────
 * 主播放界面是 WebView 里的 &lt;video&gt;，用哪个解码器由系统 WebView 决定，页面
 * 拿不到任何旋钮。于是同一部片在 A 手机上能放、B 手机上 MediaError 4
 * （SRC_NOT_SUPPORTED）—— 差别只有设备有没有那个硬解、WebView 版本里带没带
 * 那个解码器。软件解码这条路在 WebView 里根本不存在。
 *
 * libVLC 自带完整的 FFmpeg，HEVC、10bit、AV1、AC3/E-AC3 一律软解，和设备的
 * MediaCodec 能力脱钩。所以「别的手机放不了」这个问题只能在原生播放器里解决，
 * 而不是抛给用户去装 MX Player。
 *
 * ── 和页面的关系 ─────────────────────────────────────────────────────
 * 页面通过 `__player:native?payload=<json>` 把它拉起来；退出时这里把播放位置
 * 写进 SharedPreferences（hg_player/last），页面回到前台后读 `__player:last`
 * 拿回去，走原来那套 remember()/report() —— WebView 和原生两边的进度因此是
 * 同一份数据，不各存各的。
 *
 * 这也是**不**用 startActivityForResult 的原因：MainActivity 的
 * onActivityResult 在 smali 里，加一段得动 dispatch 区，而进度只要落到
 * prefs，页面一回到前台就能取，多一条无谓的链路没有必要。
 */
public class VlcPlayerActivity extends Activity implements MediaPlayer.EventListener {

    /** 页面传进来的那份 JSON。字段见 JS 侧 nativePayload()。 */
    public static final String EXTRA_PAYLOAD = "payload";

    /** 进度落盘。和 Java 侧的源配置分开放 —— 那是用户的设置，这是一次播放的现场。 */
    private static final String PREFS = "hg_player";
    private static final String KEY_LAST = "last";

    private static final long HIDE_CONTROLS_MS = 3200;
    private static final long SEEK_SPAN_MS = 180000;   // 横划满屏 = 3 分钟
    private static final float TOUCH_SLOP_DP = 14f;

    private int surfaceWidth;
    private int surfaceHeight;

    private LibVLC libVLC;
    private MediaPlayer player;
    private SurfaceView surface;

    private final JSONObject payload = new JSONObject();

    private long durationMs = 0;

    // 起播耗时打点。tag 固定成 HG.Vlc，`adb logcat -s HG.Vlc` 就能读到。
    // 这几个数是决定「能不能把整条流搬进原生」的唯一硬指标，所以写进产品里
    // 留着，而不是临时加一行测完就删。
    private long tCreate;
    private long tVlcReady;
    private long tPlay;
    private boolean tLogged;
    private long startedAt = 0;      // 页面要求从哪开始（ms）
    private boolean ended = false;
    private boolean errorShown = false;
    private String errorText = "";
    private long savedPosition = 0;
    private boolean hwDecode = true;

    private FrameLayout root;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private TextView titleView;
    private TextView metaView;
    private TextView clockView;
    private TextView hintView;
    private TextView errorView;
    private IconButton playButton;
    private TextView speedButton;
    private SeekBar seekBar;
    private LinearLayout speedPanel;
    private float feedRate = 1f;
    private static final float[] RATE_CHOICES = {2f, 1.5f, 1.25f, 1f, 0.75f, 0.5f};
    private ProgressBar spinner;

    // ── mini-feed（整部剧上下滑）──────────────────────────────────────
    //
    // payload 里带 eps（全部集数）+ index（从第几集进来）时走这一套：
    // 竖向 RecyclerView + PagerSnapHelper 当翻页器，两个 MediaPlayer 固定身份
    // 轮转（A/B 各认一页），地址按需经 MainActivity 的 WebView 向页面要 ——
    // 解密、wasm、多线路那套逻辑全在页面里，原生不重复实现。
    private boolean feedMode;
    private int[] eps = new int[0];
    private int curPos;
    private RecyclerView pager;
    private LinearLayoutManager pagerLayout;
    private FeedAdapter feedAdapter;
    private MediaPlayer playerA, playerB;
    private int pageA = -1, pageB = -1;
    private MediaPlayer activeFeedPlayer;
    /** 退到后台的那一刻是不是正在播。回来时只在这条为真才自动续播。 */
    private boolean feedWasPlaying;
    private final Map<MediaPlayer, String> playingUrl = new HashMap<MediaPlayer, String>();
    private final Map<Integer, String> urlCache = new HashMap<Integer, String>();
    private final Set<Integer> resolving = new HashSet<Integer>();
    private final Set<Integer> failed = new HashSet<Integer>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideControls = new Runnable() {
        @Override public void run() {
            MediaPlayer current = feedMode ? activeFeedPlayer : player;
            if (current != null && current.isPlaying()) setControlsVisible(false);
        }
    };

    // ── 起播入口 ─────────────────────────────────────────────────────

    /** 页面桥调这里。context 通常是 MainActivity。 */
    public static void start(Context context, JSONObject config) {
        Intent intent = new Intent(context, VlcPlayerActivity.class);
        intent.putExtra(EXTRA_PAYLOAD, config.toString());
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** 只探测一次：loadLibraries 会把 45MB 的 libvlc.so 映射进来，别反复试。 */
    private static Boolean nativeReady;

    /**
     * 这台设备上到底能不能用内置播放器。
     *
     * 判据是「试着加载一次」：LibVLC.loadLibraries() 在 so 缺失/ABI 不匹配时抛
     * UnsatisfiedLinkError，拿到就算能用。
     *
     * **不要改成查 nativeLibraryDir 里有没有 libvlc.so。** 那个目录的内容取决于
     * manifest 的 android:extractNativeLibs，两种取值都出现过：
     *
     *   "false"（1.0.23 及以前）so 留在 APK 里，链接器直接 mmap，目录永远是空的
     *   "true" （1.0.24 起）   安装时解压，目录里才有实体文件
     *
     * 也就是说「目录非空」只在当前配置下成立，换个配置就静默判错（表现为播放器
     * 起不来、页面退回网页播放器）。试加载在两种配置下都对。
     *
     * 为什么从 false 换成 true：下载体积 56.0 → 26.2 MB（libvlc.so 能 deflate 了），
     * 代价是多占 23MB 磁盘。真机实测冷启动无可测差异，播放路径完全等价。
     *
     * 结果缓存住，理由见上面那句 45MB。
     */
    public static boolean available(Context context) {
        if (nativeReady != null) return nativeReady.booleanValue();
        boolean ok = false;
        try {
            ok = supportsAbi();
            if (ok) {
                LibVLC.loadLibraries();
            }
        } catch (Throwable e) {
            ok = false;
        }
        nativeReady = Boolean.valueOf(ok);
        return ok;
    }

    private static boolean supportsAbi() {
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis == null) return false;
            for (int i = 0; i < abis.length; i++) {
                if ("arm64-v8a".equals(abis[i])) return true;
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    public static String abi() {
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            return abis != null && abis.length > 0 ? abis[0] : "";
        } catch (Throwable e) {
            return "";
        }
    }

    /** 页面回到前台时读走一次，读完就清 —— 同一条记录不能被算了两次。 */
    public static JSONObject takeLastSession(Context context) {
        try {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_LAST, "");
            if (raw == null || raw.length() == 0) return null;
            prefs.edit().remove(KEY_LAST).apply();
            return new JSONObject(raw);
        } catch (Throwable e) {
            return null;
        }
    }

    // ── 生命周期 ─────────────────────────────────────────────────────

    @Override protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        tCreate = System.currentTimeMillis();
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        try {
            String raw = getIntent().getStringExtra(EXTRA_PAYLOAD);
            if (raw != null) {
                JSONObject parsed = new JSONObject(raw);
                java.util.Iterator<String> keys = parsed.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    payload.put(key, parsed.opt(key));
                }
            }
        } catch (Throwable ignored) {}

        startedAt = payload.optLong("start", 0L);
        savedPosition = startedAt;

        // mini-feed：eps 至少要两集才值得起翻页器，单集走老路。
        org.json.JSONArray epsRaw = payload.optJSONArray("eps");
        if (epsRaw != null && epsRaw.length() > 1) {
            feedMode = true;
            eps = new int[epsRaw.length()];
            for (int i = 0; i < epsRaw.length(); i++) eps[i] = epsRaw.optInt(i, i + 1);
            curPos = Math.max(0, Math.min(eps.length - 1, payload.optInt("index", 0)));
        }

        buildUi();
        if (!feedMode) {
            surface.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) {}
                @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    surfaceWidth = width;
                    surfaceHeight = height;
                    applyWindowSize();
                }
                @Override public void surfaceDestroyed(SurfaceHolder holder) {
                    surfaceWidth = 0;
                    surfaceHeight = 0;
                }
            });
        }
        applyImmersive();
        startPlayback();
    }

    private void startPlayback() {
        if (feedMode) {
            if (!available(this)) {
                showError("这个安装包没有带内置播放器（只带了 arm64-v8a）。可以在设置里换回网页播放器。");
                return;
            }
            try {
                initFeedVlc();
            } catch (Throwable e) {
                showError("内置播放器起不来：" + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " " + e.getMessage()));
                return;
            }
            activate(curPos);
            return;
        }
        final String url = payload.optString("url", "");
        if (url.length() == 0) {
            showError("没有拿到播放地址");
            return;
        }
        if (!available(this)) {
            showError("这个安装包没有带内置播放器（只带了 arm64-v8a）。可以在设置里换回网页播放器。");
            return;
        }
        try {
            startVlc(url);
        } catch (Throwable e) {
            // libVLC 在加载 so 或建实例时抛的都是 native 的错，能读到的只有消息。
            showError("内置播放器起不来：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
        }
    }

    // ── 媒体流走内置核心 ────────────────────────────────────────────
    //
    // 这条路走的是「换地址」，不是「加选项」。原因见下面 relayUrl 的注释：
    // libVLC 的 media option 在这套 Android 构建上对 HTTP 取流全部无效，
    // 实测过 :socks / :http-proxy / 全局 --socks 三种，SOCKS 端口始终零连接。
    //
    // 所以 VLC 拿到的是一个本机 HTTP 地址（VlcRelay），真正取流的是 OkHttp，
    // 代理、TLS、Range 全在 OkHttp 那边，和 AiiHttp 走同一套 effectiveProxy()。
    //
    // 已知限制：核心重启会换端口，此时 VLC 正在播的那条流会断。不做热切换 ——
    // VLC 出错重试会重走 startVlc/switchTo，那时 relayUrl() 读到新端口能自己恢复。
    // 另外 VlcRelay 自己的监听端口一旦启动就不变，所以换的核心端口不影响它。

    /**
     * 把播放地址换成中继地址。
     *
     * **这是让 VLC 走代理的唯一可行办法。** 它的 media option（:socks / :http-proxy）
     * 实测全都不生效 —— 那两个选项挂在 Live555 的 RTSP 栈上，对 HTTP 取流没用。
     * 所以改成「换地址」：给 VLC 一个本机 HTTP 地址，背后是走代理的 OkHttp。
     *
     * 没有内置核心（或中继起不来）时原样返回，行为等同于直连 —— 和以前一样。
     */
    private String relayUrl(String url) {
        if (url == null || url.length() == 0) return url;
        // 判据（核心在不在跑、要不要绕）全在 VlcRelay.wrap 里，这里不再重复判断 ——
        // 两处各判一次，早晚会有一处改漏，然后表现成「核心开着但视频直连」。
        String wrapped = VlcRelay.wrap(url);
        return wrapped == null ? url : wrapped;
    }

    private void startVlc(String url) {
        ArrayList<String> options = new ArrayList<String>();
        options.add("--no-video-title-show");
        options.add("--audio-time-stretch");
        options.add("--network-caching=" + payload.optInt("cache", 1500));

        String decode = payload.optString("decode", "auto");
        hwDecode = !"sw".equals(decode);
        if (!hwDecode) options.add("--avcodec-hw=none");

        try {
            libVLC = new LibVLC(this, options);
        } catch (Throwable e) {
            // 参数被 libvlc_new 拒掉时会走到这里（它只回 0，异常消息里没有原因）。
            // 退到默认参数再试一次：宁可少几个偏好项，也别让用户对着黑屏。
            android.util.Log.w("HG.Vlc", "带参数创建失败：" + e + "，改用默认参数重试");
            libVLC = new LibVLC(this);
        }
        tVlcReady = System.currentTimeMillis();
        player = new MediaPlayer(libVLC);

        IVLCVout vout = player.getVLCVout();
        vout.setVideoView(surface);
        vout.attachViews();
        // 这一步不能省。不设窗口尺寸时 android_window 会把画面按**视频原始分辨率**
        // （比如 320×192）铺在左下角 —— 表现是「有声音、画面缩在角落一小块」。
        applyWindowSize();

        player.setEventListener(this);

        Media media = new Media(libVLC, Uri.parse(relayUrl(url)));
        // 有些站的 CDN 会校验 Referer/UA，缺了就只回一小段或直接 403。页面那边
        // 拿不到这两个头（同样是 WebView 的限制），在这里补上。
        String referer = payload.optString("referer", "");
        String agent = payload.optString("ua", "");
        if (referer.length() > 0) media.addOption(":http-referrer=" + referer);
        if (agent.length() > 0) media.addOption(":http-user-agent=" + agent);
        media.setHWDecoderEnabled(hwDecode, false);
        player.setMedia(media);
        media.release();

        float rate = (float) payload.optDouble("rate", 1.0);
        if (rate > 0.1f && Math.abs(rate - 1.0f) > 0.01f) player.setRate(rate);

        tPlay = System.currentTimeMillis();
        player.play();
        android.util.Log.i("HG.Vlc", "play() 提交：libVLC " + (tVlcReady - tCreate)
                + "ms，建播放器到提交 " + (tPlay - tVlcReady) + "ms");
        if (startedAt > 2000) {
            // play() 之后立刻 setTime 会被丢掉（还没有 demuxer），所以等长度的
            // 第一个事件再来落位置 —— 见 onEvent 里的 LengthChanged。
        }

        metaView.setText((hwDecode ? "硬解优先" : "软解") + " · " + abi());
        ui.postDelayed(hideControls, HIDE_CONTROLS_MS);
    }

    /**
     * 把 SurfaceView 的像素尺寸交给 vout。
     *
     * IVLCVout.setWindowSize 是「渲染区域占多大」的唯一来源，VLC 不会自己去问
     * View 有多大：不设的话它按视频原始分辨率铺在左下角。
     */
    private void applyWindowSize() {
        // mini-feed：认中心页的 TextureView。旋转时 Activity 不重建
        //（configChanges 里声明了），TextureView 尺寸变化会走
        // onSurfaceTextureSizeChanged → attachVout，这里兜底转屏瞬间。
        if (feedMode) {
            PageView pv = feedAdapter == null ? null : feedAdapter.pageAt(curPos);
            if (pv != null && pv.getWidth() > 0 && pv.getHeight() > 0) {
                setWindowSizeFor(playerFor(curPos), pv.getWidth(), pv.getHeight());
            }
            return;
        }
        try {
            if (player == null) return;
            int width = surfaceWidth > 0 ? surfaceWidth : surface.getWidth();
            int height = surfaceHeight > 0 ? surfaceHeight : surface.getHeight();
            if (width <= 0 || height <= 0) return;
            IVLCVout vout = player.getVLCVout();
            if (vout != null) vout.setWindowSize(width, height);
        } catch (Throwable ignored) {}
    }

    /** 窗口尺寸改完必须 updateVideoSurfaces，否则 vout 还按旧尺寸铺画面。 */
    private void setWindowSizeFor(MediaPlayer mp, int width, int height) {
        if (mp == null || width <= 0 || height <= 0) return;
        try {
            IVLCVout vout = mp.getVLCVout();
            if (vout == null) return;
            vout.setWindowSize(width, height);
            mp.updateVideoSurfaces();
        } catch (Throwable ignored) {}
    }

    /**
     * 退到后台就闭嘴。
     *
     * 上一版只 pause 了单集模式的 player，feed 模式的两个 player 没人管 ——
     * 按 home 键、切到别的 app，画面走了声音还在放。onStop 再兜一次，
     * 因为有些 ROM 的 onPause 与 onStop 中间隔了几百毫秒，够放好几秒声音。
     */
    @Override protected void onPause() {
        if (feedMode) {
            pauseFeedForBackground();
            persistFeedProgress();
        } else {
            persist(false);
            if (player != null && player.isPlaying()) player.pause();
        }
        super.onPause();
    }

    @Override protected void onStop() {
        if (feedMode) pauseFeedForBackground();
        else if (player != null && player.isPlaying()) player.pause();
        super.onStop();
    }

    @Override protected void onStart() {
        super.onStart();
        // 只恢复「是被后台打断的播放」。用户自己按暂停退出去，回来还该是暂停。
        if (feedMode && feedWasPlaying) {
            feedWasPlaying = false;
            MediaPlayer mp = activeFeedPlayer;
            if (mp != null) {
                try { mp.play(); } catch (Throwable ignored) {}
                // 后台期间 TextureView 的 buffer 可能被系统回收了，回来重挂一次
                // 才是画面，不然是有声音的黑屏。
                ui.postDelayed(new Runnable() {
                    @Override public void run() { attachVout(curPos, true); }
                }, 150);
            }
        }
    }

    /** 两个 player 全静下来；记录「退后台前是不是在播」。 */
    private void pauseFeedForBackground() {
        MediaPlayer[] all = { playerA, playerB };
        for (int i = 0; i < all.length; i++) {
            MediaPlayer mp = all[i];
            if (mp == null) continue;
            try {
                if (mp.isPlaying()) {
                    if (mp == activeFeedPlayer) feedWasPlaying = true;
                    mp.pause();
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override protected void onDestroy() {
        if (feedMode) {
            // 顺序要紧：先落盘再释放。反过来 getTime() 全是 0。
            persistFeedProgress();
            releaseFeedPlayers();
        } else {
            persist(ended);
        }
        ui.removeCallbacksAndMessages(null);
        releaseOne(player);
        player = null;
        try { if (libVLC != null) { libVLC.release(); libVLC = null; } } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        ui.post(new Runnable() {
            @Override public void run() { applyWindowSize(); }
        });
        // 布局动画第二拍再兜一次，防止单靠 TextureView 回调时序不齐。
        ui.postDelayed(new Runnable() {
            @Override public void run() { applyWindowSize(); }
        }, 320);
    }

    /**
     * 同一个 Activity 被再次拉起（比如用户在播放页又点了一次「内置」）。
     * 默认行为是把 intent 丢掉，画面还停在上一条流上 —— 这里重新解析参数、重开一次，
     * 免得用户以为按钮没反应。
     */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        persist(ended);
        try {
            String raw = intent.getStringExtra(EXTRA_PAYLOAD);
            if (raw == null) return;
            JSONObject parsed = new JSONObject(raw);
            java.util.Iterator<String> keys = parsed.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                payload.put(key, parsed.opt(key));
            }
        } catch (Throwable ignored) { return; }

        ended = false;
        tCreate = System.currentTimeMillis();
        tLogged = false;
        android.util.Log.i("HG.Vlc", "换集：重开 media");
        startedAt = payload.optLong("start", 0L);
        savedPosition = startedAt;
        durationMs = 0;
        if (feedMode) {
            // feed 用的是一对 player，closePlayer 只管单集那个 —— 不在这里收掉
            // 旧的一对，再 initFeedVlc 就是两个实例叠着放，声音会双重。
            releaseFeedPlayers();
            urlCache.clear();
            playingUrl.clear();
            resolving.clear();
            failed.clear();
        }
        closePlayer();
        startPlayback();
    }

    private void closePlayer() {
        releaseOne(player);
        player = null;
        try { if (libVLC != null) { libVLC.release(); libVLC = null; } } catch (Throwable ignored) {}
    }

    @Override public void onBackPressed() {
        if (feedMode) persistFeedProgress();
        else persist(ended);
        super.onBackPressed();
    }

    /** 上一次落盘的时间。onPause 和 onDestroy 都会写，别让后者把前者的记录再写一遍。 */
    private long lastPersistAt;

    /** 把这一次播放的现场落到 prefs。finished=true 表示播完了。 */
    private void persist(boolean finished) {
        // feed 模式有自己那份（persistFeedProgress）：集数、当前集地址都不一样。
        // 这里再写一条会把刚写好的覆盖成「payload 里 index 那一集 + 位置 0」，
        // 页面收回去就成了「回滑到第一集、从头开始」。
        if (feedMode) return;
        // 退出时 onPause → onDestroy 会连着来两次。两次都写的话，页面收回第一条之后
        // prefs 里还会剩一条一模一样的 —— 下次回到前台它又被收一遍，把更新的进度盖回旧的。
        long now = System.currentTimeMillis();
        if (!finished && now - lastPersistAt < 1500) return;
        lastPersistAt = now;
        try {
            long position = savedPosition;
            if (player != null) {
                long live = player.getTime();
                if (live > 0) position = live;
            }
            if (durationMs <= 0 && player != null) durationMs = player.getLength();

            JSONObject record = new JSONObject();
            record.put("url", payload.optString("url", ""));
            record.put("id", payload.optString("id", ""));
            record.put("episode", payload.optInt("episode", 1));
            record.put("title", payload.optString("title", ""));
            record.put("cover", payload.optString("cover", ""));
            record.put("source", payload.optString("source", ""));
            record.put("position", Math.max(0, position));
            record.put("duration", Math.max(0, durationMs));
            record.put("finished", finished || ended);
            record.put("error", errorText);
            record.put("at", System.currentTimeMillis());
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST, record.toString()).apply();
        } catch (Throwable ignored) {}
    }

    // ── 界面 ─────────────────────────────────────────────────────────

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int ui(int color) {
        return color;
    }

    private android.graphics.drawable.GradientDrawable rounded(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private View scrim(boolean fromTop) {
        View view = new View(this);
        view.setBackground(new android.graphics.drawable.GradientDrawable(
                fromTop ? android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                        : android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xD9000000, 0x00000000}));
        return view;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (feedMode) {
            pagerLayout = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
            pager = new RecyclerView(this);
            pager.setLayoutManager(pagerLayout);
            pager.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            new PagerSnapHelper().attachToRecyclerView(pager);
            feedAdapter = new FeedAdapter();
            pager.setAdapter(feedAdapter);
            pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrollStateChanged(RecyclerView rv, int newState) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        int pos = centeredPosition();
                        if (pos >= 0 && pos != curPos) activate(pos);
                    }
                }
            });
            pager.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
                private float downX, downY;
                private long downAt;
                private boolean seekMode;

                @Override public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        downX = e.getX(); downY = e.getY(); downAt = System.currentTimeMillis();
                        seekMode = false;
                        return false;
                    }
                    // 单击（没挪、够快）= 出/收控件。RecyclerView 自己会吞 DOWN，
                    // OnClickListener 收不到，所以 tap 的识别只能放在这里。
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && !seekMode
                            && System.currentTimeMillis() - downAt < 300) {
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (Math.abs(dx) < dp(TOUCH_SLOP_DP) && Math.abs(dy) < dp(TOUCH_SLOP_DP)) {
                            toggleControls();
                        }
                    }
                    if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        // 横向够长就归 seek；竖向还给翻页器
                        if (!seekMode && Math.abs(dx) > dp(TOUCH_SLOP_DP) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                            seekMode = true;
                        }
                        if (seekMode) return true;
                    }
                    return false;
                }

                @Override public void onTouchEvent(RecyclerView rv, MotionEvent e) {
                    if (e.getActionMasked() == MotionEvent.ACTION_MOVE && seekMode) {
                        float dx = e.getX() - downX;
                        long target = feedTimeOf(curPos) + (long) (dx / Math.max(1, rv.getWidth()) * SEEK_SPAN_MS);
                        long dur = feedDurationOf(curPos);
                        if (dur > 0) target = Math.max(0, Math.min(dur, target));
                        showHint((target >= feedTimeOf(curPos) ? "快进 " : "回退 ")
                                + Math.abs((target - feedTimeOf(curPos)) / 1000) + " 秒 · "
                                + fmt(target) + (dur > 0 ? " / " + fmt(dur) : ""));
                        gestureTarget = target;
                        return;
                    }
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && seekMode) {
                        MediaPlayer mp = activeFeedPlayer;
                        if (mp != null && gestureTarget >= 0) mp.setTime(gestureTarget);
                        hideHintSoon();
                    }
                }

                @Override public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
            });
            root.addView(pager);
        } else {
            surface = new SurfaceView(this);
            surface.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(surface);
        }

        // 上下两条渐变压暗。控件浮在渐变上，不再是整条黑块。
        View topScrim = scrim(true);
        topScrim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(130), Gravity.TOP));
        root.addView(topScrim);
        View bottomScrim = scrim(false);
        bottomScrim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170), Gravity.BOTTOM));
        root.addView(bottomScrim);

        // 错误时不黑屏 —— 黑着屏最说不清是「在缓冲」还是「已经死了」。
        errorView = new TextView(this);
        errorView.setTextColor(0xFFE9E9EC);
        errorView.setTextSize(14f);
        errorView.setGravity(Gravity.CENTER);
        errorView.setBackground(rounded(0xE6171720, 18));
        errorView.setPadding(dp(26), dp(20), dp(26), dp(20));
        errorView.setVisibility(View.GONE);
        FrameLayout.LayoutParams errorParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        errorParams.setMargins(dp(32), 0, dp(32), 0);
        root.addView(errorView, errorParams);

        spinner = new ProgressBar(this);
        FrameLayout.LayoutParams spinParams = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER);
        spinner.setLayoutParams(spinParams);
        root.addView(spinner);

        hintView = new TextView(this);
        hintView.setTextColor(0xFFFFFFFF);
        hintView.setTextSize(14f);
        hintView.setGravity(Gravity.CENTER);
        hintView.setBackground(rounded(0xB3000000, 20));
        hintView.setPadding(dp(20), dp(11), dp(20), dp(11));
        hintView.setVisibility(View.GONE);
        hintView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(hintView);

        // 顶栏：返回（圆钮）+ 标题 + 编码信息。整条透明，靠渐变压暗。
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(14), dp(10), dp(18), dp(10));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(19f);
        back.setTextColor(0xFFFFFFFF);
        back.setGravity(Gravity.CENTER);
        back.setBackground(rounded(0x33000000, 24));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        topBar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(dp(14), 0, 0, 0);
        titles.setLayoutParams(titleParams);
        titleView = new TextView(this);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(15.5f);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setShadowLayer(dp(2), 0, dp(1), 0x99000000);
        String title = payload.optString("title", "");
        if (title.length() == 0) title = "播放中";
        titleView.setText(title);
        titles.addView(titleView);
        metaView = new TextView(this);
        metaView.setTextColor(0xB3FFFFFF);
        metaView.setTextSize(11.5f);
        metaView.setSingleLine(true);
        titles.addView(metaView);
        topBar.addView(titles);

        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // 底栏：细进度条 + 时间 + 控制按钮
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(18), dp(8), dp(18), dp(20));

        seekBar = new SeekBar(this);
        styleSeekBar(seekBar);
        bottomBar.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(4), dp(2), 0);

        clockView = new TextView(this);
        clockView.setTextColor(0xFFFFFFFF);
        clockView.setTextSize(12f);
        clockView.setText("00:00 / --:--");
        row.addView(clockView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 中间一组要真正居中：左右各放一个等重占位
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER_VERTICAL);
        group.addView(makeIconButton(IconButton.REW, new View.OnClickListener() {
            @Override public void onClick(View v) { seekBy(-10000); }
        }));
        playButton = makeIconButton(IconButton.PAUSE, new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        group.addView(playButton);
        group.addView(makeIconButton(IconButton.FWD, new View.OnClickListener() {
            @Override public void onClick(View v) { seekBy(10000); }
        }));
        row.addView(group);

        speedButton = makeButton("1×", new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSpeedPanel(); }
        });
        speedButton.setGravity(Gravity.CENTER);
        // 倍速是胶囊，不能被 weight 拉宽 —— 外面包一个等重容器、靠右放
        android.widget.FrameLayout speedSlot = new android.widget.FrameLayout(this);
        speedSlot.addView(speedButton, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));
        row.addView(speedSlot, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bottomBar.addView(row);
        root.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // 倍速面板：挂在底栏上方靠右，点击选项即生效
        speedPanel = new LinearLayout(this);
        speedPanel.setOrientation(LinearLayout.VERTICAL);
        speedPanel.setBackground(rounded(0xE6202128, 14));
        speedPanel.setPadding(dp(6), dp(6), dp(6), dp(6));
        speedPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                dp(120), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.BOTTOM);
        panelParams.setMargins(0, 0, dp(20), dp(96));
        root.addView(speedPanel, panelParams);

        // 进度条拖动：拖动期间不跟着播放头跳，松手才 seek。
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || durationMs <= 0) return;
                clockView.setText(fmt(durationMs * progress / 1000) + " / " + fmt(durationMs));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {
                ui.removeCallbacks(hideControls);
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                MediaPlayer target = feedMode ? activeFeedPlayer : player;
                if (target != null && durationMs > 0) {
                    target.setTime(durationMs * bar.getProgress() / 1000);
                }
                ui.postDelayed(hideControls, HIDE_CONTROLS_MS);
            }
        });

        if (!feedMode) {
            root.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent event) {
                    return onRootTouch(event);
                }
            });
        }

        setContentView(root);
        // 播放中把播放按钮渲染成暂停，暂停时反之。
        setControlsVisible(true);
    }

    /** 播放控制键：图标用 Canvas 画，不走字符 —— ⏪⏸ 那类字符在小米上是彩色 emoji。 */
    private static class IconButton extends View {
        static final int PAUSE = 0, PLAY = 1, REW = 2, FWD = 3;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int icon = PLAY;

        IconButton(Context context) { super(context); }

        void setIcon(int which) { icon = which; invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float u = getWidth() / 12f;
            paint.setColor(0xFFFFFFFF);
            if (icon == PAUSE) {
                float w = u;
                canvas.drawRect(cx - u * 1.5f - w / 2, cy - u * 2f, cx - u * 1.5f + w / 2, cy + u * 2f, paint);
                canvas.drawRect(cx + u * 1.5f - w / 2, cy - u * 2f, cx + u * 1.5f + w / 2, cy + u * 2f, paint);
            } else if (icon == PLAY) {
                Path path = new Path();
                path.moveTo(cx - u * 1.2f, cy - u * 2f);
                path.lineTo(cx + u * 1.8f, cy);
                path.lineTo(cx - u * 1.2f, cy + u * 2f);
                path.close();
                canvas.drawPath(path, paint);
            } else {
                boolean fwd = icon == FWD;
                for (int i = 0; i < 2; i++) {
                    float off = (fwd ? 1 : -1) * (i == 0 ? u * 0.6f : u * 2.6f);
                    Path path = new Path();
                    if (fwd) {
                        path.moveTo(cx + off - u * 1.1f, cy - u * 1.6f);
                        path.lineTo(cx + off + u * 0.9f, cy);
                        path.lineTo(cx + off - u * 1.1f, cy + u * 1.6f);
                    } else {
                        path.moveTo(cx + off + u * 1.1f, cy - u * 1.6f);
                        path.lineTo(cx + off - u * 0.9f, cy);
                        path.lineTo(cx + off + u * 1.1f, cy + u * 1.6f);
                    }
                    path.close();
                    canvas.drawPath(path, paint);
                }
            }
        }
    }

    private IconButton makeIconButton(int icon, View.OnClickListener click) {
        IconButton button = new IconButton(this);
        button.setIcon(icon);
        button.setContentDescription(icon == IconButton.PAUSE ? "暂停" : icon == IconButton.PLAY ? "播放"
                : icon == IconButton.REW ? "快退" : "快进");
        button.setBackground(rounded(0x30FFFFFF, 30));
        button.setOnClickListener(click);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(56), dp(56));
        params.setMargins(dp(6), 0, dp(6), 0);
        button.setLayoutParams(params);
        return button;
    }

    /** 细线进度条：4dp 圆角轨道 + 白色已播 + 14dp 圆点拇指，全代码绘制。 */
    private void styleSeekBar(SeekBar bar) {
        int inset = dp(11);   // 轨道 4dp，撑在 26dp 高的条里
        bar.setMax(1000);   // 上轮重构时丢了这行：默认满量 100，进度全错
        android.graphics.drawable.GradientDrawable trackBg = new android.graphics.drawable.GradientDrawable();
        trackBg.setColor(0x66FFFFFF);
        trackBg.setCornerRadius(dp(2));

        android.graphics.drawable.GradientDrawable trackPro = new android.graphics.drawable.GradientDrawable();
        trackPro.setColor(0xFFFFFFFF);
        trackPro.setCornerRadius(dp(2));

        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{trackBg, trackPro});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        layers.setLayerInset(0, 0, inset, 0, inset);
        layers.setLayerInset(1, 0, inset, 0, inset);
        bar.setProgressDrawable(layers);

        android.graphics.drawable.GradientDrawable thumb = new android.graphics.drawable.GradientDrawable();
        thumb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumb.setColor(0xFFFFFFFF);
        thumb.setSize(dp(14), dp(14));
        bar.setThumb(thumb);
        bar.setThumbOffset(0);
        bar.setPadding(0, dp(6), 0, dp(6));
    }

    private TextView makeButton(String text, View.OnClickListener click) {
        return makeButton(text, click, false);
    }

    private TextView makeButton(String text, View.OnClickListener click, boolean primary) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(primary ? 15f : 12.5f);
        button.setTextColor(0xFFFFFFFF);
        button.setGravity(Gravity.CENTER);
        button.setBackground(primary ? rounded(0x30FFFFFF, 24) : rounded(0x1FFFFFFF, 19));
        button.setPadding(primary ? dp(10) : dp(15), dp(10), primary ? dp(10) : dp(15), dp(10));
        button.setOnClickListener(click);
        button.setPadding(primary ? dp(8) : dp(14), dp(9), primary ? dp(8) : dp(14), dp(9));
        button.setLayoutParams(new LinearLayout.LayoutParams(
                primary ? dp(48) : ViewGroup.LayoutParams.WRAP_CONTENT,
                primary ? dp(48) : ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    /** 沉浸式全屏。上滑调出系统栏是系统行为，不拦。 */
    private void applyImmersive() {
        try {
            Window window = getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.hide(android.view.WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Throwable ignored) {}
    }

    private void setControlsVisible(boolean visible) {
        animateBar(topBar, visible);
        animateBar(bottomBar, visible);
        MediaPlayer current = feedMode ? activeFeedPlayer : player;
        if (visible) {
            ui.removeCallbacks(hideControls);
            if (current != null && current.isPlaying()) ui.postDelayed(hideControls, HIDE_CONTROLS_MS);
        } else {
            ui.removeCallbacks(hideControls);
        }
    }

    private void animateBar(View bar, boolean visible) {
        float target = visible ? 1f : 0f;
        bar.animate().alpha(target).setDuration(180).start();
        bar.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        if (!visible && speedPanel != null) speedPanel.setVisibility(View.GONE);
    }

    private void toggleControls() {
        setControlsVisible(bottomBar.getVisibility() != View.VISIBLE);
    }

    // ── 手势 ─────────────────────────────────────────────────────────

    private float downX, downY;
    private long downAt, gestureStartMs;
    private int gesture = 0;              // 0 未定 / 1 拖进度 / 2 亮度 / 3 音量
    private float downBrightness;
    private int downVolume, maxVolume;

    private boolean onRootTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX(); downY = event.getY(); downAt = System.currentTimeMillis();
                gesture = 0;
                try { downBrightness = getWindow().getAttributes().screenBrightness; } catch (Throwable ignored) {}
                if (downBrightness < 0) downBrightness = 0.5f;
                try {
                    AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    downVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                } catch (Throwable ignored) { maxVolume = 0; }
                if (player != null) gestureStartMs = player.getTime();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (gesture == 0) {
                    if (Math.abs(dx) < dp(TOUCH_SLOP_DP) && Math.abs(dy) < dp(TOUCH_SLOP_DP)) return true;
                    if (Math.abs(dx) > Math.abs(dy)) gesture = 1;
                    else gesture = event.getX() < root.getWidth() / 2f ? 2 : 3;
                    if (gesture == 1) ui.removeCallbacks(hideControls);
                }
                if (gesture == 1) {
                    long target = gestureStartMs + (long) (dx / Math.max(1, root.getWidth()) * SEEK_SPAN_MS);
                    if (durationMs > 0) target = Math.max(0, Math.min(durationMs, target));
                    gestureTarget = Math.max(0, target);
                    long delta = (gestureTarget - gestureStartMs) / 1000;
                    showHint((delta >= 0 ? "快进 " : "回退 ") + Math.abs(delta) + " 秒 · "
                            + fmt(gestureTarget) + (durationMs > 0 ? " / " + fmt(durationMs) : ""));
                } else if (gesture == 2) {
                    float value = downBrightness - dy / Math.max(1, root.getHeight());
                    value = Math.max(0.02f, Math.min(1f, value));
                    WindowManager.LayoutParams attrs = getWindow().getAttributes();
                    attrs.screenBrightness = value;
                    getWindow().setAttributes(attrs);
                    showHint("亮度 " + Math.round(value * 100) + "%");
                } else if (gesture == 3 && maxVolume > 0) {
                    float ratio = downVolume - dy / Math.max(1f, root.getHeight()) * maxVolume;
                    int value = Math.max(0, Math.min(maxVolume, Math.round(ratio)));
                    try {
                        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
                    } catch (Throwable ignored) {}
                    showHint("音量 " + Math.round(value * 100f / maxVolume) + "%");
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                long spent = System.currentTimeMillis() - downAt;
                if (gesture == 1) {
                    if (player != null) player.setTime(gestureTarget);
                    ui.postDelayed(hideControls, HIDE_CONTROLS_MS);
                } else if (gesture == 0 && spent < 260) {
                    toggleControls();
                }
                hideHintSoon();
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                hideHintSoon();
                return true;
            default:
                return false;
        }
    }

    private long gestureTarget = 0;

    private void showHint(String text) {
        hintView.setText(text);
        hintView.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideHint);
    }

    private void hideHintSoon() {
        ui.removeCallbacks(hideHint);
        ui.postDelayed(hideHint, 700);
    }

    private final Runnable hideHint = new Runnable() {
        @Override public void run() { hintView.setVisibility(View.GONE); }
    };

    // ── 播放控制 ─────────────────────────────────────────────────────

    private MediaPlayer feedTarget() {
        return feedMode ? activeFeedPlayer : player;
    }

    private void seekBy(long delta) {
        MediaPlayer player = feedTarget();
        if (player == null) return;
        long target = Math.max(0, player.getTime() + delta);
        if (durationMs > 0) target = Math.min(durationMs, target);
        player.setTime(target);
        showHint((delta > 0 ? "快进 " : "回退 ") + Math.abs(delta / 1000) + " 秒");
        hideHintSoon();
    }

    private void togglePlay() {
        MediaPlayer player = feedTarget();
        if (player == null) return;
        boolean wasPlaying = player.isPlaying();
        if (wasPlaying) {
            player.pause();
            playButton.setIcon(IconButton.PLAY);
        } else {
            player.play();
            playButton.setIcon(IconButton.PAUSE);
        }
        setControlsVisible(true);
    }

    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 0.75f};
    private int speedIndex = 0;

    private void toggleSpeedPanel() {
        if (speedPanel == null) return;
        if (speedPanel.getVisibility() == View.VISIBLE) {
            speedPanel.setVisibility(View.GONE);
            return;
        }
        MediaPlayer player = feedTarget();
        float current = player == null ? feedRate : player.getRate();
        if (current <= 0.01f) current = feedRate;
        speedPanel.removeAllViews();
        for (final float rate : RATE_CHOICES) {
            final boolean on = Math.abs(rate - current) < 0.01f;
            TextView item = new TextView(this);
            item.setText(trimRate(rate) + "×");
            item.setTextSize(14f);
            item.setTextColor(on ? 0xFF4C8DFF : 0xFFF2F2F5);
            item.getPaint().setFakeBoldText(on);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(10), dp(13), dp(10), dp(13));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { chooseRate(rate); }
            });
            speedPanel.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        speedPanel.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideControls);
    }

    private void chooseRate(float rate) {
        feedRate = rate;
        MediaPlayer player = feedTarget();
        if (player != null) player.setRate(rate);
        speedButton.setText(trimRate(rate) + "×");
        speedPanel.setVisibility(View.GONE);
        showHint("倍速 " + trimRate(rate) + "×");
        hideHintSoon();
        ui.postDelayed(hideControls, HIDE_CONTROLS_MS);
    }

    private static String trimRate(float rate) {
        String text = String.format(Locale.US, "%.2f", rate);
        if (text.endsWith("0")) text = text.substring(0, text.length() - 1);
        if (text.endsWith(".0")) text = text.substring(0, text.length() - 2);
        return text;
    }

    private void showError(String text) {
        errorText = text;
        errorShown = true;
        spinner.setVisibility(View.GONE);
        errorView.setText(text + "\n\n点返回可以换一个源试试。");
        errorView.setVisibility(View.VISIBLE);
        metaView.setText("播放失败");
    }

    // ── libVLC 回调（跑在 VLC 自己的线程上） ──────────────────────────

    @Override public void onEvent(final MediaPlayer.Event event) {
        ui.post(new Runnable() {
            @Override public void run() { handleEvent(event); }
        });
    }

    private void handleEvent(MediaPlayer.Event event) {
        final int type = event.type;
        if (player == null) return;
        if (type == MediaPlayer.Event.LengthChanged) {
            durationMs = player.getLength();
            if (startedAt > 2000 && durationMs > startedAt + 3000) {
                player.setTime(startedAt);
                startedAt = 0;
            }
        } else if (type == MediaPlayer.Event.TimeChanged) {
            long time = player.getTime();
            if (time > 0) savedPosition = time;
            if (time % 1000 < 400) {
                updateClock(time);
                describeTrack();
            }
        } else if (type == MediaPlayer.Event.Playing) {
            if (!tLogged) {
                tLogged = true;
                long now = System.currentTimeMillis();
                android.util.Log.i("HG.Vlc", "首帧：onCreate→播放 " + (now - tCreate)
                        + "ms（其中 play() 之后 " + (now - tPlay) + "ms）");
            }
            spinner.setVisibility(View.GONE);
            playButton.setIcon(IconButton.PAUSE);
            describeTrack();
        } else if (type == MediaPlayer.Event.Buffering) {
            if (event.getBuffering() < 100f && !player.isPlaying()) spinner.setVisibility(View.VISIBLE);
            else spinner.setVisibility(View.GONE);
        } else if (type == MediaPlayer.Event.Paused) {
            playButton.setIcon(IconButton.PLAY);
            setControlsVisible(true);
        } else if (type == MediaPlayer.Event.EndReached) {
            ended = true;
            playButton.setIcon(IconButton.PLAY);
            setControlsVisible(true);
            persist(true);
        } else if (type == MediaPlayer.Event.EncounteredError) {
            showError("这个地址内置播放器也解不了。可能地址过期了，回上一页重新取一次。");
        }
    }

    /** 把当前视频轨的编码/分辨率写到副标题上 —— 排查「这台机器放不了」时最有用的一行。 */
    private void describeTrack() {
        try {
            if (player == null) return;
            IMedia.VideoTrack track = player.getCurrentVideoTrack();
            if (track == null) return;
            String codec = track.codec == null ? "" : track.codec.toUpperCase(Locale.US);
            String text = track.width + "×" + track.height
                    + (codec.length() > 0 ? " " + codec : "")
                    + " · " + (hwDecode ? "硬解优先" : "软解");
            if (!text.equals(metaView.getText().toString())) metaView.setText(text);
        } catch (Throwable ignored) {}
    }

    private void updateClock(long time) {
        if (durationMs <= 0) durationMs = player == null ? 0 : player.getLength();
        clockView.setText(fmt(time) + " / " + (durationMs > 0 ? fmt(durationMs) : "--:--"));
        if (durationMs > 0) {
            int progress = (int) Math.min(1000, time * 1000 / durationMs);
            if (!seekBar.isPressed()) seekBar.setProgress(progress);
        }
    }

    private static String fmt(long ms) {
        if (ms <= 0) return "00:00";
        long total = ms / 1000;
        long hour = total / 3600;
        long minute = (total % 3600) / 60;
        long second = total % 60;
        if (hour > 0) return String.format(Locale.US, "%d:%02d:%02d", hour, minute, second);
        return String.format(Locale.US, "%02d:%02d", minute, second);
    }

    // ══ mini-feed：两个轮转 + 按需取地址 ══════════════════════════════

    private void initFeedVlc() throws Throwable {
        ArrayList<String> options = new ArrayList<String>();
        options.add("--no-video-title-show");
        options.add("--audio-time-stretch");
        options.add("--network-caching=" + payload.optInt("cache", 1500));
        hwDecode = !"sw".equals(payload.optString("decode", "auto"));
        if (!hwDecode) options.add("--avcodec-hw=none");
        try {
            libVLC = new LibVLC(this, options);
        } catch (Throwable e) {
            android.util.Log.w("HG.Vlc", "带参数创建失败：" + e + "，改用默认参数重试");
            libVLC = new LibVLC(this);
        }
        playerA = new MediaPlayer(libVLC);
        playerB = new MediaPlayer(libVLC);
        playerA.setEventListener(new FeedEvents(playerA));
        playerB.setEventListener(new FeedEvents(playerB));
    }

    /**
     * 某一页固定用哪个 player：按奇偶绑死（偶数页 → A，奇数页 → B）。
     *
     * 上一版是「挑一个不在当前页的」，滑相邻两集时会挑到正在播的那个，
     * 于是 stop 掉用户正在看的流 —— 表现就是「上一集还有声音，新的一集黑屏」。
     * 奇偶绑定后相邻页永远落在两个不同的 player 上，互不干扰，滑回上一集
     * 还能从暂停的位置接着播。
     */
    private MediaPlayer slotFor(int pos) {
        if (playerA == null) return null;
        return (pos % 2 == 0) ? playerA : playerB;
    }

    private int pageOf(MediaPlayer mp) {
        if (mp == null) return -1;
        return mp == playerA ? pageA : (mp == playerB ? pageB : -1);
    }

    /** 认领着这一页的 player。没认领（或认领的是别的页）就给 null。 */
    private MediaPlayer playerFor(int pos) {
        MediaPlayer mp = slotFor(pos);
        return pageOf(mp) == pos ? mp : null;
    }

    /** 播某页。同一槽位换页时先停旧流，vout 拆了再接。 */
    private void playPage(int pos, String url) {
        MediaPlayer mp = slotFor(pos);
        if (mp == null) return;
        int claimed = pageOf(mp);
        if (claimed != pos) {
            if (claimed >= 0) {
                // 这个槽位之前认领的是同奇偶的另一页（0↔2、1↔3）。必须停掉，
                // 否则 setMedia 会在旧流上叠加 —— VLC 会报 Cancellation 然后黑屏。
                try { mp.stop(); } catch (Throwable ignored) {}
            }
            try { mp.getVLCVout().detachViews(); } catch (Throwable ignored) {}
            playingUrl.remove(mp);
            if (mp == playerA) pageA = pos; else pageB = pos;
        }
        // 幂等：这一页就挂在这个 player 上、地址也没变。滑走又滑回来走这条。
        if (pos == claimed && url.equals(playingUrl.get(mp))) {
            try {
                if (!mp.isPlaying()) mp.play();
                mp.setVolume(100);
            } catch (Throwable ignored) {}
            if (Math.abs(feedRate - 1f) > 0.01f) {
                try { mp.setRate(feedRate); } catch (Throwable ignored) {}
            }
            attachVout(pos, true);
            activeFeedPlayer = mp;
            MediaPlayer otherQuiet = (mp == playerA) ? playerB : playerA;
            try { if (otherQuiet.isPlaying()) otherQuiet.pause(); } catch (Throwable ignored) {}
            spinner.setVisibility(View.GONE);
            updateEpisodeUi();
            return;
        }
        Media media = new Media(libVLC, Uri.parse(relayUrl(url)));
        media.setHWDecoderEnabled(hwDecode, false);
        String referer = payload.optString("referer", "");
        String agent = payload.optString("ua", "");
        if (referer.length() > 0) media.addOption(":http-referrer=" + referer);
        if (agent.length() > 0) media.addOption(":http-user-agent=" + agent);
        mp.setMedia(media);
        media.release();
        playingUrl.put(mp, url);
        long resume = resumeOf(pos);
        mp.play();
        if (resume > 2000) mp.setTime(resume);
        mp.setVolume(100);
        if (Math.abs(feedRate - 1f) > 0.01f) mp.setRate(feedRate);
        attachVout(pos, true);
        activeFeedPlayer = mp;
        // 另一个若在响，静下来（它认领着别的页，回滑时从原地继续）
        MediaPlayer other = (mp == playerA) ? playerB : playerA;
        try { if (other.isPlaying()) other.pause(); } catch (Throwable ignored) {}
        spinner.setVisibility(View.GONE);
        setControlsVisible(false);
        updateEpisodeUi();
    }

    private void attachVout(int pos) {
        attachVout(pos, false);
    }

    /**
     * 把某页的 vout 接到它那页的 TextureView 上（页面没就绪就等 surface 回调再接）。
     *
     * force=true 时无论当前挂没挂都先拆再挂。VLC 在已 attach 的状态下换 view 不会
     * 真正切到新 surface 上 —— 复用出来的 PageView 换个 position 之后画面就写进
     * 了旧的那个（已回收的）surface，屏上只剩黑。
     */
    private void attachVout(int pos, boolean force) {
        final MediaPlayer mp = playerFor(pos);
        if (mp == null) return;
        PageView pv = feedAdapter == null ? null : feedAdapter.pageAt(pos);
        if (pv == null || !pv.surfaceReady) return;
        try {
            IVLCVout vout = mp.getVLCVout();
            if (force) {
                try { vout.detachViews(); } catch (Throwable ignored) {}
            }
            vout.setVideoView(pv.texture);
            if (!vout.areViewsAttached()) vout.attachViews();
            setWindowSizeFor(mp, pv.getWidth(), pv.getHeight());
        } catch (Throwable ignored) {}
    }

    /** 除了 keepPos 那一页自己的 player，别的都暂停。 */
    private void silenceOthers(int keepPos) {
        MediaPlayer keep = slotFor(keepPos);
        MediaPlayer first = (playerA != null && playerA != keep) ? playerA : null;
        MediaPlayer second = (playerB != null && playerB != keep) ? playerB : null;
        try { if (first != null && first.isPlaying()) first.pause(); } catch (Throwable ignored) {}
        try { if (second != null && second.isPlaying()) second.pause(); } catch (Throwable ignored) {}
    }

    private void activate(int pos) {
        if (pos < 0 || pos >= eps.length) return;
        // startPlayback 会调一次，RecyclerView 首次布局的 scroll-idle 还会调一次。
        // 不去重的话第二次会对正在播的 player 再 setMedia 一遍 —— VLC 当场把
        // 输入流取消掉（日志是 Cancellation + Failed to create demuxer），黑屏。
        if (pos == curPos && playerFor(pos) != null && playerFor(pos) == activeFeedPlayer) return;
        curPos = pos;
        updateEpisodeUi();
        persistFeedProgress();
        // 滑走就闭嘴：上一集的声音不能跟着新的一页继续响。地址还没到之前
        // 屏幕上什么都没有，声音却还是上一集 —— 用户只会当成卡死。
        silenceOthers(pos);
        // 滑回一集失败过的页面时要重新给机会：上一次可能只是那会儿网络抖了。
        failed.remove(pos);
        // 页面起播时给的地址（index 那一集）直接吃掉——它刚刚就在网页播放器里放着
        if (pos == payload.optInt("index", -1) && urlCache.get(pos) == null) {
            String seed = payload.optString("url", "");
            if (seed.length() > 0) urlCache.put(pos, seed);
        }
        String url = urlCache.get(pos);
        if (url != null) {
            playPage(pos, url);
            preloadAround(pos);
            return;
        }
        // 失败过的页不拉黑名单：AGE 那条 wasm 线路要十几秒，网络抖一下就失败，
        // 拉黑的话用户划走再回来也永远拿不到。重试交给 resolving 去重就行。
        PageView pv = feedAdapter.pageAt(pos);
        if (pv != null) { pv.spin.setVisibility(View.VISIBLE); pv.error.setVisibility(View.GONE); }
        final int target = pos;
        if (!resolving.add(pos)) return;   // 已经有人在取了
        new Thread(new Runnable() {
            @Override public void run() {
                final String url = resolveViaJs(eps[target]);
                ui.post(new Runnable() {
                    @Override public void run() {
                        resolving.remove(target);
                        if (url == null || url.length() == 0) {
                            failed.add(target);
                            PageView pv = feedAdapter.pageAt(target);
                            if (pv != null) {
                                pv.spin.setVisibility(View.GONE);
                                pv.error.setVisibility(View.VISIBLE);
                                pv.error.setText("第 " + eps[target] + " 集取不到播放地址，滑到别的集再回来试试");
                            }
                            return;
                        }
                        urlCache.put(target, url);
                        if (target == curPos) {
                            playPage(target, url);
                            preloadAround(target);
                        }
                    }
                });
            }
        }).start();
    }

    /** 相邻两集的地址先要回来（只握手，不起播），滑动出去时基本是即时的。 */
    private void preloadAround(int pos) {
        preload(pos + 1);
        preload(pos - 1);
    }

    private void preload(final int pos) {
        if (pos < 0 || pos >= eps.length || urlCache.containsKey(pos) || resolving.contains(pos)
                || failed.contains(pos)) return;
        resolving.add(pos);
        new Thread(new Runnable() {
            @Override public void run() {
                final String url = resolveViaJs(eps[pos]);
                ui.post(new Runnable() {
                    @Override public void run() {
                        resolving.remove(pos);
                        if (url != null && url.length() > 0) {
                            urlCache.put(pos, url);
                            // 用户可能在预取排队期间就滑到了这一页 —— 那时 activate
                            // 看到 resolving 里已有它就直接返回了，没人负责起播，
                            // 结果就是画面停在上一集、声音也还是上一集。这里补上。
                            if (pos == curPos) {
                                playPage(pos, url);
                                preloadAround(pos);
                            }
                        } else if (pos == curPos) {
                            PageView pv = feedAdapter == null ? null : feedAdapter.pageAt(pos);
                            if (pv != null) {
                                pv.spin.setVisibility(View.GONE);
                                pv.error.setVisibility(View.VISIBLE);
                                pv.error.setText("第 " + eps[pos] + " 集取不到播放地址，滑到别的集再回来试试");
                            }
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 找页面要播放地址。解密、wasm、多线路全在页面那头，这里只发一个问题。
     *
     * 链路：反射拿 MainActivity.host 的 webView（同一个进程，字段没混淆），
     * evaluateJavascript 调 window.__hgNativeEpisode(id, ep)，拿回一个 URL 字符串。
     * 主线程才能调 evaluateJavascript，所以在主线程发、用闩锁等结果。
     */
    private String resolveViaJs(int ep) {
        // 页面那个口子是同步的：有缓存当场给，没有就回去发起解析、返回空串。
        // 所以这里轮询——当前集一般第一击就中（网页播放器刚播过它），
        // 预取的邻集要等页面解析完，AGE 那条 wasm 线路最慢十几秒。
        // 45 次 × 800ms ≈ 36 秒：宁可多等一会儿，也别让用户滑到一页看到「取不到地址」。
        //
        // 这段包装脚本是关键：页面那个 hook 有两种历史实现 —— 同步的（直接给字符串）
        // 和 async 的（evaluateJavascript 收到的是 "{}"）。这里两种都认：
        // 同步的直接用，Promise 的挂一个 then 把结果写进 __hgNativeEps 表，
        // 下一轮从这里读。热更新还没下发到位的机器也能正常取地址。
        final String idLit = JSONObject.quote(payload.optString("id", ""));
        final String script =
                "(function(){"
                + "var t = window.__hgNativeEps || (window.__hgNativeEps = {});"
                + "var k = " + idLit + " + ':" + ep + "';"
                + "if (t[k]) return t[k];"
                + "try{"
                + "var h = window.__hgNativeEpisode && window.__hgNativeEpisode(" + idLit + "," + ep + ");"
                + "if (typeof h === 'string' && h) { t[k] = h; return h; }"
                + "if (h && typeof h.then === 'function') { h.then(function(u){ if (u) t[k] = u; }, function(){}); }"
                + "}catch(e){}"
                + "return '';"
                + "})()";
        for (int attempt = 0; attempt < 45; attempt++) {
            String url = askPageOnce(script);
            if (url != null && url.length() > 0) return url;
            try { Thread.sleep(800); } catch (InterruptedException e) { return null; }
        }
        return null;
    }

    /** 在页面里跑一段同步脚本，拿回字符串（没有/失败都是空串）。 */
    private String askPageOnce(final String script) {
        try {
            Object host = MainActivity.host;
            if (host == null) return "";
            // 字段是 private，getField 拿不到 —— 得 getDeclaredField + setAccessible，
            // 而且要沿父类链找。
            java.lang.reflect.Field field = null;
            for (Class<?> type = host.getClass(); type != null && field == null; type = type.getSuperclass()) {
                try { field = type.getDeclaredField("webView"); } catch (NoSuchFieldException ignored) {}
            }
            if (field == null) return "";
            field.setAccessible(true);
            final Object webView = field.get(host);
            if (!(webView instanceof android.webkit.WebView)) return "";
            final java.util.concurrent.SynchronousQueue<String> lane =
                    new java.util.concurrent.SynchronousQueue<String>();
            ui.post(new Runnable() {
                @Override public void run() {
                    try {
                        ((android.webkit.WebView) webView).evaluateJavascript(script,
                                new android.webkit.ValueCallback<String>() {
                                    @Override public void onReceiveValue(String value) {
                                        try { lane.put(value == null ? "" : value); } catch (Throwable ignored) {}
                                    }
                                });
                    } catch (Throwable e) {
                        try { lane.put(""); } catch (Throwable ignored) {}
                    }
                }
            });
            String raw = lane.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            if (raw == null || raw.length() < 2) return "";
            Object value = new org.json.JSONTokener(raw.trim()).nextValue();
            return value instanceof String ? (String) value : "";
        } catch (Throwable e) {
            android.util.Log.w("HG.Vlc", "问页面要地址失败：" + e);
            return "";
        }
    }

    private long resumeOf(int pos) {
        if (pos == payload.optInt("index", 0)) return startedAt;
        return 0;
    }

    private long feedTimeOf(int pos) {
        MediaPlayer mp = playerFor(pos);
        return mp == null ? 0 : Math.max(0, mp.getTime());
    }

    private long feedDurationOf(int pos) {
        MediaPlayer mp = playerFor(pos);
        return mp == null ? 0 : mp.getLength();
    }

    private void updateEpisodeUi() {
        titleView.setText(payload.optString("title", "播放中") + " · 第 " + eps[curPos] + " 集");
        metaView.setText((hwDecode ? "硬解优先" : "软解") + " · " + abi()
                + " · " + (curPos + 1) + "/" + eps.length);
    }

    /** 把当前这一集的现场写进 prefs。切集和退出都会调。 */
    private void persistFeedProgress() {
        try {
            MediaPlayer mp = activeFeedPlayer;
            long position = mp == null ? 0 : Math.max(0, mp.getTime());
            long duration = mp == null ? 0 : mp.getLength();
            JSONObject record = new JSONObject();
            record.put("url", urlCache.containsKey(curPos) ? urlCache.get(curPos) : payload.optString("url", ""));
            record.put("id", payload.optString("id", ""));
            record.put("episode", eps[curPos]);
            record.put("title", payload.optString("title", ""));
            record.put("cover", payload.optString("cover", ""));
            record.put("source", payload.optString("source", ""));
            record.put("position", position);
            record.put("duration", duration);
            record.put("finished", ended);
            record.put("error", errorText);
            record.put("at", System.currentTimeMillis());
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST, record.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private void releaseFeedPlayers() {
        // 逐个释放，每个动作单独兜异常的旧写法有坑：playerA.stop() 或
        // detachViews() 只要抛一次，整个 try 就跳出去，playerB 再也不会被释放 ——
        // 表现就是「退出播放器了声音还在放」。
        releaseOne(playerA);
        releaseOne(playerB);
        playerA = null;
        playerB = null;
        pageA = pageB = -1;
        activeFeedPlayer = null;
        feedWasPlaying = false;
        playingUrl.clear();
    }

    /** 彻底关掉一个 player：先静音再停，stop 之后 VLC 还会吐几百毫秒的尾巴。 */
    private void releaseOne(MediaPlayer mp) {
        if (mp == null) return;
        try { mp.setVolume(0); } catch (Throwable ignored) {}
        try { mp.setEventListener(null); } catch (Throwable ignored) {}
        try { mp.stop(); } catch (Throwable ignored) {}
        try { mp.getVLCVout().detachViews(); } catch (Throwable ignored) {}
        try { mp.release(); } catch (Throwable ignored) {}
    }

    /** 每个轮转 player 一份事件。只有「当前中心」那份会动 UI。 */
    private class FeedEvents implements MediaPlayer.EventListener {
        private final MediaPlayer mp;
        FeedEvents(MediaPlayer mp) { this.mp = mp; }

        @Override public void onEvent(final MediaPlayer.Event event) {
            ui.post(new Runnable() {
                @Override public void run() { handleFeedEvent(mp, event); }
            });
        }
    }

    private void handleFeedEvent(MediaPlayer mp, MediaPlayer.Event event) {
        if (mp == null || mp != activeFeedPlayer) return;
        final int type = event.type;
        if (type == MediaPlayer.Event.LengthChanged) {
            durationMs = mp.getLength();
        } else if (type == MediaPlayer.Event.TimeChanged) {
            long time = mp.getTime();
            if (durationMs <= 0) durationMs = mp.getLength();
            clockView.setText(fmt(time) + " / " + (durationMs > 0 ? fmt(durationMs) : "--:--"));
            if (durationMs > 0 && !seekBar.isPressed()) {
                seekBar.setProgress((int) Math.min(1000, time * 1000 / durationMs));
            }
            describeFeedTrack(mp);
        } else if (type == MediaPlayer.Event.Playing) {
            PageView pv = feedAdapter != null ? feedAdapter.pageAt(curPos) : null;
            if (pv != null) pv.spin.setVisibility(View.GONE);
            playButton.setIcon(IconButton.PAUSE);
            describeFeedTrack(mp);
            ui.postDelayed(hideControls, HIDE_CONTROLS_MS);
        } else if (type == MediaPlayer.Event.Paused) {
            playButton.setIcon(IconButton.PLAY);
            setControlsVisible(true);
        } else if (type == MediaPlayer.Event.EndReached) {
            ended = true;
            persistFeedProgress();
            ended = false;
            // 抖音流的规矩：播完自动滑到下一集
            if (curPos + 1 < eps.length) {
                pager.smoothScrollToPosition(curPos + 1);
            } else {
                playButton.setIcon(IconButton.PLAY);
                setControlsVisible(true);
            }
        } else if (type == MediaPlayer.Event.EncounteredError) {
            PageView pv = feedAdapter.pageAt(curPos);
            if (pv != null) {
                pv.spin.setVisibility(View.GONE);
                pv.error.setVisibility(View.VISIBLE);
                pv.error.setText("第 " + eps[curPos] + " 集内置播放器也解不了，回上一页重新取一次");
            }
        }
    }

    private void describeFeedTrack(MediaPlayer mp) {
        try {
            IMedia.VideoTrack track = mp.getCurrentVideoTrack();
            if (track == null) return;
            String codec = track.codec == null ? "" : track.codec.toUpperCase(Locale.US);
            metaView.setText(track.width + "×" + track.height
                    + (codec.length() > 0 ? " " + codec : "")
                    + " · " + (hwDecode ? "硬解优先" : "软解")
                    + " · 第 " + eps[curPos] + " 集");
        } catch (Throwable ignored) {}
    }

    private int centeredPosition() {
        if (pagerLayout == null) return -1;
        int first = pagerLayout.findFirstVisibleItemPosition();
        int last = pagerLayout.findLastVisibleItemPosition();
        if (first < 0 || last < 0) return -1;
        int best = -1;
        long bestArea = -1;
        android.graphics.Rect rect = new android.graphics.Rect();
        for (int i = first; i <= last; i++) {
            View view = pagerLayout.findViewByPosition(i);
            if (view == null) continue;
            view.getHitRect(rect);
            long area = (long) Math.max(0, rect.height()) * Math.max(0, rect.width());
            if (area > bestArea) { bestArea = area; best = i; }
        }
        return best;
    }

    /** 一页 = 一块全屏 TextureView + 取地址时的转圈 + 出错时的一行字。 */
    private class PageView extends FrameLayout implements TextureView.SurfaceTextureListener {
        final TextureView texture;
        final ProgressBar spin;
        final TextView error;
        int pos = -1;
        boolean surfaceReady;

        PageView(Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            texture = new TextureView(context);
            texture.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            texture.setSurfaceTextureListener(this);
            addView(texture);

            spin = new ProgressBar(context);
            LayoutParams spinParams = new LayoutParams(dp(36), dp(36), Gravity.CENTER);
            spin.setLayoutParams(spinParams);
            spin.setVisibility(View.GONE);
            addView(spin);

            error = new TextView(context);
            error.setTextColor(0xFFE9E9EC);
            error.setTextSize(13f);
            error.setGravity(Gravity.CENTER);
            error.setPadding(dp(24), dp(24), dp(24), dp(24));
            error.setVisibility(View.GONE);
            addView(error, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
        }

        @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
            surfaceReady = true;
            if (pos == curPos) attachVout(pos, true);
        }

        @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
            // 旋转时只 updateVideoSurfaces 不够 —— SurfaceTexture 的缓冲还停在
            // 旧尺寸，画面就按旧几何塞在新屏的左上角。强制拆掉重挂一次，
            // 延迟一拍等布局落定。
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    if (pos != curPos) return;
                    MediaPlayer mp = playerFor(pos);
                    if (mp == null) return;
                    try { mp.getVLCVout().detachViews(); } catch (Throwable ignored) {}
                    attachVout(pos);
                }
            }, 160);
        }

        @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
            surfaceReady = false;
            MediaPlayer mp = playerFor(pos);
            if (mp != null) {
                try { mp.getVLCVout().detachViews(); } catch (Throwable ignored) {}
            }
            return true;
        }

        @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
    }

    private class FeedAdapter extends RecyclerView.Adapter<FeedViewHolder> {
        final Map<Integer, PageView> live = new HashMap<Integer, PageView>();

        @Override public FeedViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new FeedViewHolder(new PageView(parent.getContext()));
        }

        @Override public void onBindViewHolder(FeedViewHolder holder, int position) {
            PageView pv = holder.page;
            pv.pos = position;
            pv.spin.setVisibility(View.GONE);
            pv.error.setVisibility(View.GONE);
            pv.texture.setSurfaceTextureListener(pv);
            // 复用的 View 上 SurfaceTexture 已经存在，setSurfaceTextureListener
            // 不会再回调 onSurfaceTextureAvailable。不在这儿补一手的话
            // surfaceReady 永远是 false，attachVout 次次跳过 —— 黑屏。
            pv.surfaceReady = pv.texture.isAvailable();
            live.put(position, pv);
            if (pv.surfaceReady && position == curPos && playerFor(position) != null) {
                attachVout(position, true);
            }
        }

        @Override public void onViewRecycled(FeedViewHolder holder) {
            int pos = holder.page.pos;
            live.remove(pos);
            MediaPlayer mp = playerFor(pos);
            if (mp != null) {
                try { mp.getVLCVout().detachViews(); } catch (Throwable ignored) {}
            }
            holder.page.surfaceReady = false;
        }

        @Override public int getItemCount() { return eps.length; }

        PageView pageAt(int pos) {
            PageView pv = live.get(pos);
            if (pv != null && pv.pos == pos) return pv;
            // findViewByPosition 兜底：live 表可能被回收时序打乱
            View view = pagerLayout == null ? null : pagerLayout.findViewByPosition(pos);
            return view instanceof PageView ? (PageView) view : null;
        }
    }

    private static class FeedViewHolder extends RecyclerView.ViewHolder {
        final PageView page;
        FeedViewHolder(PageView page) {
            super(page);
            this.page = page;
        }
    }

}
