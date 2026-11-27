# 커뮤니티 파인더 — 작업 안내 (새 세션용)

국내·해외 커뮤니티 인기글을 모아 보여주고, 스레드(Threads)에 올릴 이미지와 요약 글을 만들어 주는 안드로이드 앱.
수집은 GitHub Actions, 앱은 Kotlin + Compose. 이 파일은 이전 작업 세션의 맥락을 넘기기 위한 것이다.

## 사용자와 일하는 방식 (꼭 지킬 것)

- **답변은 한국어로, 짧게, 결과 위주.** 토큰을 아끼고 싶어 한다. 확인 단계를 길게 늘이지 않는다.
- **출시는 "출시해"라고 할 때만.** 평소에는 커밋 메시지 앞에 `[skip ci]`를 붙여 푸시(임시저장)하고, 로컬에서 빌드한 **테스트 APK 파일을 대화로 보낸다.**
- 테스트 APK는 **매번 버전 번호를 올려서** 빌드한다 (덮어쓰기 설치용). 마지막으로 보낸 것: `VERSION_CODE=137`, `1.9.2-test`.
  `cd android && VERSION_CODE=<n> VERSION_NAME=<x.y.z-test> ./gradlew --no-daemon -q assembleRelease compileDebugUnitTestKotlin`
- 화면 캡처를 보내며 짧게 말한다("잘린다", "안 된다"). 캡처를 보고 원인을 추정해 바로 고친다.
- 안 되는 게 있으면 "GitHub 뒤져서 방법 찾아"라고 한다 → `gh api -X GET search/code -f q='…'`로 공개 코드에서 선택자·방식을 확인한다.
- 앱 동작에 유료 API나 Claude 토큰이 들면 안 된다. 무료 키(Gemini, Groq)와 휴대폰 내장 기능만 쓴다.
- 사이트가 자동 접근을 막으면 **차단 우회(프록시, 위장)는 넣지 않는다.** 대안: 그 소스를 빼거나, 휴대폰에서 낮은 빈도로 받기.
- 스레드 자동화에서 **게시 버튼은 자동으로 누르지 않는다.**

## 구조

- `src/` — 클라우드 수집기 (Node 22). `sources.js`(소스·선택자), `crawler.js`, `score.js`(화제도), `measure.js`(내보내기 예상 장수), `global.js`(해외 JSON/RSS), `crawl-once.js`.
- `.github/workflows/crawl.yml` — 한 번 켜지면 약 5시간 동안 10분마다 수집해 `data` 브랜치에 `posts.json` 게시, 끝나면 스스로 다음 실행 예약. (GitHub cron은 몇 시간씩 밀려서 이렇게 바꿈)
- `.github/workflows/android.yml` — `android/**` 푸시 시 테스트·스크린샷·APK 빌드. Releases 게시는 수동 실행에서 `release` 체크했을 때만.
- `android/` — 앱. 파일별 역할은 `docs/HANDOFF-부동산뉴스파인더.md` 4번 표 참고. 그 문서 6·9·10번에 겪은 문제와 해결이 정리돼 있다.

## 지금 상태 (2026-11 말)

- **국내 소스:** 디시·클리앙·네이트판·보배드림은 클라우드 수집. 더쿠·루리웹은 클라우드를 막아서 휴대폰이 대신 받음(`LocalFallback.kt`, 더쿠는 WebView 방식 `WebFetcher.kt`). 에펨코리아는 처음부터 휴대폰 수집(`FmkoreaLocal.kt`).
- **해외 소스:** Lemmy(클라우드), 일본 RSS(클라우드). Reddit은 로그인 없는 접근이 막혀 있어 공식 API 클라이언트 ID가 필요한데, **사용자가 Reddit에 API 사용 등록을 신청해야 하는 상태에서 멈춤**(승인 여부 모름). ID만 넣으면 동작하도록 코드는 준비돼 있다.
- **번역:** Gemini → Groq → 휴대폰 내장(ML Kit). 사진 속 글자 번역 포함.
- **내보내기:** 페이지 캡처 → 본문 자르기 → 큰 여백 줄이기 → 분할(`ThreadsKit.cutPoints`) → 요약(`Summarizer.kt`) → 스레드로 공유 + 접근성 자동 입력.
- **요약:** 내용 우선. 본보기(잘 터진 글)는 참고만. "다시 요약"마다 참고 본보기를 순환. 사용자가 보내주는 잘 된 글은 `Summarizer.BUILTIN_EXAMPLES`에 추가한다.
- **스레드 계정 전환:** 접근성 서비스로 프로필 → 설정 → 로그아웃 → 계정 선택. 로그아웃 확인까지는 실제 기기에서 성공 확인. 마지막 단계(로그인 목록에서 계정 누르기)는 대기 시간을 늘린 뒤의 결과를 아직 못 들음.
- **짧은 글 필터:** 클라우드가 새 글을 10분에 8개씩 열어 예상 장수를 추정. 추정 정확도는 아직 실제와 비교하지 못함.
- **설정 백업·복원:** 설정 화면에서 글자로 복사/붙여넣기 (`Prefs.exportBackup/importBackup`).

## 확인 못 한 것 / 남은 일

- 이 환경에서는 에뮬레이터가 안 돌고 커뮤니티 사이트 접속도 막혀 있다. 실제 동작은 사용자의 휴대폰 캡처로만 확인한다.
- 네이트판·보배드림의 본문만 추리기와 작성자 모자이크는 일부 선택자가 추정이다.
- 루리웹 글에 작성자 프로필 영역이 같이 찍힌다 → 네이트판처럼 본문만 추리는 방식(`ISOLATE_SELECTORS`)을 적용하자고 제안해 둠(사용자 답 없음).
- "스레드로 보낸 글을 목록에서도 숨길지" 물어봤으나 답 없음(지금은 내보내기 화면만 닫음).
- Releases의 최신은 아주 오래된 v1.0.2다. 사용자는 대화로 받은 테스트 APK를 쓴다.
- README.md는 초기 내용 그대로라 현재 기능과 맞지 않는다.

## 작업 환경 준비 (세션마다)

Android SDK가 없으면 설치한다:

```bash
mkdir -p /root/android-sdk/cmdline-tools && cd /root/android-sdk
curl -sSL -o clt.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q clt.zip -d cmdline-tools && mv cmdline-tools/cmdline-tools cmdline-tools/latest && rm clt.zip
yes | cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null 2>&1
cmdline-tools/latest/bin/sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=/root/android-sdk" > /home/user/community-finder/android/local.properties
```

- 로컬에서는 `assembleRelease`와 테스트 **컴파일**까지만 된다. Robolectric 실행은 네이티브 런타임 다운로드가 막혀 GitHub Actions에서만 돈다.
- 테스트 함수 이름은 영어로 쓴다 (한글 이름은 빌드 경로 문제로 실패).
- 수집기 테스트: `npm test`. 수집 결과 확인: `git ls-remote origin refs/heads/data`로 sha를 얻어 `raw.githubusercontent.com/<owner>/<repo>/<sha>/posts.json`을 받는다 (브랜치 이름 주소는 5분 캐시됨).
- 서명 키는 저장소의 `android/app/community-finder.keystore` (어디서 빌드해도 같은 서명).
