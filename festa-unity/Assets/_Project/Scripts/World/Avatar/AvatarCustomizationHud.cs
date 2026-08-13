using System.Collections.Generic;
using Synty.SidekickCharacters.Enums;
using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 인게임 캐릭터 커스터마이징 창.
    /// 파츠를 바꾸면 즉시 서버에 반영되고 모든 클라이언트가 실시간으로 본다.
    ///
    /// 정식 서비스에서는 React 오버레이가 이 역할을 맡고 Unity는 AvatarBridge로 값만 받는다
    /// (Docs/avatar-customization-contract.md). 이 HUD는 그때까지의 구현 + 월드 단독 데모용.
    /// </summary>
    public class AvatarCustomizationHud : MonoBehaviour
    {
        [SerializeField] bool _openByDefault;

        // WebGL IMGUI는 기본 폰트에 한글 글리프가 없어 영문 라벨을 쓴다.
        // 정식 UI(React/uGUI)로 넘어가면 해결되는 임시 제약.
        static readonly Dictionary<CharacterPartType, string> PartLabels = new()
        {
            { CharacterPartType.Head, "Head" },
            { CharacterPartType.Hair, "Hair" },
            { CharacterPartType.FacialHair, "Beard" },
            { CharacterPartType.Torso, "Torso" },
            { CharacterPartType.ArmUpperLeft, "Arm (upper)" },
            { CharacterPartType.ArmLowerLeft, "Arm (lower)" },
            { CharacterPartType.HandLeft, "Hands" },
            { CharacterPartType.Hips, "Hips" },
            { CharacterPartType.LegLeft, "Legs" },
            { CharacterPartType.FootLeft, "Feet" },
            { CharacterPartType.AttachmentHead, "Headgear" },
        };

        bool _open;
        Vector2 _scroll;

        void Awake() => _open = _openByDefault;

        void Update()
        {
            var kb = UnityEngine.InputSystem.Keyboard.current;
            if (kb != null && kb.cKey.wasPressedThisFrame) _open = !_open;
        }

        void OnGUI()
        {
            if (Application.isBatchMode) return;

            var nm = NetworkManager.Singleton;
            if (nm == null || !nm.IsClient) return;

            var controller = GetLocalController();
            if (controller == null) return;

            var toggleRect = new Rect(Screen.width - 190, 10, 180, 28);
            if (GUI.Button(toggleRect, _open ? "Close (C)" : "Customize (C)")) _open = !_open;

            if (_open) DrawPanel(controller);
        }

        void DrawPanel(PlayerAppearanceController controller)
        {
            var service = SidekickRuntimeService.Instance;
            var current = controller.Current;
            var visual = controller.GetComponent<PlayerAvatarVisual>();

            GUILayout.BeginArea(new Rect(Screen.width - 300, 44, 290, Screen.height - 60), GUI.skin.box);
            GUILayout.Label("<b>Character Creator</b>", Rich());

            // ── 준비 상태 ──
            if (!service.IsReady)
            {
                GUILayout.Space(8);
                GUILayout.Label(string.IsNullOrEmpty(service.LastError)
                    ? "Loading part data..."
                    : $"Load failed:\n{service.LastError}");
                GUILayout.EndArea();
                return;
            }

            GUILayout.Space(4);

            // ── 모드 전환 ──
            GUILayout.BeginHorizontal();
            if (GUILayout.Button(current.IsRuntime ? "▶ Custom" : "Custom", GUILayout.Height(24)) && !current.IsRuntime)
                controller.RequestChange(AvatarAppearance.FromParts(service.RandomParts(), current.TintHex));

            if (GUILayout.Button(!current.IsRuntime ? "▶ Preset" : "Preset", GUILayout.Height(24)) && current.IsRuntime)
                controller.RequestChange(new AvatarAppearance
                {
                    PresetCode = AvatarAppearance.DefaultPreset,
                    TintHex = current.TintHex
                });
            GUILayout.EndHorizontal();

            // 요청이 거부됐다면 이유를 화면에 보여준다 (콘솔을 안 봐도 알 수 있게, T-24)
            if (!string.IsNullOrEmpty(controller.LastRequestError))
                GUILayout.Label($"! {controller.LastRequestError}");

            GUILayout.Space(6);

            _scroll = GUILayout.BeginScrollView(_scroll);

            if (current.IsRuntime)
                DrawPartSelectors(controller, service, current);
            else
                DrawPresetSelectors(controller, visual, current);

            DrawTintPalette(controller, visual, current);

            GUILayout.EndScrollView();
            GUILayout.EndArea();
        }

        // ── 파츠 선택 (런타임 조립 모드) ──
        static void DrawPartSelectors(PlayerAppearanceController controller,
                                      SidekickRuntimeService service,
                                      AvatarAppearance current)
        {
            GUILayout.Label("<b>Parts</b>", Rich());

            if (GUILayout.Button("Randomize All", GUILayout.Height(24)))
            {
                controller.RequestChange(AvatarAppearance.FromParts(service.RandomParts(), current.TintHex));
                return;
            }

            GUILayout.Space(4);

            foreach (var kv in service.PartNames)
            {
                var type = kv.Key;
                var names = kv.Value;
                if (names.Count == 0) continue;

                // 좌우 짝의 오른쪽은 UI에 노출하지 않는다 (왼쪽 변경 시 자동 동기화)
                if (SidekickRuntimeService.MirrorPairs.ContainsValue(type)) continue;
                if (!PartLabels.TryGetValue(type, out var label)) label = type.ToString();

                current.Parts.TryGetValue((int)type, out var currentName);
                int index = Mathf.Max(0, names.IndexOf(currentName));

                GUILayout.BeginHorizontal();
                GUILayout.Label(label, GUILayout.Width(90));

                if (GUILayout.Button("<", GUILayout.Width(26)))
                    controller.RequestPartChange((int)type, names[(index - 1 + names.Count) % names.Count]);

                GUILayout.Label($"{index + 1}/{names.Count}", GUILayout.Width(46));

                if (GUILayout.Button(">", GUILayout.Width(26)))
                    controller.RequestPartChange((int)type, names[(index + 1) % names.Count]);

                GUILayout.EndHorizontal();
            }
        }

        // ── 프리셋 선택 ──
        static void DrawPresetSelectors(PlayerAppearanceController controller,
                                        PlayerAvatarVisual visual,
                                        AvatarAppearance current)
        {
            var catalog = visual != null ? visual.Catalog : null;
            if (catalog == null)
            {
                GUILayout.Label("No AvatarCatalog assigned.");
                return;
            }

            GUILayout.Label("<b>Presets</b>", Rich());

            int shown = 0;
            foreach (var entry in catalog.Entries)
            {
                if (string.IsNullOrEmpty(entry.presetCode)) continue;
                shown++;

                bool selected = entry.presetCode == current.PresetCode;
                var label = string.IsNullOrEmpty(entry.displayName) ? entry.presetCode : entry.displayName;
                if (selected) label = "▶ " + label;

                if (GUILayout.Button(label, GUILayout.Height(24)) && !selected)
                    controller.RequestChange(new AvatarAppearance
                    {
                        PresetCode = entry.presetCode,
                        TintHex = current.TintHex
                    });
            }

            if (shown == 0)
                GUILayout.Label("Catalog is empty.\nFill Preset Code fields in AvatarCatalog,\nor use Custom mode.");
        }

        // ── 색상 ──
        static void DrawTintPalette(PlayerAppearanceController controller,
                                    PlayerAvatarVisual visual,
                                    AvatarAppearance current)
        {
            var catalog = visual != null ? visual.Catalog : null;
            if (catalog == null) return;

            GUILayout.Space(10);
            GUILayout.Label("<b>Color</b>", Rich());
            GUILayout.BeginHorizontal();

            foreach (var hex in catalog.TintPalette)
            {
                bool selected = (hex ?? "") == (current.TintHex ?? "");
                var prevBg = GUI.backgroundColor;

                if (!string.IsNullOrEmpty(hex) && ColorUtility.TryParseHtmlString("#" + hex, out var c))
                    GUI.backgroundColor = c;

                var label = selected ? "●" : (string.IsNullOrEmpty(hex) ? "-" : " ");
                if (GUILayout.Button(label, GUILayout.Height(28), GUILayout.Width(38)) && !selected)
                {
                    var next = current;
                    next.TintHex = string.IsNullOrEmpty(hex) ? null : hex;
                    controller.RequestChange(next);
                }

                GUI.backgroundColor = prevBg;
            }

            GUILayout.EndHorizontal();
            GUILayout.Space(6);
            GUILayout.Label("Changes are visible to everyone instantly.\nSaving is mocked until Spring is ready.");
        }

        static GUIStyle Rich() => new(GUI.skin.label) { richText = true };

        static PlayerAppearanceController GetLocalController()
        {
            var nm = NetworkManager.Singleton;
            var playerObject = nm != null && nm.IsClient ? nm.LocalClient?.PlayerObject : null;
            return playerObject != null ? playerObject.GetComponent<PlayerAppearanceController>() : null;
        }
    }
}
