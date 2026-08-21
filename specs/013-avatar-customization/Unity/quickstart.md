# Quickstart: 캐릭터 커스터마이징

**Spec**: 013 | **Date**: 2026-08-12

---

## 1. 새 옷 하나 추가하기 (예: SSAFY 후드티)

**코드는 한 줄도 고치지 않는다.** 아래 3단계면 캐릭터 생성 화면에 나타난다.

### 1단계 — 시각 자산 준비

후드티 메시를 프로젝트에 넣고, 프로젝트용 프리팹으로 만든다.

```text
festa-unity/Assets/_Project/Prefabs/Avatar/Tops/SSAFY_Hoodie.prefab
```

⚠️ 벤더 에셋 폴더 안에 만들지 않는다. 에셋 업데이트 시 사라진다.

### 2단계 — 항목 정의 에셋 생성

`Create > FESTA > Avatar > Item Definition` 으로 SO를 만든다.

```text
festa-unity/Assets/_Project/ScriptableObjects/Avatar/Tops/Item_Top_SSAFYHoodie.asset
```

채울 값:

| 필드 | 값 |
|---|---|
| `itemId` | **아직 쓰이지 않은 새 번호.** 기존 번호 재사용 금지 |
| `displayName` | `SSAFY 후드티` |
| `category` | `Top` |
| `gender` | `Both` (남녀 공용이면) |
| `visual` | 1단계에서 만든 프리팹 |
| `thumbnail` | 캡처 도구로 생성 (아래 §2) |
| `availableColors` | 지원할 색상 ID 목록 |
| `hiddenBodyParts` | **후드티가 가리는 신체 부위** — 안 넣으면 몸이 옷 밖으로 튀어나온다 |
| `isDefault` | 체크 안 함 |

### 3단계 — Catalog에 등록

```text
festa-unity/Assets/_Project/ScriptableObjects/Avatar/AvatarCatalog.asset
```

`items` 목록에 2단계 에셋을 드래그해 추가한다. **끝이다.**

### 확인

1. `CharacterLobby.unity` 실행
2. 상의 카테고리에 후드티가 보이는지
3. 선택 시 프리뷰에 즉시 반영되는지
4. 몸이 옷 밖으로 튀어나오지 않는지 (`hiddenBodyParts` 확인)
5. 월드 입장 후 다른 클라이언트에도 보이는지

⚠️ **Web + Linux Server 빌드를 둘 다** 다시 만들어야 다른 사람에게도 보인다.

---

## 2. 썸네일 만들기

에디터 캡처 도구를 사용한다 (수작업 금지).

```text
Tools > FESTA > Avatar > Capture Thumbnails
```

Catalog의 모든 항목을 순회하며 썸네일을 생성한다. 새 항목만 다시 뽑을 수도 있다.

---

## 3. 개발 중 자주 쓰는 것

### 작업할 spec 지정

```bash
echo '{ "feature_directory": "specs/013-avatar-customization" }' > .specify/feature.json
bash .specify/scripts/bash/check-prerequisites.sh --json --paths-only
```

### 빌드 용량 확인

```bash
du -sh festa-unity/Builds/web
```

목표: **현재(약 87MB)를 넘지 않을 것** (research.md R-02)

### 서버 갱신 (외형 데이터 구조를 바꿨을 때)

```powershell
cd C:\Users\SSAFY\Desktop\SSAFESTA\festa-unity
# Unity 에서 Linux Server 빌드 후
docker ps                                    # 실제 컨테이너 이름 확인
docker rm -f festa-world-01
docker build -t festa-world:dev -f Docker/Dockerfile Builds/linux-server
docker run -d --name festa-world-01 -p 7777:7777 festa-world:dev
docker logs -f festa-world-01
```

⚠️ 빌드 컨텍스트는 `Builds/linux-server`다. `Docker/` 폴더가 아니다 (T-26).

### 로컬 웹 서버

```powershell
python festa-unity/Tools/serve.py
```

`python -m http.server`는 wasm MIME과 캐시 문제가 있다 (T-13, T-23).

---

## 4. 막혔을 때

| 증상 | 먼저 볼 것 |
|---|---|
| 캐릭터가 T포즈로 굳음 | Animator의 Controller·Avatar가 채워졌는지 (T-27) |
| 항목을 바꿔도 아무 반응 없음 | 서버 빌드가 최신인지. `docker logs`에 반영 로그가 찍히는지 (T-24, T-25) |
| 다른 사람에게 안 보임 | Web/Server 빌드를 둘 다 갱신했는지 |
| 몸이 옷 밖으로 나옴 | `hiddenBodyParts` 설정 |
| 색을 바꿨더니 남의 캐릭터도 바뀜 | 공유 머티리얼을 직접 수정하고 있다 (research R-05) |
| 인스펙터 값이 사라짐 | 직렬화 필드 이름을 바꿨다면 `[FormerlySerializedAs]` (T-21) |
| 빌드가 갑자기 커짐 | 구 아바타 에셋이 아직 남아 있는지 |

전체 목록: `docs/25_트러블슈팅.md`
