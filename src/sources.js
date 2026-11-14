// 커뮤니티별 수집 설정.
// 사이트 HTML 구조가 바뀌면 이 파일의 selector만 고치면 됩니다.
//   row      : 글 한 줄(목록의 각 항목)
//   title    : 제목 텍스트가 들어있는 요소 (row 기준)
//   link     : href를 가진 a 태그 (없으면 title에서 찾음)
//   views / comments / likes : 숫자가 들어있는 요소 (없으면 생략)
//   exclude  : 공지·광고 등 제외할 row 선택자
//   keepParams : 글 주소에서 남길 쿼리 파라미터 (나머지는 제거 → 같은 글이 순위가 바뀌어도 같은 id)
//   titleStrip : 제목에서 지울 패턴 (정규식)
//   disabled : true면 수집 안 함
import { fetchReddit, fetchLemmy, fetchRss } from './global.js';

export const SOURCES = [
  {
    id: 'dcinside',
    name: '디시인사이드',
    board: '실시간 베스트',
    url: 'https://gall.dcinside.com/board/lists/?id=dcbest',
    color: '#3b4890',
    keepParams: ['id', 'no'],
    selectors: {
      row: 'tr.ub-content.us-post',
      exclude: '[data-type="icon_notice"]',
      title: 'td.gall_tit > a:first-child',
      views: 'td.gall_count',
      likes: 'td.gall_recommend',
      comments: 'td.gall_tit .reply_num',
    },
  },
  {
    id: 'fmkorea',
    name: '에펨코리아',
    board: '포텐 터짐',
    url: 'https://www.fmkorea.com/best',
    color: '#4a7bd0',
    // 클라우드(해외 서버) 자동 접근을 차단함 (HTTP 430). 차단을 우회하지 않고 꺼둠
    disabled: true,
    selectors: {
      row: '.fm_best_widget li.li',
      title: 'h3.title a',
      comments: 'h3.title .comment_count',
      likes: '.pc_voted_count .count',
    },
  },
  {
    id: 'theqoo',
    name: '더쿠',
    board: 'HOT',
    url: 'https://theqoo.net/hot',
    color: '#e0559b',
    selectors: {
      row: 'table.theqoo_board_table tbody tr',
      exclude: '.notice, .notice_expand',
      title: 'td.title > a:first-child',
      views: 'td.m_no:last-child',
      comments: 'td.title a.replyNum',
    },
  },
  {
    id: 'ruliweb',
    name: '루리웹',
    board: '유머 베스트',
    url: 'https://bbs.ruliweb.com/best/humor_only',
    color: '#2b6fb6',
    titleStrip: '^\\d{1,2}\\s+',
    selectors: {
      row: 'tr.table_body.blocktarget',
      exclude: '.notice',
      title: 'a.subject_link',
      views: 'td.hit',
      likes: 'td.recomd',
      comments: '.num_reply',
    },
  },
  {
    id: 'clien',
    name: '클리앙',
    board: '모두의공원 추천',
    url: 'https://www.clien.net/service/board/park?&od=T33',
    color: '#3d5a80',
    selectors: {
      row: 'div.list_item.symph_row',
      exclude: '.blocked, .notice',
      title: 'span.subject_fixed',
      link: 'a.list_subject',
      views: '.list_hit .hit',
      comments: '.list_reply .rSymph05',
      likes: '.list_symph span',
    },
  },
  {
    id: 'natepann',
    name: '네이트판',
    board: '랭킹',
    url: 'https://pann.nate.com/talk/ranking',
    color: '#ef4136',
    selectors: {
      row: 'ul.post_wrap > li',
      title: 'dt a',
      views: '.count',
      likes: '.rcm',
      comments: '.reply-num',
    },
  },
  {
    id: 'bobaedream',
    name: '보배드림',
    board: '베스트',
    url: 'https://www.bobaedream.co.kr/list?code=best',
    color: '#0072bc',
    keepParams: ['code', 'No'],
    selectors: {
      row: 'table#boardlist tbody tr[itemtype]',
      title: 'a.bsubject',
      views: 'td.count',
      likes: 'td.recomm',
      comments: '.totreply',
    },
  },
  // Reddit은 클라우드 접근을 막아서(HTTP 403) 앱이 휴대폰에서 직접 받아옴 (RedditLocal.kt)
  // ---- 해외 유머 (id가 reddit-/lemmy- 로 시작하면 앱에서 '해외' 탭 + 번역 대상) ----
  { id: 'reddit-funny', name: 'Reddit r/funny', board: '오늘의 인기', url: 'https://www.reddit.com/r/funny/', color: '#ff4500', sub: 'funny', fetch: fetchReddit, disabled: true },
  { id: 'reddit-memes', name: 'Reddit r/memes', board: '오늘의 인기', url: 'https://www.reddit.com/r/memes/', color: '#ff6a33', sub: 'memes', fetch: fetchReddit, disabled: true },
  { id: 'reddit-unexpected', name: 'Reddit r/Unexpected', board: '오늘의 인기', url: 'https://www.reddit.com/r/Unexpected/', color: '#d93a00', sub: 'Unexpected', fetch: fetchReddit, disabled: true },
  { id: 'lemmy-memes', name: 'Lemmy memes', board: '오늘의 인기', url: 'https://lemmy.world/c/memes', color: '#00a846', host: 'lemmy.world', community: 'memes', fetch: fetchLemmy },
  { id: 'lemmy-shitpost', name: 'Lemmy shitpost', board: '오늘의 인기', url: 'https://lemmy.world/c/lemmyshitpost', color: '#1b8f5a', host: 'lemmy.world', community: 'lemmyshitpost', fetch: fetchLemmy },
  { id: 'lemmy-funny', name: 'Lemmy funny', board: '오늘의 인기', url: 'https://lemmy.world/c/funny', color: '#3aa76d', host: 'lemmy.world', community: 'funny', fetch: fetchLemmy },
  { id: 'lemmy-meirl', name: 'Lemmy me_irl', board: '오늘의 인기', url: 'https://lemmy.world/c/me_irl', color: '#2e9e6b', host: 'lemmy.world', community: 'me_irl', fetch: fetchLemmy },
  { id: 'lemmy-comics', name: 'Lemmy 만화', board: '오늘의 인기', url: 'https://lemmy.world/c/comicstrips', color: '#4a9d5f', host: 'lemmy.world', community: 'comicstrips', fetch: fetchLemmy },
  { id: 'lemmy-greentext', name: 'Lemmy greentext', board: '오늘의 인기', url: 'https://sh.itjust.works/c/greentext', color: '#789922', host: 'lemmy.world', community: 'greentext@sh.itjust.works', fetch: fetchLemmy },
  { id: 'lemmy-comebacks', name: 'Lemmy 사이다 댓글', board: '오늘의 인기', url: 'https://lemmy.world/c/clevercomebacks', color: '#16a085', host: 'lemmy.world', community: 'clevercomebacks', fetch: fetchLemmy },
  { id: 'lemmy-tumblr', name: 'Lemmy tumblr', board: '오늘의 인기', url: 'https://lemmy.world/c/tumblr', color: '#36465d', host: 'lemmy.world', community: 'tumblr', fetch: fetchLemmy },
  { id: 'lemmy-proghumor', name: 'Lemmy 개발자 유머', board: '오늘의 인기', url: 'https://programming.dev/c/programmer_humor', color: '#27ae60', host: 'lemmy.world', community: 'programmer_humor@programming.dev', fetch: fetchLemmy },
  // ---- 일본 유머 (id가 jp- 로 시작하면 앱에서 '해외' 탭 + 일본어 번역 대상) ----
  { id: 'jp-hatena', name: '하테나 북마크', board: '재미 인기글', url: 'https://b.hatena.ne.jp/hotentry/fun', color: '#00a4de', feed: 'https://b.hatena.ne.jp/hotentry/fun.rss', fetch: fetchRss },
  { id: 'jp-nwknews', name: '철학뉴스', board: '5ch 마토메', url: 'http://blog.livedoor.jp/nwknews/', color: '#7a5c3e', feed: 'http://blog.livedoor.jp/nwknews/index.rdf', fetch: fetchRss },
  { id: 'jp-himasoku', name: '히마진 속보', board: '5ch 마토메', url: 'http://himasoku.com/', color: '#e67e22', feed: 'http://himasoku.com/index.rdf', fetch: fetchRss },
  { id: 'jp-alfalfa', name: '알팔파 모자이크', board: '5ch 마토메', url: 'http://alfalfalfa.com/', color: '#8e44ad', feed: 'http://alfalfalfa.com/index.rdf', fetch: fetchRss },
  { id: 'jp-vipper', name: 'VIPPER', board: '5ch 마토메', url: 'http://blog.livedoor.jp/news23vip/', color: '#c0392b', feed: 'http://blog.livedoor.jp/news23vip/index.rdf', fetch: fetchRss },
];

export const ACTIVE_SOURCES = SOURCES.filter((s) => !s.disabled);
export const sourceById = Object.fromEntries(SOURCES.map((s) => [s.id, s]));
