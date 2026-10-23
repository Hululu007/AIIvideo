package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/**
 * 预热键的归一化：预热存的是内部路径，前端来要的是完整地址，两边必须落到同一个键上。
 * 这里踩过一次（见 AiiHttpCache.prefetchKey 的注释），所以钉住。
 */
public class AiiHttpCacheTest {

    @Test
    public void 完整地址归一成路径() {
        assertEquals("/api/home",
                AiiHttpCache.prefetchKey("https://cab3f.nt17g20k.cc/api/home"));
        assertEquals("/api/home?a=1",
                AiiHttpCache.prefetchKey("https://cab3f.nt17g20k.cc/api/home?a=1"));
    }

    @Test
    public void 内部路径原样保留() {
        assertEquals("/api/home", AiiHttpCache.prefetchKey("/api/home"));
    }

    @Test
    public void 空值与没有斜杠的值不炸() {
        assertEquals("", AiiHttpCache.prefetchKey(null));
        assertEquals("https://example.com", AiiHttpCache.prefetchKey("https://example.com"));
    }

    @Test
    public void 重置返回被清掉的条数() {
        int[] cleared = AiiHttpCache.reset();
        assertEquals(2, cleared.length);
        assertArrayEquals(new int[]{0, 0}, cleared);
    }
}
