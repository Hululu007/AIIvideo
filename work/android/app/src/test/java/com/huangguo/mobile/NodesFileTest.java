package com.huangguo.mobile;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 内置节点文件必须能被 NodesFeed 的校验放过（全 https 代理那种）。 */
public class NodesFileTest {
    @Test
    public void 资产里的节点池能通过校验() throws Exception {
        String raw = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/assets/free-nodes.json")), "UTF-8");
        NodesFeed.Stats stats = NodesFeed.validate(raw);
        assertNotNull("NodesFeed 拒了这份文件", stats);
        assertTrue("节点数 " + stats.nodes, stats.nodes >= 3);
        System.out.println("links=" + stats.links + " nodes=" + stats.nodes + " updated=" + stats.updated);
    }
}
