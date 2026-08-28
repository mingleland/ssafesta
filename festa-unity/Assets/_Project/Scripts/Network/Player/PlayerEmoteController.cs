using Festa.World;
using Unity.Netcode;
using UnityEngine;
using UnityEngine.InputSystem;

namespace Festa.Network
{
    /// <summary>Alt + 좌클릭 드래그로 8방향 감정표현을 선택한다 (오버워치식 방사형 휠).</summary>
    [RequireComponent(typeof(NetworkPlayer))]
    public sealed class PlayerEmoteController : NetworkBehaviour
    {
        const float DeadZone = 52f;      // 허브 반경과 맞춘다 — 허브 안에서는 선택되지 않는다
        const float WheelRadius = 150f;  // 링 외곽 반경(화면 픽셀)

        // 휠 8칸 — 시계 방향(위부터). Labels 와 순서가 1:1 로 맞아야 한다.
        static readonly PlayerEmoteId[] Emotes =
        {
            PlayerEmoteId.Greeting, PlayerEmoteId.Clap,
            PlayerEmoteId.Cheer,    PlayerEmoteId.Nod,
            PlayerEmoteId.Laugh,    PlayerEmoteId.SitGround,
            PlayerEmoteId.Drink,    PlayerEmoteId.Thanks,
        };

        static readonly string[] Labels =
        {
            "인사", "박수", "환호", "끄덕임",
            "웃음", "앉기", "건배", "감사"
        };

        NetworkPlayer _player;
        bool _wheelOpen;
        Vector2 _center;
        int _selected = -1;
        float _oneShotStopAt;
        GUIStyle _labelStyle;
        GUIStyle _selectedStyle;
        GUIStyle _titleStyle;
        GUIStyle _hubPickStyle;
        Texture2D _ringTexture;
        Texture2D _wedgeTexture;
        Texture2D _hubTexture;

        void Awake() => _player = GetComponent<NetworkPlayer>();

        public override void OnNetworkSpawn() => enabled = IsOwner;

        void Update()
        {
            if (!IsOwner) return;
            var keyboard = Keyboard.current;
            var mouse = Mouse.current;
            if (keyboard == null || mouse == null) return;

            bool alt = keyboard.leftAltKey.isPressed || keyboard.rightAltKey.isPressed;
            var pointer = mouse.position.ReadValue();
            if (!_wheelOpen && alt && mouse.leftButton.wasPressedThisFrame)
            {
                _wheelOpen = true;
                _center = ClampCenter(pointer);
                UpdateSelection(pointer);
            }

            if (_wheelOpen)
            {
                if (!alt) CloseWheel(false);
                else
                {
                    UpdateSelection(pointer);
                    if (mouse.leftButton.wasReleasedThisFrame) CloseWheel(true);
                }
            }

            TrackOneShotDuration();

            if (_oneShotStopAt > 0f && Time.unscaledTime >= _oneShotStopAt)
            {
                _oneShotStopAt = 0f;
                if (_player.EmoteId.Value != PlayerEmoteId.None && !IsLooping(_player.EmoteId.Value))
                    _player.EmoteId.Value = PlayerEmoteId.None;
            }
        }

        Vector2 ClampCenter(Vector2 pointer)
        {
            var margin = WheelRadius + 74f;
            return new Vector2(
                Mathf.Clamp(pointer.x, margin, Screen.width - margin),
                Mathf.Clamp(pointer.y, margin, Screen.height - margin));
        }

        void UpdateSelection(Vector2 pointer)
        {
            var delta = pointer - _center;
            if (delta.magnitude < DeadZone) { _selected = -1; return; }
            var clockwise = Mathf.Repeat(90f - Mathf.Atan2(delta.y, delta.x) * Mathf.Rad2Deg, 360f);
            _selected = Mathf.FloorToInt((clockwise + 22.5f) / 45f) % 8;
        }

        void CloseWheel(bool apply)
        {
            if (apply && _selected >= 0)
            {
                var emote = Emotes[_selected];
                _player.EmoteId.Value = emote;
                // 길이는 상태가 실제로 재생되기 시작한 뒤에 읽는다 (TrackOneShotDuration).
                _oneShotStopAt = 0f;
                _awaitingDuration = IsLooping(emote) ? PlayerEmoteId.None : emote;
            }
            _wheelOpen = false;
            _selected = -1;
        }

        // 루프 이모트는 자동으로 끝나지 않는다 — 다시 선택하거나 이동하면 해제된다
        // (PlayerMovement 가 이동 시 EmoteId 를 None 으로 되돌린다).
        static bool IsLooping(PlayerEmoteId emote) =>
            emote == PlayerEmoteId.SitGround || emote == PlayerEmoteId.Drink;

