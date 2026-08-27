using System;
using UnityEngine;

namespace Festa.Booth
{
    // ── Draft 계약 ──────────────────────────────────────────────
    // 이 스키마는 doc 00 기준 "팀 확정 필요" 항목이다.
    // React Booth Studio ↔ Spring ↔ Unity가 공유하는 JSON 계약이므로
    // 필드 추가/변경 시 반드시 3파트 합의 후 수정한다.
    // ───────────────────────────────────────────────────────────

    [Serializable]
    public class BoothLayoutDto
    {
        public int boothId;
        public string template;
        public int version;       // Published Layout 버전 = 게시 횟수 (BE PublishedView.version)
        public int schemaVersion;  // Layout JSON 구조 버전 (R-10). 0 = 필드 없던 구버전 응답
        public BoothObjectDto[] objects;
    }

    [Serializable]
    public class BoothObjectDto
    {
        public string objectId;  // canonical 계약 필드
        public string id;        // 구 Mock/저장 데이터 읽기 호환 전용
        public string type;      // BoothObjectType 문자열 (예: "VIDEO_SCREEN")
        public string assetCode; // FURNITURE/DECORATION의 구체 Unity 자산 식별자
        public PositionDto position;
        public float rotationY;
        public int configId;     // Spring 측 콘텐츠 설정 ID (AI Agent, Video 등)

        public string ResolvedObjectId =>
            !string.IsNullOrEmpty(objectId) ? objectId : id;
    }

    [Serializable]
    public class PositionDto
    {
        public float x;
        public float y;
        public float z;

        public Vector3 ToVector3() => new(x, y, z);
    }

    public static class BoothLayoutParser
    {
        /// <summary>이 클라이언트가 아는 Layout JSON 구조 버전 (R-10).</summary>
        public const int SupportedSchemaVersion = 1;

        /// <summary>JSON → DTO. 실패 시 null 반환(예외를 삼키지 않고 로그).</summary>
        public static BoothLayoutDto Parse(string json)
        {
            if (string.IsNullOrEmpty(json)) return null;
            try
            {
                var dto = JsonUtility.FromJson<BoothLayoutDto>(json);
                if (dto == null || dto.objects == null)
                {
                    Debug.LogError("[BoothLayoutParser] Parsed layout has no objects");
                    return null;
                }

                // 구조 버전이 앞서 있어도 렌더는 시도한다 — 모르는 type/필드는 어차피
                // 스킵된다(BoothObjectFactory forward-compat). 다만 조용히 지나가면
                // "일부만 그려지는" 원인을 못 찾으니 경고는 남긴다.
                if (dto.schemaVersion > SupportedSchemaVersion)
                    Debug.LogWarning($"[BoothLayoutParser] schemaVersion {dto.schemaVersion} > 지원 {SupportedSchemaVersion} — 모르는 요소는 스킵된다 (booth {dto.boothId})");

                return dto;
            }
            catch (Exception e)
            {
                Debug.LogError($"[BoothLayoutParser] Parse failed: {e.Message}");
                return null;
            }
        }
    }
}
