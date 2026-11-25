/**
 * 语音链路端到端测试：让 Chrome 的假麦克风"播放"一段真人语音，
 * 走完 按下录音 → 上传 → 服务端识别 → 变成一条消息 的全过程。
 *
 * 这是唯一能证明"语音识别真的能听懂人话"的测试 —— 之前的测试只证明了
 * 接口能被调用、链路不报错，但用合成音（正弦波）是识别不出文字的。
 *
 * 前置：
 *   1. 后端 6100、前端 5173 在跑；
 *   2. 有一段 16kHz 单声道 WAV 人声（默认用 macOS say 生成：
 *      say -v Tingting "感冒了怎么办" -o a.aiff && afconvert -f WAVE -d LEI16@16000 -c 1 a.aiff a.wav）
 *
 * 跑法：AUDIO=/tmp/asr-test.wav EXPECT=感冒 npm run e2e:voice
 */
import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';

const BASE = process.env.E2E_BASE || 'https://127.0.0.1:5173';
const CHROME = process.env.E2E_CHROME
  || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const AUDIO = process.env.AUDIO || '/tmp/asr-test.wav';
const EXPECT = process.env.EXPECT || '感冒';
const SHOTS = path.resolve('e2e/shots');

if (!fs.existsSync(AUDIO)) {
  console.error(`找不到测试音频：${AUDIO}`);
  console.error('先生成一段：say -v Tingting "感冒了怎么办" -o /tmp/a.aiff && afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/a.aiff /tmp/asr-test.wav');
  process.exit(2);
}

let passed = 0;
const failures = [];
function check(name, ok, detail = '') {
  if (ok) {
    passed += 1;
    console.log(`  ✓ ${name}`);
  } else {
    failures.push(`${name}${detail ? ` — ${detail}` : ''}`);
    console.log(`  ✗ ${name}${detail ? ` — ${detail}` : ''}`);
  }
}

const stamp = Date.now().toString().slice(-8);
const USERNAME = `v_${stamp}`;
const PASSWORD = 'voice123456';

const browser = await chromium.launch({
  executablePath: CHROME,
  headless: true,
  args: [
    '--no-sandbox',
    '--disable-gpu',
    '--use-fake-ui-for-media-stream',
    '--use-fake-device-for-media-stream',
    // 关键：假麦克风不放正弦波，放这段真实人声
    `--use-file-for-fake-audio-capture=${AUDIO}`,
  ],
});

const context = await browser.newContext({
  ignoreHTTPSErrors: true,
  viewport: { width: 390, height: 844 },
  locale: 'zh-CN',
});
await context.grantPermissions(['microphone'], { origin: BASE });
const page = await context.newPage();

const consoleErrors = [];
page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));

try {
  console.log(`\n[1] 注册账号 ${USERNAME} 并进入聊天页`);
  await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForSelector('.auth__card', { timeout: 20000 });
  await page.getByRole('tab', { name: '注册' }).click();
  await page.locator('input[placeholder="3~24 位，中文/字母/数字"]').fill(USERNAME);
  await page.locator('input[placeholder="至少 6 位"]').fill(PASSWORD);
  await page.locator('input[placeholder="再输一次"]').fill(PASSWORD);
  await page.getByRole('button', { name: '注册并进入' }).click();
  const ready = await page.waitForSelector('.composer', { timeout: 20000 }).then(() => true).catch(() => false);
  check('注册后进入聊天页', ready);

  console.log(`\n[2] 切到语音，对着"麦克风"说 ${path.basename(AUDIO)}`);
  await page.locator('.composer__mode').click();
  await page.waitForSelector('.composer__hold', { timeout: 8000 });

  // 语音消息渲染成 .voice-msg--out（不是文字气泡），统计时两种都算
  const outBefore = await page.locator('.voice-msg--out, .bubble--out').count();
  await page.locator('.composer__hold').click();

  const started = await page.waitForFunction(
    () => {
      const btn = document.querySelector('.composer__hold');
      return btn && /结束|正在听/.test(btn.textContent || '');
    },
    { timeout: 8000 },
  ).then(() => true).catch(() => false);
  check('录音已启动', started);

  // 音频本体约 1.2 秒，假设备会循环播放；录 4 秒保证整句都被收进去
  await page.waitForTimeout(4000);
  fs.mkdirSync(SHOTS, { recursive: true });
  await page.screenshot({ path: path.join(SHOTS, 'v1-recording.png') });

  console.log('\n[3] 结束录音，等待识别结果');
  await page.locator('.recording__actions').getByRole('button', { name: '发送' }).click();

  // 识别 + 发送后会多出一条用户消息（语音气泡）
  const appeared = await page.waitForFunction(
    (before) => document.querySelectorAll('.voice-msg--out, .bubble--out').length > before,
    outBefore,
    { timeout: 90000 },
  ).then(() => true).catch(() => false);
  check('录音被识别并作为一条消息发出', appeared);
  await page.waitForTimeout(1200);   // 给落库一点时间，避免读到"本地有、库里还没有"的中间态

  // 气泡上只有时长，识别出来的文字在库里 —— 直接读接口，别靠 DOM 猜
  const lastUserMessage = await page.evaluate(async () => {
    const token = JSON.parse(localStorage.getItem('soulmate.auth.v1')).token;
    const res = await fetch('/api/v1/session/bootstrap/get', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: '{}',
    });
    const json = await res.json();
    const messages = (json?.data?.messages || []).filter((m) => m.role === 'user');
    return messages.length ? messages[messages.length - 1] : null;
  });
  const sent = (lastUserMessage?.text || '').trim();
  console.log(`  识别出的文字：「${sent}」`);
  check('这条消息真的落库了（不是本地乐观插入的幽灵气泡）',
    Boolean(lastUserMessage), `接口返回：${JSON.stringify(lastUserMessage)}`);
  check(`识别结果包含「${EXPECT}」（说明真的听懂了这段人声）`, sent.includes(EXPECT), `实际：${sent}`);
  check('识别结果不是 mock 的固定文本', !sent.includes('多穿件衣服'), `实际：${sent}`);
  check('这条消息确实是从录音来的（带时长）', Boolean(lastUserMessage?.media), JSON.stringify(lastUserMessage?.media));

  await page.screenshot({ path: path.join(SHOTS, 'v2-recognized.png') });
  check('页面无 JS 报错', consoleErrors.length === 0, `${consoleErrors.length} 条`);

  console.log(`\n通过 ${passed} 项，失败 ${failures.length} 项`);
  if (failures.length) failures.forEach((f) => console.log(`  - ${f}`));
  console.log(`截图：${SHOTS}`);
} finally {
  await browser.close();
}

process.exit(failures.length === 0 ? 0 : 1);
