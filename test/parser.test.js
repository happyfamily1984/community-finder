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
  assert.equal(posts[0].url, 'https://www.clien.net/service/board/park/1001?od=T33');
  assert.deepEqual([posts[0].views, posts[0].comments, posts[0].likes], [3400, 42, 17]);
});
