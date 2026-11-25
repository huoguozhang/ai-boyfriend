/**
 * 待办提醒端到端（真实点击 + 真实 LLM 工具调用 + 真实到点投递）。
 *
 * 覆盖：说一句"一分钟后提醒我吃药" → 她真的调 set_reminder 落库 → 到点后端翻 due →
 * 前端轮询到 → 弹提醒卡 → 点「知道了」→ 标记已送达。
 *
 * 跑法：先起前后端（同 ui.mjs），然后 `npm run e2e:reminder`。
 * 断言失败打印实际值并留截图，绝不"看起来跑通了"。
 */
import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';

const BASE = process.env.E2E_BASE || 'https://127.0.0.1:5173';
const CHROME = process.env.E2E_CHROME
  || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const SHOTS = path.resolve('e2e/shots');

const stamp = Date.now().toString().slice(-8);
const USERNAME = `rem_${stamp}`;
const PASSWORD = 'e2e123456';

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
  args: ['--no-sandbox', '--disable-gpu'],
});

const context = await browser.newContext({
  ignoreHTTPSErrors: true,
  viewport: { width: 390, height: 844 },
  locale: 'zh-CN',
  timezoneId: 'Asia/Shanghai',
});
const page = await context.newPage();

const consoleErrors = [];
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text());
});
page.on('pageerror', (err) => consoleErrors.push(`pageerror: ${err.message}`));

try {
  // ---------------------------------------------------------------- 1. 注册
  console.log(`\n[1] 注册 ${USERNAME} 并进入聊天页`);
  await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.getByRole('tab', { name: '注册' }).click();
  await page.locator('input[placeholder="3~24 位，中文/字母/数字"]').fill(USERNAME);
  await page.locator('input[placeholder="至少 6 位"]').fill(PASSWORD);
  await page.locator('input[placeholder="再输一次"]').fill(PASSWORD);
  await page.getByRole('button', { name: '注册并进入' }).click();
  check('注册成功进入聊天页', await waitFor(page, '.composer', 20000));

  // ---------------------------------------------------------------- 2. 说一句要提醒
  console.log('\n[2] 说「一分钟后提醒我吃药」，等她真的落一条提醒');
  await page.locator('.composer textarea').fill('一分钟后提醒我吃药');
  await page.locator('.composer__send').click();

  // 等她回话（真实 LLM + 工具调用，给足时间）
  const replied = await page.waitForFunction(
    () => {
      const text = Array.from(document.querySelectorAll('.bubble--in'))
        .map((n) => (n.textContent || '').trim()).join('');
      return text.length >= 2 && !document.querySelector('.typing');
    },
    { timeout: 120000 },
  ).then(() => true).catch(() => false);
  const replyText = (await page.locator('.bubble--in').last().textContent().catch(() => '') || '').trim();
  check('收到她的回复', replied, `最后一条：${replyText.slice(0, 40)}`);

  // 她应该真调了 set_reminder：界面里有一条「记下了」的系统提示，不是只有一句空话
  const noticed = await page.waitForFunction(
    () => /记下了|记下/.test(document.body.innerText || ''),
    { timeout: 30000 },
  ).then(() => true).catch(() => false);
  check('界面出现「记下了」这类确认（工具真的执行了）', noticed);
  await shot(page, 'r1-set-reminder');

  // 更硬的证据：后端真的多了一条待办提醒（内容=吃药）
  const pending = await page.evaluate(async () => {
    const raw = localStorage.getItem('soulmate.auth.v1');
    const token = raw ? JSON.parse(raw).token : '';
    const res = await fetch('/api/v1/reminders', {
      headers: { Authorization: `Bearer ${token}` },
    });
    const json = await res.json();
    return (json?.data || []).map((r) => r.content);
  });
  check('后端真的落了这条提醒（内容含「吃药」）',
    Array.isArray(pending) && pending.some((c) => String(c).includes('吃药')),
    `实际待办：${JSON.stringify(pending)}`);

  // ---------------------------------------------------------------- 3. 等到点投递
  console.log('\n[3] 等到点：后端翻 due，前端轮询到后弹提醒卡');
  const cardAppeared = await page.waitForSelector('.reminder-card', {
    state: 'visible',
    timeout: 150000,
  }).then(() => true).catch(() => false);
  const cardText = (await page.locator('.reminder-card').textContent().catch(() => '') || '').trim();
  check('到点后弹出提醒卡', cardAppeared, `卡片内容：${cardText.slice(0, 60)}`);
  check('提醒卡里写着要提醒的事（吃药）', cardText.includes('吃药'), `实际 ${cardText}`);
  check('提醒卡带时间信息', /今天|明天|\d{1,2}\s*[:：]\s*\d{2}/.test(cardText), `实际 ${cardText}`);
  await shot(page, 'r2-reminder-due');

  // ---------------------------------------------------------------- 4. 点「知道了」
  console.log('\n[4] 点「知道了」→ 标记已送达');
  const ackBtn = await page.locator('.reminder-card__ack').first();
  const ackVisible = await ackBtn.isVisible().catch(() => false);
  check('卡片上有「知道了」按钮', ackVisible);
  if (ackVisible) {
    await ackBtn.click();
  }
  const acked = await page.waitForFunction(
    () => /已收到|知道了/.test(document.body.innerText || ''),
    { timeout: 8000 },
  ).then(() => true).catch(() => false);
  // 点完后卡片应熄灭（is-acked），且后端不再有 due
  const stillDue = await page.evaluate(async () => {
    const raw = localStorage.getItem('soulmate.auth.v1');
    const token = raw ? JSON.parse(raw).token : '';
    const res = await fetch('/api/v1/reminders/due', {
      headers: { Authorization: `Bearer ${token}` },
    });
    const json = await res.json();
    return (json?.data || []).length;
  });
  check('确认后待投递队列清空（已送达）', stillDue === 0, `仍剩 ${stillDue} 条`);
  await shot(page, 'r3-acknowledged');

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
