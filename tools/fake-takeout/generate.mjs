#!/usr/bin/env node
// 가상 Google Takeout 기록 생성기 (의존성 없음, Node 20+)
//
// 실제 "내 활동 → YouTube · JSON"(내활동.json)과 같은 구조·비율로 가짜 기록을 만든다.
// 비율 근거: docs/l1-design.md "채택한 파일의 내용" (2026-10-24, 계정 1개, 제목은 보지 않고 개수만 셈)
//
// 사용 예:
//   node tools/fake-takeout/generate.mjs --watches 50000 --out tools/results/myactivity-50k.json
//   node tools/fake-takeout/generate.mjs --watches 300 --seed 1 --out tools/fixtures/myactivity-small.json
//   node tools/fake-takeout/generate.mjs --kind youtube --lang en --watches 1000 --out /tmp/watch-history.json
//
// 옵션:
//   --watches N     시청 항목 수 (기본 50000). 검색·기타 활동은 실제 비율로 따라 붙는다
//   --start, --end  기간 (기본 2023-03-11 ~ 2026-10-24)
//   --seed N        난수 시드 (기본 42). 같은 시드면 같은 파일이 나온다
//   --kind          myactivity(기본, 내 활동 → YouTube) | youtube(YouTube 및 YouTube Music → 시청 기록, 검색 없음)
//   --lang          ko(기본) | en (영어 문구는 공개 자료 기준 추정)
//   --out PATH      출력 파일 (없으면 표준 출력)

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";

// ---------- 옵션 ----------
const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, cur, i, arr) => {
    if (cur.startsWith("--")) acc.push([cur.slice(2), arr[i + 1]]);
    return acc;
  }, []),
);
const WATCHES = Number(args.watches ?? 50000);
const START = new Date(`${args.start ?? "2023-03-11"}T00:00:00+09:00`).getTime();
const END = new Date(`${args.end ?? "2026-10-24"}T23:59:59+09:00`).getTime();
const SEED = Number(args.seed ?? 42);
const KIND = args.kind ?? "myactivity";
const LANG = args.lang ?? "ko";
const OUT = args.out;

// ---------- 실제 파일에서 잰 비율 ----------
const RATIO = {
  searchPerWatch: 8639 / 95681, // 검색 / 시청
  otherPerWatch: 6705 / 95681, // 기타 활동 / 시청
  deleted: 4305 / 95681, // 삭제·비공개 영상 (제목이 URL, 채널 없음)
  musicApp: 20811 / 95681, // 뮤직 앱 재생
  topicInYoutubeApp: (25409 - 20811) / 95681, // 일반 앱에서 듣는 Topic 채널 음원
  songsPerMusicPlay: 4628 / 25409, // 고유 곡 / 규칙에 걸리는 음악 재생 (곡당 약 5.5회)
  mvShareOfRest: 0.15, // 나머지 일반 앱 시청 중 뮤직비디오·공식 채널 비율 (실측 아님, L2 판정 대상용 가정)
  newGeneralVideo: 0.84, // 일반 영상은 대부분 새 영상 (규칙 밖 재생 65,967회에 고유 영상 52,494개)
};

// 연도별 하루 평균 시청 수 (실제: 2023년 3~12월 1,534 / 2024년 9,982 / 2025년 46,911 / 2026년 10월까지 37,254)
const DAILY_RATE_BY_YEAR = { 2023: 5.2, 2024: 27.3, 2025: 128.5, 2026: 125.4 };

// ---------- 결정적 난수 ----------
let state = SEED >>> 0;
const rand = () => {
  state = (state + 0x6d2b79f5) >>> 0;
  let t = state;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
const pick = (arr) => arr[Math.floor(rand() * arr.length)];
const chance = (p) => rand() < p;
const ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
const randomId = (len) => Array.from({ length: len }, () => pick(ID_CHARS)).join("");
const pad = (n, w) => String(n).padStart(w, "0");

// 인기 순위를 따르는 분포 (몇 곡을 아주 많이 듣고 나머지는 가끔)
const zipfSampler = (n, s = 1.0) => {
  const weights = Array.from({ length: n }, (_, i) => 1 / Math.pow(i + 1, s));
  const total = weights.reduce((a, b) => a + b, 0);
  const cdf = [];
  let acc = 0;
  for (const w of weights) cdf.push((acc += w / total));
  return () => {
    const r = rand();
    let lo = 0, hi = n - 1;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (cdf[mid] < r) lo = mid + 1; else hi = mid;
    }
    return lo;
  };
};

// ---------- 문구 (언어별) ----------
const T = LANG === "en"
  ? {
      watched: (t) => `Watched ${t}`,
      searched: (q) => `Searched for ${q}`,
      watchCtrl: "YouTube watch history",
      searchCtrl: "YouTube search history",
      others: [(x) => `Viewed ${x}`, (x) => `Subscribed to ${x}`, (x) => `Viewed post ${x}`],
    }
  : {
      watched: (t) => `${t} 을(를) 시청했습니다.`,
      searched: (q) => `${q} 을(를) 검색했습니다.`,
      watchCtrl: "YouTube 시청 기록",
      searchCtrl: "YouTube 검색 기록",
      others: [(x) => `${x} 목록을 확인함`, (x) => `${x} 채널을 구독했습니다.`, (x) => `${x} 게시물을 조회함`],
    };