        // ── 원샷 이모트 종료 시점 ──────────────────────────────────
        // 클립 이름으로 길이를 찾을 수 없다. 애니메이터 상태 이름은 `Emote_{enum}` 규약이지만
        // **클립 파일 이름은 다르다**(예: 상태 `Emote_Greeting` ← 클립 `HumanF@HandWave01`).
        // 이전 구현은 클립 이름이 상태 이름과 같다고 가정해, 클립을 교체한 순간 조용히
        // 폴백 3초를 쓰게 됐다. 그래서 **실제로 재생 중인 상태의 길이**를 읽는다 —
        // 클립을 어떻게 바꿔도 따라오고, 상태 speed 까지 반영된 값이다.
        PlayerEmoteId _awaitingDuration;

        void TrackOneShotDuration()
        {
            if (_awaitingDuration == PlayerEmoteId.None) return;

            var animator = GetComponent<PlayerAvatarVisual>()?.CurrentAnimator;
            if (animator == null) { _awaitingDuration = PlayerEmoteId.None; return; }

            int wanted = Animator.StringToHash($"Emote_{_awaitingDuration}");
            var cur = animator.GetCurrentAnimatorStateInfo(0);
            if (cur.shortNameHash != wanted) return;   // 아직 크로스페이드 중

            // length 는 speed 가 반영된 재생 시간이다. 끝에서 살짝 앞서 끊어 로코모션으로 넘긴다.
            _oneShotStopAt = Time.unscaledTime + Mathf.Max(0.5f, cur.length - 0.15f);
            _awaitingDuration = PlayerEmoteId.None;
        }

        // ── 방사형 휠 렌더 (오버워치식) ─────────────────────────────
        // 이전에는 8개의 사각 버튼이 공중에 흩어져 있어 "개별 UI" 로 보였다. 방사형 휠은
        // 하나의 링을 8칸으로 나눠 보여주므로 **어디로 끌면 무엇이 나오는지** 한눈에 읽힌다.
        //
        // 링·웨지·허브는 **런타임에 한 번 절차적으로 굽는다.** 스프라이트 에셋을 만들지 않아도
        // 부드러운 곡선이 나오고, 색만 바꿔 테마를 맞출 수 있다. 매 프레임 생성하면 GC 를
        // 때리므로 `EnsureGuiAssets` 에서 한 번만 만든다.

        const int TexSize = 384;
        const float TexOuter = 188f;   // 텍스처 공간 반경 — 화면 반경(WheelRadius)으로 스케일된다
        const float TexInner = 66f;

        void OnGUI()
        {
            if (!_wheelOpen || !IsOwner) return;
            EnsureGuiAssets();

            var center = new Vector2(_center.x, Screen.height - _center.y);
            float scale = WheelRadius / TexOuter;
            float side = TexSize * scale;
            var ringRect = new Rect(center.x - side * 0.5f, center.y - side * 0.5f, side, side);

            GUI.DrawTexture(ringRect, _ringTexture);

            if (_selected >= 0)
            {
                var prev = GUI.matrix;
                GUIUtility.RotateAroundPivot(_selected * 45f, center);   // 위(0번)에서 시계 방향
                GUI.DrawTexture(ringRect, _wedgeTexture);
                GUI.matrix = prev;
            }

            float hub = TexInner * scale * 2f;
            GUI.DrawTexture(new Rect(center.x - hub * 0.5f, center.y - hub * 0.5f, hub, hub), _hubTexture);

            bool picked = _selected >= 0;
            GUI.Label(new Rect(center.x - 90f, center.y - 26f, 180f, 26f),
                      picked ? Labels[_selected] : "감정 표현", picked ? _hubPickStyle : _titleStyle);
            GUI.Label(new Rect(center.x - 90f, center.y + 2f, 180f, 22f),
                      picked ? "놓아서 실행" : "방향으로 끌기", _labelStyle);

            float labelRadius = (TexInner + TexOuter) * 0.5f * scale;
            for (var i = 0; i < 8; i++)
            {
                float radians = (90f - i * 45f) * Mathf.Deg2Rad;
                var point = center + new Vector2(Mathf.Cos(radians), -Mathf.Sin(radians)) * labelRadius;
                GUI.Label(new Rect(point.x - 52f, point.y - 12f, 104f, 24f),
                          Labels[i], i == _selected ? _selectedStyle : _labelStyle);
            }
        }

