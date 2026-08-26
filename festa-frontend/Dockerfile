# FE 이미지 (S15P21A604-254). Infra 의 integration compose 가 FRONT_IMAGE_REF 로 참조하는 산출물.
# 설계 정본: docs/LJH/27_FE_Docker_설계.md
# 빌드 컨텍스트는 festa-frontend/ 다 — 저장소 루트가 아니다.

FROM node:24-alpine AS base
WORKDIR /app
# lockfile 만 먼저 복사해 의존성 계층을 소스 변경과 분리한다 — 소스만 바뀌면 npm ci 를 다시 돌리지 않는다.
COPY package.json package-lock.json ./
RUN npm ci

# ── dev: vite dev server. 소스는 bind mount 로 넣고 node_modules 는 컨테이너 것을 쓴다 ──
#   docker run -v "$PWD:/app" -v festa-fe-node-modules:/app/node_modules -p 5173:5173 <image>
# 두 번째 -v 가 핵심이다 — 없으면 호스트의 /app 마운트가 이미지 안 node_modules 를 가려
# rolldown 네이티브 바인딩을 못 찾고 죽는다. **이름 있는 볼륨을 쓴다** — 익명 볼륨
# (-v /app/node_modules)으로는 이 환경(Windows·Docker 29.6.1)에서 같은 증상이 났다
# (실측 2026-08-26). 원인은 확인하지 않았고, 이름 있는 볼륨에서는 재현되지 않는다.
FROM base AS dev
ENV NODE_ENV=development
# bind mount 는 inotify 를 컨테이너로 전달하지 않는다 — polling 을 켜야 HMR 이 산다(vite.config.ts).
ENV VITE_USE_POLLING=true
COPY . .
EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]

# ── build: 정적 산출물 생성 ──
FROM base AS build
COPY . .
RUN npm run build

# ── prod: nginx 정적 서빙 ──
# 80 포트로 서빙한다 — infra/deploy/compose/dev/front.compose.yaml 의 healthcheck 가
# wget -qO- http://127.0.0.1/ 로 확인한다.
FROM nginx:alpine AS prod
COPY --from=build /app/dist /usr/share/nginx/html
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
# nginx 이미지가 기동 직전 /docker-entrypoint.d/*.sh 를 실행한다 — entrypoint 를 재정의하지 않는다.
COPY docker/40-runtime-config.sh /docker-entrypoint.d/40-runtime-config.sh
RUN chmod +x /docker-entrypoint.d/40-runtime-config.sh
EXPOSE 80
