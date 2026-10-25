# 앨범 표지 측정 도구

곡(제목 + 가수)마다 **원곡이 처음 공식 발매된 음반**을 MusicBrainz에서 찾고, 그 음반 표지를 Cover Art Archive에서 가져와 정답과 비교한다. 규칙과 측정 결과는 `docs/l1-design.md` 4-3장.

```bash
( set -a; . ./.env; set +a; node tools/cover-art/measure.mjs --out tools/results/cover-art )
```

- 37곡 기준 약 4분(MusicBrainz 초당 1회 제한). 무료 API만 쓴다.
- 표지 이미지(`번호.jpg`)와 `result.json`은 `--out` 폴더에 저장되고, 이 폴더는 git에서 제외한다. 자동 판정은 음반 이름만 비교하므로, 표지가 맞는지는 이미지를 직접 열어 확인한다.
- 표본을 늘리려면 `samples.json`에 추가한다. 정답을 확신하지 못하면 `"sure": false`로 둔다.
- `LASTFM_API_KEY`가 없으면 Last.fm 표기 단계만 건너뛴다.