        void EnsureGuiAssets()
        {
            if (_labelStyle != null) return;
            var font = Resources.Load<Font>("Fonts/MalgunGothicLight");
            _labelStyle = new GUIStyle(GUI.skin.label)
            {
                alignment = TextAnchor.MiddleCenter,
                font = font,
                fontSize = 15,
                fontStyle = FontStyle.Bold,
                normal = { textColor = new Color(0.80f, 0.83f, 0.90f) }
            };
            _selectedStyle = new GUIStyle(_labelStyle)
            {
                fontSize = 17,
                normal = { textColor = new Color(1f, 0.97f, 0.88f) }
            };
            _titleStyle = new GUIStyle(_labelStyle) { fontSize = 18 };
            _hubPickStyle = new GUIStyle(_labelStyle)
            {
                fontSize = 20,
                normal = { textColor = new Color(1f, 0.78f, 0.28f) }
            };

            _ringTexture = BuildRing(new Color(0.05f, 0.07f, 0.11f, 0.80f),
                                     new Color(0.30f, 0.36f, 0.48f, 0.85f));
            _wedgeTexture = BuildWedge(new Color(0.95f, 0.62f, 0.14f, 1f));
            _hubTexture = BuildDisc(new Color(0.03f, 0.04f, 0.07f, 0.90f),
                                    new Color(0.45f, 0.52f, 0.66f, 0.9f));
        }

        /// <summary>가장자리 1.5px 를 부드럽게 — 절차적 텍스처의 계단을 없앤다.</summary>
        static float Feather(float distance) => Mathf.Clamp01(distance / 1.5f);

        /// <summary>도넛 링 + 8칸 구분선.</summary>
        static Texture2D BuildRing(Color fill, Color divider)
        {
            return Bake((dx, dy, radius, angle) =>
            {
                float band = Mathf.Min(Feather(radius - TexInner), Feather(TexOuter - radius));
                if (band <= 0f) return Color.clear;

                // 가장 가까운 칸 경계(22.5° + 45°k)까지의 호 길이 — 픽셀 단위로 재야 두께가 균일하다.
                float boundary = Mathf.Round((angle - 22.5f) / 45f) * 45f + 22.5f;
                float arc = Mathf.Abs(Mathf.DeltaAngle(angle, boundary)) * Mathf.Deg2Rad * radius;
                var color = Color.Lerp(fill, divider, Feather(1.4f - arc));
                color.a *= band;
                return color;
            });
        }

        /// <summary>위쪽 한 칸(±22.5°)만 채운 하이라이트. 선택 칸으로 회전시켜 쓴다.</summary>
        static Texture2D BuildWedge(Color accent)
        {
            return Bake((dx, dy, radius, angle) =>
            {
                float band = Mathf.Min(Feather(radius - TexInner - 1f), Feather(TexOuter - 1f - radius));
                if (band <= 0f) return Color.clear;

                float halfArc = (22.5f - Mathf.Abs(angle)) * Mathf.Deg2Rad * radius;
                float wedge = Feather(halfArc - 1f);
                if (wedge <= 0f) return Color.clear;

                // 바깥으로 갈수록 진해진다 — 선택 방향이 시선을 끌게.
                float t = Mathf.InverseLerp(TexInner, TexOuter, radius);
                var color = accent;
                color.a *= band * wedge * Mathf.Lerp(0.45f, 0.92f, t);
                return color;
            });
        }

        /// <summary>중앙 허브 — 채운 원 + 얇은 테두리.</summary>
        static Texture2D BuildDisc(Color fill, Color rim)
        {
            return Bake((dx, dy, radius, angle) =>
            {
                float inside = Feather(TexInner - radius);
                if (inside <= 0f) return Color.clear;
                var color = Color.Lerp(fill, rim, Feather(radius - (TexInner - 2.5f)));
                color.a *= inside;
                return color;
            });
        }

        /// <summary>각 픽셀의 극좌표(반경·위에서 시계 방향 각도)를 넘겨 텍스처를 굽는다.</summary>
        static Texture2D Bake(System.Func<float, float, float, float, Color> shade)
        {
            var texture = new Texture2D(TexSize, TexSize, TextureFormat.RGBA32, false);
            var pixels = new Color[TexSize * TexSize];
            float c = TexSize * 0.5f;
            for (var y = 0; y < TexSize; y++)
            {
                for (var x = 0; x < TexSize; x++)
                {
                    float dx = x - c + 0.5f;
                    float dy = y - c + 0.5f;
                    float radius = Mathf.Sqrt(dx * dx + dy * dy);
                    float angle = Mathf.Atan2(dx, dy) * Mathf.Rad2Deg;   // 0 = 위, + = 오른쪽
                    pixels[y * TexSize + x] = shade(dx, dy, radius, angle);
                }
            }
            texture.SetPixels(pixels);
            texture.Apply(false, true);
            texture.wrapMode = TextureWrapMode.Clamp;
            return texture;
        }
    }
}
