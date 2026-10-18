package com.huangguo.mobile;

import android.content.Context;
import android.util.Base64;
import com.huangguo.mobile.Text;
import com.huangguo.mobile.Ctl;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/**
 * 当前源的**内容通路**：REST 取 JSON、封面缓存、控制面 profile。
 *
 * ── 2026-10-18：删掉八条假接口 ─────────────────────────────────────
 * 这里原来有 home / category / search / tags / tagListing / episode /
 * recommend 七个方法，每个都只有一句
 * {@code throw new UnsupportedOperationException("…已改为纯 REST，抓 HTML 的分支
 * 已移除")}，再加上 MainActivity 里对应的七条桥指令。它们不是「以后要实现的桩」，
 * 而是**已经死掉的分支**：前端的内容请求统一走 {@code web.paths} 拼出来的路径
 * （→ detail 桥 → 这里 → AiiHttp），永远到不了这几条命令。
 *
 * 留着它们的代价不是几行代码，而是：任何读代码的人都会以为「内容语义在 Java 里
 * 还有一半」，于是新加一个源时先来这里找该覆盖哪个方法 —— 而正确答案是
 * 「去配置里写 paths」。
 */
final class SiteRepository {
    private final Context appContext;
    private final File coverDir;
    private final SiteProfile profile;

    private byte[] fetch(String str) throws Exception {
        return AiiHttp.get(str, this.profile);
    }

    /**
     * 控制面（源/播放器/代理/进度/HTTP）要用的 profile。
     *
     * 单独开一个访问器而不是把字段放开：detail 那条路是「按 id 取数据」，
     * 这条是「把当前生效的源配置交给状态机」—— 两者语义不同，
     * 将来 profile 的来源变了（比如按请求选源），只改这里。
     */
    SiteProfile profileForControl() {
        return this.profile;
    }

    JSONObject detail(String str) throws Exception {
        String str2;
        if (str.startsWith("__")) {
            return UserSource.handle(this.appContext, this.profile, str);
        }
        if (str.startsWith("https://")) {
            str2 = str;
        } else {
            if (!str.startsWith("/")) {
                throw new UnsupportedOperationException("路径既不是 / 开头的动作路径，也不是 https 绝对地址，无法处理");
            }
            str2 = this.profile.origin + str;
        }
        return new JSONObject(new String(fetch(str2), StandardCharsets.UTF_8));
    }

    SiteRepository(Context context) {
        this(context, HotUpdateManager.get(context).currentSite());
    }

    SiteRepository(Context context, SiteProfile siteProfile) {
        this.profile = siteProfile;
        this.appContext = context.getApplicationContext();
        File file = new File(context.getCacheDir(), "covers");
        this.coverDir = file;
        if (file.exists()) {
            return;
        }
        file.mkdirs();
    }

