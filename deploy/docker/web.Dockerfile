# 웹: 빌드된 정적 파일(web/dist)을 nginx로 제공. /api는 Ingress가 api로 보낸다
FROM nginx:1.29-alpine
COPY deploy/docker/web-nginx.conf /etc/nginx/conf.d/default.conf
COPY web/dist /usr/share/nginx/html
