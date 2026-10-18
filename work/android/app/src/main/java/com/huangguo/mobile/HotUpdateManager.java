package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.Base64;
import android.webkit.WebResourceResponse;
import androidx.webkit.ProxyConfig;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.internal.AssetHelper;
import com.huangguo.mobile.Http;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

final class HotUpdateManager {
    static final int BRIDGE_VERSION = 1;
    private static final long BUNDLED_UI_REVISION = 15;
    /**
     * 热更清单的候选地址。2026-10-18 起全部指向 GitHub 仓库的 hot/ 目录，
     * 不再依赖自己的服务器 —— 理由见 Endpoints.HOT_RAW 的注释。
     *
     * 这个常量只剩「给日志和对拍用」的意义，真正取值走 Endpoints.hotIndexUrls()。
     */
    static final String INDEX = Endpoints.hotIndexUrls().get(0);

    /** 一个资源包最多多少个条目。防「几万个空文件」撑爆目录遍历。 */
    private static final int MAX_ENTRIES = 512;

    /** 解压后总字节上限 25MB。防 zip bomb。 */
    private static final long MAX_UNPACKED_BYTES = 26214400L;
    static final String UI_URL = "https://appassets.androidplatform.net/assets/app.html";
    private static HotUpdateManager singleton;
    private final AtomicBoolean checking;
    private final Context context;
    private final SharedPreferences preferences;
    private final PublicKey publicKey;
    private final File root;
    private final AtomicFile stateFile;

    static synchronized HotUpdateManager get(Context context) {
        HotUpdateManager hotUpdateManager;
        hotUpdateManager = singleton;
        if (hotUpdateManager == null) {
            try {
                hotUpdateManager = new HotUpdateManager(context);
                singleton = hotUpdateManager;
            } catch (Exception e) {
                throw new IllegalStateException("无法初始化资源更新", e);
            }
        }
        return hotUpdateManager;
    }

    HotUpdateManager(Context context) throws Exception {
        this(context, new File(context.getFilesDir(), "hot-updates"));
    }

