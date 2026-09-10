#!/bin/sh
# nginx 이미지의 /docker-entrypoint.d/ 규약을 그대로 쓴다 — 기동 직전에 이 스크립트가 실행된다.
# PUBLIC_* 환경변수를 받아 정적 산출물의 runtime-config.js 를 다시 쓴다.
#
# 키 목록은 src/shared/config/runtime.ts 의 RuntimeAssetConfig 와 1:1 이다 — 한쪽만 늘리지 않는다.
# 그 1:1 은 이제 주석이 아니라 테스트가 지킨다(tools/runtimeConfigKeys.test.mjs, S15P21A604-567).
# 예전에는 이 주석만 있었고 실제로는 5키 중 2키만 써서 authBaseUrl·aiApiBaseUrl·boothAssetBase 를
# 배포에서 주입할 방법이 없었다.
#
# **값이 빈 키는 아예 넣지 않는다.** 빈 문자열을 넣으면 accessor 의 "미설정" 판정이 흐려진다 —
# runtime.ts 의 판정은 빈 값을 빌드타임 값으로 내리는 신호로 쓰므로 결과는 같지만, 브라우저에서
# window.__FESTA_CONFIG__ 를 열어 봤을 때 "주입했는데 비었다" 와 "주입하지 않았다" 가 구분되지 않는다.
#
# 빌드된 index.html 을 치환하지 않는 이유: 치환 실패가 조용히 남고 재실행마다 원본이 오염된다.
set -eu

TARGET=/usr/share/nginx/html/runtime-config.js

ENTRIES=''

# append <RuntimeAssetConfig 키> <값> <미지정일 때 남길 안내>
append() {
    key=$1
    # 큰따옴표·역슬래시는 값에서 제거한다 — URL 에 올 일이 없고, 남으면 생성되는 JS 가 깨진다.
    value=$(printf '%s' "$2" | tr -d '"\\')
    if [ -z "$value" ]; then
        echo "[runtime-config] $key 미지정 — $3" >&2
        return 0
    fi
    if [ -n "$ENTRIES" ]; then
        ENTRIES="$ENTRIES, "
    fi
    ENTRIES="$ENTRIES$key: \"$value\""
    echo "[runtime-config] $key=$value" >&2
    return 0
}

append apiBaseUrl "${PUBLIC_API_BASE_URL:-}" \
    "앱이 빌드타임 VITE_API_BASE_URL 로 내려간다. 그마저 없으면 FE 오리진을 쓴다(same-origin gateway)"
append authBaseUrl "${PUBLIC_AUTH_BASE_URL:-}" \
    "인증 계열이 apiBaseUrl 과 같은 base 를 쓴다. OAuth 호스트가 API 호스트와 다르면 지정해야 한다"
append aiApiBaseUrl "${PUBLIC_AI_API_BASE_URL:-}" \
    "앱이 빌드타임 VITE_AI_API_BASE_URL 로 내려간다. 그마저 없으면 FE 오리진의 /ai/v1 을 부른다"
append unityBuildBase "${PUBLIC_UNITY_BUILD_BASE:-}" \
    "앱이 빌드타임 VITE_UNITY_BUILD_BASE 로 내려간다 (둘 다 비어 있으면 월드 로드 실패)"
append boothAssetBase "${PUBLIC_BOOTH_ASSET_BASE:-}" \
    "앱이 자기 BASE_URL 아래 assets/booth-runtime/ 을 쓴다"

cat > "$TARGET" <<CONFIG
window.__FESTA_CONFIG__ = { ${ENTRIES} };
CONFIG
