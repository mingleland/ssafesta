using System.Runtime.InteropServices;
using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// React ↔ Unity 아바타 커스터마이징 브릿지.
    /// 캐릭터 생성 창(UI)은 React가 담당하고, Unity는 결과를 적용·동기화만 한다
    /// (Booth Studio와 동일한 역할 분담. 상세 계약: Docs/avatar-customization-contract.md).
    ///
    /// 씬에 이 컴포넌트를 가진 오브젝트 이름은 반드시 "AvatarBridge" 여야 한다
    /// (React가 SendMessage로 이 이름을 호출).
    /// </summary>
    public class AvatarBridge : MonoBehaviour
    {
        public const string ObjectName = "AvatarBridge";

#if UNITY_WEB && !UNITY_EDITOR
        // React 쪽 window.FestaUnity.onAvatarApplied(json) 호출용 (jslib 필요)
        [DllImport("__Internal")] static extern void FestaNotifyAvatarApplied(string json);
#endif

        /// <summary>
        /// React → Unity. 인코딩된 외형 문자열을 적용한다.
        /// 호출 예 (JS): unityInstance.SendMessage('AvatarBridge', 'ApplyAppearance', 'sk_02|c=E85D5D')
        /// </summary>
        public void ApplyAppearance(string encoded)
        {
            var controller = FindLocalController();
            if (controller == null)
            {
                Debug.LogWarning("[AvatarBridge] 로컬 플레이어 없음 — 월드 접속 후 호출하세요");
                return;
            }

            var appearance = AvatarAppearance.Decode(encoded);
            controller.RequestChange(appearance);
            NotifyApplied(appearance.Encode());
        }

        /// <summary>React → Unity. 현재 외형을 되돌려준다 (창 초기값용).</summary>
        public string GetAppearance()
        {
            var controller = FindLocalController();
            return controller != null ? controller.Current.Encode() : AvatarAppearance.Default.Encode();
        }

        /// <summary>
        /// React → Unity. 카탈로그에 등록된 프리셋 목록을 JSON으로 반환한다.
        /// React가 선택지 UI를 그릴 때 사용 (썸네일 이미지는 웹 자산으로 별도 관리).
        /// </summary>
        public string GetAvailablePresets()
        {
            var visual = FindLocalVisual();
            if (visual == null || visual.Catalog == null) return "{\"presets\":[]}";

            var sb = new System.Text.StringBuilder("{\"presets\":[");
            bool first = true;
            foreach (var e in visual.Catalog.Entries)
            {
                if (string.IsNullOrEmpty(e.presetCode)) continue;
                if (!first) sb.Append(',');
                first = false;
                var name = string.IsNullOrEmpty(e.displayName) ? e.presetCode : e.displayName;
                sb.Append($"{{\"code\":\"{e.presetCode}\",\"name\":\"{name}\"}}");
            }
            sb.Append("]}");
            return sb.ToString();
        }

        static void NotifyApplied(string encoded)
        {
#if UNITY_WEB && !UNITY_EDITOR
            FestaNotifyAvatarApplied($"{{\"avatarCode\":\"{encoded}\"}}");
#else
            Debug.Log($"[AvatarBridge] onAvatarApplied → {encoded}");
#endif
        }

        static PlayerAppearanceController FindLocalController()
        {
            var playerObject = NetworkManager.Singleton != null && NetworkManager.Singleton.IsClient
                ? NetworkManager.Singleton.LocalClient?.PlayerObject
                : null;
            return playerObject != null ? playerObject.GetComponent<PlayerAppearanceController>() : null;
        }

        static PlayerAvatarVisual FindLocalVisual()
        {
            var playerObject = NetworkManager.Singleton != null && NetworkManager.Singleton.IsClient
                ? NetworkManager.Singleton.LocalClient?.PlayerObject
                : null;
            return playerObject != null ? playerObject.GetComponent<PlayerAvatarVisual>() : null;
        }
    }
}
