import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { parseCount, parseList } from '../src/parser.js';
import { sourceById } from '../src/sources.js';

const fixture = (n) => fs.readFileSync(new URL(`./fixtures/${n}.html`, import.meta.url), 'utf8');

test('parseCount: 다양한 숫자 표기', () => {
  assert.equal(parseCount('1,234'), 1234);
  assert.equal(parseCount('조회 1.2만'), 12000);
  assert.equal(parseCount('[12]'), 12);
  assert.equal(parseCount('3.4 k'), 3400);
  assert.equal(parseCount('-'), null);
  assert.equal(parseCount(''), null);
});

test('디시: 공지 제외, 제목/숫자 추출, 절대 URL', () => {
  const posts = parseList(fixture('dcinside'), sourceById.dcinside);
  assert.equal(posts.length, 2);
  assert.deepEqual(posts[0], {
    id: 'dcinside:https://gall.dcinside.com/board/view/?id=dcbest&no=100',
    source: 'dcinside',
    title: '고양이 짤 모음',
    url: 'https://gall.dcinside.com/board/view/?id=dcbest&no=100',
    views: 45678,
    comments: 123,
    likes: 321,
  });
  assert.equal(posts[1].views, 12000);
});

test('클리앙: 별도 link 선택자, 차단글 제외', () => {
  const posts = parseList(fixture('clien'), sourceById.clien);
  assert.equal(posts.length, 1);
  assert.equal(posts[0].title, '맥북 샀어요');
  assert.equal(posts[0].url, 'https://www.clien.net/service/board/park/1001');
  assert.deepEqual([posts[0].views, posts[0].comments, posts[0].likes], [3400, 42, 17]);
});

test('루리웹: 제목 앞 순위 숫자 제거', () => {
  const html = '<table><tr class="table_body blocktarget"><td><a class="subject_link" href="/best/board/300143/read/1?m=humor&t=1">1 서울우유 결과</a></td><td class="hit">100</td></tr></table>';
  const [p] = parseList(html, sourceById.ruliweb);
  assert.equal(p.title, '서울우유 결과');
  assert.equal(p.url, 'https://bbs.ruliweb.com/best/board/300143/read/1');
});

test('해외: 사진 주소 판별', async () => {
  const { isImageUrl } = await import('../src/global.js');
  assert.equal(isImageUrl('https://i.redd.it/abc.jpg', null), true);
  assert.equal(isImageUrl('https://media.piefed.world/x/y', 'image/webp'), true);
  assert.equal(isImageUrl('https://example.com/article', 'text/html'), false);
  assert.equal(isImageUrl('http://insecure.com/a.png', null), false);
});

test('RSS/RDF 파싱: 북마크 수, 광고 제외', async () => {
  const { parseRss } = await import('../src/global.js');
  const xml = `<?xml version="1.0"?><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:hatena="http://www.hatena.ne.jp/info/xmlns#">
    <item rdf:about="https://a.jp/1"><title>猫が  かわいい</title><link>https://a.jp/1</link><hatena:bookmarkcount>321</hatena:bookmarkcount></item>
    <item><title>PR: 広告</title><link>https://ad.jp/x</link></item>
    <item rdf:about="https://a.jp/2"><title>犬</title><link>https://a.jp/2</link></item></rdf:RDF>`;
  const posts = parseRss(xml, { id: 'jp-test' });
  assert.deepEqual(posts.map((p) => [p.id, p.title, p.likes]), [['jp-test:https://a.jp/1', '猫が かわいい', 321], ['jp-test:https://a.jp/2', '犬', null]]);
});

test('글 길이 추정: 짧은 글은 1장, 사진 많은 글은 여러 장', async () => {
  const { estimatePages } = await import('../src/measure.js');
  const short = '<div class="b"><p>짧은 글입니다</p><img src="a.jpg" width="600" height="400"></div>';
  assert.equal(estimatePages(short, '.b').pages, 1);
  const long = `<div class="b">${'<img src="x.jpg">'.repeat(8)}<p>${'가'.repeat(600)}</p></div>`;
  assert.ok(estimatePages(long, '.b').pages >= 4);
  // 이모티콘은 사진으로 세지 않음
  assert.equal(estimatePages('<div class="b"><img class="dccon" src="e.png"><p>ㅋㅋ</p></div>', '.b').media, 0);
  assert.equal(estimatePages('<div>없음</div>', '.b'), null);
});
