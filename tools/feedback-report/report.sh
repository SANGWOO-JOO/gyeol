#!/usr/bin/env bash
# L3-5 추천 평가 집계: 이유 RAG 켬/끔 좋아요 비율, 점수 순위·후보 경로별 (docs/l3-design.md 12장)
# 사용: tools/feedback-report/report.sh   (로컬 PostgreSQL 컨테이너)
set -eu
PG=$(docker ps -qf name=postgres)
q() { docker exec "$PG" psql -U gyeol -d gyeol -P pager=off -c "$1"; }

echo "## 이유 비교군별 (RAG = 근거 자료와 함께, NORAG = 근거 없이)"
q "SELECT reason_variant AS 비교군, count(*) AS 평가,
          count(*) FILTER (WHERE rating = 'LIKE') AS 좋아요,
          round(100.0 * count(*) FILTER (WHERE rating = 'LIKE') / count(*), 1) AS 좋아요_비율,
          round(100.0 * count(*) FILTER (WHERE reason_wrong) / count(*), 1) AS 이유_틀림_비율
   FROM rec_feedback GROUP BY 1 ORDER BY 1"

echo "## LLM 재정렬 전 점수 순위별 (LLM이 끌어올린 곡 = 점수 21위 이하)"
q "SELECT CASE WHEN score_rank <= 10 THEN '01-10' WHEN score_rank <= 20 THEN '11-20' ELSE '21+' END AS 점수_순위, count(*) AS 평가,
          round(100.0 * count(*) FILTER (WHERE rating = 'LIKE') / count(*), 1) AS 좋아요_비율
   FROM rec_feedback GROUP BY 1 ORDER BY 1"

echo "## 후보 경로별"
q "SELECT coalesce(source, '-') AS 경로, count(*) AS 평가, round(100.0 * count(*) FILTER (WHERE rating = 'LIKE') / count(*), 1) AS 좋아요_비율
   FROM rec_feedback GROUP BY 1 ORDER BY 1"

echo "## 표본: 세션 $(docker exec "$PG" psql -U gyeol -d gyeol -Atc "SELECT count(DISTINCT session_id) FROM rec_feedback")개 (지운 세션은 빠짐)"