// ---------- 가상 카탈로그 (실제 곡·가수 이름은 쓰지 않는다) ----------
const ARTIST_STYLES = [
  (n) => `가상가수${pad(n, 3)}`,
  (n) => `Fake Artist ${pad(n, 3)}`,
  (n) => `架空歌手${pad(n, 3)}`,
];
const SONG_STYLES = [
  (n) => `가상노래 ${pad(n, 4)}`,
  (n) => `Fake Song ${pad(n, 4)}`,
  (n) => `架空の歌 ${pad(n, 4)}`,
];
const channel = () => ({ id: `UC${randomId(22)}` });

const musicPlays = Math.round(WATCHES * (RATIO.musicApp + RATIO.topicInYoutubeApp));
const songCount = Math.max(5, Math.round(musicPlays * RATIO.songsPerMusicPlay));
const artistCount = Math.max(2, Math.round(songCount / 8));
const artists = Array.from({ length: artistCount }, (_, i) => ({
  name: pick(ARTIST_STYLES)(i + 1),
  topic: channel(),
  official: channel(),
}));
const songs = Array.from({ length: songCount }, (_, i) => ({
  title: pick(SONG_STYLES)(i + 1),
  artist: artists[Math.floor(rand() * artistCount)],
  audioId: randomId(11), // Topic 채널 음원 영상
  mvId: randomId(11), // 공식 뮤직비디오 영상
}));
const nextSong = zipfSampler(songCount, 1.0);

// 일반 영상은 대부분 한 번씩만 본다 (실제 재생 1.26회 정도). 가끔 본 영상을 다시 본다.
const generalChannels = Array.from({ length: Math.max(5, Math.round(WATCHES / 25)) }, (_, i) => ({
  name: `가상채널 ${pad(i + 1, 4)}`,
  ...channel(),
}));
const generalVideos = [];
const nextGeneralVideo = () => {
  if (generalVideos.length === 0 || chance(RATIO.newGeneralVideo)) {
    const v = { title: `일반 영상 ${pad(generalVideos.length + 1, 6)}`, id: randomId(11), ch: pick(generalChannels) };
    generalVideos.push(v);
    return v;
  }
  return pick(generalVideos);
};

// ---------- 시각 (최근일수록 많이, 한국 시간 생활 패턴) ----------
const HOUR_WEIGHTS = [5, 3, 2, 1, 1, 1, 2, 5, 7, 5, 4, 4, 6, 5, 4, 4, 4, 5, 6, 7, 8, 9, 9, 8]; // 0~23시 (KST)
const hourCdf = (() => {
  const s = HOUR_WEIGHTS.reduce((a, b) => a + b, 0);
  let acc = 0;
  return HOUR_WEIGHTS.map((w) => (acc += w / s));
})();
// 기간을 연도 구간으로 나눠, 실제 하루 평균 시청 수에 비례해 구간을 고른다
const yearSegments = (() => {
  const segs = [];
  const firstYear = new Date(START + 9 * 3600e3).getUTCFullYear();
  const lastYear = new Date(END + 9 * 3600e3).getUTCFullYear();
  for (let y = firstYear; y <= lastYear; y++) {
    const from = Math.max(START, new Date(`${y}-01-01T00:00:00+09:00`).getTime());
    const to = Math.min(END, new Date(`${y + 1}-01-01T00:00:00+09:00`).getTime());
    if (to <= from) continue;
    const weight = ((to - from) / 86400e3) * (DAILY_RATE_BY_YEAR[y] ?? 100);
    segs.push({ from, to, weight });
  }
  const total = segs.reduce((a, s) => a + s.weight, 0);
  let acc = 0;
  return segs.map((s) => ({ ...s, cdf: (acc += s.weight / total) }));
})();

const randomTime = () => {
  const r0 = rand();
  const seg = yearSegments.find((s) => s.cdf >= r0) ?? yearSegments.at(-1);
  const day0 = seg.from + rand() * (seg.to - seg.from);
  const d = new Date(day0 + 9 * 3600e3); // KST 날짜로 바꿔서 시를 정한다
  const r = rand();
  const hour = hourCdf.findIndex((c) => c >= r);
  d.setUTCHours(hour, Math.floor(rand() * 60), Math.floor(rand() * 60), Math.floor(rand() * 1000));
  const t = Math.min(END, Math.max(START, d.getTime() - 9 * 3600e3));
  const iso = new Date(t).toISOString();
  return chance(0.001) ? iso.replace(/\.\d{3}Z$/, "Z") : iso; // 실제처럼 일부는 밀리초 없음
};

