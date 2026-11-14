const $ = (s) => document.querySelector(s);
const PREFS_KEY = 'cf-prefs';
const state = { source: '', sort: 'score', q: '', sources: [] };

const fmt = (n) => (n == null ? null : n >= 10000 ? `${(n / 10000).toFixed(1).replace(/\.0$/, '')}만` : n.toLocaleString('ko-KR'));
const esc = (s) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

function ago(ts) {
  const m = Math.round((Date.now() - ts) / 60000);
  if (m < 1) return '방금';
  if (m < 60) return `${m}분 전`;
  return `${Math.floor(m / 60)}시간 전`;
}

function loadPrefs() {
  try {
    return { minScore: 80, keywords: [], sources: null, ...JSON.parse(localStorage.getItem(PREFS_KEY)) };
  } catch {
    return { minScore: 80, keywords: [], sources: null };
  }
}
function savePrefs(p) {
  try { localStorage.setItem(PREFS_KEY, JSON.stringify(p)); } catch {}
}

// ---------- 목록 ----------
async function loadSources() {
  state.sources = await fetch('/api/sources').then((r) => r.json());
  const chips = $('#sourceChips');
  const all = [{ id: '', name: '전체', color: 'transparent' }, ...state.sources];
  chips.innerHTML = all
    .map((s) => {
      const err = s.status && !s.status.ok;
      const tip = err ? `수집 실패: ${s.status.error}` : s.board || '';
      return `<button class="chip${err ? ' err' : ''}" data-id="${s.id}" title="${esc(tip)}" aria-pressed="${s.id === state.source}">${
        s.id ? `<span class="dot" style="background:${s.color}"></span>` : ''
      }${esc(s.name)}</button>`;
    })
    .join('');
}

async function loadPosts() {
  const params = new URLSearchParams({ sort: state.sort });
  if (state.source) params.set('source', state.source);
  if (state.q) params.set('q', state.q);
  const data = await fetch(`/api/posts?${params}`).then((r) => r.json());
  $('#demoBadge').hidden = !data.demo;

  const failed = state.sources.filter((s) => s.status && !s.status.ok).map((s) => s.name);
  $('#meta').textContent =
    (data.lastCrawlAt ? `마지막 수집 ${ago(data.lastCrawlAt)} · ${data.intervalMin}분마다 갱신` : '수집 중…') +
    (failed.length ? ` · 수집 실패: ${failed.join(', ')}` : '');

  const byId = Object.fromEntries(state.sources.map((s) => [s.id, s]));
  const list = $('#list');
  if (!data.posts.length) {
    list.innerHTML = `<li class="empty">${data.lastCrawlAt ? '표시할 글이 없어요' : '첫 수집을 진행하고 있어요…'}</li>`;
    return;
  }
  list.innerHTML = data.posts
    .map((p) => {
      const src = byId[p.source];
      const isNew = Date.now() - p.firstSeenAt < 15 * 60000;
      const stats = [
        p.views != null && `<span>👀 ${fmt(p.views)}</span>`,
        p.comments != null && `<span>💬 ${fmt(p.comments)}</span>`,
        p.likes != null && `<span>👍 ${fmt(p.likes)}</span>`,
        p.viewsPerMin >= 50 && `<span class="surge">🚀 분당 ${fmt(p.viewsPerMin)}</span>`,
        isNew && `<span class="new">NEW</span>`,
      ].filter(Boolean);
      return `<li><a class="post" href="${esc(p.url)}" target="_blank" rel="noopener">
        <div class="score${p.score >= 80 ? ' hot' : ''}">${p.score ?? '-'}<small>화제도</small></div>
        <div>
          <p class="title">${esc(p.title)}</p>
          <div class="info"><span class="src" style="color:${src?.color}">${esc(src?.name ?? p.source)}</span>${stats.join('')}<span>${ago(p.firstSeenAt)}</span></div>
        </div></a></li>`;
    })
    .join('');
}

async function refresh() {
  try {
    await loadSources();
    await loadPosts();
  } catch (e) {
    $('#meta').textContent = `서버에 연결할 수 없어요 (${e.message})`;
  }
}

