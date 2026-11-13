"""음악 지도 판 만들기 (오프라인, room-plan.md 8절 ②).

DB의 가수 벡터(artist_vector.mixed = 원문 0.5 + 설명 0.5) → UMAP 2D → 구역(KMeans) → 새 map_version을 만들고 활성으로 바꾼다.
설명 없는 가수(descr NULL)는 UMAP에 넣지 않고, 원문 벡터끼리 가까운 5명의 좌표 평균·구역 다수결로 놓는다 (MapService.place와 같은 규칙).
섞은 벡터에 설명 성분이 없으면 다른 가수 전부와 멀어져 한 구역으로 떨어져 나간다 (11-13 지도 판 4).
전체 재계산은 가끔만 한다. 그 사이 새 가수는 worker가 이웃 평균 자리에 놓는다 (MapService.place).

실행 (로컬 DB):
  uv run --directory tools/map-build python build.py            # 판을 만들고 활성으로
  uv run --directory tools/map-build python build.py --dry-run  # 쓰지 않고 통계만
  uv run --directory tools/map-build python build.py --eval     # + Last.fm 비슷한 가수와 비교 (fetcher 경유, 가수당 1초)
결과 보고서: tools/results/map-build-<판>.txt (git 제외)
"""
import argparse
import json
import math
import os
import time
import urllib.parse
import urllib.request
from collections import Counter

import numpy as np
import psycopg

p = argparse.ArgumentParser()
p.add_argument('--db', default=os.environ.get('DB_URL', 'postgresql://gyeol:gyeol@localhost:15432/gyeol'))
p.add_argument('--neighbors', type=int, default=10)
p.add_argument('--min-dist', type=float, default=0.1)
p.add_argument('--seed', type=int, default=42)
p.add_argument('--clusters', type=int, default=0, help='0이면 sqrt(가수 수 / 2), 6~60')
p.add_argument('--dry-run', action='store_true')
p.add_argument('--eval', action='store_true')
p.add_argument('--fetcher', default=os.environ.get('FETCHER_URL', 'http://localhost:18081'))
args = p.parse_args()

with psycopg.connect(args.db) as conn:
    rows = conn.execute("""
        SELECT v.artist_id, a.name, p.country, p.seed_country, v.mixed::text, v.raw::text, v.descr IS NULL
        FROM artist_vector v JOIN artist a ON a.id = v.artist_id LEFT JOIN artist_profile p ON p.artist_id = v.artist_id
        ORDER BY v.artist_id
    """).fetchall()
n = len(rows)
if n < 20:
    raise SystemExit(f'가수 벡터가 {n}개뿐이라 지도를 만들 수 없습니다 (worker /internal/map/embed 먼저)')
ids = [r[0] for r in rows]
names = [r[1] for r in rows]
country = [r[2] or '?' for r in rows]


def mat(col):
    M = np.array([[float(x) for x in r[col].strip('[]').split(',')] for r in rows], dtype=np.float32)
    return M / np.linalg.norm(M, axis=1, keepdims=True)


V = mat(4)
R = mat(5)
nodesc = np.array([r[6] for r in rows])
fit = np.where(~nodesc)[0]
rest = np.where(nodesc)[0]

t0 = time.time()
import umap  # noqa: E402 (느린 import)
xy = np.zeros((n, 2), dtype=np.float64)
xy[fit] = umap.UMAP(n_neighbors=args.neighbors, min_dist=args.min_dist, metric='cosine', random_state=args.seed).fit_transform(V[fit])
lo, hi = xy[fit].min(axis=0), xy[fit].max(axis=0)
xy[fit] = 0.04 + 0.92 * (xy[fit] - lo) / np.where(hi - lo == 0, 1, hi - lo)

from sklearn.cluster import KMeans  # noqa: E402
k = args.clusters or max(6, min(60, round(math.sqrt(len(fit) / 2))))
labels = np.zeros(n, dtype=int)
labels[fit] = KMeans(n_clusters=k, n_init=10, random_state=args.seed).fit_predict(xy[fit])
# 설명 없는 가수: 원문 벡터끼리 가까운 5명(UMAP에 들어간 가수 중)의 좌표 평균, 구역 다수결
for i in rest:
    near = fit[np.argsort(-(R[fit] @ R[i]))[:5]]
    xy[i] = xy[near].mean(axis=0)
    labels[i] = Counter(labels[near].tolist()).most_common(1)[0][0]
