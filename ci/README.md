# Component CI Adapter

`ci/validate`, `ci/test`, `ci/build`, `ci/package`, `ci/verify`는 `ai`, `back`, `front`, `game` 브랜치가 공유하는 SCM 중립 계약이다. `CI_COMPONENT`에 따라 다음 기본 도구를 감지한다.

- AI: `pyproject.toml` 또는 `requirements.txt`, pytest, Python compile, Dockerfile
- Back: Gradle wrapper 또는 Maven wrapper, Dockerfile
- Front: `package.json`, npm test/build, Dockerfile
- Game: `festa-unity/ci/`의 Unity 전용 adapter

파트가 기본 명령과 다르면 `<component>/ci/<stage>`를 제공해 해당 단계만 명시적으로 대체할 수 있다. 파일이나 명령이 없으면 성공으로 간주하지 않고 non-zero로 실패한다. 모든 단계는 `CI_ARTIFACT_DIR/stage-summary.json`을 생성하며 실제 Secret 값을 출력하지 않는다.
