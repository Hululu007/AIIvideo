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
        check("线上真实配置", real, true);
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
