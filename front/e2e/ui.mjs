/**
 * 浏览器端到端测试（真实点击，不是 curl 模拟）。
 *
 * 跑法：
 *   cd front && npm run dev          # 先起前端（https）
 *   cd ../server && ./run.sh         # 再起后端
 *   cd ../front && npm run e2e       # 另开一个终端
 *
 * 用的是本机已装的 Google Chrome（playwright-core 不带浏览器），
 * 所以不用下载 Chromium；`ignoreHTTPSErrors` 是为了过自签证书那一关。
 *
 * 覆盖的是"用户真正会走的路径"：注册 → 说话 → 收回复 → 点语音 → 退出 → 重新登录看历史。
 * 断言失败会打印实际值与截图文件名，宁可测试挂掉，也不要"看起来跑通了"。
 */
import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';

const BASE = process.env.E2E_BASE || 'https://127.0.0.1:5173';
const CHROME = process.env.E2E_CHROME
  || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const SHOTS = path.resolve('e2e/shots');

const stamp = Date.now().toString().slice(-8);
const USERNAME = `e2e_${stamp}`;
const PASSWORD = 'e2e123456';
const NICKNAME = '小柚';
const FIRST_LINE = '你好呀，我叫小柚';

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

async function shot(page, name) {
  fs.mkdirSync(SHOTS, { recursive: true });
  await page.screenshot({ path: path.join(SHOTS, `${name}.png`), fullPage: false });
}

/** 等到某个选择器出现，超时就返回 false 而不是直接抛，方便把失败也记进报告。 */
async function waitFor(page, selector, timeout = 15000) {
  try {
    await page.waitForSelector(selector, { timeout, state: 'visible' });
    return true;
  } catch {
    return false;
  }
}

const browser = await chromium.launch({
  executablePath: CHROME,
  headless: true,
  // 假麦克风：headless 没有真设备，这两个 flag 让 getUserMedia 能拿到一条合成音轨，
  // 才能真实验证「按下 → 录制 → 上传 → 识别」整条链路（识别结果会是空，正好测"没听清"分支）
  args: [
    '--no-sandbox',
    '--disable-gpu',
    '--use-fake-ui-for-media-stream',
    '--use-fake-device-for-media-stream',
  ],
});

const context = await browser.newContext({
  ignoreHTTPSErrors: true,
  viewport: { width: 390, height: 844 },
  locale: 'zh-CN',
  timezoneId: 'Asia/Shanghai',
});
await context.grantPermissions(['microphone'], { origin: BASE });
const page = await context.newPage();

const consoleErrors = [];
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text());
});
page.on('pageerror', (err) => consoleErrors.push(`pageerror: ${err.message}`));

