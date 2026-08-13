# ⚠️ 이 문서는 폐기되었습니다 (2026-08-12)

이 문서의 내용은 **더 이상 유효하지 않습니다.** 아래를 대신 보세요.

| 찾는 것 | 위치 |
|---|---|
| 아바타 요구사항 | `specs/013-avatar-customization/spec.md` |
| 데이터 구조 | `specs/013-avatar-customization/data-model.md` |
| **네트워크 동기화 계약** | `specs/013-avatar-customization/contracts/network-avatar-config.md` |
| **Spring 저장 API 제안** | `specs/013-avatar-customization/contracts/avatar-profile-api.md` |
| **Unity ↔ React 브릿지 계약** | `specs/013-avatar-customization/contracts/avatar-bridge.md` |
| 구현 지시 | `docs/29_아바타_커스터마이징_작업지시.md` |
| 새 옷 추가 방법 | `specs/013-avatar-customization/quickstart.md` |

---

## 무엇이 왜 바뀌었나

이 문서는 **프리셋 방식**(미리 구운 캐릭터 몇 종 중 택일)을 전제로 작성되었습니다.
이후 두 번의 방식 변경을 거쳐 현재는 **모듈러 프리팹 조립 방식**을 사용합니다.

| 항목 | 폐기된 내용 | 현재 |
|---|---|---|
| 외형 표현 | `avatarCode` 문자열 (`sk_01\|c=E85D5D`) | **ID 집합** (카테고리별 itemId + 색상 ID) |
| **최대 길이** | **29~32자** | **길이 개념 없음** — 네트워크는 고정 크기 struct |
| 네트워크 | 문자열 동기화 | `INetworkSerializable` 구조체 |
| 저장 컬럼 | `VARCHAR(32)` | **`TEXT`** |
| 파츠 구성 | 프리셋 통째 교체 | 카테고리별 개별 교체 + 조합 규칙 |

## ⚠️ Backend 담당자에게

**`VARCHAR(32)`로 아바타 컬럼을 만들면 안 됩니다.**

이 문서가 남아 있는 동안 "avatarCode는 최대 32자"라는 정보가 돌아다녔고,
실제로는 파츠 조립 시점에 600자를 넘겨 **저장이 조용히 잘리는 사고**가 있었습니다
(`docs/25_트러블슈팅.md` T-24).

현재 확정값은 **`TEXT`(또는 넉넉한 가변 문자열)** 입니다 — 헌법 23조.

## 왜 지우지 않고 남겼나

이 문서를 참조한 대화·이슈·커밋이 이미 존재합니다.
파일이 사라지면 "그 문서가 뭐였지"가 되고, 남아 있으면 잘못된 수치를 그대로 믿게 됩니다.
그래서 **내용을 비우고 이동 안내만** 남깁니다.

원본 내용이 필요하면 Git 히스토리에서 이 파일의 2026-08-12 이전 버전을 보세요.
