import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * 开发时用 vite 代理，把 /api 与 /media 转到本地 Node 服务，
 * 这样浏览器同源，不需要后端开 CORS。
 * 生产构建由 server 直接托管 front/dist，那时不需要 vite。
 *
 * 目标端口按优先级取：
 *   1. VITE_API_TARGET          —— 显式指定，最优先
 *   2. server/.env 里的 PORT    —— 和后端配置保持一致，只改一处（当前 6100）
 *   3. 6100                     —— 兜底，与 application.yml 的默认值一致
 *
 * 为什么要读 .env：端口就写在 server/.env 里，若这里硬编码，一旦改了 PORT，
 * 代理就会打到别人身上，表现是「接口 404 / 返回一段不相干的 JSON」而不是明确报错，
 * 比起不来更难查。8080 在本机是 Nacos，6000 段才是本项目。
 */
const HERE = path.dirname(fileURLToPath(import.meta.url));

function resolveTarget() {
  if (process.env.VITE_API_TARGET) return process.env.VITE_API_TARGET;
  try {
    const env = fs.readFileSync(path.resolve(HERE, '../server/.env'), 'utf8');
    const port = env.match(/^\s*PORT\s*=\s*(\d+)/m)?.[1];
    // 用 127.0.0.1 而不是 localhost：后者在 macOS 上会先解析成 ::1，
    // 而服务只监听 IPv4（HOST=0.0.0.0），代理会直接 ECONNREFUSED。
    if (port) return `http://127.0.0.1:${port}`;
  } catch {
    // server/.env 还没生成（刚 clone、还没起过后端），走兜底
  }
  return 'http://127.0.0.1:6100';
}

const TARGET = resolveTarget();

/**
 * 开发用自签证书（certs/dev-cert.pem，由 `./gen-cert.sh` 生成）。
 *
 * 为什么非要 https：浏览器只在「安全上下文」里暴露 navigator.mediaDevices，
 * 而 http://192.168.x.x 不算 —— 手机连同一个 Wi-Fi 打开时录音直接不可用，
 * 报错还是句"浏览器不支持录音"，很容易误判。localhost 是唯一豁免的 http 地址。
 * 证书不存在就退回 http：本机 localhost 调试不需要它，别为了它起不来。
 */
function devHttps() {
  try {
    return {
      key: fs.readFileSync(path.resolve(HERE, 'certs/dev-key.pem')),
      cert: fs.readFileSync(path.resolve(HERE, 'certs/dev-cert.pem')),
    };
  } catch {
    return undefined;
  }
}

// 想强制走 http（本机调试不想点证书警告）：npm run dev:http
const HTTPS = process.env.VITE_NO_HTTPS ? undefined : devHttps();

/**
 * 开发期提供一个证书下载入口：手机打开 /__dev/ca.crt 就能拿到 .crt 装进系统。
 * 只挂在 dev server 上，构建产物里没有这个路由。
 */
function devCertRoute() {
  return {
    name: 'dev-cert-route',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use('/__dev/ca.crt', (_req, res) => {
        try {
          const pem = fs.readFileSync(path.resolve(HERE, 'certs/dev-cert.pem'));
          res.setHeader('Content-Type', 'application/x-x509-ca-cert');
          res.setHeader('Content-Disposition', 'attachment; filename="ai-soulmate-dev.crt"');
          res.end(pem);
        } catch {
          res.statusCode = 404;
          res.end('certs/dev-cert.pem not found, run ./gen-cert.sh first');
        }
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), devCertRoute()],
  server: {
    port: 5173,
    // 监听 0.0.0.0：手机连同一个 Wi-Fi 时可以用 https://<本机IP>:5173 打开。
    // 代理目标仍是 127.0.0.1（vite 跑在本机，后端也在本机），和手机访问不冲突。
    host: '0.0.0.0',
    https: HTTPS,
    proxy: {
      '/api': { target: TARGET, changeOrigin: true },
      '/media': { target: TARGET, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1200,
  },
});
