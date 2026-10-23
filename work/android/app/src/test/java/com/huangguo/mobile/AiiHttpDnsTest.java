package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * DoH 应答的解析与「毒答案」筛选。2026-10-23 从 AiiHttp 拆出来时补的回归：
 * 这两条判断原来没有任何测试，而它们决定了 OkHttp 第一个去连哪个地址。
 */
public class AiiHttpDnsTest {

    @Test
    public void 只取A记录并丢掉IPv6() {
        String body = "{\"Answer\":["
                + "{\"type\":1,\"data\":\"43.229.39.228\"},"
                + "{\"type\":28,\"data\":\"2606:4700::1\"},"
                + "{\"type\":1,\"data\":\"2606:4700::2\"}]}";
        List<String> ips = AiiHttpDns.parseAnswers(body);
        assertEquals(Arrays.asList("43.229.39.228"), ips);
    }

    @Test
    public void 同一条地址只留一次() {
        String body = "{\"Answer\":["
                + "{\"type\":1,\"data\":\"1.2.3.4\"},"
                + "{\"type\":1,\"data\":\"1.2.3.4\"},"
                + "{\"type\":1,\"data\":\"5.6.7.8\"}]}";
        assertEquals(Arrays.asList("1.2.3.4", "5.6.7.8"), AiiHttpDns.parseAnswers(body));
    }

    @Test
    public void 毒答案在解析阶段就被筛掉() {
        String body = "{\"Answer\":["
                + "{\"type\":1,\"data\":\"0.0.0.0\"},"
                + "{\"type\":1,\"data\":\"127.0.0.1\"},"
                + "{\"type\":1,\"data\":\"255.255.255.255\"},"
                + "{\"type\":1,\"data\":\"43.229.39.228\"}]}";
        assertEquals(Arrays.asList("43.229.39.228"), AiiHttpDns.parseAnswers(body));
    }

    @Test
    public void 应答体坏掉时返回空表而不是抛() {
        assertTrue(AiiHttpDns.parseAnswers("not json").isEmpty());
        assertTrue(AiiHttpDns.parseAnswers("{}").isEmpty());
    }

    @Test
    public void usable的判据() {
        assertFalse(AiiHttpDns.usable(null));
        assertFalse(AiiHttpDns.usable(""));
        assertFalse(AiiHttpDns.usable("0.0.0.0"));
        assertFalse(AiiHttpDns.usable("0.1.2.3"));
        assertFalse(AiiHttpDns.usable("127.0.0.1"));
        assertFalse(AiiHttpDns.usable("255.1.2.3"));
        assertTrue(AiiHttpDns.usable("43.229.39.228"));
        assertTrue(AiiHttpDns.usable(" 8.8.8.8 "));
    }
}
