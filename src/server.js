import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SOURCES } from './sources.js';
import { Crawler } from './crawler.js';
import { PushService } from './push.js';
import { demoFetchItems } from './demo.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PORT = +process.env.PORT || 3000;
const DEMO = process.env.DEMO === '1';
const INTERVAL_MIN = +process.env.CRAWL_INTERVAL_MIN || (DEMO ? 0.5 : 3);
const dataDir = path.join(root, DEMO ? 'data-demo' : 'data');

const push = new PushService({ dataDir, contact: process.env.VAPID_CONTACT });
const crawler = new Crawler({
  dataDir,
  fetchItems: DEMO ? demoFetchItems : undefined,
  onNewPosts: (posts) => push.notify(posts).catch((e) => console.warn('[push]', e.message)),
});

const app = express();
app.use(express.json({ limit: '64kb' }));
app.use(express.static(path.join(root, 'public')));

app.get('/api/sources', (_req, res) => {
  res.json(
    SOURCES.map(({ id, name, board, url, color }) => ({ id, name, board, url, color, status: crawler.status[id] ?? null })),
  );
});

app.get('/api/posts', (req, res) => {
  const { source, sort, q } = req.query;
  res.json({
    lastCrawlAt: crawler.lastCrawlAt,
    intervalMin: INTERVAL_MIN,
    demo: DEMO,
    posts: crawler.list({ source, sort, q, limit: Math.min(+req.query.limit || 200, 500) }),
  });
});

app.get('/api/push/key', (_req, res) => res.json({ publicKey: push.publicKey }));

app.post('/api/push/subscribe', (req, res) => {
  const { subscription, prefs } = req.body || {};
  if (!subscription?.endpoint) return res.status(400).json({ error: 'subscription 필요' });
  push.upsert(subscription, prefs);
  res.json({ ok: true });
});

app.post('/api/push/unsubscribe', (req, res) => {
  if (req.body?.endpoint) push.remove(req.body.endpoint);
  res.json({ ok: true });
});

app.post('/api/push/test', async (req, res) => {
  const top = crawler.list({ limit: 1 })[0] ?? { id: 'test', source: 'test', title: '테스트 알림입니다', url: '/' };
  try {
    const sent = await push.sendTo(req.body?.endpoint, top);
    if (!sent) return res.status(404).json({ error: '구독 정보 없음' });
    res.json({ ok: true });
  } catch (e) {
    res.status(502).json({ error: `푸시 전송 실패 (${e.statusCode || e.message})` });
  }
});

async function loop() {
  const t = Date.now();
  await crawler.crawl();
  const ok = Object.values(crawler.status).filter((s) => s.ok).length;
  console.log(`[crawl] ${ok}/${SOURCES.length}개 커뮤니티 수집 완료 (${Date.now() - t}ms)`);
  for (const [id, s] of Object.entries(crawler.status)) if (!s.ok) console.log(`  ✗ ${id}: ${s.error}`);
}

app.listen(PORT, () => {
  console.log(`커뮤니티 파인더: http://localhost:${PORT}${DEMO ? '  (데모 모드)' : ''}`);
  loop();
  setInterval(loop, INTERVAL_MIN * 60 * 1000);
});
