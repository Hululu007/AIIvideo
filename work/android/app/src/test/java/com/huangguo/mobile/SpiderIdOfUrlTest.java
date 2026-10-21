package com.huangguo.mobile;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 换源顺带装 spider 时，从配置里的地址推出 id —— 推错就装不上，或者装成另一个 id。 */
public class SpiderIdOfUrlTest {

    @Test public void plainJs() {
        assertEquals("dozagonza",
                SpiderStore.idOfUrl("https://149.104.21.177.sslip.io/spiders/dozagonza.js"));
    }

    @Test public void queryIsDropped() {
        assertEquals("iwara",
                SpiderStore.idOfUrl("https://x.example/spiders/iwara.js?v=2"));
    }

    @Test public void noExtension() {
        assertEquals("cms", SpiderStore.idOfUrl("https://x.example/spiders/cms"));
    }
}