    String coverData(String str) throws Exception {
        String str2;
        String strCoverUrl = this.profile.coverUrl(str);
        URL url = new URL(strCoverUrl);
        String path = url.getPath();
        String lowerCase = path.substring(path.lastIndexOf(46) + 1).toLowerCase(Locale.ROOT);
        if (this.profile.object("cover").optBoolean("checkExtension", true) && !Arrays.asList("jpg", "jpeg", "png", "webp", "gif").contains(lowerCase)) {
            throw new IllegalArgumentException("不支持的封面格式");
        }
        byte[] bArrDigest = MessageDigest.getInstance("SHA-256").digest((this.profile.fingerprint + ":" + url.getHost() + path).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(String.format("%02x", Integer.valueOf(bArrDigest[i] & 255)));
        }
        File file = new File(this.coverDir, ((Object) sb) + ".img");
        byte[] bArr = null;
        if (file.isFile()) {
            byte[] allBytes = Files.readAllBytes(file.toPath());
            if (repairConfiguredHeader(allBytes)) {
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                try {
                    fileOutputStream.write(allBytes);
                    fileOutputStream.close();
                } catch (Throwable th) {
                    try {
                        fileOutputStream.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                    throw th;
                }
            }
            if (!validImage(allBytes)) {
                file.delete();
            } else {
                file.setLastModified(System.currentTimeMillis());
                bArr = allBytes;
            }
        }
        if (bArr == null) {
            byte[] bArrFetch = fetch(strCoverUrl);
            String strOptString = this.profile.object("cover").optString("mode");
            if ("aes-cbc".equals(strOptString) || (Text.TEXT_EMPHASIS_AUTO.equals(strOptString) && !validImage(bArrFetch))) {
                Cipher cipher = Cipher.getInstance(this.profile.object("cover").getString("transformation"));
                cipher.init(2, new SecretKeySpec(this.profile.keyBytes("key"), "AES"), new IvParameterSpec(this.profile.keyBytes("iv")));
                bArrFetch = cipher.doFinal(bArrFetch);
            }
            bArr = bArrFetch;
            repairConfiguredHeader(bArr);
            if (!validImage(bArr)) {
                throw new IllegalStateException("封面解密结果无效");
            }
            FileOutputStream fileOutputStream2 = new FileOutputStream(file);
            try {
                fileOutputStream2.write(bArr);
                fileOutputStream2.close();
                trimCoverCache();
            } catch (Throwable th3) {
                try {
                    fileOutputStream2.close();
                } catch (Throwable th4) {
                    th3.addSuppressed(th4);
                }
                throw th3;
            }
        }
        byte b = bArr[0];
        if (b == -1) {
            str2 = Mime.IMAGE_JPEG;
        } else if (b == -119) {
            str2 = Mime.IMAGE_PNG;
        } else {
            str2 = b == 71 ? "image/gif" : Mime.IMAGE_WEBP;
        }
        return "data:" + str2 + ";base64," + Base64.encodeToString(bArr, 2);
    }

    private boolean repairConfiguredHeader(byte[] bArr) throws Exception {
        return ("legacy-xor".equals(this.profile.object("cover").optString("headerRepair")) && repairCoverHeader(bArr)) || this.profile.patchHeader(bArr);
    }

    private static boolean repairCoverHeader(byte[] bArr) {
        if (bArr.length < 12) {
            return false;
        }
        byte b = bArr[0];
        boolean z = b == -119 && bArr[1] == 80 && bArr[2] == 78 && bArr[3] == 71 && bArr[4] == 13 && bArr[5] == 10 && bArr[6] == 20 && bArr[7] == 10;
        boolean z2 = b == -1 && bArr[1] == -40 && bArr[2] == -1;
        boolean z3 = z2 && bArr[3] == -32 && bArr[6] == 68 && bArr[7] == 70 && bArr[8] == 73 && bArr[9] == 70 && bArr[10] == 0;
        boolean z4 = z2 && bArr[3] == -37 && (bArr[6] & 252) == 12;
        boolean z5 = z2 && bArr[3] == -31 && bArr[6] == 75 && bArr[7] == 120 && bArr[8] == 105 && bArr[9] == 102;
        boolean z6 = z2 && bArr[3] == -30 && bArr[6] == 71 && bArr[7] == 67 && bArr[8] == 67 && bArr[9] == 95;
        if (!z && !z3 && !z4 && !z5 && !z6) {
            return false;
        }
        bArr[6] = (byte) (bArr[6] ^ Ctl.SO);
        return true;
    }

    private static boolean validImage(byte[] bArr) {
        if (bArr.length < 16) {
            return false;
        }
        byte b = bArr[0];
        return (b == -1 && bArr[1] == -40) || (b == -119 && bArr[1] == 80 && bArr[2] == 78 && bArr[3] == 71 && bArr[4] == 13 && bArr[5] == 10 && bArr[6] == 26 && bArr[7] == 10) || ((b == 82 && bArr[1] == 73 && bArr[2] == 70 && bArr[3] == 70 && bArr[8] == 87 && bArr[9] == 69 && bArr[10] == 66 && bArr[11] == 80) || (b == 71 && bArr[1] == 73 && bArr[2] == 70));
    }

    private void trimCoverCache() {
        File[] fileArrListFiles = this.coverDir.listFiles();
        if (fileArrListFiles == null) {
            return;
        }
        long length = 0;
        for (File file : fileArrListFiles) {
            length += file.length();
        }
        if (length <= 157286400) {
            return;
        }
        Arrays.sort(fileArrListFiles, Comparator.comparingLong(File::lastModified));
        for (File file2 : fileArrListFiles) {
            if (length <= 157286400) {
                return;
            }
            long length2 = file2.length();
            if (file2.delete()) {
                length -= length2;
            }
        }
    }

    void clearCovers() {
        File[] fileArrListFiles = this.coverDir.listFiles();
        if (fileArrListFiles != null) {
            for (File file : fileArrListFiles) {
                file.delete();
            }
        }
    }
}
