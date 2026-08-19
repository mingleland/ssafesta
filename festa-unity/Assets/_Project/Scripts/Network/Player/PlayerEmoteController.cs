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

        static readonly PlayerEmoteId[] Emotes =
        {
            PlayerEmoteId.Greeting, PlayerEmoteId.Salute,
            PlayerEmoteId.King, PlayerEmoteId.GangnamStyle,
            PlayerEmoteId.Defeat, PlayerEmoteId.Praying,
            PlayerEmoteId.Twerk, PlayerEmoteId.JoyfulJump,
        };

        static readonly string[] Labels =
        {
            "인사", "경례", "왕의 자세", "강남스타일",
            "패배", "기도", "트월킹", "기쁨의 점프"
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
                _oneShotStopAt = IsLooping(emote) ? 0f : Time.unscaledTime + FindClipDuration(emote);
            }
            _wheelOpen = false;
            _selected = -1;
        }

        static bool IsLooping(PlayerEmoteId emote) =>
            emote == PlayerEmoteId.GangnamStyle || emote == PlayerEmoteId.Twerk;

        float FindClipDuration(PlayerEmoteId emote)
        {
            var animator = GetComponent<PlayerAvatarVisual>()?.CurrentAnimator;
            if (animator != null && animator.runtimeAnimatorController != null)
            {
                var expected = $"Emote_{emote}";
                foreach (var clip in animator.runtimeAnimatorController.animationClips)
                    if (clip != null && clip.name == expected)
                        return Mathf.Max(0.5f, clip.length - 0.15f);
            }
            return 3f;
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
