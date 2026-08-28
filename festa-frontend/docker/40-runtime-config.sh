#!/bin/sh
# nginx 이미지의 /docker-entrypoint.d/ 규약을 그대로 쓴다 — 기동 직전에 이 스크립트가 실행된다.
# PUBLIC_API_BASE_URL 을 받아 정적 산출물의 runtime-config.js 를 다시 쓴다.
# 빌드된 index.html 을 치환하지 않는 이유: 치환 실패가 조용히 남고 재실행마다 원본이 오염된다.
set -eu

TARGET=/usr/share/nginx/html/runtime-config.js

# 큰따옴표·역슬래시는 값에서 제거한다 — URL 에 올 일이 없고, 남으면 생성되는 JS 가 깨진다.
API_BASE_URL=$(printf '%s' "${PUBLIC_API_BASE_URL:-}" | tr -d '"\\')

cat > "$TARGET" <<CONFIG
window.__FESTA_CONFIG__ = { apiBaseUrl: "${API_BASE_URL}" };
CONFIG

if [ -z "$API_BASE_URL" ]; then
    echo "[runtime-config] PUBLIC_API_BASE_URL 미지정 — 앱이 빌드타임 VITE_API_BASE_URL 로 내려간다" >&2
else
    echo "[runtime-config] apiBaseUrl=$API_BASE_URL" >&2
fi