    HotUpdateManager(Context context, File file) throws Exception {
        this.checking = new AtomicBoolean();
        this.context = context.getApplicationContext();
        this.root = file;
        if (!file.isDirectory() && !file.mkdirs()) {
            throw new IOException("无法建立资源目录");
        }
        this.stateFile = new AtomicFile(new File(file, "state.json"));
        this.preferences = context.getSharedPreferences("hot-updates", 0);
        this.publicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.decode(new String(read(context.getAssets().open("hot-update-public.pem"), 8192), StandardCharsets.US_ASCII).replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""), 0)));
    }

    static final class Session {
        final String bootId = UUID.randomUUID().toString();
        final File directory;
        final long revision;
        final SiteProfile site;
        final JSONObject theme;
        final boolean trial;
        final String version;

        Session(long j, String str, File file, SiteProfile siteProfile, JSONObject jSONObject, boolean z) {
            this.revision = j;
            this.version = str;
            this.directory = file;
            this.site = siteProfile;
            this.theme = jSONObject;
            this.trial = z;
        }
    }

    private JSONObject state() {
        try {
            return new JSONObject(new String(read(this.stateFile.openRead(), 65536), StandardCharsets.UTF_8));
        } catch (Exception unused) {
            return new JSONObject();
        }
    }

    private void save(JSONObject jSONObject) throws Exception {
        FileOutputStream fileOutputStreamStartWrite = this.stateFile.startWrite();
        try {
            fileOutputStreamStartWrite.write(jSONObject.toString().getBytes(StandardCharsets.UTF_8));
            this.stateFile.finishWrite(fileOutputStreamStartWrite);
        } catch (Exception e) {
            this.stateFile.failWrite(fileOutputStreamStartWrite);
            throw e;
        }
    }

    private File directory(long j) {
        return new File(this.root, Long.toString(j));
    }

    private JSONObject metadata(long j) throws Exception {
        return verifyEnvelope(read(new FileInputStream(new File(directory(j), "envelope.json")), 262144));
    }

    private Session load(long j, boolean z) throws Exception {
        if (j == 0) {
            return new Session(0L, "内置", null, new SiteProfile(this.context, null), new JSONObject(), false);
        }
        JSONObject jSONObjectMetadata = metadata(j);
        requireCompatible(jSONObjectMetadata);
        File fileDirectory = directory(j);
        JSONObject jSONObjectJson = json(new File(fileDirectory, "site.json"));
        JSONObject jSONObjectJson2 = json(new File(fileDirectory, "theme.json"));
        validateTheme(fileDirectory, jSONObjectJson2);
        return new Session(j, jSONObjectMetadata.getString("version"), fileDirectory, new SiteProfile(this.context, jSONObjectJson), jSONObjectJson2, z);
    }

    synchronized SiteProfile currentSite() {
        SiteProfile profile;
        try {
            profile = load(state().optLong("active"), false).site;
        } catch (Exception unused) {
            try {
                profile = new SiteProfile(this.context, null);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return profile;
    }

    synchronized Session beginSession() {
        long jOptLong;
        boolean z;
        JSONObject jSONObjectState = state();
        // 热更这条路一直是「静默工作」的：成功没日志、失败也没日志，出问题只能
        // 靠猜。这四行是刻意留的 —— 用户报「UI 没更新」时，一条 logcat 就能看出
        // 是没拉到、被 blocked、还是加载时抛了异常。
        android.util.Log.d("AIIvideo", "[hot] session state=" + jSONObjectState);
        // smali 里所有逃出的 Exception 最终都走 :catch_3 -> IllegalStateException，
        // 所以这个方法没有 throws。jadx 把最后的正常路径 load(jOptLong, z) 放到了
        // catch 链外面，导致 javac 认为它未被捕获。
        try {
            try {
                try {
                    if (jSONObjectState.optLong("trial") != 0) {
                        rollbackState(jSONObjectState, jSONObjectState.optLong("trial"));
                    }
                    jOptLong = jSONObjectState.optLong("pending");
                    long jOptLong2 = jSONObjectState.optLong("active");
                    z = true;
                    if (jOptLong <= jOptLong2 || jOptLong == jSONObjectState.optLong("blocked")) {
                        jOptLong = jOptLong2;
                    } else {
                        try {
                            load(jOptLong, true);
                            jSONObjectState.put("previous", jOptLong2).put("active", jOptLong).put("pending", 0).put("trial", jOptLong);
                            save(jSONObjectState);
                        } catch (Exception e) {
                            android.util.Log.w("AIIvideo", "[hot] 新版本 " + jOptLong + " 加载失败，标记 blocked", e);
                            jSONObjectState.put("blocked", jOptLong).put("pending", 0);
                            save(jSONObjectState);
                            return load(jOptLong2, false);
                        }
                    }
                    if (jSONObjectState.optLong("trial") != jOptLong || jOptLong == 0) {
                        z = false;
                    }
                } catch (Exception e) {
                    android.util.Log.w("AIIvideo", "[hot] 会话初始化失败，回滚到 active", e);
                    rollbackState(jSONObjectState, Math.max(jSONObjectState.optLong("active"), jSONObjectState.optLong("pending")));
                    return load(jSONObjectState.optLong("active"), false);
                }
            } catch (Exception e) {
                android.util.Log.e("AIIvideo", "[hot] 连 active 都加载不了，退回内置", e);
                save(new JSONObject().put("blocked", jSONObjectState.optLong("active")));
                return load(0L, false);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        try {
            return load(jOptLong, z);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void rollbackState(JSONObject jSONObject, long j) throws Exception {
        jSONObject.put("blocked", Math.max(j, jSONObject.optLong("blocked"))).put("active", jSONObject.optLong("previous")).put("trial", 0).put("pending", 0).put("previous", 0);
        save(jSONObject);
    }

    synchronized void markHealthy(Session session) throws Exception {
        JSONObject jSONObjectState = state();
        if (session.revision == jSONObjectState.optLong("active") && jSONObjectState.optLong("trial") == session.revision) {
            jSONObjectState.put("trial", 0);
            save(jSONObjectState);
            prune(jSONObjectState);
        }
    }

    synchronized void rollback(Session session) throws Exception {
        JSONObject jSONObjectState = state();
        if (session.revision == jSONObjectState.optLong("active") && session.revision != 0) {
            rollbackState(jSONObjectState, session.revision);
        }
    }

    synchronized JSONObject status() throws Exception {
        JSONObject jSONObjectState;
        long jOptLong;
        long jOptLong2;
        String string;
        jSONObjectState = state();
        jOptLong = jSONObjectState.optLong("active");
        jOptLong2 = jSONObjectState.optLong("pending");
        if (jOptLong == 0) {
            string = "内置";
        } else {
            try {
                string = metadata(jOptLong).getString("version");
            } catch (Exception unused) {
                string = "内置";
            }
        }
        return new JSONObject().put("revision", jOptLong).put("version", string).put("pending", jOptLong2).put("checking", this.checking.get()).put("blocked", jSONObjectState.optLong("blocked")).put("bridgeVersion", 1);
    }

    boolean hasPending() {
        boolean z;
        synchronized (this) {
            z = state().optLong("pending") > 0;
        }
        return z;
    }

    JSONObject check(boolean z) throws Exception {
        JSONObject jSONObjectPut;
        boolean z2 = true;
        if (!this.checking.compareAndSet(false, true)) {
            return status();
        }
        try {
            long jCurrentTimeMillis = System.currentTimeMillis();
            int i = this.context.getPackageManager().getPackageInfo(this.context.getPackageName(), 0).versionCode;
            if (this.preferences.getInt("lastApkVersionCode", 0) == i) {
                z2 = false;
            }
            if (z || z2 || jCurrentTimeMillis - this.preferences.getLong("lastCheck", 0L) >= 1800000) {
                this.preferences.edit().putLong("lastCheck", jCurrentTimeMillis).putInt("lastApkVersionCode", i).apply();
                Envelope best = fetchBestEnvelope();
                byte[] bArrDownload = best.raw;
                JSONObject jSONObjectVerifyEnvelope = best.payload;
                requireCompatible(jSONObjectVerifyEnvelope);
                long j = jSONObjectVerifyEnvelope.getLong("revision");
                synchronized (this) {
                    JSONObject jSONObjectState = state();
                    if (j <= Math.max(Math.max(jSONObjectState.optLong("active"), jSONObjectState.optLong("pending")), jSONObjectState.optLong("blocked"))) {
                        jSONObjectPut = status().put("checking", false);
                    } else {
                        stage(bArrDownload, downloadFirst(Endpoints.mirrorsOf(jSONObjectVerifyEnvelope.getString("url")), 12582912));
                        jSONObjectPut = status().put("checking", false);
                    }
                }
            } else {
                jSONObjectPut = status().put("checking", false);
            }
            this.checking.set(false);
            return jSONObjectPut;
        } catch (Throwable th) {
            this.checking.set(false);
            throw th;
        }
    }

    JSONObject verifyEnvelope(byte[] bArr) throws Exception {
        JSONObject jSONObject = new JSONObject(new String(bArr, StandardCharsets.UTF_8));
        byte[] bArrDecode = Base64.decode(jSONObject.getString(VlcPlayerActivity.EXTRA_PAYLOAD), 0);
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(this.publicKey);
        signature.update(bArrDecode);
        if (!signature.verify(Base64.decode(jSONObject.getString("signature"), 0))) {
            throw new SecurityException("资源更新签名无效");
        }
        JSONObject jSONObject2 = new JSONObject(new String(bArrDecode, StandardCharsets.UTF_8));
        if (jSONObject2.getInt("schema") != 1 || jSONObject2.getLong("revision") < 1 || !jSONObject2.getString("sha256").matches("[0-9a-f]{64}") || jSONObject2.getLong("size") <= 0 || jSONObject2.getLong("size") > 12582912) {
            throw new IllegalArgumentException("资源更新清单无效");
        }
        new URL(jSONObject2.getString("url"));
        return jSONObject2;
    }

    private void requireCompatible(JSONObject jSONObject) throws Exception {
        int i = this.context.getPackageManager().getPackageInfo(this.context.getPackageName(), 0).versionCode;
        if (jSONObject.optLong("revision") < BUNDLED_UI_REVISION || jSONObject.optInt("contentModeVersion") != 1 || jSONObject.getInt("minVersionCode") > i || jSONObject.getInt("bridgeVersion") != 1 || jSONObject.optInt("maxVersionCode", Integer.MAX_VALUE) < i) {
            throw new IllegalArgumentException("资源包需要更新应用版本");
        }
    }

    /**
     * 解包并暂存一个热更资源包。成功后状态标成 pending，等下次启动才生效。
     *
     * 三道校验在下载阶段就做过了（签名、sha256、体积），这里只做结构校验：
     * 条目数 ≤ 512、路径不许重名、只允许 ui/ theme/ 下的白名单后缀、
     * 解压总大小 ≤ 25MB（防 zip bomb）、ui/ 下必须有 app.html/app.js/app.css。
     *
     * ⚠ 2026-10-17 修过一次 NullPointerException：jadx 把 try-with-resources 的
     * 「主异常寄存器」拆成了 th 和 zipInputStream 两个变量，正常路径走到收尾段时
     * 两个都是 null，close() 一抛就 NPE。现在改回 try-with-resources，由编译器
     * 生成 addSuppressed，语义与 smali 的 :catchall_8 一致。
     */
    synchronized void stage(byte[] envelopeBytes, byte[] payload) throws Exception {
        JSONObject envelope = verifyEnvelope(envelopeBytes);
        requireCompatible(envelope);
        if (payload.length != envelope.getLong("size")
                || !SiteProfile.hex(MessageDigest.getInstance("SHA-256").digest(payload)).equals(envelope.getString("sha256"))) {
            throw new SecurityException("资源包校验失败");
        }

        long revision = envelope.getLong("revision");
        JSONObject state = state();
        long highestKnown = Math.max(Math.max(state.optLong("active"), state.optLong("pending")), state.optLong("blocked"));
        if (revision <= highestKnown) {
            return;
        }

        File staging = new File(this.root, revision + ".staging");
        File target = directory(revision);
        delete(staging);
        staging.mkdirs();

        try {
            unpack(payload, envelopeBytes, staging, envelope, state, revision);
        } catch (Exception e) {
            // 任何失败都把半成品目录清掉，别在盘上留个不完整包
            delete(staging);
            throw e;
        }
    }

    /** 解包主体。抽出来是为了让 stage() 的失败清理只有一个出口。 */
    private void unpack(byte[] payload, byte[] envelopeBytes, File staging, JSONObject envelope, JSONObject state, long revision) throws Exception {
        HashSet<String> seen = new HashSet<>();
        int entryCount = 0;
        long totalBytes = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(payload))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES || !seen.add(entry.getName())) {
                    throw new IOException("资源包文件异常");
                }

                File out = inside(staging, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }

                if (!entry.getName().matches("(?:ui/|theme/).*|site\\.json|theme\\.json")
                        || !entry.getName().matches(".*\\.(html|css|js|svg|png|jpg|jpeg|webp|gif|json|woff|woff2|txt)$")) {
                    throw new IOException("资源包含有不支持的文件");
                }
                out.getParentFile().mkdirs();

                try (FileOutputStream outStream = new FileOutputStream(out)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        totalBytes += read;
                        if (totalBytes > MAX_UNPACKED_BYTES) {
                            throw new IOException("资源包解压大小超限");
                        }
                        outStream.write(buffer, 0, read);
                    }
                }
            }
        }

        new SiteProfile(this.context, json(new File(staging, "site.json")));
        validateTheme(staging, json(new File(staging, "theme.json")));

        File uiDir = new File(staging, "ui");
        // 包里没带 ui/ 时，从上个版本的 ui/ 复制一份过来（theme-only 更新）
        if (!uiDir.exists() && !envelope.optBoolean("resetUi", false) && state.optLong("active") != 0) {
            File previousUi = new File(directory(state.optLong("active")), "ui");
            if (previousUi.isDirectory()) {
                copyUi(previousUi, uiDir, new long[]{totalBytes});
            }
        }
        if (uiDir.isDirectory()
                && (!new File(uiDir, "app.html").isFile()
                    || !new File(uiDir, "app.js").isFile()
                    || !new File(uiDir, "app.css").isFile())) {
            throw new IOException("界面包缺少入口文件");
        }

        // envelope.json 存的是**签名信封原文**，不是资源包。
        //
        // 这里原来写的是 payload（= zip 字节），而 metadata() 又拿这个文件去
        // verifyEnvelope() —— 对一堆 zip 字节做 JSONObject 解析，必然抛异常。
        // 后果不是「热更失败」，是 beginSession 每次都走 catch：新版本被标成
        // blocked，active 也被清掉，最后 load(0) 回内置。表现是「热更发布了、
        // 下载了、提示了下次启动生效，但重启之后永远是内置版本」，而且一条
        // 错误日志都不打（2026-10-18 实测踩到，加日志才看出来）。
        try (FileOutputStream out = new FileOutputStream(new File(staging, "envelope.json"))) {
            out.write(envelopeBytes);
        }

        delete(directory(revision));
        if (!staging.renameTo(directory(revision))) {
            throw new IOException("无法保存资源包");
        }
        state.put("pending", revision);
        save(state);
    }

    private void copyUi(File file, File file2, long[] jArr) throws Exception {
        if (file.getCanonicalPath().startsWith(this.root.getCanonicalPath() + File.separator)) {
            if (file2.getCanonicalPath().startsWith(this.root.getCanonicalPath() + File.separator)) {
                if (file.isDirectory()) {
                    if (!file2.isDirectory() && !file2.mkdirs()) {
                        throw new IOException("无法保留界面目录");
                    }
                    File[] fileArrListFiles = file.listFiles();
                    if (fileArrListFiles != null) {
                        for (File file3 : fileArrListFiles) {
                            copyUi(file3, new File(file2, file3.getName()), jArr);
                        }
                        return;
                    }
                    return;
                }
                long length = jArr[0] + file.length();
                jArr[0] = length;
                if (length > 26214400) {
                    throw new IOException("合并后资源包大小超限");
                }
                try (FileInputStream in = new FileInputStream(file);
                     FileOutputStream out = new FileOutputStream(file2)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                return;
            }
        }
        throw new IOException("界面目录越界");
    }

    private void validateTheme(File file, JSONObject jSONObject) throws Exception {
        String[] strArr = {"startsAt", "endsAt"};
        for (int i = 0; i < 2; i++) {
            String str = strArr[i];
            if (!jSONObject.isNull(str) && jSONObject.has(str)) {
                Instant.parse(jSONObject.getString(str));
            }
        }
        if (!jSONObject.isNull("startsAt") && jSONObject.has("startsAt") && !jSONObject.isNull("endsAt") && jSONObject.has("endsAt") && !Instant.parse(jSONObject.getString("endsAt")).isAfter(Instant.parse(jSONObject.getString("startsAt")))) {
            throw new IllegalArgumentException("主题起止时间无效");
        }
        if (jSONObject.has("css")) {
            requireThemeAsset(file, jSONObject.getString("css"));
        }
        JSONObject jSONObjectOptJSONObject = jSONObject.optJSONObject("background");
        if (jSONObjectOptJSONObject != null && jSONObjectOptJSONObject.has("asset")) {
            requireThemeAsset(file, jSONObjectOptJSONObject.getString("asset"));
        }
        JSONObject jSONObjectOptJSONObject2 = jSONObject.optJSONObject("dock");
        JSONObject jSONObjectOptJSONObject3 = jSONObjectOptJSONObject2 == null ? null : jSONObjectOptJSONObject2.optJSONObject("icons");
        Iterator<String> itEmptyIterator = jSONObjectOptJSONObject3 == null ? Collections.emptyIterator() : jSONObjectOptJSONObject3.keys();
        while (itEmptyIterator.hasNext()) {
            requireThemeAsset(file, jSONObjectOptJSONObject3.getString(itEmptyIterator.next()));
        }
    }

    private void requireThemeAsset(File file, String str) throws Exception {
        if (!str.startsWith("theme/") || !inside(file, str).isFile()) {
            throw new IOException("主题资源不存在");
        }
    }

    private void prune(JSONObject jSONObject) throws Exception {
        HashSet hashSet = new HashSet(Arrays.asList(Long.toString(jSONObject.optLong("active")), Long.toString(jSONObject.optLong("previous")), Long.toString(jSONObject.optLong("pending"))));
        File[] fileArrListFiles = this.root.listFiles();
        if (fileArrListFiles != null) {
            for (File file : fileArrListFiles) {
                if (file.isDirectory() && file.getName().matches("[0-9]+(?:\\.staging)?") && !hashSet.contains(file.getName())) {
                    delete(file);
                }
            }
        }
    }

    private void delete(File file) throws Exception {
        if (file.exists()) {
            if (!file.getCanonicalPath().startsWith(this.root.getCanonicalPath() + File.separator)) {
                throw new IOException("资源目录越界");
            }
            File[] fileArrListFiles = file.listFiles();
            if (fileArrListFiles != null) {
                for (File file2 : fileArrListFiles) {
                    delete(file2);
                }
            }
            if (!file.delete()) {
                throw new IOException("无法清理资源目录");
            }
        }
    }

    static File inside(File file, String str) throws Exception {
        if (str.isEmpty() || str.startsWith("/") || str.contains("\\") || str.contains("%") || Arrays.asList(str.split("/")).contains("..")) {
            throw new IOException("资源路径无效");
        }
        File file2 = new File(file, str);
        if (file2.getCanonicalPath().startsWith(file.getCanonicalPath() + File.separator)) {
            return file2;
        }
        throw new IOException("资源路径越界");
    }

    private static JSONObject json(File file) throws Exception {
        return new JSONObject(new String(read(new FileInputStream(file), 131072), StandardCharsets.UTF_8));
    }

    /**
     * 把流读进内存，超过 limit 字节就抛，读完关流。
     *
     * 关流放在这里而不是让调用方管：五个调用点全是「开个流、读完就扔」
     * （assets 里的公钥、state.json、envelope.json、site.json、HTTP 响应体）。
     * 以前是 jadx 展开的 addSuppressed 三件套，有一处把主异常变量提到了方法头，
     * null 上调用 addSuppressed 直接 NPE —— 2026-10-17 就是这里崩的。
     */
    static byte[] read(InputStream inputStream, int limit) throws IOException {
        try (InputStream in = inputStream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > limit) {
                    throw new IOException("资源文件大小超限");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static byte[] download(String str, int i) throws Exception {
        int i2 = 0;
        while (i2 < 6) {
            URL url = new URL(str);
            if (!ProxyConfig.MATCH_HTTPS.equals(url.getProtocol())) {
                throw new SecurityException("资源更新必须使用 HTTPS");
            }
            HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
            httpURLConnection.setConnectTimeout(15000);
            httpURLConnection.setReadTimeout(30000);
            httpURLConnection.setInstanceFollowRedirects(false);
            httpURLConnection.setRequestProperty(Http.USER_AGENT, "AIIvideo-HotUpdate/1");
            try {
                int responseCode = httpURLConnection.getResponseCode();
                if (responseCode < 300 || responseCode >= 400) {
                    if (responseCode != 200) {
                        throw new IOException("资源更新连接失败（" + responseCode + "）");
                    }
                    byte[] bArr = read(httpURLConnection.getInputStream(), i);
                    httpURLConnection.disconnect();
                    return bArr;
                }
                String headerField = httpURLConnection.getHeaderField(Http.LOCATION);
                if (headerField == null) {
                    throw new IOException("无效更新跳转");
                }
                String string = new URL(url, headerField).toString();
                httpURLConnection.disconnect();
                i2++;
                str = string;
            } catch (Throwable th) {
                httpURLConnection.disconnect();
                throw th;
            }
        }
        throw new IOException("更新跳转次数超限");
    }

    /** 一份通过验签的信封，连同它的原始字节（stage 需要原文再验一次）。 */
    private static final class Envelope {
        final byte[] raw;
        final JSONObject payload;

        Envelope(byte[] bArr, JSONObject jSONObject) {
            this.raw = bArr;
            this.payload = jSONObject;
        }
    }

    /**
     * 依次尝试一组地址，返回第一份拿到的内容。
     *
     * 用在下发热更包：信封里给的地址是 GitHub 的，国内直连基本超时，所以
     * 前面插了镜像。任何一个成功就停，全失败才把最后一个错误抛出去 ——
     * 报错要保留原因，不然用户只看到「更新失败」四个字，没法判断是网断了
     * 还是包坏了。
     */
    private static byte[] downloadFirst(java.util.List<String> list, int i) throws Exception {
        Exception exc = null;
        for (int j = 0; j < list.size(); j++) {
            try {
                return download(list.get(j), i);
            } catch (Exception e) {
                exc = e;
            }
        }
        throw exc == null ? new IOException("没有可用的更新地址") : exc;
    }

    /**
     * 把清单的所有候选都拉一遍，取 revision 最大的那份。
     *
     * ── 为什么不「第一个成功就用」──
     * raw.githubusercontent 和几个镜像前面都有 CDN 缓存（实测大约 5 分钟）。
     * 刚发布时完全可能 A 镜像还吐着上一版、B 镜像已经拿到新版。如果按顺序
     * 取第一个，就会一直读到旧的那份，表现是「发布了但客户端永远不更新」，
     * 而且下个 30 分钟周期可能又读到同一个旧镜像，卡死在那儿。
     *
     * 三份清单加起来不到 2KB，全拉一遍取最新，代价可以忽略。
     *
     * 全部失败时抛最后一个错误；任何一份验签不过也算失败（不能因为它是
     * 「最后一份能下载的」就放行）。
     */
    private Envelope fetchBestEnvelope() throws Exception {
        Envelope best = null;
        Exception exc = null;
        java.util.List<String> list = Endpoints.hotIndexUrls();
        for (int i = 0; i < list.size(); i++) {
            try {
                byte[] bArrDownload = download(list.get(i), 262144);
                JSONObject jSONObjectVerifyEnvelope = verifyEnvelope(bArrDownload);
                if (best == null
                        || jSONObjectVerifyEnvelope.getLong("revision") > best.payload.getLong("revision")) {
                    best = new Envelope(bArrDownload, jSONObjectVerifyEnvelope);
                }
            } catch (Throwable th) {
                exc = th instanceof Exception ? (Exception) th : new Exception(th);
            }
        }
        if (best != null) return best;
        throw exc == null ? new IOException("没有可用的热更清单") : exc;
    }

    /*
     * ⚠ 这个方法是反编译拼出来的，且踩过坑，改之前先读完。
     *
     * 原字节码里 MIME 类型存在寄存器 v7/v10 上，String.hashCode() 的
     * sparse-switch 分派到各个分支去改写它。jadx 还原不了 switch-over-string，
     * 把同一个寄存器拆成了两个 Java 变量，结果 return 时取错了那个 ——
     * 表现是所有 css/js/png 返回错误的 Content-Type，配合下面响应头里的
     * X-Content-Type-Options: nosniff，浏览器直接拒绝加载样式表，
     * 页面 HTML 出来了但完全没有样式，且不崩溃、logcat 干净。
     *
     * 2026-10-17 已按 smali 的 :goto_4（move-object v10, v7）修正为统一走 str3。
     * 那段"Code duplicated"是三个 catch handler 共用一个收尾块被展开成的副本，
     * 合并它们要小心，别顺手动到 MIME 那条线。
     */
    WebResourceResponse resource(Session session, String str) {
        File fileInside;
        InputStream inputStreamOpen;
        // 这两个是 jadx 还原 switch-over-string 时打散的临时变量（文件头有
        // "Failed to restore switch over string" 警告）。字节码里 v1/v10 在
        // :goto_4 处由不同分支赋不同值，javac 的数据流看不到这一点。
        // 所有实际执行路径都会在 return 前赋值，初值只为通过编译。
        HashMap map;
        try {
            String str3 = "application/javascript";
            String str4 = null;
            if (!"runtime.js".equals(str)) {
                if (session.directory == null || "hot-runtime.js".equals(str) || "site-default.json".equals(str) || "hot-update-public.pem".equals(str)) {
                    fileInside = null;
                } else {
                    fileInside = inside(session.directory, str.startsWith("theme/") ? str : "ui/" + str);
                }
                inside(this.root, str);
                inputStreamOpen = (fileInside == null || !fileInside.isFile()) ? this.context.getAssets().open(str) : new FileInputStream(fileInside);
                String lowerCase = str.substring(str.lastIndexOf(46) + 1).toLowerCase(Locale.ROOT);
                switch (lowerCase.hashCode()) {
                    case 3401:
                        if (!lowerCase.equals("js")) {
                            str3 = "application/octet-stream";
                        }
                        break;
                    case 98819:
                        if (!lowerCase.equals("css")) {
                            str3 = "application/octet-stream";
                        } else {
                            str3 = "text/css";
                        }
                        break;
                    case 102340:
                        if (!lowerCase.equals("gif")) {
                            str3 = "application/octet-stream";
                        } else {
                            str3 = "image/gif";
                        }
                        break;
                    case 105441:
                        if (!lowerCase.equals("jpg")) {
                            str3 = "application/octet-stream";
                        } else {
                            str3 = Mime.IMAGE_JPEG;
                        }
                        break;
                    case 111145:
                        if (!lowerCase.equals("png")) {
                            str3 = "application/octet-stream";
                        } else {
                            str3 = Mime.IMAGE_PNG;
                        }
                        break;
                    case 114276:
                        if (!lowerCase.equals("svg")) {
                            str3 = "application/octet-stream";
                        } else {
                            str3 = "image/svg+xml";
                        }
                        break;
                    case 3213227:
                        if (!lowerCase.equals("html")) {
                            str3 = "application/octet-stream";
                        } else {
                            str3 = "text/html";
                        }
                        break;
                    case 3268712:
                        if (!lowerCase.equals("jpeg")) {
                            str3 = "application/octet-stream";
                        } else {
                            str3 = Mime.IMAGE_JPEG;
                        }
                        break;
                    case 3271912:
                        if (!lowerCase.equals("json")) {
                            str3 = "application/octet-stream";
                        } else {
                            str3 = "application/json";
                        }
                        break;
                    case 3645340:
                        if (!lowerCase.equals("webp")) {
                            str3 = "application/octet-stream";
                        } else {
                            str3 = Mime.IMAGE_WEBP;
                        }
                        break;
                    default:
                        str3 = "application/octet-stream";
                        break;
                }
                map = new HashMap();
                map.put(Http.CACHE_CONTROL, "no-store");
                map.put(Http.X_CONTENT_TYPE_OPTIONS, "nosniff");
                if ("text/html".equals(str3)) {
                    map.put(Http.CONTENT_SECURITY_POLICY, "default-src 'self' data:; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; media-src 'self' https: data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'self'; form-action 'none'");
                }
                if (str3.startsWith("image/")) {
                    str4 = "UTF-8";
                }
                return new WebResourceResponse(str3, str4, 200, "OK", map, inputStreamOpen);
            }
            inputStreamOpen = new ByteArrayInputStream(("window.HotRuntime=" + new JSONObject().put("revision", session.revision).put("version", session.version).put("theme", session.theme).put("bootId", session.bootId) + ";").getBytes(StandardCharsets.UTF_8));
            map = new HashMap();
            map.put(Http.CACHE_CONTROL, "no-store");
            map.put(Http.X_CONTENT_TYPE_OPTIONS, "nosniff");
            if (str3.startsWith("image/")) {
                str4 = "UTF-8";
            }
            return new WebResourceResponse(str3, str4, 200, "OK", map, inputStreamOpen);
        } catch (Exception unused) {
            return new WebResourceResponse(AssetHelper.DEFAULT_MIME_TYPE, "UTF-8", 404, "Not Found", Collections.EMPTY_MAP, new ByteArrayInputStream(new byte[0]));
        }
    }

    static boolean trusted(Uri uri) {
        if (ProxyConfig.MATCH_HTTPS.equals(uri.getScheme()) && WebViewAssetLoader.DEFAULT_DOMAIN.equals(uri.getHost()) && uri.getUserInfo() == null) {
            return (uri.getPort() == -1 || uri.getPort() == 443) && uri.getPath().startsWith("/assets/");
        }
        return false;
    }
}
