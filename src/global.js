// 해외 유머 커뮤니티 수집 (공개 JSON API 사용)
const UA = 'android:com.communityfinder.app:v1 (personal feed reader)';

async function getJson(url) {
  const res = await fetch(url, { headers: { 'User-Agent': UA, Accept: 'application/json' }, signal: AbortSignal.timeout(15000) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export function isImageUrl(url, contentType) {
  if (!url || !/^https:\/\//.test(url)) return false;
  if (contentType && /^image\//.test(contentType)) return true;
  return /\.(jpe?g|png|webp|gif|avif)(\?.*)?$/i.test(url) || /\/pictrs\/image\//.test(url);
}

// Reddit: 오늘의 인기글 (성인/고정글 제외)
export async function fetchReddit(source) {
  const json = await getJson(`https://www.reddit.com/r/${source.sub}/top.json?t=day&limit=30&raw_json=1`);
  return json.data.children
    .map((c) => c.data)
    .filter((d) => !d.over_18 && !d.stickied)
    .map((d) => ({
      id: `${source.id}:${d.id}`,
      source: source.id,
      title: d.title,
      url: `https://www.reddit.com${d.permalink}`,
      views: null,
      comments: d.num_comments ?? null,
      likes: d.score ?? null,
      image: d.post_hint === 'image' && isImageUrl(d.url, null) ? d.url : null,
    }));
}

// Lemmy: 오늘의 인기글 (성인글 제외)
export async function fetchLemmy(source) {
  const json = await getJson(`https://${source.host}/api/v3/post/list?community_name=${encodeURIComponent(source.community)}&sort=TopDay&limit=30`);
  const p0 = json.posts[0]?.post;
  if (p0) console.log(`[lemmy 샘플] url=${p0.url} type=${p0.url_content_type} thumb=${p0.thumbnail_url}`);
  return json.posts
    .filter((p) => !p.post.nsfw && !p.post.deleted && !p.post.removed)
    .map((p) => ({
      id: `${source.id}:${p.post.id}`,
      source: source.id,
      title: p.post.name,
      url: `https://${source.host}/post/${p.post.id}`,
      views: null,
      comments: p.counts?.comments ?? null,
      likes: p.counts?.score ?? null,
      // 사진이 본문인 글이면 원본 사진 주소 (앱이 사이트 화면 대신 사진만 깔끔하게 보여줌)
      image: isImageUrl(p.post.url, p.post.url_content_type) ? p.post.url : null,
    }));
}
