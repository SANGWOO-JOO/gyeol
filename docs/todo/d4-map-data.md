# D4 음악 지도 데이터 옮기기 절차 (2026-11-17, 절차만 · 실행 안 함)

`deploy-decisions.md` D4: 로컬에서 만든 지도(가수 약 1,500명)를 운영 DB로 옮긴다. 새로 만들면 Bedrock 약 2.5달러 + MusicBrainz 약 2시간이다.
11-16 k3d 확인에서 빈 DB는 지도가 placeholder(은하 0)로 나왔다. 그래서 첫 배포 직후, 사용자를 받기 전에 한다.

## 0. 원칙
- **옮기는 것:** 공용 음악 데이터(가수·곡·지도·설명·검색 색인)와 그 이미지(S3 `artists/`·`covers/`)
- **옮기지 않는 것**
  - 사용자 데이터: 방·참가자·담은 곡·세션·청취 기록·업로드·선물·감사 기록
  - YouTube API 파생: `video_check`, `channel_image`·S3 `channels/`, 판정 경로 별칭(`title_alias.category_checked_at IS NOT NULL`)
  - 운영 기록·임시 표: `llm_call`, `mail_send_log`, `artist_prepare`, `search_artist_prep*`, `backup_*`
- 운영 DB가 **빈 상태**(첫 배포로 마이그레이션만 적용)일 때 넣는다. 그래야 id가 그대로 맞는다. 사용자가 방을 만든 뒤에는 하지 않는다.
- 로컬과 운영의 마이그레이션 버전이 같아야 한다. `flyway_schema_history`의 최대 버전을 양쪽에서 비교한다.

## 1. 옮길 표 (11-17 로컬 기준, 합계 약 25MB)
| 묶음 | 표 | 비고 |
|---|---|---|
| 가수 | `artist`, `artist_profile`(설명), `artist_vector`(11MB), `artist_recording`, `artist_release_scan`, `artist_top_track`, `artist_image`(COMMONS 929·NONE 561), `artist_image_rule` | `artist_image`는 S3 `artists/` 키를 가리킨다 |
| 지도 | `map_version`(active 1), `galaxy`, `artist_position` | |
| 곡 | `track`, `track_credit`, `track_descriptor`, `track_vector`, `track_search_term` | `track.plays_total`은 로컬 청취 누적이다. 옮긴 뒤 0으로 지운다(4절) |
| 식별 캐시 | `title_alias` (판정 경로 32행 제외, 599행) | Topic·방 담기 키라 YouTube 파생이 아님 |
| 지식 | `knowledge_chunk`, `knowledge_fetch` | 새 가수 설명·enrich가 다시 부르지 않게 |

- 외래 키는 `artist ← track ← (track_* · title_alias · artist_top_track)`, `map_version ← galaxy ← artist_position`이다. `--disable-triggers`로 넣으면 순서를 신경 쓰지 않아도 된다. 운영 DB 사용자 `gyeol`은 컨테이너 초기 사용자라 superuser다.

## 2. 로컬에서 덤프 만들기 (맥북)
```bash
cd ~/Desktop/github/gyeol
T="-t artist -t artist_profile -t artist_vector -t artist_recording -t artist_release_scan -t artist_top_track -t artist_image -t artist_image_rule -t map_version -t galaxy -t artist_position -t track -t track_credit -t track_descriptor -t track_vector -t track_search_term -t knowledge_chunk -t knowledge_fetch"
docker exec gyeol-local-postgres-1 pg_dump -U gyeol -d gyeol --data-only -Fc $T -f /tmp/map.dump
docker cp gyeol-local-postgres-1:/tmp/map.dump .run/map.dump
```
- `title_alias`는 판정 경로 행을 빼야 해서 따로 CSV로 만든다.
```bash
docker exec gyeol-local-postgres-1 psql -U gyeol -d gyeol -c "\copy (select * from title_alias where category_checked_at is null) to '/tmp/title_alias.csv' csv header"
docker cp gyeol-local-postgres-1:/tmp/title_alias.csv .run/title_alias.csv
```
- 덤프는 `.run/`(git 제외)에 둔다. 저장소에 넣지 않는다.

## 3. S3로 올리기 (덤프·이미지)
- 덤프: 백업 버킷의 별도 접두어에 올린다(14일 수명 규칙으로 자동 삭제).
```bash
aws s3 cp .run/map.dump s3://<backup_bucket>/d4/map.dump
aws s3 cp .run/title_alias.csv s3://<backup_bucket>/d4/title_alias.csv
```
- 이미지: LocalStack에서 받아 운영 covers 버킷으로 올린다. `channels/`·`characters/`는 뺀다.
```bash
mkdir -p .run/covers-export
for p in artists covers; do docker exec gyeol-local-localstack-1 awslocal s3 sync s3://gyeol-local-covers/$p /tmp/export/$p --region ap-northeast-2; done
docker cp gyeol-local-localstack-1:/tmp/export/. .run/covers-export/
aws s3 sync .run/covers-export/ s3://<cover_bucket>/
```
- 양(11-17): `artists/` 929개 53MB, `covers/` 492개 10MB. PUT 약 1,400건이라 요청 비용은 1센트 안쪽이다(추정).
- `<backup_bucket>`·`<cover_bucket>`은 base 출력값이다(`terraform -chdir=infra/terraform/base output`).

