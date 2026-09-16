// ESC 메뉴 '아바타 설정': 월드 씬·NGO 연결·플레이어 NetworkObject·위치를 그대로 둔 채 커스터마이징 씬을 위에 얹고 내리는 장치 (GitLab #197).
using Unity.Netcode;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace Festa.World
{
    /// <summary>
    /// 월드를 떠나지 않는 커스터마이징 진입.
    ///
    /// <para><b>왜 재접속하지 않나.</b> 옛 경로(<c>DevConnectionHud.ReturnToCustomization</c>)는 연결을 끊고 로비로 갔다가
    /// 다시 접속한다 — 커스터마이징을 열었다 닫는 것만으로 스폰 복귀가 재현된다(#197). 헌법 5조대로 외형은 식별자만
    /// 동기화하고 3D 는 각자 로컬 생성이라, 외형 교체에 네트워크 재협상이 필요 없다.
    /// <see cref="PlayerAppearanceController.RequestChange"/> 한 번이면 같은 Player 에 외형만 바뀐다.</para>
    ///
    /// <para><b>어떻게.</b> <c>CharacterLobby</c> 씬을 <b>Additive</b> 로 얹는다. 로비 컨트롤러가 인플레이스 모드를 감지해
    /// 무대 전체를 <see cref="StageOrigin"/> 으로 옮기고(월드 지오메트리와 겹치지 않게), 월드 카메라는 끄고 프리뷰 카메라가
    /// 그린다. 이동 입력은 <see cref="Festa.Integration.InputBridge"/> 로 잠근다. 닫을 때 씬을 내리고 카메라·입력을 되돌린다.</para>
    ///
    /// <para><b>동결 준수.</b> <c>NetworkPlayer.cs</c>·<c>ConnectionManager.cs</c> 는 건드리지 않는다 — 외형 반영은 이미 있는
    /// <see cref="PlayerAppearanceController"/>(별도 NetworkBehaviour)가 한다.</para>
    /// </summary>
    public static class AvatarInPlaceCustomization
    {
        public const string LockOwner = "avatar-customization";

        /// <summary>
        /// 로비 무대를 옮겨 둘 자리. 월드 원점 근처에는 축제장 지오메트리가 있어 그대로 얹으면 프리뷰 카메라에 벽이 찍힌다.
        /// 아래로 4000u — 월드에서 쓰지 않는 영역이다.
        /// </summary>
        public static readonly Vector3 StageOrigin = new Vector3(0f, -4000f, 0f);

        /// <summary>열려 있거나 여는 중이면 true. 로비 컨트롤러가 Awake 에서 이 값으로 인플레이스 모드를 결정한다.</summary>
        public static bool IsOpen { get; private set; }

        static Camera s_worldCamera;
        static bool s_worldCameraWasEnabled;

        /// <summary>
        /// 연다. 실패하면 false 와 이유 — 조용히 넘기지 않는다. 게스트는 커스터마이징을 쓰지 않는다(S15P21A604-437).
        /// </summary>
        public static bool TryOpen(string reason, out string error)
        {
            error = null;
            if (IsOpen) { error = "이미 열려 있다"; return false; }

            var nm = NetworkManager.Singleton;
            var player = nm != null && nm.IsClient ? nm.LocalClient?.PlayerObject : null;
            if (player == null) { error = "월드에 접속한 로컬 플레이어가 없다"; return false; }
            if (player.GetComponent<PlayerAppearanceController>() == null) { error = "플레이어에 PlayerAppearanceController 가 없다"; return false; }
            if (Festa.Integration.AuthBridge.IsGuest) { error = "게스트는 커스터마이징을 쓰지 않는다 (S15P21A604-437)"; return false; }
            if (SceneManager.GetSceneByName(AvatarSceneHandoff.LobbySceneName).isLoaded) { error = "로비 씬이 이미 올라와 있다"; return false; }

            IsOpen = true;
            s_worldCamera = Camera.main;
            s_worldCameraWasEnabled = s_worldCamera != null && s_worldCamera.enabled;
            Festa.Integration.InputBridge.SetLocked(true, LockOwner);
            Debug.Log($"[AvatarInPlace] 열기 ({reason}) — 로비 씬 additive 로드");

            var op = SceneManager.LoadSceneAsync(AvatarSceneHandoff.LobbySceneName, LoadSceneMode.Additive);
            if (op == null)
            {
                Debug.LogError("[AvatarInPlace] 로비 씬을 additive 로 올리지 못했다 — 빌드 씬 목록을 확인하라");
                Close("load-failed");
                error = "로비 씬 로드 실패";
                return false;
            }
            Festa.Integration.WorldUiBridge.Publish();
            return true;
        }

        /// <summary>로비 컨트롤러 Awake 가 부른다 — 월드 카메라를 끄고 프리뷰 카메라에 화면을 넘긴다.</summary>
        public static void OnLobbyAwake()
        {
            if (!IsOpen) return;
            if (s_worldCamera != null) s_worldCamera.enabled = false;
        }

        /// <summary>
        /// 닫는다. 씬을 내리고 카메라·입력 잠금을 되돌린다. 외형 적용은 호출자(로비의 "적용하고 돌아가기")가 먼저 한다.
        /// 멱등 — 닫혀 있으면 아무 일도 하지 않는다.
        /// </summary>
        public static void Close(string reason)
        {
            if (!IsOpen) return;
            IsOpen = false;

            var scene = SceneManager.GetSceneByName(AvatarSceneHandoff.LobbySceneName);
            if (scene.isLoaded) SceneManager.UnloadSceneAsync(scene);

            if (s_worldCamera != null) s_worldCamera.enabled = s_worldCameraWasEnabled;
            s_worldCamera = null;
            Festa.Integration.InputBridge.SetLocked(false, LockOwner);
            Debug.Log($"[AvatarInPlace] 닫기 ({reason})");
            Festa.Integration.WorldUiBridge.Publish();
        }
    }
}
