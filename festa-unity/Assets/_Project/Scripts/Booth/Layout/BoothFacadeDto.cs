using System;
using System.Globalization;
using UnityEngine;

namespace Festa.Booth
{
    // ── Facade 계약 ─────────────────────────────────────────────
    // GET /api/v1/booths/{boothId} 응답의 facade 블록 (docs/08 §4).
    // 저장 endpoint 는 아직 계약이 없다 — docs/26 "부스 외부 설정(Facade) 저장 계약" 미결.
    // Unity 는 읽기만 한다. 색상 유효성 검증은 서버 몫이다 (헌법 16조).
    // ───────────────────────────────────────────────────────────

    [Serializable]
    public class BoothDetailDto
    {
        public int boothId;
        public int slotId;
        public string name;
        public string leaseStatus;
        public bool entryAvailable;
        public BoothFacadeDto facade;
        public int publishedLayoutVersion;

        /// <summary>
        /// 이 부스가 설정한 AI 직원. <b>아직 서버가 내려주지 않는 필드다</b> — 없으면 0 이다.
        ///
        /// <para>왜 여기를 보는가. AI 직원은 게시 레이아웃의 <c>AI_AGENT</c> 오브젝트에만 실려 오는데
        /// (BE <c>LayoutConfigResolver</c> — configId 로 콘텐츠를 묶는 유일한 타입), 배치 툴을 제공하지
        /// 않기로 하면서 그 오브젝트를 깔 주체가 없어졌다. 부스 안 AI 직원은 씬에 고정으로 놓여 있으므로
        /// 필요한 것은 배치가 아니라 <b>"이 부스의 AI 가 누구인가"</b> 하나뿐이다.</para>
        ///
        /// <para>방문자가 읽을 수 있는 부스 정보는 이 공개 상세뿐이다 —
        /// <c>GET /booths/{'{'}id{'}'}/agents</c> 는 부스 편집 권한 전용이라 남의 부스에서는 401·403 이다
        /// (2026-09-18 실측). 그래서 이 자리를 제안했다.</para>
        ///
        /// <para>이름이 확정되지 않아 <c>aiAgentId</c>·<c>agentId</c> 둘 다 받는다. 서버가 어느 쪽으로
        /// 내려도 붙고, 아직 안 내려주면 0 이라 지금까지와 똑같이 "준비 중"으로 남는다.</para>
        /// </summary>
        public int aiAgentId;
        public int agentId;

        /// <summary>둘 중 실린 값. 없으면 0.</summary>
        public int ResolvedAiAgentId => aiAgentId > 0 ? aiAgentId : agentId;
    }

    [Serializable]
    public class BoothFacadeDto
    {
        public string themeCode;     // 예: "SSAFY_BLUE"
        public string primaryColor;  // hex 문자열. 예: "#3B82F6" (#17: 저장은 hex, 입력은 팔레트 12색)
        public string signText;
        public string logoUrl;
    }

    public static class BoothFacadeParser
    {
        /// <summary>JSON → DTO. 실패 시 null 반환(예외를 삼키지 않고 로그).</summary>
        public static BoothDetailDto Parse(string json)
        {
            if (string.IsNullOrEmpty(json)) return null;
            try
            {
                var dto = JsonUtility.FromJson<BoothDetailDto>(json);
                if (dto == null)
                {
                    Debug.LogError("[BoothFacadeParser] 응답을 BoothDetailDto 로 해석할 수 없다");
                    return null;
                }
                return dto;
            }
            catch (Exception e)
            {
                Debug.LogError($"[BoothFacadeParser] Parse 실패: {e.Message}");
                return null;
            }
        }

        /// <summary>
        /// hex 문자열을 Color 로. 지원 형식: #RGB, #RRGGBB, #RRGGBBAA (# 없어도 됨).
        ///
        /// 값 검증의 최종 책임은 서버다 (헌법 16조). 그래도 Unity 가 방어적으로 파싱하는 이유는
        /// 잘못된 값이 와도 부스 전체가 깨지지 않아야 하기 때문이다. 대신 조용히 넘기지 않고
        /// 경고를 남긴다 — 미지정(정상)과 오타/불량값을 구분해야 한다 (issue #6 assetCode 와 같은 원칙).
        /// </summary>
        public static bool TryParseHexColor(string hex, out Color color)
        {
            color = Color.white;
            if (string.IsNullOrWhiteSpace(hex)) return false; // 미지정 — 경고하지 않는다

            var s = hex.Trim();
            if (s.StartsWith("#")) s = s.Substring(1);

            if (s.Length != 3 && s.Length != 6 && s.Length != 8)
            {
                Debug.LogWarning($"[BoothFacadeParser] primaryColor 길이가 잘못됐다: '{hex}' " +
                                 "(#RGB / #RRGGBB / #RRGGBBAA 만 허용) — 기본색 유지");
                return false;
            }

            if (s.Length == 3) // #RGB → #RRGGBB
                s = $"{s[0]}{s[0]}{s[1]}{s[1]}{s[2]}{s[2]}";

            if (!uint.TryParse(s, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var v))
            {
                Debug.LogWarning($"[BoothFacadeParser] primaryColor 가 16진수가 아니다: '{hex}' — 기본색 유지");
                return false;
            }

            if (s.Length == 6)
            {
                color = new Color(
                    ((v >> 16) & 0xFF) / 255f,
                    ((v >> 8) & 0xFF) / 255f,
                    (v & 0xFF) / 255f,
                    1f);
            }
            else // 8자리
            {
                color = new Color(
                    ((v >> 24) & 0xFF) / 255f,
                    ((v >> 16) & 0xFF) / 255f,
                    ((v >> 8) & 0xFF) / 255f,
                    (v & 0xFF) / 255f);
            }
            return true;
        }
    }
}
