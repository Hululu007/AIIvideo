package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 空封面地址：**没封面不是故障**。
 *
 * 线上证据（2.0.23，13 台设备共 15 条）：`stage=cover` 的
 * `IllegalArgumentException: 无效封面地址：` —— 冒号后面什么都没有，`ms` 全是 0~3。
 *
 * 链路是这样的：`adapters.js` 的选集适配器会给条目 `cover: ''`，而 hero 轮播那条
 * （`app.js` 的 setHero）取封面时没有判空，空串一路走到 Java 的
 * `SiteProfile.coverUrl("")` → `new URI("")` 的 scheme 是 null → 当场抛。
 * 每轮 banner 轮播重报一遍，所以是「十几台设备、每台一两条」而不是「一台刷几百条」。
 *
 * 两层都补了：前端 `item.cover ? await cover.get(...) : ''`，Java 这里早退。
 * 这个测试钉的是 Java 那一层 —— 前端哪天再漏一个空值下来，它也不该被算成
 * 一场网络/配置故障。
 */
public class CoverBlankUrlTest {

    @Test
    public void 空串空引用全空白都算没有封面() {
        assertTrue(SiteRepository.isBlankCover(null));
        assertTrue(SiteRepository.isBlankCover(""));
        assertTrue(SiteRepository.isBlankCover("   "));
        assertTrue(SiteRepository.isBlankCover("\t\n"));
    }

    @Test
    public void 真地址不算空() {
        assertFalse(SiteRepository.isBlankCover("https://img.example.com/a.jpg"));
        // 相对路径不是空 —— 它是个**真错了**的地址，该照常报「无效封面地址」。
        // 这条边界要是糊了，配置写错就再也没人发现了。
        assertFalse(SiteRepository.isBlankCover("/upload_01/x.jpg"));
        assertFalse(SiteRepository.isBlankCover("//img.example.com/a.jpg"));
    }

    @Test
    public void 空地址不进负缓存() {
        // 进缓存就麻烦了：一个条目没有封面，会被记成「这台主机的封面挂过」，
        // 再连带拦掉同主机所有正常的封面。
        SiteRepository.resetCoverFailureCache();
        assertTrue("空地址在进负缓存之前就早退了", SiteRepository.isBlankCover(""));
        assertNull("空串取不出主机，也就没有主机级失败", SiteRepository.hostFailure(""));
        assertNull(SiteRepository.hostFailure(null));
    }

    @Test
    public void 空地址不会走到主机级失败() {
        // 记一条真失败之后，空地址仍然不该命中它 —— 负缓存按 URL/主机取键，
        // 而空地址连键都没有。
        SiteRepository.resetCoverFailureCache();
        SiteRepository.rememberFailure("https://cdn.example.com/a.jpg", "Connection reset");
        assertNull(SiteRepository.hostFailure(""));
        assertEquals("", SiteRepository.hostOf(""));
    }
}
