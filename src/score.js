// 화제도 점수 (0~100)
// 커뮤니티마다 조회수 규모가 달라서 절대값으로는 비교가 안 됩니다.
// 그래서 같은 커뮤니티 안에서의 백분위(조회·댓글·추천)를 구해 평균내고,
// 직전 수집 대비 조회수 증가율(급상승)을 가산점으로 더합니다.

const METRICS = ['views', 'comments', 'likes'];
const WEIGHT = { views: 1, comments: 1.2, likes: 1 };

function percentileMap(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const n = sorted.length;
  return (v) => {
    if (n <= 1) return 0.5;
    // v 이하인 값의 비율 (동점은 중간값 처리)
    let lo = 0;
    while (lo < n && sorted[lo] < v) lo++;
    let hi = lo;
    while (hi < n && sorted[hi] === v) hi++;
    return ((lo + hi - 1) / 2) / (n - 1);
  };
}

export function scorePosts(posts) {
  const bySource = new Map();
  for (const p of posts) {
    if (!bySource.has(p.source)) bySource.set(p.source, []);
    bySource.get(p.source).push(p);
  }

  for (const group of bySource.values()) {
    const pct = {};
    for (const m of METRICS) {
      const vals = group.map((p) => p[m]).filter((v) => v != null);
      if (vals.length >= 2) pct[m] = percentileMap(vals);
    }
    group.forEach((p, idx) => {
      let sum = 0;
      let w = 0;
      for (const m of METRICS) {
        if (pct[m] && p[m] != null) {
          sum += pct[m](p[m]) * WEIGHT[m];
          w += WEIGHT[m];
        }
      }
      // 숫자 정보가 하나도 없으면 사이트 베스트 목록의 순서를 사용
      const base = w > 0 ? sum / w : 1 - idx / Math.max(group.length - 1, 1);
      const surge = Math.min(p.viewsPerMin ?? 0, 500) / 500; // 분당 500회 이상이면 만점
      p.score = Math.round(Math.min(100, base * 85 + surge * 15));
    });
  }
  return posts;
}
