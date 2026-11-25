#!/usr/bin/env bash
#
# 生成开发用自签证书（certs/dev-cert.pem + dev-key.pem）。
#
# 为什么要这个：浏览器只在「安全上下文」（https / localhost）里暴露
# navigator.mediaDevices。手机用 http://<本机IP>:5173 打开时它是 undefined，
# 录音直接不可用。给 dev server 挂上 https 才能真机测语音。
#
# IP 变了（换 Wi-Fi、DHCP 重新分配）就重跑一次：
#   cd front && ./gen-cert.sh && <重启 npm run dev>
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/certs"
IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo 127.0.0.1)"
HOST="$(scutil --get LocalHostName 2>/dev/null || echo dev)"

mkdir -p "$OUT"

openssl req -x509 -newkey rsa:2048 -sha256 -days 397 -nodes \
  -keyout "$OUT/dev-key.pem" -out "$OUT/dev-cert.pem" \
  -subj "/CN=ai-soulmate-dev/O=ai-soulmate-dev/C=CN" \
  -addext "subjectAltName=DNS:localhost,DNS:${HOST}.local,IP:127.0.0.1,IP:${IP}" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,digitalSignature,keyEncipherment,keyCertSign" \
  -addext "extendedKeyUsage=serverAuth" 2>/dev/null

# iOS 认 .crt 后缀，复制一份方便 AirDrop / 下载
cp "$OUT/dev-cert.pem" "$OUT/ai-soulmate-dev.crt"

echo "证书已生成：$(date '+%Y-%m-%d %H:%M')"
echo "  SAN : localhost / ${HOST}.local / 127.0.0.1 / ${IP}"
echo "  有效: 397 天"
echo
echo "手机访问：https://${IP}:5173"
echo "首次会提示「连接非私人」，两条路："
echo "  A. 直接【显示详细信息 → 访问此网站】，多数情况录音即可用；"
echo "  B. 想彻底消掉警告：把 certs/ai-soulmate-dev.crt 传到 iPhone 安装，"
echo "     再到 设置 → 通用 → 关于本机 → 证书信任设置 里启用完全信任。"
