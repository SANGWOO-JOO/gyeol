#!/bin/bash
# k3d 클러스터와 k3d 전용 LocalStack을 지운다(클러스터 안 DB도 함께 사라짐). 개발 환경 compose는 그대로 둔다
k3d cluster delete gyeol
docker rm -f gyeol-k3d-localstack >/dev/null 2>&1 || true
