# SSAFY FESTA — Unity Dedicated Server
# 빌드 컨텍스트는 Linux Server 빌드 출력 폴더 (Builds/linux-server)
#
# 빌드 (festa-unity 폴더에서):
#   docker build -t festa-world:dev -f Docker/Dockerfile Builds/linux-server
# 실행:
#   docker run -d --name festa-world-01 -p 7777:7777 festa-world:dev
# 로그:
#   docker logs -f festa-world-01

FROM ubuntu:22.04

# Unity Linux 서버 런타임 최소 의존성
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libatomic1 \
    && rm -rf /var/lib/apt/lists/*

# 비루트 실행 (보안 기본기)
RUN useradd -m -d /app unity \
    && install -d -o unity -g unity /var/lib/festa-world
WORKDIR /app

COPY --chown=unity:unity . /app

# 실행 파일 이름이 다르면 여기와 ENTRYPOINT를 함께 수정
RUN chmod +x /app/festa-unity.x86_64

USER unity

# NGO WebSocket 포트 (ALB → 컨테이너)
EXPOSE 7777

# 정원은 **CLI 인자 > WORLD_MAX_PLAYERS 환경변수 > 빌드 기본값** 순으로 정해진다
# (NetworkBootstrap.ResolveMaxPlayers). 인프라 compose 는 예전부터 WORLD_MAX_PLAYERS 를 컨테이너에
# 넣고 있었지만 예전 코드가 CLI 인자만 읽어서 그 값이 아무 데도 쓰이지 않았다 — 이제 읽는다.
# 아래 CMD 가 인자를 주므로 지금은 이 값이 이긴다. 인프라가 WORLD_MAX_PLAYERS 를 맞춘 뒤에는
# 이 인자를 빼서 env 하나로 정하게 할 수 있다 (S15P21A604-935).
#   INSTANCE_ID / WORLD_ID / CHANNEL_ID (doc 15 §8) 는 아직 연동 전이다.
ENTRYPOINT ["/app/festa-unity.x86_64", "-batchmode", "-nographics"]
CMD ["-port", "7777", "-maxPlayers", "100"]
