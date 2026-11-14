import fs from 'node:fs';
import path from 'node:path';
import * as webpush from 'web-push';
import { sourceById } from './sources.js';

// 웹 푸시 알림: 앱이 꺼져 있어도(브라우저만 살아 있으면) 알림이 뜹니다.
export class PushService {
  constructor({ dataDir, contact = 'mailto:admin@localhost' }) {
    this.dir = dataDir;
    fs.mkdirSync(dataDir, { recursive: true });
    this.keyFile = path.join(dataDir, 'vapid.json');
    this.subFile = path.join(dataDir, 'subscriptions.json');

    if (!fs.existsSync(this.keyFile)) {
      fs.writeFileSync(this.keyFile, JSON.stringify(webpush.generateVAPIDKeys(), null, 2));
    }
    this.keys = JSON.parse(fs.readFileSync(this.keyFile, 'utf8'));
    webpush.setVapidDetails(contact, this.keys.publicKey, this.keys.privateKey);

    this.subs = fs.existsSync(this.subFile) ? JSON.parse(fs.readFileSync(this.subFile, 'utf8')) : [];
  }

  get publicKey() {
    return this.keys.publicKey;
  }

  saveSubs() {
    fs.writeFileSync(this.subFile, JSON.stringify(this.subs, null, 2));
  }

  upsert(subscription, prefs) {
    this.subs = this.subs.filter((s) => s.subscription.endpoint !== subscription.endpoint);
    this.subs.push({ subscription, prefs: normalizePrefs(prefs) });
    this.saveSubs();
  }

  remove(endpoint) {
    this.subs = this.subs.filter((s) => s.subscription.endpoint !== endpoint);
    this.saveSubs();
  }

  // 조건 상관없이 특정 구독자에게 바로 전송 (테스트 알림용)
  async sendTo(endpoint, post) {
    const sub = this.subs.find((s) => s.subscription.endpoint === endpoint);
    if (!sub) return false;
    await webpush.sendNotification(sub.subscription, JSON.stringify(buildPayload([post])));
    return true;
  }

  async notify(posts) {
    const dead = [];
    await Promise.all(
      this.subs.map(async ({ subscription, prefs }) => {
        const matched = posts.filter((p) => matchesPrefs(p, prefs));
        if (!matched.length) return;
        try {
          await webpush.sendNotification(subscription, JSON.stringify(buildPayload(matched)));
        } catch (e) {
          if (e.statusCode === 404 || e.statusCode === 410) dead.push(subscription.endpoint);
          else console.warn('[push] 전송 실패:', e.statusCode || e.message);
        }
      }),
    );
    if (dead.length) {
      this.subs = this.subs.filter((s) => !dead.includes(s.subscription.endpoint));
      this.saveSubs();
    }
  }
}

export function normalizePrefs(prefs = {}) {
  return {
    minScore: Number.isFinite(+prefs.minScore) ? +prefs.minScore : 80,
    keywords: (prefs.keywords || []).map((k) => String(k).trim()).filter(Boolean),
    sources: Array.isArray(prefs.sources) ? prefs.sources : null, // null = 전체
  };
}

// 알림 조건: 선택한 커뮤니티의 새 베스트 글 중 (화제도 >= 기준) 또는 (키워드 포함)
export function matchesPrefs(post, prefs) {
  if (prefs.sources && !prefs.sources.includes(post.source)) return false;
  const title = post.title.toLowerCase();
  if (prefs.keywords.some((k) => title.includes(k.toLowerCase()))) return true;
  return (post.score ?? 0) >= prefs.minScore;
}

function buildPayload(posts) {
  const top = [...posts].sort((a, b) => (b.score ?? 0) - (a.score ?? 0))[0];
  const src = sourceById[top.source]?.name ?? top.source;
  return {
    title: posts.length > 1 ? `🔥 화제글 ${posts.length}개 — ${src}` : `🔥 ${src} 화제글`,
    body: top.title,
    url: top.url,
    tag: posts.length > 1 ? 'community-finder-batch' : top.id,
  };
}
