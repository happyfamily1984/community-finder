import fs from 'node:fs';
import path from 'node:path';
import { ACTIVE_SOURCES as SOURCES } from './sources.js';
import { fetchHtml } from './fetcher.js';
import { parseList } from './parser.js';
import { scorePosts } from './score.js';
import { estimatePages } from './measure.js';

const KEEP_MS = 24 * 60 * 60 * 1000; // 24시간 지난 글은 정리

export class Crawler {
  constructor({ dataDir, fetchItems, sources = SOURCES, onNewPosts = () => {} } = {}) {
    // fetchItems(source) -> 글 배열. 기본값은 실제 사이트를 가져와 파싱
    this.fetchItems = fetchItems ?? (async (source) => (source.fetch ? source.fetch(source) : parseList(await fetchHtml(source.url), source)));
    this.sources = sources;
    // 글 길이 추정은 실제 수집일 때만 (테스트·데모에서는 끔). 사이트에 무리가 안 되도록 한 번에 조금씩만
    this.measureBody = fetchItems ? null : fetchHtml;
    this.measurePerRun = 8;
    this.measureDelayMs = 1500;
    this.onNewPosts = onNewPosts;
    this.posts = new Map();
    this.status = {}; // source id -> { ok, count, error, at }
    this.lastCrawlAt = null;
    this.file = dataDir ? path.join(dataDir, 'state.json') : null;
    this.load();
  }

  load() {
    if (!this.file || !fs.existsSync(this.file)) return;
    try {
      const arr = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      for (const p of arr) this.posts.set(p.id, p);
      this.hasCrawledBefore = this.posts.size > 0;
    } catch (e) {
      console.warn('[crawler] 저장된 데이터를 읽지 못했습니다:', e.message);
    }
  }

  save() {
    if (!this.file) return;
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    fs.writeFileSync(this.file, JSON.stringify([...this.posts.values()]));
  }

  async crawlSource(source, now) {
    try {
      const items = await this.fetchItems(source);
      if (items.length === 0) throw new Error('글을 찾지 못했습니다 (사이트 구조 변경 또는 차단 가능성)');
      this.status[source.id] = { ok: true, count: items.length, at: now };
      return items;
    } catch (e) {
      this.status[source.id] = { ok: false, count: 0, error: e.message, at: now };
      return [];
    }
  }

  async crawl() {
    const now = Date.now();
    const results = await Promise.all(this.sources.map((s) => this.crawlSource(s, now)));
    const fresh = [];

    results.forEach((items, si) => {
      const src = this.sources[si];
      const current = new Set(items.map((it) => it.id));
      items.forEach((item, rank) => {
        const prev = this.posts.get(item.id);
        const p = { ...prev, ...item, rank: rank + 1, lastSeenAt: now, onBoard: true };
        if (!prev) {
          p.firstSeenAt = now;
          p.viewsPerMin = 0;
          fresh.push(p);
        } else if (prev.views != null && item.views != null && now > prev.lastSeenAt) {
          const mins = (now - prev.lastSeenAt) / 60000;
          p.viewsPerMin = Math.max(0, Math.round((item.views - prev.views) / mins));
        }
        this.posts.set(p.id, p);
      });
      // 이번 수집에서 베스트 목록에서 빠진 글 표시 (수집 성공한 소스만)
      if (this.status[src.id]?.ok) {
        for (const p of this.posts.values()) {
          if (p.source === src.id && !current.has(p.id)) p.onBoard = false;
        }
      }
    });

    for (const [id, p] of this.posts) {
      if (now - p.lastSeenAt > KEEP_MS) this.posts.delete(id);
    }

    scorePosts([...this.posts.values()].filter((p) => p.onBoard));
    await this.measureNewPosts();
    this.lastCrawlAt = now;
    this.save();

    // 처음 실행할 때는 기존 베스트 글 전부가 '새 글'이므로 알림을 보내지 않음
    if (this.hasCrawledBefore && fresh.length) this.onNewPosts(fresh);
    this.hasCrawledBefore = true;
    return fresh;
  }

  // 아직 길이를 모르는 글 중 화제도 높은 순으로 몇 개만 열어서 '내보내기 몇 장'인지 추정
  async measureNewPosts() {
    if (!this.measureBody) return;
    const bodyOf = Object.fromEntries(this.sources.filter((s) => s.body).map((s) => [s.id, s.body]));
    const todo = [...this.posts.values()]
      .filter((p) => p.onBoard && bodyOf[p.source] && p.pages === undefined && (p.measureTries ?? 0) < 2)
      .sort((a, b) => (b.score ?? 0) - (a.score ?? 0))
      .slice(0, this.measurePerRun);
    for (const p of todo) {
      p.measureTries = (p.measureTries ?? 0) + 1;
      try {
        const est = estimatePages(await this.measureBody(p.url), bodyOf[p.source]);
        if (est) p.pages = est.pages;
      } catch {
        // 실패하면 다음 수집 때 한 번만 더 시도
      }
      await new Promise((r) => setTimeout(r, this.measureDelayMs));
    }
    if (todo.length) console.log(`[measure] ${todo.filter((p) => p.pages !== undefined).length}/${todo.length}개 글 길이 추정`);
  }

  list({ source, sort = 'score', q, limit = 200 } = {}) {
    let arr = [...this.posts.values()].filter((p) => p.onBoard);
    if (source) arr = arr.filter((p) => p.source === source);
    if (q) {
      const k = q.toLowerCase();
      arr = arr.filter((p) => p.title.toLowerCase().includes(k));
    }
    const key = {
      score: (p) => p.score ?? 0,
      views: (p) => p.views ?? -1,
      comments: (p) => p.comments ?? -1,
      likes: (p) => p.likes ?? -1,
      surge: (p) => p.viewsPerMin ?? 0,
      latest: (p) => p.firstSeenAt,
    }[sort] ?? ((p) => p.score ?? 0);
    arr.sort((a, b) => key(b) - key(a));
    return arr.slice(0, limit);
  }
}
