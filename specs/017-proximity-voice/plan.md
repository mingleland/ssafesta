# Implementation Plan: 거리 기반 음성채팅

**Spec**: `specs/017-proximity-voice/spec.md`
**Branch**: `game` (Unity 몫) / `front` (음성 본체)
**Date**: 2026-08-12
**Status**: 2차 MVP — 착수 전 C-01(SFU 방식) 결정 필요

---

## Summary

**Unity가 하는 일은 위치를 넘기는 것뿐이다.** 음성 캡처·전송·믹싱은 전부 브라우저(React)와 SFU가 담당한다.
Unity Web은 마이크를 쓸 수 없기 때문이다 (spec §기술 제약).

따라서 이 plan은 Unity 파트 기준으로는 **작은 작업**이고, 실제 무게는 FE/Infra에 있다.
Unity 리드로서 할 일은 ① 위치 전달 경로 제공 ② 층 구분 정보 제공 ③ 말하는 사람 표시다.

## Technical Context

| 항목 | 값 |
|---|---|
| 음성 경로 | WebRTC + SFU (LiveKit / mediasoup 등) — **게임 서버 미경유** |
| 캡처 | 브라우저 `getUserMedia` |
| 볼륨 제어 | Web Audio API `GainNode` (참가자별) |
| Unity 역할 | 위치·층 정보 제공, 말하기 표시 수신 |
| 브릿지 | jslib 콜백 (006/013과 공용 `festa-bridge.jslib`) |
| Unity 제약 | **`Microphone` 클래스 WebGL 미지원** — 우회 불가, 구조로 해결 |

## Constitution Check

| 조항 | 준수 방법 |
|---|---|
| 2조 실시간/영구 분리 | 음성은 게임 서버를 거치지 않는다. NGO 대역폭 잠식 없음 |
| 3조 장애 격리 (정신 준용) | 음성/SFU 장애가 월드를 중단시키지 않는다 — 완전 분리된 경로 |
| 17조 미디어는 웹 레이어 | 캡처·재생·믹싱 전부 React. Unity는 좌표만 |
| 21조 계약 변경 절차 | 위치 전달 payload는 FE와 합의 |

**위반 없음.** 오히려 이 구조가 헌법 취지에 가장 부합한다.

## Project Structure (Unity 파트만)

```text
Assets/_Project/Scripts/World/Voice/          [신규]
├── VoicePositionReporter.cs      주기적으로 전 플레이어 위치·층을 JS로 전달
└── SpeakingIndicator.cs          JS에서 받은 "말하는 중" 표시를 캐릭터에 반영

Assets/Plugins/WebGL/festa-bridge.jslib       [확장] 006/013과 공용
```

FE/Infra 산출물(React 음성 모듈, SFU 배포)은 각 파트 repo에서 별도 관리.

## 접근 방식

1. **위치 전달은 저빈도로 시작**한다. 볼륨은 사람이 걷는 속도로만 변하므로 초당 5~10회면 충분하다.
   매 프레임 브릿지를 호출하면 WebGL에서 비용이 크다 (C-06).
2. **층 격리는 자동으로 해결된다.** spec 018이 **층별 세션**으로 확정되어, 음성 방을 층(세션) 단위로 두면
   다른 층 소리는 애초에 도달하지 않는다. 거리 계산에서 층을 걸러내는 로직이 필요 없다.
   payload에 `floorId`는 방 식별 용도로만 넣는다.
3. **Unity는 볼륨을 계산하지 않는다.** 거리 계산과 감쇠 곡선은 React가 가진다 —
   그래야 곡선을 조정할 때 Unity 재빌드가 필요 없다 (헌법 4조 정신).
4. **SFU 결정(C-01) 전에도 Unity 몫은 진행 가능**하다. 위치 전달은 음성 방식과 무관하다.

## Complexity Tracking

| 위험 | 대응 |
|---|---|
| **SFU 호스팅 비용·부하 미지수** | AWS 제공 서버 사양 확인 후 결정. 관리형 무료 티어부터 검토 |
| 30~40명 동시 음성의 대역폭 | 가청 거리 밖 참가자는 **구독하지 않는 것**이 핵심 (SFU 선택 기준) |
| 에코·하울링 | 브라우저 기본 에코 캔슬레이션 사용, 이어폰 권장 안내 |
| 브릿지 호출 빈도 | 저빈도 시작 후 튐 현상 있으면 보간으로 해결 (빈도 증가는 최후) |
| 층 구분이 018에 종속 | 층 개념이 없는 동안에는 고정값 전달 |

## 파트 경계

| 범위 | 담당 |
|---|---|
| 마이크 캡처, WebRTC 연결, 볼륨 적용, 음소거 UI | **FE** |
| SFU 서버 배포·운영 | **Infra** |
| 위치·층 전달, 말하는 사람 표시 | **Unity** |
| 가청 거리·감쇠 곡선 값 | 기획 + FE (Unity 재빌드 불필요하게) |
