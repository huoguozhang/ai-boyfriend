# 生产部署（腾讯云 Lighthouse `101.43.26.29`）

一台 2 核 3.5G 的轻量应用服务器，四个进程共用：

```
公网 :443 ── nginx ──┬─ /            /opt/soulmate/web          前端静态（front/dist）
（LE IP 证书）        ├─ /api/       127.0.0.1:6100              Java 后端（SSE 必须关 buffering）
                     └─ /mem0/      127.0.0.1:6101              mem0（外部调试用；后端自己直连）

公网 :80  ── nginx ──┬─ /.well-known/acme-challenge/  出文件给 LE 验证
                     ├─ /mem0/      127.0.0.1:6101   明文保留（本地后端还在用它）
                     └─ /            301 到 https

systemd  ai-soulmate.service  → /opt/soulmate/server/app.jar（Java 21，-Xmx640m）
         certbot-renew.timer  → 每 3~4 天续一次 IP 证书

docker   soulmate-biz-pg  postgres:16   127.0.0.1:5433   业务库 ai-soulmate
         soulmate-pg      pgvector:pg17 0.0.0.0:5432     mem0 的向量库
         soulmate-mem0    memory-service 0.0.0.0:6101
```

业务库单独用 PG16，是为了和本地开发环境（Homebrew PostgreSQL 16）版本对齐；
mem0 那套是 pgvector/pg17，两套互不影响，端口也错开。

## HTTPS：Let's Encrypt 的 IP 证书

浏览器只在**安全上下文**（`https` 或 `localhost`）里暴露 `navigator.mediaDevices`，
纯 HTTP 下点「按住说话」直接抛错，语音等于废掉。所以这一层是为语音加的。

没有域名也能拿**公共可信**的证书：Let's Encrypt 自 2026-01-15 起正式签发
[IP 地址证书](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability)。限制很明确：

| 项目 | 值 |
|---|---|
| 有效期 | **160 小时**（约 6.7 天），只有 `shortlived` profile 有 |
| 验证方式 | 只能 http-01 / tls-alpn-01；**IP 没有 dns-01** |
| 客户端 | certbot ≥ 5.4（要 `--ip-address` + `--webroot`）；dnf 源的 2.8 不行 |
| 费用 | 免费，不需要账号 |

### 申请

```bash
# 1. certbot 5.x（dnf 里只有 2.8，走 venv）
python3 -m venv /opt/certbot
/opt/certbot/bin/pip install -U certbot -i https://pypi.tuna.tsinghua.edu.cn/simple
ln -sfn /opt/certbot/bin/certbot /usr/local/bin/certbot

# 2. 验证目录（nginx 80 块里 /.well-known/acme-challenge/ 直接出文件，不能被 301）
mkdir -p /var/www/letsencrypt/.well-known/acme-challenge

# 3. 先 staging 走通流程
certbot certonly --webroot -w /var/www/letsencrypt \
  --preferred-profile shortlived --ip-address 101.43.26.29 \
  -n --agree-tos --register-unsafely-without-email --staging

# 4. staging 那份必须先删，否则正式申请会误判「还没到期」
certbot delete --cert-name 101.43.26.29 -n

# 5. 正式
certbot certonly --webroot -w /var/www/letsencrypt \
  --preferred-profile shortlived --ip-address 101.43.26.29 \
  -n --agree-tos --register-unsafely-without-email
```

产物在 `/etc/letsencrypt/live/101.43.26.29/`（`fullchain.pem` 含 leaf + YE1 + Root YE + ISRG Root X2）。

### 续期（两个坑）

160 小时意味着**必须自动化**，而 certbot 的默认值在这里都是错的：

1. **默认续期阈值 30 天 > 证书寿命**，结果每次检查都续、一天两次直接撞 LE 速率限制。
   改成 3 天：在 `/etc/letsencrypt/renewal/101.43.26.29.conf` 的 `[renewalparams]` 下加
   `renew_before_expiry = 3 days`（约每 3.7 天续一次，每周 2 张）。
2. **`certbot renew` 默认随机 sleep 约 5 分钟**（多云环境错峰用）。单机没必要，
   加 `--no-random-sleep-on-renew`，否则 systemd 里看着像卡死。

调度用 `/etc/systemd/system/certbot-renew.{service,timer}`，每天 03:17 / 15:17 各查一次；
续期成功后由 `/etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh` 重载 nginx，
否则 nginx 会一直拿着旧证书跑到过期。

演练（不动真证书）：`certbot renew --dry-run --no-random-sleep-on-renew`

### 回退到自签

LE 抽风、或者想彻底脱离外部依赖时，把 `soulmate.conf` 里两行换成自签即可：

```nginx
ssl_certificate     /opt/soulmate/certs/fullchain.pem;
ssl_certificate_key /opt/soulmate/certs/privkey.pem;
```

私有 CA 还在 `/opt/soulmate/certs/`（10 年），`ca.crt` 也留在 web 根目录
（`https://101.43.26.29/ca.crt`），手机装一次就没有警告。**现在正常访问不需要装任何东西。**

## 目录

