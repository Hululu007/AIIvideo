# 账号后端

自建的登录 / 收藏 / 观看历史服务，跑在 `149.104.21.177`（云服务器，不是 BWG 那台）。

## 为什么做成「Parse 兼容」而不是自己一套 REST

因为**客户端里打包了完整的 Parse Android SDK**（115 个类）。原版把账号、收藏、
历史全挂在 Back4App 上，客户端是这么写的：

```java
ParseUser.logIn(...) / ParseUser.signUp(...)
ParseObject("UserLibrary") / ParseQuery.whereEqualTo("owner", userId)
ParseFile                       // 头像
```

所以换后端最省事、也最不容易出错的做法是**沿用这套协议，只把服务器地址换掉**：

```smali
# work/shells/cz_repack/smali/com/huangguo/mobile/HuangGuoApp.smali
- const-string v0, "https://parseapi.back4app.com/"
+ const-string v0, "https://149.104.21.177.sslip.io/api/"
```

appId / clientKey 还是 `res/values/strings.xml` 里那两个（`back4app_app_id` /
`back4app_client_key`），服务端的 `PARSE_APP_ID` 必须和它一致。

另一条路是自己拆掉 Parse 换成裸 REST —— 那要动 2000 多行反编译出来的 smali，
还得重写登录、注册、头像、收藏、历史、同步全部逻辑。不值得。

## 拓扑

```text
手机 ──https──> Caddy (host 网络，80/443)
                  ├─ /api/*  →  handle_path 剥掉前缀  →  127.0.0.1:8090  (account-api)
                  │                                          └─> account-db:5432 (PostgreSQL 16)
                  └─ /*      →  /srv/cfg 静态文件（测试用的站点配置）
```

`handle_path` 会**剥掉 `/api` 前缀**再转出去，所以 API 进程不知道自己外面还挂着
一层 —— 头像 URL 只能靠 `PUBLIC_BASE` 环境变量拼成绝对地址（见 `api/main.py`）。

## 部署 / 运维

```bash
cd /srv/account

docker compose up -d --build      # 起（改了代码就跑这个）
docker compose ps                 # 状态
docker compose logs -f api        # 日志
docker compose down               # 停（数据在 ./pgdata，不会丢）

./backup.sh                       # 手动备份一次 → backups/aii-<时间>.sql.gz
```

挂个每日备份：

```bash
crontab -e
0 4 * * * /srv/account/backup.sh >> /var/log/aii-backup.log 2>&1
```

配置在 `.env`（`chmod 600`，**不要提交**）。改完要 `docker compose up -d` 才生效。

## 接口

标准 Parse REST 的一个子集。认证只看 `X-Parse-Session-Token`，
`X-Parse-Application-Id` 不对直接 403。没有 REST API Key / Master Key 那套。

| 方法 | 路径 | 干什么 |
|---|---|---|
| POST | `/users` | 注册 |
| POST / GET | `/login` | 登录（SDK 两种都发过） |
| POST | `/logout` | 退出（那条 session 作废） |
| GET | `/users/me` | 当前用户 |
| PUT | `/users/<id>` | 改用户名 / 密码 / 头像 |
| POST / GET | `/classes/<类名>` | 建 / 查对象 |
| GET / PUT / DELETE | `/classes/<类名>/<id>` | 取 / 改 / 删一个对象 |
| POST | `/files/<名字>` | 上传（头像） |
| GET | `/files/<appId>/<名字>` | 取回 |

客户端实际用的类名只有两个：`UserLibrary` 和 `DualUserLibrary`。对象表是
「一张表 + JSONB」不是一类一表 —— 类名是客户端运行时决定的，照着一类一表建，
下次客户端加一个类就得改服务端。

### 几个不能想当然的地方

**`_method`。** Parse SDK 对「where 太长」的查询**不发 GET**，而是 POST 到同一个
地址、body 里带 `{"_method":"GET"}`：

```json
POST /api/classes/UserLibrary
{"where":{...},"limit":1,"order":"-updatedAt","_method":"GET"}
```

不认这个的话，那次查询会被当成「建一个新对象」写进去 —— 库里多一条内容是查询
参数的垃圾记录，而客户端的收藏列表是空的（它等的是 `results`）。

**`owner` 是 Pointer 不是字符串。**

```json
{"owner": {"__type": "Pointer", "className": "_User", "objectId": "q37l3PsLbG"}}
```

直接 `str()` 会得到一整串 JSON，跟什么都对不上，查询永远空 —— 界面上看起来就是
「收藏和历史都没了」，但一个错都不报。

**错误码要用官方那套数字。** 客户端是按 `code` 分支的：`101` 才报「账号不存在或
密码错误」，`202` 才报「已被注册」。自定义数字换来的是一句没有上下文的英文。

**时间格式必须带毫秒**：`2026-10-16T04:01:49.847Z`。少一位 SDK 解析异常。

**建表要加锁。** uvicorn 起了 2 个 worker，而 Postgres 的
`CREATE TABLE IF NOT EXISTS` **不是原子的** —— 两个会话可以同时通过「不存在」的
检查，然后一个成功、另一个撞 `pg_type_typname_nsp_index` 直接退出。第一次部署
就是这么挂的（容器报 healthy，因为健康检查探的是另一个活着的 worker，日志里却
是 `Application startup failed`）。现在用 `pg_advisory_lock` 串起来。

## 安全

- 口令 **scrypt**（`hashlib`，标准库），参数在 `main.py` 的 `PASSWORD_N/R/P`。
  **改这三个值会让所有旧口令失效。**
- 账号不存在和密码错误返回**同一个** code，否则输入框就成了「这个用户名有没有人
  注册」的探测接口。
- 对象查询强制 `owner_id = 当前用户`，**不接受例外**。客户端本来就带
  `where owner=<自己>`，但那是客户端在做过滤 —— 服务端不自己拦一道的话，
  改个请求就能把别人的收藏全拉走。（早先这里写过
  `owner_id = me or owner_id is null`，本意是兼容没设 ACL 的旧数据，
  实际后果是「凡是没有归属的对象对所有人可见」。）
- 头像文件不查 session —— Parse 的图片 URL 是直接给 `<img>` 用的，浏览器不会带
  自定义头。文件名里有 10 位随机串，等于不可猜的地址。
- ⚠️ **现在只有 IP + sslip.io 的域名，没有真域名。** 换台机器 IP 变了，所有已
  安装的 App 就都连不上了。要长期跑得先把域名定下来，改
  `HuangGuoApp.smali` 里那一行 + Caddyfile。

## 备份

`backup.sh` 走 `pg_dump` 出 gzip 到 `backups/`，只留 14 天。

数据库本身在 `./pgdata`（bind mount），和 compose 文件在一起。

⚠️ **`pgdata` 里是所有人的账号和口令哈希，别把它拷到别处，也别提交。**
