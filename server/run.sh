#!/usr/bin/env bash
# 后端启动入口（两种模式，都以前台方式运行，Ctrl+C 即优雅退出）
#
#   ./run.sh          编译后 java -jar 前台运行。适合「就想让它稳稳跑着」。
#                     Ctrl+C 退出码是 130/143，不会再出现 Maven 的 BUILD FAILURE 尾巴。
#   ./run.sh --hot    mvn spring-boot:run，改 Java 代码由 devtools 自动重启。适合改代码。
#
# 说明：exit code 143 = 128 + 15（SIGTERM），是「被正常终止」，不是构建/运行错误。
set -euo pipefail

cd "$(dirname "$0")"

JAR="target/ai-soulmate-server-1.0.0.jar"
PORT="$(grep -E '^PORT=' .env 2>/dev/null | tail -1 | cut -d= -f2 | tr -d '[:space:]')"
PORT="${PORT:-6100}"

# 端口已占用就直接说清楚，别让新进程去撞 6100 再抛一堆 netty 异常
if curl -s -o /dev/null --max-time 1 "http://127.0.0.1:${PORT}/api/health"; then
  echo "端口 ${PORT} 已有实例在跑。要重启先： lsof -nP -iTCP:${PORT} -sTCP:LISTEN | awk 'NR==2{print \$2}' | xargs kill"
  exit 1
fi

if [ "${1:-}" = "--hot" ]; then
  echo "热重启模式：mvn -o spring-boot:run（改 Java 代码自动 restart）"
  exec mvn -o spring-boot:run
fi

# jar 不存在，或 src/pom 比 jar 新，才重新打包
if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" -print -quit 2>/dev/null)" ]; then
  echo "编译打包中……"
  mvn -o -q package -DskipTests
fi

echo "启动：http://127.0.0.1:${PORT}  （Ctrl+C 退出）"
exec java -jar "$JAR"