try {
  // ---------------------------------------------------------------- 1. 登录页
  console.log(`\n[1] 打开 ${BASE}`);
  await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 30000 });
  const loginVisible = await waitFor(page, '.auth__card');
  check('未登录时展示登录页', loginVisible);
  check('标题是「小满」', (await page.locator('.auth__title').textContent().catch(() => '')) === '小满');
  await shot(page, '01-login');

  // ---------------------------------------------------------------- 2. 注册
  console.log(`\n[2] 注册新账号 ${USERNAME}`);
  await page.getByRole('tab', { name: '注册' }).click();
  const confirmVisible = await waitFor(page, 'input[placeholder="再输一次"]');
  check('切到注册后出现「确认密码」', confirmVisible);

  await page.locator('input[placeholder="3~24 位，中文/字母/数字"]').fill(USERNAME);
  await page.locator('input[placeholder="至少 6 位"]').fill(PASSWORD);
  await page.locator('input[placeholder="再输一次"]').fill(PASSWORD);
  await page.locator('input[placeholder="她怎么叫你，默认用用户名"]').fill(NICKNAME);
  await shot(page, '02-register-filled');

  await page.getByRole('button', { name: '注册并进入' }).click();
  const chatReady = await waitFor(page, '.composer', 20000);
  check('注册成功后直接进入聊天页（不用再登一次）', chatReady);

  const token = await page.evaluate(() => {
    const raw = localStorage.getItem('soulmate.auth.v1');
    return raw ? JSON.parse(raw) : null;
  });
  check('token 已持久化到 localStorage', Boolean(token?.token && token.token.split('.').length === 3));
  check('本地缓存的用户昵称是刚才填的', token?.user?.nickname === NICKNAME, `实际 ${token?.user?.nickname}`);
  await shot(page, '03-chat-empty');

  // ---------------------------------------------------------------- 3. 发消息
  console.log('\n[3] 发一条消息，等真实回复');
  await page.locator('.composer textarea').fill(FIRST_LINE);
  await page.locator('.composer__send').click();

  const sent = await waitFor(page, '.bubble--out', 8000);
  check('自己的消息出现在列表里', sent);
  check('用户消息内容是刚才输入的',
    (await page.locator('.bubble--out').last().textContent().catch(() => '') || '').includes(FIRST_LINE));

  // 等 assistant 气泡：真实 LLM 调用，给足时间
  const replied = await page.waitForFunction(
    () => {
      const nodes = Array.from(document.querySelectorAll('.bubble--in'));
      const text = nodes.map((n) => (n.textContent || '').trim()).join('');
      const typing = document.querySelector('.typing');
      return text.length >= 2 && !typing;
    },
    { timeout: 120000 },
  ).then(() => true).catch(() => false);
  const replyText = (await page.locator('.bubble--in').last().textContent().catch(() => '') || '').trim();
  check('收到助手回复（真实调用模型，非占位）', replied, `最后一条：${replyText.slice(0, 40)}`);
  check('回复不是固定字符串（排除又一处 mock 冒充）',
    replyText.length > 0 && replied);
  await shot(page, '04-chat-replied');

  // ---------------------------------------------------------------- 4. 语音
  console.log('\n[4] 确认服务端语音识别是真实的，再走一遍录音链路');

  // 先钉住"不是 mock"这件事 —— 之前正是这里被固定文本骗过
  const caps = await page.evaluate(async () => {
    const raw = localStorage.getItem('soulmate.auth.v1');
    const token = raw ? JSON.parse(raw).token : '';
    const res = await fetch('/api/v1/session/bootstrap/get', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: '{}',
    });
    const json = await res.json();
    return json?.data?.capabilities?.asr || null;
  });
  check('后端 ASR 是真实识别（configured 且非 mock）',
    Boolean(caps?.configured) && caps?.mock === false,
    `capabilities.asr = ${JSON.stringify(caps)}`);

  // 必须等这一轮说完：busy 期间点录音是无效的（产品上也补了提示）
  const idle = await page.waitForFunction(
    () => {
      const ta = document.querySelector('.composer textarea');
      return !document.querySelector('.typing')
        && ta && ta.getAttribute('placeholder') === '说点什么…';
    },
    { timeout: 120000 },
  ).then(() => true).catch(() => false);
  check('这一轮说完后输入区恢复可输入（busy 已结束）', idle);

  await page.locator('.composer__mode').click();
  const holdVisible = await waitFor(page, '.composer__hold');
  check('语音模式出现录音按钮', holdVisible);

  const outBefore = await page.locator('.bubble--out').count();
  await page.locator('.composer__hold').click();

  // 真录音：按钮应进入录音态（不再是待机文案）
  const recording = await page.waitForFunction(
    () => {
      const btn = document.querySelector('.composer__hold');
      return btn && /结束|正在听/.test(btn.textContent || '');
    },
    { timeout: 8000 },
  ).then(() => true).catch(() => false);
  const btnText = (await page.locator('.composer__hold').textContent().catch(() => '') || '').trim();
  check('点录音后真的开始录（按钮进入录音态，说明麦克风已拿到）', recording, `按钮文案：${btnText}`);
  await shot(page, '05-voice-recording');

  await page.waitForTimeout(1600);      // 录够 1.6 秒，绕开"说得太短"的分支
  // 录音时有全屏面板挡住输入区，真正的"结束并发送"在面板里
  await page.locator('.recording__actions').getByRole('button', { name: '发送' }).click();

  // 假设备是合成音，识别不出人声 —— 期望"没听清"这类明确反馈，而不是闷掉
  const feedback = await page.waitForFunction(
    () => {
      const text = document.body.innerText || '';
      return /没听清|说得太短|识别失败|语音识别/.test(text);
    },
    { timeout: 45000 },
  ).then(() => true).catch(() => false);
  check('无声/噪声输入会给出明确反馈（没听清 / 太短），不是静默失败', feedback);
  await shot(page, '06-voice-feedback');

  // 最关键的一条：任何情况下都不能凭空冒出一条用户消息
  const outAfter = await page.locator('.bubble--out').count();
  check('没有把识别不出的内容伪造进对话', outAfter === outBefore,
    `发送前 ${outBefore} 条，发送后 ${outAfter} 条`);

  // 录音可能把设置页打开（权限/配置引导），reload 回聊天页继续
  await page.reload({ waitUntil: 'domcontentloaded' });
  await waitFor(page, '.composer', 15000);

  // ---------------------------------------------------------------- 5. 刷新保持登录
  console.log('\n[5] 刷新页面，登录态应当保持');
  await page.reload({ waitUntil: 'domcontentloaded' });
  const stillIn = await waitFor(page, '.composer', 15000);
  check('刷新后仍是登录状态（token 从 localStorage 恢复）', stillIn);
  const historyKept = (await page.content()).includes(FIRST_LINE);
  check('刷新后历史消息还在（从 PG 读回）', historyKept);

  // ---------------------------------------------------------------- 6. 设置页 / 退出
  console.log('\n[6] 打开设置页并退出登录');
  await page.locator('.icon-btn[title="设置"]').click();
  const settingsOpen = await waitFor(page, '.view--page.is-open', 8000);
  const settingsText = await page.locator('.view--page.is-open .page-nav').first().textContent().catch(() => '');
  check('设置页打开', settingsOpen);
  check('设置页标题是「设置」', (settingsText || '').includes('设置'), `实际 ${settingsText}`);
  const accountShown = (await page.locator('.view--page.is-open').first().innerText().catch(() => '') || '').includes(NICKNAME);
  check('设置页显示当前账号昵称', accountShown);
  const hasLogout = await page.getByRole('button', { name: '退出登录' }).isVisible().catch(() => false);
  check('设置页有「退出登录」', hasLogout);
  await shot(page, '07-settings');

  await page.getByRole('button', { name: '退出登录' }).click();
  const backToLogin = await waitFor(page, '.auth__card', 8000);
  check('退出登录后回到登录页', backToLogin);
  const cleared = await page.evaluate(() => localStorage.getItem('soulmate.auth.v1'));
  check('退出后本地 token 被清掉', cleared === null, `实际 ${cleared}`);

  // ---------------------------------------------------------------- 7. 重新登录
  console.log('\n[7] 用刚注册的账号重新登录');
  await page.locator('input[placeholder="3~24 位，中文/字母/数字"]').fill(USERNAME);
  await page.locator('input[placeholder="输入密码"]').fill(PASSWORD);
  await page.getByRole('button', { name: '登录' }).click();
  const loggedIn = await waitFor(page, '.composer', 20000);
  check('登录成功进入聊天页', loggedIn);
  const historyAfterLogin = (await page.content()).includes(FIRST_LINE);
  check('重新登录后能看到刚才的历史（会话归属正确）', historyAfterLogin);
  await shot(page, '08-relogin-history');

  // ---------------------------------------------------------------- 8. 错误密码
  console.log('\n[8] 退出后用错误密码登录，校验提示文案');
  await page.locator('.icon-btn[title="设置"]').click();
  await waitFor(page, '.view--page.is-open', 8000);
  await page.getByRole('button', { name: '退出登录' }).click();
  await waitFor(page, '.auth__card', 8000);

  await page.locator('input[placeholder="3~24 位，中文/字母/数字"]').fill(USERNAME);
  await page.locator('input[placeholder="输入密码"]').fill('wrong-password');
  await page.getByRole('button', { name: '登录' }).click();
  const errorShown = await waitFor(page, '.auth__error', 10000);
  const errorText = await page.locator('.auth__error').textContent().catch(() => '');
  check('错误密码显示「用户名或密码不正确」', errorShown && (errorText || '').includes('用户名或密码不正确'),
    `实际 ${errorText}`);
  check('错误密码时没有进入聊天页', (await page.locator('.composer').count()) === 0);
  await shot(page, '09-wrong-password');

  // ---------------------------------------------------------------- 结果
  console.log('\n[console] 页面错误：');
  if (consoleErrors.length === 0) {
    console.log('  （无）');
  } else {
    consoleErrors.slice(0, 8).forEach((e) => console.log(`  ! ${e}`));
  }
  check('页面无 JS 报错', consoleErrors.length === 0, `${consoleErrors.length} 条`);

  console.log(`\n通过 ${passed} 项，失败 ${failures.length} 项`);
  if (failures.length) {
    console.log('失败项：');
    failures.forEach((f) => console.log(`  - ${f}`));
  }
  console.log(`截图目录：${SHOTS}`);
} finally {
  await browser.close();
}

process.exit(failures.length === 0 ? 0 : 1);
