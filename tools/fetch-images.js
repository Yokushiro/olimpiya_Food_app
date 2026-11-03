// Запускается на GitHub Actions перед сборкой APK.
// Открывает сайт в настоящем браузере (Playwright), прокручивает страницу, открывает карточки блюд
// и сохраняет ВСЕ загруженные картинки в app/src/main/assets/preload + manifest.json.
const { chromium, devices } = require('playwright');
const fs = require('fs');
const path = require('path');

const SITE = process.env.SITE_URL || 'https://falling-dust-d961.olimpiafood.workers.dev/';
const ORIGIN = new URL(SITE).origin;
const OUT = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'preload');
const EXT = {
  'image/jpeg': '.jpg', 'image/jpg': '.jpg', 'image/png': '.png', 'image/webp': '.webp',
  'image/gif': '.gif', 'image/svg+xml': '.svg', 'image/avif': '.avif',
  'image/x-icon': '.ico', 'image/vnd.microsoft.icon': '.ico',
};

(async () => {
  fs.rmSync(OUT, { recursive: true, force: true });
  fs.mkdirSync(OUT, { recursive: true });

  const browser = await chromium.launch();
  const context = await browser.newContext({ ...devices['Pixel 7'] });
  const page = await context.newPage();

  const seen = new Map(); // url -> { buf, ct, headers }
  page.on('response', async (resp) => {
    try {
      if (resp.request().method() !== 'GET' || resp.status() !== 200) return;
      const headers = resp.headers();
      const ct = (headers['content-type'] || '').split(';')[0].trim().toLowerCase();
      if (!ct.startsWith('image/')) return;
      const url = resp.url();
      if (url.startsWith('data:') || seen.has(url)) return;
      seen.set(url, null);
      const buf = await resp.body();
      seen.set(url, { buf, ct, headers });
    } catch (e) { /* пропускаем */ }
  });

  await page.goto(SITE, { waitUntil: 'networkidle', timeout: 90000 });

  const forceLazy = () => page.evaluate(() => {
    document.querySelectorAll('img[loading="lazy"]').forEach((i) => { i.loading = 'eager'; });
    document.querySelectorAll('img[data-src]').forEach((i) => {
      if (!i.getAttribute('src')) i.src = i.getAttribute('data-src');
    });
  }).catch(() => {});

  // 1) Прокручиваем страницу до конца, пока она не перестанет расти
  let stable = 0, lastHeight = -1;
  for (let i = 0; i < 300 && stable < 4; i++) {
    await forceLazy();
    await page.evaluate(() => window.scrollBy(0, Math.round(window.innerHeight * 0.8))).catch(() => {});
    await page.waitForTimeout(350);
    const { h, y, vh } = await page.evaluate(() => ({
      h: document.documentElement.scrollHeight, y: window.scrollY, vh: window.innerHeight,
    })).catch(() => ({ h: 0, y: 0, vh: 0 }));
    if (y + vh >= h - 2 && h === lastHeight) stable++; else stable = 0;
    lastHeight = h;
  }
  await page.waitForLoadState('networkidle').catch(() => {});

  // 2) Открываем карточки блюд (в окне могут быть картинки покрупнее)
  const count = Math.min(await page.locator('img').count().catch(() => 0), 400);
  for (let i = 0; i < count; i++) {
    try {
      await page.locator('img').nth(i).click({ timeout: 1500, force: true });
      await page.waitForTimeout(350);
      await page.locator('button:has-text("×")').first().click({ timeout: 400 }).catch(() => {});
      await page.keyboard.press('Escape').catch(() => {});
      if (!page.url().startsWith(ORIGIN)) {
        await page.goto(SITE, { waitUntil: 'networkidle', timeout: 60000 });
        await forceLazy();
      }
    } catch (e) { /* пропускаем */ }
  }
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(1500);
  await browser.close();

  const manifest = [];
  let n = 0, total = 0;
  for (const [url, v] of seen) {
    if (!v || !v.buf || v.buf.length === 0) continue;
    const name = String(++n).padStart(4, '0') + (EXT[v.ct] || '.img');
    fs.writeFileSync(path.join(OUT, name), v.buf);
    total += v.buf.length;
    manifest.push({
      url, file: name, mime: v.ct,
      etag: v.headers['etag'] || '', lastModified: v.headers['last-modified'] || '',
    });
  }
  fs.writeFileSync(path.join(OUT, 'manifest.json'), JSON.stringify(manifest, null, 1));
  console.log(`Saved ${manifest.length} images, ${(total / 1048576).toFixed(1)} MB`);
  manifest.forEach((m) => console.log(' -', m.url));
})().catch((e) => { console.error(e); process.exit(1); });