| 路径 | 内容 |
|---|---|
| `/opt/soulmate/server/app.jar` | 后端产物 |
| `/opt/soulmate/server/.env` | 后端配置（`chmod 600`） |
| `/opt/soulmate/web/` | 前端 dist（`ca.crt` 也在里面，回退用） |
| `/opt/soulmate/biz-pg/` | `compose.yaml` + `.env`（PG 口令）+ `pgdata/` |
| `/opt/soulmate/memory-service/` | mem0 那套（见主 README） |
| `/opt/soulmate/certs/` | 自签私有 CA（回退备用，`ca.key`/`privkey.pem` 是 600） |
| `/opt/certbot/` | certbot 5.x 的 venv |
| `/etc/letsencrypt/` | LE 证书 + 续期配置 + deploy hook |
| `/etc/systemd/system/ai-soulmate.service` | 后端服务 |
| `/etc/systemd/system/certbot-renew.{service,timer}` | 证书续期调度 |
| `/www/server/panel/vhost/nginx/soulmate.conf` | 站点配置（80 跳转 + 443 主体） |
| `/www/server/panel/vhost/nginx/snippets/soulmate-locations.conf` | 两个 server 共用的 location（单一来源） |
| `/var/log/ai-soulmate/server.log` | 后端日志（logrotate 每日轮转，留 7 天） |

## 首次部署

```bash
# 1. 业务库（版本与本地一致：PG16）
mkdir -p /opt/soulmate/biz-pg && cd /opt/soulmate/biz-pg
cp /path/to/deploy/compose.biz-pg.yaml compose.yaml
printf 'PG_PASSWORD=%s\n' "$(openssl rand -hex 16)" > .env && chmod 600 .env
docker compose up -d

# 2. 后端
mkdir -p /opt/soulmate/server /var/log/ai-soulmate
# 把本地 server/.env 传上来，然后改这四项：
#   PG_URL=jdbc:postgresql://127.0.0.1:5433/ai-soulmate
#   PG_USERNAME=soulmate
#   PG_PASSWORD=<上一步生成的口令>
#   MEM0_BASE_URL=http://127.0.0.1:6101
cp /path/to/deploy/ai-soulmate.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now ai-soulmate

# 3. 前端
mkdir -p /opt/soulmate/web
tar xzf dist.tgz -C /opt/soulmate/web && chmod -R a+rX /opt/soulmate/web

# 4. 证书（命令见上面「HTTPS」一节）

# 5. nginx
mkdir -p /www/server/panel/vhost/nginx/snippets
cp /path/to/deploy/nginx-soulmate-locations.conf /www/server/panel/vhost/nginx/snippets/soulmate-locations.conf
cp /path/to/deploy/nginx-soulmate.conf /www/server/panel/vhost/nginx/soulmate.conf
nginx -t && nginx -s reload
```

## 更新版本

```bash
# 后端：换 jar 再重启，10 秒内恢复
scp target/ai-soulmate-server-1.0.0.jar root@101.43.26.29:/opt/soulmate/server/app.jar
ssh root@101.43.26.29 'systemctl restart ai-soulmate'

# 前端：覆盖静态文件即可，不用 reload nginx
scp dist.tgz root@101.43.26.29:/tmp/
ssh root@101.43.26.29 'rm -rf /opt/soulmate/web/* && tar xzf /tmp/dist.tgz -C /opt/soulmate/web && chmod -R a+rX /opt/soulmate/web'
```

## 几个不明显的坑

1. **`/api/` 必须 `proxy_buffering off`**。对话是 SSE 逐字推的，开着 buffering 会攒满一个 buffer 才吐，
   表现是「她沉默十几秒，然后一大段一起出现」——看起来像后端慢，其实是 nginx 在攒。
   HTTP/2 下同样有效（实测 26 帧跨 1.09 秒）。
2. **`server_name` 写 IP 是为了抢宝塔默认站**。宝塔在 `nginx.conf` 里自带一个
   `listen 80; server_name 127.0.0.1;` 的默认站，同端口下精确匹配优先；外部用 IP 访问正好命中我们这块。
   反过来说，**在服务器上 `curl http://127.0.0.1/api/health` 会 404**（Host 是 127.0.0.1，命中默认站），
   自测要带 `-H 'Host: 101.43.26.29'` 或直接用公网 IP 测。
3. **`.env` 是从「当前工作目录」读的**（`DotEnvEnvironmentPostProcessor` 读 `./.env`），
   所以 systemd 的 `WorkingDirectory` 必须是 `/opt/soulmate/server`，否则所有配置静默回落成默认值。
4. **业务库只绑 `127.0.0.1:5433`**，不对外。要连库看数据用 `ssh -L 5433:127.0.0.1:5433 root@101.43.26.29`，
   别学 mem0 那个 pg 开 0.0.0.0。
5. 日志用 `StandardOutput=append:`，systemd 持有 fd，所以 logrotate 要配 `copytruncate`。
6. **80 上的 `/mem0/` 故意没跳 HTTPS**。本地开发的 `server/.env` 写的是 `http://101.43.26.29/mem0`，
   一旦 301 到 https，本地后端就会拿证书去校验然后连不上。所以 80 只跳根路径，`/mem0/` 留在明文。
7. **`/.well-known/acme-challenge/` 不能被 301 掉**，否则续期必失败。它用的是 `^~` 前缀匹配，
   优先级高于 `location /` 的 return，不会被那条跳转吃掉。
8. **160 小时的证书，续期失败 3 天后就过期**。`systemctl list-timers certbot-renew.timer` 和
   `certbot certificates` 是唯一需要定期瞟一眼的东西。
