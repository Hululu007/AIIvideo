package com.huangguo.mobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * assets 读取的小工具，带上限。
 *
 * <p>原来这个方法长在 {@code HotUpdateManager} 里，而热更层已经整块拆掉了
 * （2026-10-19）—— 它剩下的唯一通用职责就是「读完一个流，超过上限就报错」，
 * 所以搬到这儿，四个调用点（SiteProfile、NodesFeed）各改一行 import 都不用。
 */
final class Assets {

    private Assets() {
    }

    /**
     * 读到底并返回字节。
     *
     * @param limit 上限字节数。超了抛，不截断 —— 截断的 JSON 解析失败时的报错
     *              指向的是「格式错误」，跟真实原因（文件被换成了个大东西）差得远。
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
}
