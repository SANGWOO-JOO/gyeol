"""지식 인입 채움률 측정 (docs/l3-design.md 8장). DB의 식별된 곡 전부를 실제 API로 보강하고 출처·언어별 채움률을 센다.
  python3 tools/knowledge-eval/measure.py          # dev 서버(scripts/dev-up.sh)가 떠 있어야 한다. 무료 API만, 몇 분
측정용 세션을 하나 만들어 모든 곡을 보강 대상으로 넣고, enrich 큐에 직접 보낸다.
"""
import json, subprocess, time, urllib.request

def sql(q):
    return subprocess.run(["docker", "exec", "gyeol-local-postgres-1", "psql", "-U", "gyeol", "-d", "gyeol", "-tAF", "|", "-c", q],
                          capture_output=True, text=True, check=True).stdout.strip()

def send(body):
    url = "http://localhost:4566/000000000000/gyeol-local-enrich"
    subprocess.run(["docker", "exec", "gyeol-local-localstack-1", "awslocal", "sqs", "send-message", "--region", "ap-northeast-2",
                    "--queue-url", url, "--message-body", json.dumps(body)], capture_output=True, check=True)

token = json.load(urllib.request.urlopen(urllib.request.Request("http://localhost:18080/api/sessions", data=b'{"entry":"TAKEOUT"}',
                                                                headers={"Content-Type": "application/json"})))["token"]
sid = int(sql(f"select id from session order by id desc limit 1"))
tracks = [int(x) for x in sql("select id from track order by id").split()]
sql(f"insert into enrich_target (session_id, track_id) select {sid}, id from track on conflict do nothing")
t0 = time.time()
for i in range(0, len(tracks), 5):
    send({"type": "TRACKS", "sessionId": sid, "trackIds": tracks[i:i + 5]})
while True:
    done, total = map(int, sql(f"select count(*) filter (where status <> 'PENDING'), count(*) from enrich_target where session_id = {sid}").split("|"))
    print(f"\r{done}/{total} {time.time() - t0:.0f}초", end="", flush=True)
    if done == total: break
    time.sleep(5)
elapsed = time.time() - t0
print()

n = len(tracks)
def pct(q):
    k = int(sql(q)); return f"{k}/{n} ({k * 100 // n}%)"
has = lambda where: f"""select count(*) from track t join artist a on a.id = t.artist_id where exists (select 1 from knowledge_chunk c where {where})"""
rows = {
    "MusicBrainz 녹음(크레딧·태그)": has("c.entity_type='TRACK' and c.entity_mbid=t.recording_mbid and c.source='MUSICBRAINZ'"),
    "작곡·작사 크레딧": "select count(distinct track_id) from track_credit where role in ('COMPOSER','LYRICIST','WRITER')",
    "Last.fm 곡": has("c.entity_type='TRACK' and c.entity_mbid=t.recording_mbid and c.source='LASTFM'"),
    "Wikipedia 곡(작품)": has("c.entity_type='WORK' and c.entity_mbid = any(t.work_mbids) and c.source='WIKIPEDIA'"),
    "Wikipedia 음반": has("c.entity_type='RELEASE_GROUP' and c.entity_mbid=t.release_group_mbid and c.source='WIKIPEDIA'"),
    "Last.fm 음반": has("c.entity_type='RELEASE_GROUP' and c.entity_mbid=t.release_group_mbid and c.source='LASTFM'"),
    "Wikipedia 가수 (아무 언어)": has("c.entity_type='ARTIST' and c.entity_mbid=a.mbid and c.source='WIKIPEDIA'"),
    "Wikipedia 가수 한국어": has("c.entity_type='ARTIST' and c.entity_mbid=a.mbid and c.source='WIKIPEDIA' and c.lang='ko'"),
    "Wikipedia 가수 영어": has("c.entity_type='ARTIST' and c.entity_mbid=a.mbid and c.source='WIKIPEDIA' and c.lang='en'"),
    "Wikipedia 가수 일본어": has("c.entity_type='ARTIST' and c.entity_mbid=a.mbid and c.source='WIKIPEDIA' and c.lang='ja'"),
    "Last.fm 가수": has("c.entity_type='ARTIST' and c.entity_mbid=a.mbid and c.source='LASTFM'"),
    "곡·음반 단위 설명 근거 (위키 또는 Last.fm 위키 본문)": has("((c.entity_type='TRACK' and c.entity_mbid=t.recording_mbid) or (c.entity_type='WORK' and c.entity_mbid=any(t.work_mbids)) or (c.entity_type='RELEASE_GROUP' and c.entity_mbid=t.release_group_mbid)) and c.source in ('WIKIPEDIA','LASTFM') and length(c.text) > 80"),
}
print(f"곡 {n}개, {elapsed:.0f}초 (곡당 {elapsed / n:.1f}초), 청크 {sql('select count(*) from knowledge_chunk')}개")
for k, q in rows.items(): print(f"  {k}: {pct(q)}")
