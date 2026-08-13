using System;
using System.Collections.Generic;
using System.Text;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 아바타 외형 정보. 네트워크로는 문자열 하나로 인코딩되어 오간다.
    ///
    /// 두 가지 모드를 지원한다:
    ///   1) 프리셋 모드   "sk_01"                  — 미리 구운 프리팹 (가볍고 안전)
    ///   2) 런타임 조립   "rt|10=abc|17=def|..."   — Sidekick Runtime API로 파츠 조립
    ///
    /// 인코딩 규칙
    ///   - 세그먼트 구분 '|', 각 세그먼트는 "키=값"
    ///   - 첫 세그먼트가 "rt"면 런타임 조립 모드, 아니면 프리셋 코드
    ///   - 파츠 키는 CharacterPartType의 정수값 (Head=1, Torso=10 …)
    ///   - 모르는 세그먼트는 무시 → 구버전 클라이언트가 깨지지 않는다
    ///
    /// 길이 주의: 런타임 모드는 길어지므로 NetworkVariable을 FixedString512Bytes로 쓴다
    /// (PlayerNetworkAppearance 참조). 프리셋 모드는 짧다.
    /// </summary>
    [Serializable]
    public struct AvatarAppearance
    {
        public const string DefaultPreset = "sk_01";
        public const string RuntimeMarker = "rt";

        // Sidekick 파츠 이름이 길어서(파츠당 ~25자 × 16종) 500자를 넘을 수 있다.
        // 동기화 필드가 FixedString4096Bytes이므로 여유 있게 잡는다 (T-24).
        public const int MaxEncodedLength = 3800;

        /// <summary>프리셋 모드일 때의 프리팹 코드. 런타임 모드면 null.</summary>
        public string PresetCode;

        /// <summary>런타임 조립 모드일 때 파츠 맵 (CharacterPartType 정수 → 파츠 이름).</summary>
        public Dictionary<int, string> Parts;

        /// <summary>RRGGBB. 빈 값이면 원본 색상.</summary>
        public string TintHex;

        public bool IsRuntime => Parts != null && Parts.Count > 0;

        public static AvatarAppearance Default => new() { PresetCode = DefaultPreset };

        public static AvatarAppearance FromParts(Dictionary<int, string> parts, string tintHex = null)
            => new() { Parts = new Dictionary<int, string>(parts), TintHex = tintHex };

        public string Encode()
        {
            var sb = new StringBuilder();

            if (IsRuntime)
            {
                sb.Append(RuntimeMarker);
                foreach (var kv in Parts)
                {
                    if (string.IsNullOrEmpty(kv.Value)) continue;
                    sb.Append('|').Append(kv.Key).Append('=').Append(kv.Value);
                }
            }
            else
            {
                sb.Append(string.IsNullOrEmpty(PresetCode) ? DefaultPreset : PresetCode);
            }

            if (!string.IsNullOrEmpty(TintHex))
                sb.Append("|c=").Append(TintHex);

            var encoded = sb.ToString();

            // 주의: 여기서 값을 몰래 바꾸지 않는다. 초과 여부 판단과 거부는 호출자
            // (PlayerAppearanceController.RequestChange)가 하고, 사용자에게 표시한다.
            // 과거에 여기서 DefaultPreset으로 되돌리는 바람에 "버튼이 무반응"이 됐다 (T-24).
            if (encoded.Length > MaxEncodedLength)
                Debug.LogError($"[AvatarAppearance] 인코딩 길이 초과({encoded.Length}/{MaxEncodedLength})");

            return encoded;
        }

        /// <summary>알 수 없는 형식이어도 예외 없이 기본값으로 복원한다.</summary>
        public static AvatarAppearance Decode(string encoded)
        {
            var result = Default;
            if (string.IsNullOrEmpty(encoded)) return result;

            var segments = encoded.Split('|');
            bool runtime = segments[0] == RuntimeMarker;

            if (runtime)
            {
                result.PresetCode = null;
                result.Parts = new Dictionary<int, string>();
            }
            else
            {
                result.PresetCode = string.IsNullOrEmpty(segments[0]) ? DefaultPreset : segments[0];
            }

            for (int i = 1; i < segments.Length; i++)
            {
                var seg = segments[i];
                int eq = seg.IndexOf('=');
                if (eq <= 0 || eq == seg.Length - 1) continue;

                var key = seg[..eq];
                var value = seg[(eq + 1)..];

                if (key == "c")
                {
                    result.TintHex = value;
                }
                else if (runtime && int.TryParse(key, out var partType))
                {
                    result.Parts[partType] = value;
                }
                // 그 외 미지원 세그먼트는 무시 (forward compatibility)
            }

            return result;
        }

        public Color GetTintColor()
        {
            if (string.IsNullOrEmpty(TintHex)) return Color.white;
            return ColorUtility.TryParseHtmlString("#" + TintHex, out var c) ? c : Color.white;
        }
    }
}
