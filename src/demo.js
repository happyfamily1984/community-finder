// 데모 모드 (DEMO=1): 실제 사이트에 접속하지 않고 가짜 글을 만들어
// 화면과 알림 동작을 확인할 수 있게 합니다. 모든 글 제목에 [데모]가 붙습니다.

const TOPICS = [
  '오늘 퇴근길 지하철 상황', '이번 주 로또 1등 당첨 지역', '편의점 신상 디저트 후기', '고양이가 키보드 위에서 잠듦',
  '회사 점심 메뉴 투표 결과', '주말 날씨 역대급이라네', '아이돌 컴백 무대 반응', '자취 3년차 꿀팁 모음',
  '중고거래 하다 생긴 일', '야구 끝내기 홈런 영상', '신작 게임 첫인상', '부모님께 스마트폰 알려드리기',
  '동네 맛집 숨은 메뉴', '택배 기사님께 받은 쪽지', '드라마 결말 해석', '헬스장 빌런 유형 정리',
];

const state = new Map(); // source id -> posts

function makePost(source, n) {
  const topic = TOPICS[Math.floor(Math.random() * TOPICS.length)];
  return {
    id: `${source.id}:demo-${n}`,
    source: source.id,
    title: `[데모] ${topic} #${n}`,
    url: `${new URL(source.url).origin}/?demo=${n}`,
    views: Math.floor(Math.random() * 20000) + 500,
    comments: Math.floor(Math.random() * 300),
    likes: Math.floor(Math.random() * 500),
  };
}

let counter = 1;
export async function demoFetchItems(source) {
  let posts = state.get(source.id);
  if (!posts) {
    posts = Array.from({ length: 20 }, () => makePost(source, counter++));
  } else {
    // 기존 글은 조회수/댓글 증가, 가끔 새 글이 베스트에 진입
    posts = posts.map((p) => ({
      ...p,
      views: p.views + Math.floor(Math.random() * 3000),
      comments: p.comments + Math.floor(Math.random() * 30),
      likes: p.likes + Math.floor(Math.random() * 40),
    }));
    if (Math.random() < 0.5) {
      const hot = makePost(source, counter++);
      hot.views *= 3;
      hot.comments *= 3;
      hot.likes *= 3;
      posts = [hot, ...posts.slice(0, 19)];
    }
  }
  state.set(source.id, posts);
  return posts;
}
