import com.huangguo.mobile.UserSource;
import org.json.JSONObject;
import java.nio.file.*;

public class UserSourceTest {
    static int pass = 0, fail = 0;
    static void check(String label, String json, boolean shouldPass) {
        String result;
        try { result = UserSource.validate(new JSONObject(json)); }
        catch (Exception e) { result = "解析异常: " + e.getMessage(); }
        boolean ok = (result == null) == shouldPass;
        if (ok) pass++; else fail++;
        System.out.printf("  %s %-30s → %s%n", ok ? "ok  " : "FAIL", label,
                          result == null ? "通过" : result);
    }
    public static void main(String[] a) throws Exception {
        String real = new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8");
        String BASE = "\"web\":{\"paths\":{\"home\":\"/api/home\"},\"categories\":{\"x\":1}}";
        // 传进来的是内置模板 assets/site-default.json。它现在是一份**模板**，不是一份能用
        // 的用户配置：「用户自带源」那一轮之后它把 origin 清空了 —— 装完不自带任何站，
        // 这是刻意的。所以它按用户配置的规则**应该被拒**（缺 origin，而 home 是相对路径）。
        // 这一条顺带守着那个决定：谁要是往模板里塞回一个 origin，这里会红。
        check("内置模板（不带源，应被拒）", real, false);
        check("缺 origin",            "{" + BASE + "}", false);
        check("origin 是 http",       "{\"origin\":\"http://a.com\"," + BASE + "}", false);
        check("origin 带路径",        "{\"origin\":\"https://a.com/sub\"," + BASE + "}", false);
        check("origin 带查询参数",    "{\"origin\":\"https://a.com?x=1\"," + BASE + "}", false);
        check("缺 web",              "{\"origin\":\"https://a.com\"}", false);
        check("paths 为空",           "{\"origin\":\"https://a.com\",\"web\":{\"paths\":{},\"categories\":{\"x\":1}}}", false);
        check("缺 paths.home",        "{\"origin\":\"https://a.com\",\"web\":{\"paths\":{\"detail\":\"/d\"},\"categories\":{\"x\":1}}}", false);
        check("categories 为空",      "{\"origin\":\"https://a.com\",\"web\":{\"paths\":{\"home\":\"/x\"},\"categories\":{}}}", false);
        check("cate_id 为 0",         "{\"origin\":\"https://a.com\",\"web\":{\"paths\":{\"home\":\"/x\"},\"categories\":{\"x\":0}}}", false);
        check("fields 空列表",        "{\"origin\":\"https://a.com\",\"web\":{\"paths\":{\"home\":\"/x\"},\"categories\":{\"x\":1},\"fields\":{\"title\":[]}}}", false);
        check("cover 缺 key",         "{\"origin\":\"https://a.com\"," + BASE + ",\"cover\":{\"mode\":\"aes-cbc\",\"iv\":\"x\"}}", false);
        check("最小可用配置",         "{\"origin\":\"https://a.com\"," + BASE + "}", true);
        System.out.printf("%n  通过 %d / 失败 %d%n", pass, fail);
    }
}
