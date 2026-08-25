using Festa.World;
using Unity.Netcode;
using UnityEngine;
using UnityEngine.InputSystem;

namespace Festa.Network
{
    /// <summary>Alt + 좌클릭 드래그로 8방향 감정표현을 선택한다.</summary>
    [RequireComponent(typeof(NetworkPlayer))]
    public sealed class PlayerEmoteController : NetworkBehaviour
    {
        const float DeadZone = 34f;
        const float WheelRadius = 126f;

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
        GUIStyle _titleStyle;
        Texture2D _discTexture;
        Texture2D _normalTexture;
        Texture2D _selectedTexture;

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

        void OnGUI()
        {
            if (!_wheelOpen || !IsOwner) return;
            EnsureGuiAssets();
            var guiCenter = new Vector2(_center.x, Screen.height - _center.y);
            GUI.DrawTexture(new Rect(guiCenter.x - 78f, guiCenter.y - 78f, 156f, 156f), _discTexture);
            GUI.Label(new Rect(guiCenter.x - 90f, guiCenter.y - 25f, 180f, 30f), "감정 표현", _titleStyle);
            GUI.Label(new Rect(guiCenter.x - 90f, guiCenter.y + 6f, 180f, 24f), "놓아서 선택", _labelStyle);

            for (var i = 0; i < 8; i++)
            {
                var radians = (90f - i * 45f) * Mathf.Deg2Rad;
                var point = guiCenter + new Vector2(Mathf.Cos(radians), -Mathf.Sin(radians)) * WheelRadius;
                var rect = new Rect(point.x - 58f, point.y - 25f, 116f, 50f);
                GUI.DrawTexture(rect, i == _selected ? _selectedTexture : _normalTexture);
                GUI.Label(rect, Labels[i], _labelStyle);
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
                fontSize = 16,
                fontStyle = FontStyle.Bold,
                normal = { textColor = new Color(0.95f, 0.95f, 1f) }
            };
            _titleStyle = new GUIStyle(_labelStyle) { fontSize = 20 };
            _discTexture = SolidTexture(new Color(0.035f, 0.045f, 0.075f, 0.88f));
            _normalTexture = SolidTexture(new Color(0.08f, 0.1f, 0.16f, 0.78f));
            _selectedTexture = SolidTexture(new Color(0.78f, 0.48f, 0.08f, 0.94f));
        }

        static Texture2D SolidTexture(Color color)
        {
            var texture = new Texture2D(1, 1, TextureFormat.RGBA32, false);
            texture.SetPixel(0, 0, color);
            texture.Apply(false, true);
            return texture;
        }
    }
}
