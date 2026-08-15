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

        bool _open;

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
            var current = controller.Current;
            var visual = controller.GetComponent<PlayerAvatarVisual>();

            GUILayout.BeginArea(new Rect(Screen.width - 300, 44, 290, Screen.height - 60), GUI.skin.box);
            GUILayout.Label("<b>Character Creator</b>", Rich());

            GUILayout.Label("정식 커스터마이징은 로비 또는 웹 오버레이에서 변경됩니다.");
            GUILayout.Label("변경한 외형은 같은 월드의 모든 사용자에게 즉시 동기화됩니다.");
            if (!string.IsNullOrEmpty(controller.LastRequestError))
                GUILayout.Label($"! {controller.LastRequestError}");
            if (current.IsModular) GUILayout.Label("현재 외형: Rukha93 모듈 아바타");
            else GUILayout.Label("현재 외형: 이전 프리셋 (새 외형 선택 시 자동 전환)");
            GUILayout.EndArea();
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
