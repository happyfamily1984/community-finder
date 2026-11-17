// 글 길이 추정: 스레드로 내보낼 때 이미지가 몇 장쯤 나올지 (앱의 분할 규칙과 같은 기준으로 어림)
import * as cheerio from 'cheerio';

// 화면 폭을 1로 봤을 때의 높이. 앱은 전체가 폭의 2배 이하면 1장, 그보다 길면 약 1.6~2배마다 1장으로 나눔
const LINE_H = 0.065; // 한 줄 높이
const CHARS_PER_LINE = 22; // 한 줄에 들어가는 한글 글자 수
const HEADER_H = 0.3; // 제목 영역

export function estimatePages(html, bodySelector) {
  const $ = cheerio.load(html);
  const body = $(bodySelector).first();
  if (!body.length) return null;
  body.find('script, style, noscript').remove();

  // 글자: 줄바꿈 단위로 나눠 줄 수 계산
  body.find('br').replaceWith('\n');
  body.find('p, div, li').each((_, el) => $(el).append('\n'));
  const lines = body.text().split('\n').map((l) => l.trim());
  let textLines = 0;
  let blank = 0;
  for (const l of lines) {
    if (!l) { blank++; continue; }
    textLines += Math.ceil(l.length / CHARS_PER_LINE);
  }
  // 빈 줄은 앱이 큰 여백을 줄이므로 조금만 반영
  let h = HEADER_H + textLines * LINE_H + Math.min(blank, textLines + 4) * LINE_H * 0.4;

  // 사진·영상: 크기 정보가 있으면 비율대로, 없으면 4:3 가로 사진으로 가정
  let media = 0;
  body.find('img, video, iframe').each((_, el) => {
    const e = $(el);
    const w = parseInt(e.attr('width') || (e.attr('style') || '').match(/width:\s*(\d+)px/)?.[1], 10);
    const hh = parseInt(e.attr('height') || (e.attr('style') || '').match(/height:\s*(\d+)px/)?.[1], 10);
    if (w && w < 80) return; // 이모티콘·아이콘
    if (/emoticon|icon|dccon|sticker/i.test(`${e.attr('class') || ''} ${e.attr('src') || ''}`)) { h += 0.2; return; }
    media++;
    h += w && hh ? Math.min(3, Math.max(0.3, hh / w)) + 0.05 : 0.8;
  });

  const pages = h <= 2 ? 1 : Math.ceil(h / 1.8);
  return { pages, textLines, media };
}
