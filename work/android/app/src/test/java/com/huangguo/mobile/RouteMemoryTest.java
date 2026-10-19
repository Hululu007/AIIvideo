package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** {@link RouteMemory} 的行为契约：学习、命中、过期、淘汰、跨进程文件共享。 */
public class RouteMemoryTest {

    private File dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("routemem").toFile();
        RouteMemory.initForTest(dir);
    }

    @After
    public void tearDown() {
        RouteMemory.initForTest(null);
    }

    @Test
    public void noOpinionBeforeLearn() {
        assertNull(RouteMemory.opinion("a.com"));
    }

    @Test
    public void learnThenHit() {
        RouteMemory.learn("a.com", RouteMemory.ROUTE_DIRECT);
        assertEquals(RouteMemory.ROUTE_DIRECT, RouteMemory.opinion("a.com"));
    }

    @Test
    public void forgetDropsEntry() {
        RouteMemory.learn("a.com", RouteMemory.ROUTE_PROXY);
        RouteMemory.forget("a.com");
        assertNull(RouteMemory.opinion("a.com"));
    }

    @Test
    public void expiredEntryYieldsNull() throws Exception {
        RouteMemory.learn("a.com", RouteMemory.ROUTE_DIRECT);
        // 直接把文件里的 at 改到 25 小时前。
        File f = new File(dir, "route-memory.json");
        String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        json = json.replace("\"at\":" + (System.currentTimeMillis() / 1000), "\"at\":0")
                   .replaceAll("\"at\":\\d+", "\"at\":" + (System.currentTimeMillis() - 25L * 3600_000L));
        Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
        assertNull(RouteMemory.opinion("a.com"));
    }

    @Test
    public void corruptedFileYieldsEmptyNotThrow() throws Exception {
        RouteMemory.learn("a.com", RouteMemory.ROUTE_DIRECT);
        File f = new File(dir, "route-memory.json");
        Files.write(f.toPath(), "这不是 JSON{{{".getBytes(StandardCharsets.UTF_8));
        assertNull(RouteMemory.opinion("a.com"));
        // 而且还能继续学。
        RouteMemory.learn("b.com", RouteMemory.ROUTE_PROXY);
        assertEquals(RouteMemory.ROUTE_PROXY, RouteMemory.opinion("b.com"));
    }

    @Test
    public void persistsAcrossReload() {
        RouteMemory.learn("a.com", RouteMemory.ROUTE_PROXY);
        // 模拟另一个进程：整个缓存清零重读文件。
        RouteMemory.initForTest(dir);
        assertEquals(RouteMemory.ROUTE_PROXY, RouteMemory.opinion("a.com"));
    }

    @Test
    public void invalidRouteIgnored() {
        RouteMemory.learn("a.com", "satellite");
        assertNull(RouteMemory.opinion("a.com"));
    }

    @Test
    public void isConnectFailureClassification() {
        assertTrue(AiiHttp.isConnectFailure(new SocketTimeoutException("connect timed out")));
        assertTrue(AiiHttp.isConnectFailure(new IOException("Connection reset")));
        assertTrue(AiiHttp.isConnectFailure(new IOException((String) null)));
        // 状态码失败 = 已到达对方，路由是通的：不换路。
        assertTrue(!AiiHttp.isConnectFailure(new IOException("HTTP 403 @ https://x.org/a")));
    }

    @Test
    public void flipRouteSwaps() {
        assertEquals(RouteMemory.ROUTE_PROXY, AiiHttp.flipRoute(RouteMemory.ROUTE_DIRECT));
        assertEquals(RouteMemory.ROUTE_DIRECT, AiiHttp.flipRoute(RouteMemory.ROUTE_PROXY));
        assertEquals("weird", AiiHttp.flipRoute("weird"));
    }
}
