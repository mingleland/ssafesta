using Festa.Booth;
using Festa.Content;
using Festa.Integration;
using Festa.World;
using UnityEngine;

namespace Festa.Minigame
{
    /// <summary>
    /// 내장 미니게임 기계에 F — **화면은 FE 오버레이가 그린다** (사용자 지시 2026-09-10, GitLab #166).
    ///
    /// <para>전에는 여기서 <c>TimerStopGameHud</c>(2026-09-11 제거, #134) 를 직접 열어 Unity 안에서 10초를 세고 결과까지 그렸다.
    /// 이제 Unity 는 <c>WORLD_MINIGAME_INTERACT {gameId, machineId}</c> 만 보내고, 진행·서버 판정 호출·결과 표시는
    /// FE 가 맡는다. 광장 게임기(<see cref="Festa.Content.Arcade.ArcadeMachineInteractable"/>)와 같은 구조다.</para>
    ///
    /// <para>FE 수신부가 아직 없으므로 <b>호스트가 3초 안에 화면을 열지 않으면 스스로 초점을 풀고 알린다</b> —
    /// 화면이 확 들어간 채 아무 일도 없으면 사용자는 갇혔다고 느낀다(2026-09-08 조사, spec 019 FR-020).
    /// 조용히 옛 HUD 로 되돌아가지 않는다: 그러면 기록·보상이 남는 줄 알게 된다(T-24).</para>
    ///
    /// <para><b>월드를 건드리지 않는다</b> (spec 014 FR-007). 실패하거나 중간에 나가도 월드 접속·플레이어 상태에 영향이 없다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class MinigameInteractable : MonoBehaviour, IBoothInteractable
    {
        /// <summary>FE 가 어떤 화면을 열지 고르는 식별자. 계약값 — 바꾸면 FE 수신부가 깨진다(#166).</summary>
        [SerializeField] string _gameId = "TIMER_STOP";

        [Tooltip("씬이 정한 canonical id. FE 는 해석하지 않고 로그·분석에만 쓴다")]
        [SerializeField] string _machineId = "lounge-timer-stop-01";

        // 화면 실측(2026-09-10, `Arcade_Screen_Material_URP` 서브메시): 월드 x −164.4~−162.5 · y 20.1~26.4 ·
        // z −308.6~−300.0. 기계 로컬(회전 90°·스케일 1.33)로 옮기면 화면 중심이 (0.23, 17.5, −0.3) 이다.
        //
        // **미터가 아니라 월드 유닛이다**(1 m ≈ 13.26). 처음에는 (0, 1.45, 1.9) 를 넣어 두어 카메라가
        // 기계 발치 바닥으로 들어갔다(사용자 지적 2026-09-10).
        [Header("초점 카메라 (기계 로컬 좌표 — 월드 유닛, 스케일 포함)")]
        [SerializeField] Vector3 _cameraLocal = new(0.23f, 18.8f, 13.5f);
        [SerializeField] Vector3 _lookLocal = new(0.23f, 17.5f, -0.3f);

        /// <summary>호스트가 이 시간 안에 화면을 열지 않으면 초점을 스스로 푼다.</summary>
        const float HostResponseTimeout = 3f;

        void Awake()
        {
            // 레이캐스트 대상 보장 — 기계에 콜라이더가 없을 수 있다.
            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();
            if (GetComponent<BoothInteractionTarget>() == null)
                gameObject.AddComponent<BoothInteractionTarget>();

            BoothInteractionInput.Ensure();
        }

        public void Interact()
        {
            if (InteractionFocusCamera.IsFocused) return;
            InteractionFocusCamera.Focus(transform, _cameraLocal, _lookLocal);
            BoothInteractBridge.SendMinigameInteract(_gameId, _machineId);
            StartCoroutine(ReleaseIfHostNeverAnswers());
        }

        /// <summary>
        /// 판정 기준은 <b>호스트가 입력 잠금을 쥐었는가</b>다. 오버레이를 여는 쪽은 반드시
        /// <c>SetInputLocked('1')</c> 을 보내므로(G-8, GitLab #132), 그 주인이 나타나면 정상 진행이다.
        /// </summary>
        System.Collections.IEnumerator ReleaseIfHostNeverAnswers()
        {
            float deadline = Time.unscaledTime + HostResponseTimeout;
            while (Time.unscaledTime < deadline)
            {
                if (!InteractionFocusCamera.IsFocused) yield break;                    // 사용자가 Esc 로 나갔다
                if (InputBridge.HoldersDescription().Contains("host")) yield break;    // 호스트가 화면을 열었다
                yield return null;
            }
            if (!InteractionFocusCamera.IsFocused) yield break;

            Debug.LogWarning($"[Minigame] {HostResponseTimeout:F0}초 동안 호스트가 게임 화면을 열지 않았다 " +
                             $"(gameId={_gameId}, machineId={_machineId}). 초점을 풀고 사용자에게 알린다 — " +
                             "FE 의 WORLD_MINIGAME_INTERACT 수신부를 확인해야 한다 (GitLab #166).");
            InteractionFocusCamera.Release();
            BoothInteractionInput.Toast("이 게임은 곧 화면으로 열려요", 3f);
        }
    }
}
