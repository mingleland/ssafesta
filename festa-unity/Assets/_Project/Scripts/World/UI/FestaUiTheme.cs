using System.Collections.Generic;
using UnityEngine;

namespace Festa.World.UI
{
    /// <summary>
    /// 킷 스프라이트 식별자. 아틀라스 좌표·9-slice 경계는 <see cref="FestaUiTheme"/> 가 안다.
    /// </summary>
    public enum UiSprite
    {
        // 패널
        PanelBlue,        // 큰 파란 그라데이션 패널 (팝업·카드)
        PanelPurple,      // 보라 버블 패널 (세로 긴 화면)
        PanelCyanOrnate,  // 청록 장식 패널
        BarNavy,          // 남색 가로 바 (HUD 띠)
        BannerPurpleStripe, // 보라 줄무늬 알약 (제목 배너)
        FieldLight,       // 밝은 알약 (입력·값 표시)
        PillDark,         // 어두운 남색 알약 (칩·힌트)
        FrameDarkSquare,  // 어두운 정사각 프레임 (아이콘 슬롯)
        // 버튼
        ButtonOrange,     // 기본(primary)
        ButtonNavy,       // 보조
        ButtonYellowShine,// 강조(큰)
        PillGreen,        // 긍정
        PillRed,          // 위험
        ButtonGreenSmall,
        ButtonYellowSmall,
        // 아이콘
        IconCoin,
        IconCoinSmall,
        IconGem,
        IconClose,        // 빨간 X
        IconBack,         // 파란 화살표
        IconCheck,
        IconGear,
        IconHome,
        IconPlus,
        IconMinus,
        IconCoinStack,
        IconCoinBag,
        IconShieldStar,
    }

    /// <summary>
    /// 300Mind "2D Game UI Kit" 아틀라스에서 필요한 조각만 골라 <b>런타임 9-slice 스프라이트</b>로 만든다.
    ///
    /// <para>킷의 슬라이스에는 9-slice 경계가 없고, 벤더 폴더의 import 설정을 건드리지 않기로 했으므로(벤더 에셋 불변 원칙)
    /// 아틀라스 텍스처만 참조해 <see cref="Sprite.Create(Texture2D, Rect, Vector2, float, uint, SpriteMeshType, Vector4)"/> 로
    /// 경계를 넣어 만든다. 좌표는 아틀라스 .meta 의 슬라이스 값(원점 좌하단)이다.</para>
    ///
    /// <para>에셋은 <c>Resources/UI/FestaUiTheme.asset</c> 하나 — 텍스처 참조 두 개만 들고 있다.</para>
    /// </summary>
    [CreateAssetMenu(fileName = "FestaUiTheme", menuName = "Festa/UI Theme")]
    public sealed class FestaUiTheme : ScriptableObject
    {
        public const string ResourcePath = "UI/FestaUiTheme";

        [SerializeField] Texture2D _atlas1;   // UI-pack_Sprite_1.png (3118×1754)
        [SerializeField] Texture2D _atlas2;   // UI-pack_Sprite_2.png (3118×1754)

        struct Entry
        {
            public int Atlas; public Rect Rect; public Vector4 Border;
            public Entry(int atlas, float x, float y, float w, float h, float bl = 0, float bb = 0, float br = 0, float bt = 0)
            { Atlas = atlas; Rect = new Rect(x, y, w, h); Border = new Vector4(bl, bb, br, bt); }
        }

