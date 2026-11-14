import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Crawler } from '../src/crawler.js';
import { scorePosts } from '../src/score.js';

const src = [{ id: 'a', name: 'A', url: 'https://a.test/' }, { id: 'b', name: 'B', url: 'https://b.test/' }];
const mk = (source, n, views) => ({ id: `${source}:${n}`, source, title: `글 ${n}`, url: `https://${source}.test/${n}`, views, comments: null, likes: null });

test('화제도: 커뮤니티 안에서 상대 순위로 매겨짐', () => {
  const posts = scorePosts([mk('a', 1, 10), mk('a', 2, 100), mk('b', 3, 100000), mk('b', 4, 1000000)]);
  const s = Object.fromEntries(posts.map((p) => [p.id, p.score]));
  assert.equal(s['a:2'], s['b:4']); // 규모가 달라도 1등끼리 같은 점수
  assert.ok(s['a:2'] > s['a:1']);
});

test('크롤러: 첫 수집은 알림 없음, 이후 새 글만 알림, 실패 소스는 상태 기록', async () => {
  let round = 0;
  const notified = [];
  const c = new Crawler({
    sources: src,
    onNewPosts: (p) => notified.push(...p.map((x) => x.id)),
    fetchItems: async (s) => {
      if (s.id === 'b') throw new Error('HTTP 403');
      return round === 0 ? [mk('a', 1, 10), mk('a', 2, 20)] : [mk('a', 3, 50), mk('a', 1, 40)];
    },
  });
  await c.crawl();
  assert.deepEqual(notified, []);
  assert.equal(c.status.b.ok, false);
  assert.equal(c.status.b.error, 'HTTP 403');

  round = 1;
  await c.crawl();
  assert.deepEqual(notified, ['a:3']);
  // a:2는 베스트에서 빠졌으므로 목록에서 제외
  assert.deepEqual(c.list({ sort: 'views' }).map((p) => p.id), ['a:3', 'a:1']);
});
