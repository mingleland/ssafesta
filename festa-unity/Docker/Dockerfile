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

# 향후 ECS Task 환경변수와 연동되는 지점:
#   INSTANCE_ID / WORLD_ID / CHANNEL_ID / MAX_PLAYERS (doc 15 §8)
# 현재는 CLI 인자만 사용 (NetworkBootstrap이 -port / -maxPlayers 파싱)
ENTRYPOINT ["/app/festa-unity.x86_64", "-batchmode", "-nographics"]
CMD ["-port", "7777", "-maxPlayers", "40"]