centers = [(float(xy[labels == c, 0].mean()), float(xy[labels == c, 1].mean())) for c in range(k)]
print(f'가수 {n}명(UMAP {len(fit)}, 이웃 평균 {len(rest)}), 구역 {k}개, UMAP·KMeans {time.time() - t0:.1f}초')


def neighbors(P, i, m):
    d = ((P - P[i]) ** 2).sum(axis=1) if P.shape[1] == 2 else -(P @ P[i])
    d[i] = np.inf
    return list(np.argsort(d)[:m])


report = [f'가수 {n}명(설명 없음 {len(rest)}명은 원문 이웃 평균), 구역 {k}개, n_neighbors {args.neighbors}, min_dist {args.min_dist}']
same5 = np.mean([np.mean([country[j] == country[i] for j in neighbors(xy, i, 5)]) for i in range(n)])
report.append(f'2D 이웃 5명 같은 나라: {same5:.0%}')
sizes = Counter(labels.tolist())
report.append(f'구역 크기: 최소 {min(sizes.values())}, 중앙 {int(np.median(list(sizes.values())))}, 최대 {max(sizes.values())}')

if args.eval:
    # Last.fm 비슷한 가수 중 이 목록 안에 있는 쌍이 지도에서 서로 이웃 10명 안에 드는 비율 (11-11과 같은 기준)
    idx = {nm.lower(): i for i, nm in enumerate(names)}
    pairs = set()
    for i, nm in enumerate(names):
        q = urllib.parse.urlencode({'artist': nm, 'limit': 30})
        try:
            items = json.load(urllib.request.urlopen(f'{args.fetcher}/internal/lastfm/similar-artists?{q}', timeout=60))['items']
        except Exception as e:  # 외부 장애는 그 가수만 건너뛴다
            print('similar 실패', nm, e)
            continue
        for it in items:
            j = idx.get(it['artist'].lower())
            if j is not None and j != i:
                pairs.add((i, j))
        if (i + 1) % 50 == 0:
            print(f'Last.fm 비슷한 가수 {i + 1}/{n}')
    hit2 = np.mean([j in neighbors(xy, i, 10) for i, j in pairs]) if pairs else float('nan')
    # 384차원: 둘 다 설명이 있으면 섞은 벡터, 아니면 원문 벡터끼리
    hit384 = np.mean([j in neighbors(V if not (nodesc[i] or nodesc[j]) else R, i, 10) for i, j in pairs]) if pairs else float('nan')
    report.append(f'Last.fm 쌍 {len(pairs)}개 중 이웃 10명 안: 384차원 {hit384:.0%} / 2D {hit2:.0%} (무작위 {10 / (n - 1):.0%})')

print('\n'.join(report))
if args.dry_run:
    raise SystemExit(0)

params = {'neighbors': args.neighbors, 'minDist': args.min_dist, 'seed': args.seed, 'clusters': k, 'metric': 'cosine', 'mix': 'raw0.5+desc0.5'}
with psycopg.connect(args.db) as conn, conn.cursor() as cur:
    cur.execute('INSERT INTO map_version (params, artist_count) VALUES (%s, %s) RETURNING id', (json.dumps(params), n))
    version = cur.fetchone()[0]
    gid = {}
    for c, (cx, cy) in enumerate(centers):
        cur.execute('INSERT INTO galaxy (map_version, label, cx, cy) VALUES (%s, %s, %s, %s) RETURNING id', (version, c, cx, cy))
        gid[c] = cur.fetchone()[0]
    cur.executemany(
        "INSERT INTO artist_position (map_version, artist_id, x, y, galaxy_id, placed_by) VALUES (%s, %s, %s, %s, %s, %s)",
        [(version, ids[i], float(xy[i, 0]), float(xy[i, 1]), gid[int(labels[i])], 'NEIGHBOR' if nodesc[i] else 'UMAP') for i in range(n)],
    )
    cur.execute('UPDATE map_version SET active = false WHERE active')
    cur.execute('UPDATE map_version SET active = true WHERE id = %s', (version,))
    # 모든 방이 새 좌표로 다시 그리게
    cur.execute('UPDATE room SET map_rev = map_rev + 1')
print(f'지도 판 {version} 활성. 다음: worker POST /internal/map/name-galaxies (구역 이름)')
os.makedirs('../results', exist_ok=True)
with open(f'../results/map-build-{version}.txt', 'w', encoding='utf-8') as f:
    f.write('\n'.join(report) + '\n')
