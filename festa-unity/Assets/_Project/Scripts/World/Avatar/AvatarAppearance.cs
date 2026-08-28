using System;
using System.Collections.Generic;
using System.Text;
using UnityEngine;
using Festa.Avatar;

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
        public const string ModularMarker = "fa";

        // Sidekick 파츠 이름이 길어서(파츠당 ~25자 × 16종) 500자를 넘을 수 있다.
        // 동기화 필드가 FixedString4096Bytes이므로 여유 있게 잡는다 (T-24).
        public const int MaxEncodedLength = 3800;

        /// <summary>프리셋 모드일 때의 프리팹 코드. 런타임 모드면 null.</summary>
        public string PresetCode;

        /// <summary>런타임 조립 모드일 때 파츠 맵 (CharacterPartType 정수 → 파츠 이름).</summary>
        public Dictionary<int, string> Parts;

        /// <summary>RRGGBB. 빈 값이면 원본 색상.</summary>
        public string TintHex;
        public AvatarConfig ModularConfig;
        public bool IsModular;

        public bool IsRuntime => Parts != null && Parts.Count > 0;

        public static AvatarAppearance Default => new() { PresetCode = DefaultPreset };

        public static AvatarAppearance FromParts(Dictionary<int, string> parts, string tintHex = null)
            => new() { Parts = new Dictionary<int, string>(parts), TintHex = tintHex };

        public static AvatarAppearance FromModularConfig(AvatarConfig config)
            => new() { IsModular = true, ModularConfig = config, PresetCode = null };

        public string Encode()
        {
            var sb = new StringBuilder();

            if (IsModular)
            {
                var c = ModularConfig;
                sb.Append(ModularMarker)
                    .Append("|g=").Append((byte)c.gender)
                    .Append("|i=").Append(c.headId).Append(',').Append(c.hairId).Append(',').Append(c.hatId).Append(',').Append(c.glassesId)
                    .Append(',').Append(c.topId).Append(',').Append(c.bottomId).Append(',').Append(c.outfitId).Append(',').Append(c.shoesId)
                    .Append("|p=").Append(c.skinColorId).Append(',').Append(c.hairColorId).Append(',').Append(c.irisColorId).Append(',')
                    .Append(c.eyebrowColorId).Append(',').Append(c.lipsColorId).Append(',').Append(c.topColorId).Append(',').Append(c.bottomColorId).Append(',').Append(c.scleraColorId).Append(',').Append(c.pupilColorId)
                    .Append("|q=").Append(Hex(c.skinColor)).Append(',').Append(Hex(c.hairColor)).Append(',').Append(Hex(c.irisColor)).Append(',')
                    .Append(Hex(c.eyebrowColor)).Append(',').Append(Hex(c.lipsColor)).Append(',').Append(Hex(c.topColor)).Append(',').Append(Hex(c.bottomColor)).Append(',').Append(Hex(c.scleraColor)).Append(',').Append(Hex(c.pupilColor));
                sb.Append("|w=");
                for (int i = 0; i < 36; i++)
                {
                    if (i > 0) sb.Append(',');
                    sb.Append(Hex(c.GetGarmentColor(i)));
                }
            }
            else if (IsRuntime)
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
            bool modular = segments[0] == ModularMarker;

            if (modular)
            {
                result.PresetCode = null;
                result.IsModular = true;
                result.ModularConfig = default;
            }
            else if (runtime)
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
                else if (modular && key == "g" && byte.TryParse(value, out var gender))
                {
                    result.ModularConfig.gender = (AvatarGender)Mathf.Clamp(gender, 0, 1);
                }
                else if (modular && key == "i")
                {
                    var values = value.Split(',');
                    if (values.Length == 8)
                    {
                        int.TryParse(values[0], out result.ModularConfig.headId); int.TryParse(values[1], out result.ModularConfig.hairId);
                        int.TryParse(values[2], out result.ModularConfig.hatId); int.TryParse(values[3], out result.ModularConfig.glassesId);
                        int.TryParse(values[4], out result.ModularConfig.topId); int.TryParse(values[5], out result.ModularConfig.bottomId);
                        int.TryParse(values[6], out result.ModularConfig.outfitId); int.TryParse(values[7], out result.ModularConfig.shoesId);
                    }
                }
                else if (modular && key == "p")
                {
                    var values = value.Split(',');
                    if (values.Length == 7 || values.Length == 8 || values.Length == 9)
                    {
                        byte.TryParse(values[0], out result.ModularConfig.skinColorId); byte.TryParse(values[1], out result.ModularConfig.hairColorId);
                        byte.TryParse(values[2], out result.ModularConfig.irisColorId); byte.TryParse(values[3], out result.ModularConfig.eyebrowColorId);
                        byte.TryParse(values[4], out result.ModularConfig.lipsColorId); byte.TryParse(values[5], out result.ModularConfig.topColorId);
                        byte.TryParse(values[6], out result.ModularConfig.bottomColorId);
                        if (values.Length >= 8) byte.TryParse(values[7], out result.ModularConfig.scleraColorId);
                        else result.ModularConfig.scleraColorId = 16;
                        if (values.Length == 9) byte.TryParse(values[8], out result.ModularConfig.pupilColorId);
                        else result.ModularConfig.pupilColorId = 6;
                    }
                }
                else if (modular && key == "q")
                {
                    var values = value.Split(',');
                    if (values.Length == 7 || values.Length == 8 || values.Length == 9)
                    {
                        result.ModularConfig.skinColor = ParseColor(values[0]); result.ModularConfig.hairColor = ParseColor(values[1]);
                        result.ModularConfig.irisColor = ParseColor(values[2]); result.ModularConfig.eyebrowColor = ParseColor(values[3]);
                        result.ModularConfig.lipsColor = ParseColor(values[4]); result.ModularConfig.topColor = ParseColor(values[5]);
                        result.ModularConfig.bottomColor = ParseColor(values[6]);
                        result.ModularConfig.scleraColor = values.Length >= 8 ? ParseColor(values[7]) : new Color32(255, 255, 255, 255);
                        result.ModularConfig.pupilColor = values.Length == 9 ? ParseColor(values[8]) : new Color32(20, 16, 18, 255);
                    }
                }
                else if (modular && key == "w")
                {
                    var values = value.Split(',');
                    if (values.Length == 24 || values.Length == 30 || values.Length == 36)
                    {
                        for (int j = 0; j < values.Length; j++) result.ModularConfig.SetGarmentColor(j, ParseColor(values[j]));
                        result.ModularConfig.garmentColorVersion = 1;
                    }
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

        static string Hex(Color32 c) => c.a == 0 ? "-" : ColorUtility.ToHtmlStringRGB(c);
        static Color32 ParseColor(string value) => value != "-" && ColorUtility.TryParseHtmlString("#" + value, out var c) ? (Color32)c : default;
    }
}