        // 아틀라스 .meta 슬라이스 좌표 (x, y, w, h) + 9-slice 경계(left, bottom, right, top)
        static readonly Dictionary<UiSprite, Entry> s_table = new()
        {
            { UiSprite.PanelBlue,          new Entry(2, 49, 847, 1002, 858, 70, 90, 70, 70) },
            { UiSprite.PanelPurple,        new Entry(2, 2068, 438, 971, 1274, 90, 110, 90, 90) },
            { UiSprite.PanelCyanOrnate,    new Entry(2, 1081, 699, 965, 780, 110, 110, 110, 110) },
            { UiSprite.BarNavy,            new Entry(2, 48, 413, 941, 210, 60, 70, 60, 60) },
            { UiSprite.BannerPurpleStripe, new Entry(2, 1160, 1512, 807, 197, 95, 95, 95, 95) },
            { UiSprite.FieldLight,         new Entry(2, 49, 661, 928, 143, 70, 70, 70, 70) },
            { UiSprite.PillDark,           new Entry(1, 1039, 1643, 254, 73, 36, 36, 36, 36) },
            { UiSprite.FrameDarkSquare,    new Entry(1, 1513, 1336, 172, 172, 40, 40, 40, 40) },

            { UiSprite.ButtonOrange,       new Entry(2, 49, 230, 264, 142, 50, 60, 50, 50) },
            { UiSprite.ButtonNavy,         new Entry(2, 354, 230, 265, 142, 50, 60, 50, 50) },
            { UiSprite.ButtonYellowShine,  new Entry(1, 71, 1352, 388, 191, 60, 70, 60, 60) },
            { UiSprite.PillGreen,          new Entry(2, 48, 59, 390, 131, 64, 64, 64, 64) },
            { UiSprite.PillRed,            new Entry(2, 477, 59, 390, 131, 64, 64, 64, 64) },
            { UiSprite.ButtonGreenSmall,   new Entry(1, 1164, 1171, 212, 80, 36, 36, 36, 36) },
            { UiSprite.ButtonYellowSmall,  new Entry(1, 1402, 1173, 225, 80, 36, 36, 36, 36) },

            { UiSprite.IconCoin,           new Entry(1, 975, 1475, 90, 96) },
            { UiSprite.IconCoinSmall,      new Entry(1, 1513, 1547, 64, 67) },
            { UiSprite.IconGem,            new Entry(1, 973, 1369, 94, 79) },
            { UiSprite.IconClose,          new Entry(1, 1601, 42, 89, 89) },
            { UiSprite.IconBack,           new Entry(1, 1724, 47, 69, 79) },
            { UiSprite.IconCheck,          new Entry(1, 880, 1608, 99, 100) },
            { UiSprite.IconGear,           new Entry(1, 741, 1600, 105, 113) },
            { UiSprite.IconHome,           new Entry(1, 390, 1593, 138, 128) },
            { UiSprite.IconPlus,           new Entry(1, 1098, 1415, 62, 62) },
            { UiSprite.IconMinus,          new Entry(1, 1098, 1344, 62, 62) },
            { UiSprite.IconCoinStack,      new Entry(1, 2464, 1554, 188, 164) },
            { UiSprite.IconCoinBag,        new Entry(1, 2116, 1310, 242, 210) },
            { UiSprite.IconShieldStar,     new Entry(2, 1847, 340, 121, 135) },
        };

        static FestaUiTheme s_instance;
        static bool s_missingLogged;
        readonly Dictionary<UiSprite, Sprite> _cache = new();

        /// <summary>Resources 의 테마. 없으면 null — 호출자는 코드 생성 둥근 사각형으로 떨어진다(조용히 넘기지 않고 에러 1회).</summary>
        public static FestaUiTheme Instance
        {
            get
            {
                if (s_instance != null) return s_instance;
                s_instance = Resources.Load<FestaUiTheme>(ResourcePath);
                if (s_instance == null && !s_missingLogged)
                {
                    s_missingLogged = true;
                    Debug.LogError($"[FestaUiTheme] Resources/{ResourcePath} 가 없다 — UI 킷 스프라이트 없이 기본 둥근 사각형으로 그린다.");
                }
                return s_instance;
            }
        }

        public Sprite Get(UiSprite id)
        {
            if (_cache.TryGetValue(id, out var cached) && cached != null) return cached;
            if (!s_table.TryGetValue(id, out var e)) return null;
            var tex = e.Atlas == 1 ? _atlas1 : _atlas2;
            if (tex == null)
            {
                Debug.LogError($"[FestaUiTheme] 아틀라스 {e.Atlas} 참조가 비어 있다 ({id}).");
                return null;
            }
            // 슬라이스 좌표는 원본(3118×1754) 기준이고 import 는 2048 로 줄어 있다 — 실제 텍스처 크기에 맞춰 비례 축소.
            Scale(tex, e, out var rect, out var border);
            var sprite = Sprite.Create(tex, rect, new Vector2(0.5f, 0.5f), 100f, 0, SpriteMeshType.FullRect, border);
            sprite.name = "ui_" + id;
            _cache[id] = sprite;
            return sprite;
        }

        public static bool IsSliced(UiSprite id) => s_table.TryGetValue(id, out var e) && e.Border != Vector4.zero;

        /// <summary>IMGUI(GUI.DrawTextureWithTexCoords) 용 원본 조각 — 텍스처·픽셀 rect·경계. 없으면 false.</summary>
        public bool TryGetPiece(UiSprite id, out Texture2D texture, out Rect pixelRect, out Vector4 border)
        {
            texture = null; pixelRect = default; border = default;
            if (!s_table.TryGetValue(id, out var e)) return false;
            texture = e.Atlas == 1 ? _atlas1 : _atlas2;
            if (texture == null) return false;
            Scale(texture, e, out pixelRect, out border);
            return true;
        }

        const float SourceAtlasWidth = 3118f;

        static void Scale(Texture2D tex, Entry e, out Rect rect, out Vector4 border)
        {
            float k = tex.width / SourceAtlasWidth;
            rect = new Rect(e.Rect.x * k, e.Rect.y * k, e.Rect.width * k, e.Rect.height * k);
            border = e.Border * k;
        }
    }
}