## 4. 서버에서 넣기 (SSM 세션)
```bash
aws ssm start-session --target <instance_id>
sudo -i
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
aws s3 cp s3://<backup_bucket>/d4/map.dump /tmp/map.dump
aws s3 cp s3://<backup_bucket>/d4/title_alias.csv /tmp/title_alias.csv
kubectl -n gyeol cp /tmp/map.dump gyeol-postgres-0:/tmp/map.dump
kubectl -n gyeol cp /tmp/title_alias.csv gyeol-postgres-0:/tmp/title_alias.csv
# 1) 버전 확인: 운영이 로컬과 같은 최대 버전인지, 옮길 표가 비었는지
kubectl -n gyeol exec gyeol-postgres-0 -- psql -U gyeol -d gyeol -tAc "select max(version::int) from flyway_schema_history where success; select count(*) from artist; select count(*) from room"
# 2) 넣기 (한 트랜잭션)
kubectl -n gyeol exec gyeol-postgres-0 -- pg_restore -U gyeol -d gyeol --data-only --disable-triggers --single-transaction /tmp/map.dump
kubectl -n gyeol exec gyeol-postgres-0 -- psql -U gyeol -d gyeol -c "\copy title_alias from '/tmp/title_alias.csv' csv header"
# 3) 시퀀스를 최대 id 뒤로, 로컬 청취 누적 지우기
kubectl -n gyeol exec gyeol-postgres-0 -- psql -U gyeol -d gyeol -c "
DO \$\$ DECLARE r record; BEGIN
  FOR r IN SELECT c.relname t, a.attname col, pg_get_serial_sequence(c.relname, a.attname) s
           FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid
           WHERE c.relkind='r' AND c.relnamespace='public'::regnamespace AND pg_get_serial_sequence(c.relname, a.attname) IS NOT NULL LOOP
    EXECUTE format('SELECT setval(%L, greatest(coalesce((SELECT max(%I) FROM %I),0),1))', r.s, r.col, r.t);
  END LOOP; END \$\$;
UPDATE track SET plays_total = 0;"
rm -f /tmp/map.dump /tmp/title_alias.csv
kubectl -n gyeol exec gyeol-postgres-0 -- rm -f /tmp/map.dump /tmp/title_alias.csv
```
- 시퀀스를 맞추지 않으면 다음 새 가수·곡 저장에서 id가 겹쳐 실패한다. `-t`로 고른 덤프에는 시퀀스 값이 들어가지 않는다.

## 5. 확인
- `select count(*) from artist_position where map_version=(select id from map_version where active)`가 로컬과 같은지 본다.
- `https://gyeol.blog`에서 방을 하나 만들고, 지도에 은하가 보이는지 본다. 가수 사진(S3 `artists/`)이 보이는지도 본다.
- 방 검색에서 로컬에서 표지가 있던 가수(예: 아이유)를 찾아 표지가 바로 나오는지 본다.
- 확인 뒤 지운다: `aws s3 rm s3://<backup_bucket>/d4/ --recursive`, 맥북 `.run/map.dump`·`.run/title_alias.csv`·`.run/covers-export/`

## 5-1. 로컬 시험 (11-17)
- 개발 DB 스키마만 뜬 빈 임시 PostgreSQL(pgvector:pg17)에 2·4절 명령을 그대로 실행했다.
- 덤프 8.7MB, `pg_restore` 1.5초, `title_alias` 599행.
- 결과: 활성 지도 자리 1,480 = 로컬과 같음, 가수 1,506, 시퀀스가 최대 id 뒤, `plays_total` 0(515곡), 방 0.
- 시험 컨테이너와 덤프는 지웠다. 운영(서버) 쪽과 S3 복사는 아직 해 보지 않았다.

## 6. 위험과 되돌리기
- 넣다가 실패하면 `--single-transaction`이라 표가 빈 채로 남는다. 원인을 고친 뒤 다시 한다.
- 넣은 뒤 이상하면 위 표들을 `TRUNCATE … CASCADE`하고 다시 넣는다. 사용자를 받기 전이라 방 데이터는 없다.
- 로컬 지도를 다시 만들었으면(`map_version`이 바뀜) 덤프도 다시 만든다.
- 사용자 방(리뷰 방·시공삶·여찾사)은 옮기지 않는다. 옮기려면 방·참가자·담은 곡·열쇠·이메일까지 개인정보가 함께 가므로 따로 정한다.