// ---------- 항목 만들기 ----------
const watchEntry = ({ header, title, url, ch }) => ({
  header,
  title: T.watched(title),
  titleUrl: url,
  ...(ch ? { subtitles: [{ name: ch.name, url: `https://www.youtube.com/channel/${ch.id}` }] } : {}),
  time: randomTime(),
  products: ["YouTube"],
  activityControls: [T.watchCtrl],
});

const entries = [];
for (let i = 0; i < WATCHES; i++) {
  const r = rand();
  if (r < RATIO.deleted) {
    // 삭제·비공개 영상: 제목 자리에 주소가 남고 채널이 없다
    const url = `https://www.youtube.com/watch?v=${randomId(11)}`;
    entries.push(watchEntry({ header: "YouTube", title: url, url }));
  } else if (r < RATIO.deleted + RATIO.musicApp) {
    // 뮤직 앱: music.youtube.com, Topic 채널 음원
    const s = songs[nextSong()];
    entries.push(watchEntry({
      header: "YouTube Music",
      title: s.title,
      url: `https://music.youtube.com/watch?v=${s.audioId}`,
      ch: { name: `${s.artist.name} - Topic`, id: s.artist.topic.id },
    }));
  } else if (r < RATIO.deleted + RATIO.musicApp + RATIO.topicInYoutubeApp) {
    // 일반 앱에서 듣는 Topic 음원
    const s = songs[nextSong()];
    entries.push(watchEntry({
      header: "YouTube",
      title: s.title,
      url: `https://www.youtube.com/watch?v=${s.audioId}`,
      ch: { name: `${s.artist.name} - Topic`, id: s.artist.topic.id },
    }));
  } else if (chance(RATIO.mvShareOfRest)) {
    // 일반 앱의 뮤직비디오·라이브·커버: 규칙 필터에 안 걸린다 (L2 YouTube API 판정 대상)
    const s = songs[nextSong()];
    const style = Math.floor(rand() * 4);
    const title = [
      `${s.artist.name} - ${s.title} (Official MV)`,
      `[MV] ${s.artist.name} _ ${s.title}`,
      `${s.title} (Live) - ${s.artist.name}`,
      `${s.artist.name} '${s.title}' 커버`,
    ][style];
    const ch = style === 3 ? pick(generalChannels) : { name: s.artist.name, id: s.artist.official.id };
    entries.push(watchEntry({ header: "YouTube", title, url: `https://www.youtube.com/watch?v=${s.mvId}`, ch }));
  } else {
    const v = nextGeneralVideo();
    entries.push(watchEntry({ header: "YouTube", title: v.title, url: `https://www.youtube.com/watch?v=${v.id}`, ch: v.ch }));
  }
}

if (KIND === "myactivity") {
  const searches = Math.round(WATCHES * RATIO.searchPerWatch);
  for (let i = 0; i < searches; i++) {
    const q = `가상검색어 ${pad(Math.floor(rand() * searches * 0.7) + 1, 5)}`;
    entries.push({
      header: "YouTube",
      title: T.searched(q),
      titleUrl: `https://www.youtube.com/results?search_query=${encodeURIComponent(q)}`,
      time: randomTime(),
      products: ["YouTube"],
      activityControls: [T.searchCtrl],
    });
  }
}

const others = Math.round(WATCHES * RATIO.otherPerWatch);
for (let i = 0; i < others; i++) {
  const r = rand();
  const make = r < 0.92 ? T.others[0] : r < 0.97 ? T.others[1] : T.others[2];
  const ch = pick(generalChannels);
  entries.push({
    header: "YouTube",
    title: make(`가상항목 ${pad(i + 1, 5)}`),
    titleUrl: `https://www.youtube.com/post/${randomId(26)}`,
    subtitles: [{ name: ch.name, url: `https://www.youtube.com/channel/${ch.id}` }],
    time: randomTime(),
    products: ["YouTube"],
    ...(chance(0.92) ? { activityControls: [T.watchCtrl] } : {}),
  });
}

// 실제 파일처럼 최신 → 과거 순
entries.sort((a, b) => (a.time < b.time ? 1 : a.time > b.time ? -1 : 0));

const json = JSON.stringify(entries, null, 2);
if (OUT) {
  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, json);
}
else process.stdout.write(json);

// 요약은 표준 오류로 (파일 내용과 섞이지 않게)
const count = (f) => entries.filter(f).length;
const watched = (e) => e.title.endsWith(T.watched("")) || e.title.startsWith("Watched ");
process.stderr.write(
  [
    `생성 완료: ${OUT ?? "(표준 출력)"} (${(json.length / 1024 / 1024).toFixed(1)}MB, 시드 ${SEED}, ${KIND}, ${LANG})`,
    `  전체 ${entries.length} = 시청 ${count(watched)} + 검색 ${count((e) => e.activityControls?.[0] === T.searchCtrl)} + 기타 ${count((e) => !watched(e) && e.activityControls?.[0] !== T.searchCtrl)}`,
    `  가상 곡 ${songCount}개, 가상 가수 ${artistCount}명, 기간 ${entries.at(-1)?.time.slice(0, 10)} ~ ${entries[0]?.time.slice(0, 10)}`,
  ].join("\n") + "\n",
);
