// 한 번 수집하고 결과를 OUT_DIR에 저장한 뒤 종료합니다. (GitHub Actions에서 주기 실행)
//   OUT_DIR/state.json : 다음 실행 때 이어서 쓰는 전체 상태 (급상승 계산용)
//   OUT_DIR/posts.json : 앱이 받아가는 피드 (현재 베스트 목록에 있는 글, 화제도순)
import fs from 'node:fs';
import path from 'node:path';
import { Crawler } from './crawler.js';
import { ACTIVE_SOURCES as SOURCES } from './sources.js';
import { demoFetchItems } from './demo.js';

const outDir = path.resolve(process.env.OUT_DIR || 'out');
const demo = process.env.DEMO === '1';

const crawler = new Crawler({ dataDir: outDir, fetchItems: demo ? demoFetchItems : undefined });
await crawler.crawl();

const feed = {
  version: 1,
  updatedAt: crawler.lastCrawlAt,
  // local: 클라우드 수집이 막혔을 때 앱이 휴대폰에서 직접 받아올 수 있도록 목록 파싱 규칙을 같이 보냄
  sources: SOURCES.map(({ id, name, board, url, color, selectors, keepParams, titleStrip }) => ({
    id, name, board, url, color, status: crawler.status[id],
    ...(selectors ? { local: { url, selectors, keepParams: keepParams ?? [], titleStrip: titleStrip ?? '' } } : {}),
  })),
  posts: crawler.list({ sort: 'score', limit: 500 }).map(
    ({ id, source, title, url, views, comments, likes, score, viewsPerMin, firstSeenAt, rank, image }) => ({
      id, source, title, url, views, comments, likes, score, viewsPerMin, firstSeenAt, rank, ...(image ? { image } : {}),
    }),
  ),
};
fs.writeFileSync(path.join(outDir, 'posts.json'), JSON.stringify(feed));

for (const s of SOURCES) {
  const st = crawler.status[s.id];
  console.log(`${st.ok ? '✓' : '✗'} ${s.name}: ${st.ok ? `${st.count}개` : st.error}`);
}
const okCount = Object.values(crawler.status).filter((s) => s.ok).length;
console.log(`수집 완료 ${okCount}/${SOURCES.length}, 피드 글 ${feed.posts.length}개 → ${outDir}`);
if (okCount === 0 && !demo) process.exit(1);
