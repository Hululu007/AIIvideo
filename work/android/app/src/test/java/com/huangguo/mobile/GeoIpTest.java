package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

/**
 * IP → 国家。表是从 DB-IP 生成的二进制，这里验的是「解码器能不能把它读回来」。
 *
 * 覆盖三件事：格式头、二分查找、以及几条固定 IP 的答案。表换了（每月一份）
 * 这几条可能会变，但它们都是稳定的大段：谷歌 DNS、中国电信、本项目的 VPS。
 */
public class GeoIpTest {

    private static void load() throws Exception {
        File f = new File("src/main/assets/ip-country.bin");
        assertTrue("找不到 ip-country.bin：" + f.getAbsolutePath(), f.isFile());
        assertTrue("表解析失败", GeoIp.loadBytes(Files.readAllBytes(f.toPath())));
    }

    @Test
    public void 表能读回来并且查得到国家() throws Exception {
        load();
        assertEquals("US", GeoIp.rawCode("8.8.8.8"));
        assertEquals("CN", GeoIp.rawCode("202.96.128.86"));
        assertEquals("CN", GeoIp.rawCode("114.114.114.114"));
        // 项目自己在用的搬瓦工机器（bwh-usca6）
        assertEquals("US", GeoIp.rawCode("64.64.224.121"));
    }

    @Test
    public void 非法输入不抛异常() throws Exception {
        load();
        assertEquals(null, GeoIp.rawCode(""));
        assertEquals(null, GeoIp.rawCode("not-an-ip"));
        assertEquals(null, GeoIp.rawCode("999.1.1.1"));
        assertEquals(null, GeoIp.rawCode("1.2.3"));
    }

    @Test
    public void 标签带国旗() throws Exception {
        load();
        String label = GeoIp.rawLabel("8.8.8.8");
        assertTrue("没有国旗：" + label, label.contains("\uD83C\uDDFA"));   // 🇺 regional indicator
        assertTrue("没有名字：" + label, label.length() > 4);
    }
}
