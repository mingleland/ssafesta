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