$('#sourceChips').addEventListener('click', (e) => {
  const b = e.target.closest('.chip');
  if (!b) return;
  state.source = b.dataset.id;
  document.querySelectorAll('.chip').forEach((c) => c.setAttribute('aria-pressed', c === b));
  loadPosts();
});
$('#sort').addEventListener('change', (e) => { state.sort = e.target.value; loadPosts(); });
let qTimer;
$('#search').addEventListener('input', (e) => {
  clearTimeout(qTimer);
  qTimer = setTimeout(() => { state.q = e.target.value.trim(); loadPosts(); }, 250);
});

// ---------- 알림 ----------
const pushSupported = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
let swReg = null;

function urlB64ToUint8(b64) {
  const pad = '='.repeat((4 - (b64.length % 4)) % 4);
  const raw = atob((b64 + pad).replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
}

async function currentSub() {
  return swReg ? swReg.pushManager.getSubscription() : null;
}

function readPrefsForm() {
  const checks = [...document.querySelectorAll('#sourceChecks input')];
  const chosen = checks.filter((c) => c.checked).map((c) => c.value);
  return {
    minScore: +$('#minScore').value,
    keywords: $('#keywords').value.split(',').map((s) => s.trim()).filter(Boolean),
    sources: chosen.length === checks.length ? null : chosen,
  };
}

async function updatePushUI() {
  const sub = await currentSub();
  const status = $('#pushStatus');
  if (!pushSupported) {
    status.textContent = '이 브라우저는 푸시 알림을 지원하지 않아요. iPhone은 Safari에서 "홈 화면에 추가" 후 앱으로 열어주세요.';
    $('#pushToggle').disabled = true;
  } else if (Notification.permission === 'denied') {
    status.textContent = '알림이 차단되어 있어요. 브라우저 사이트 설정에서 알림을 허용해주세요.';
  } else {
    status.textContent = sub ? '✅ 알림이 켜져 있어요. 설정을 바꾸면 자동 저장돼요.' : '알림이 꺼져 있어요.';
  }
  $('#pushToggle').textContent = sub ? '알림 끄기' : '알림 켜기';
  $('#pushTest').hidden = !sub;
}

async function syncPrefs() {
  const prefs = readPrefsForm();
  savePrefs(prefs);
  const sub = await currentSub();
  if (sub) {
    await fetch('/api/push/subscribe', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ subscription: sub, prefs }),
    });
  }
}

async function togglePush() {
  const existing = await currentSub();
  if (existing) {
    await fetch('/api/push/unsubscribe', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ endpoint: existing.endpoint }),
    });
    await existing.unsubscribe();
  } else {
    const perm = await Notification.requestPermission();
    if (perm !== 'granted') return updatePushUI();
    const { publicKey } = await fetch('/api/push/key').then((r) => r.json());
    await swReg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: urlB64ToUint8(publicKey) });
    await syncPrefs();
  }
  updatePushUI();
}

function renderSettings() {
  const prefs = loadPrefs();
  $('#minScore').value = prefs.minScore;
  $('#minScoreVal').textContent = prefs.minScore;
  $('#keywords').value = prefs.keywords.join(', ');
  $('#sourceChecks').innerHTML = state.sources
    .map((s) => `<label><input type="checkbox" value="${s.id}" ${!prefs.sources || prefs.sources.includes(s.id) ? 'checked' : ''}>${esc(s.name)}</label>`)
    .join('');
  updatePushUI();
}

$('#settingsBtn').addEventListener('click', () => { renderSettings(); $('#settings').showModal(); });
$('#minScore').addEventListener('input', (e) => { $('#minScoreVal').textContent = e.target.value; });
$('#settings').addEventListener('change', () => syncPrefs());
$('#pushToggle').addEventListener('click', () => togglePush().catch((e) => { $('#pushStatus').textContent = `오류: ${e.message}`; }));
$('#pushTest').addEventListener('click', async () => {
  const sub = await currentSub();
  if (sub) fetch('/api/push/test', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ endpoint: sub.endpoint }),
  });
});

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').then((r) => { swReg = r; }).catch(() => {});
}

refresh();
setInterval(refresh, 60000);
