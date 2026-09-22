using System.Collections;
using Festa.Booth;
using Festa.Integration;
using Festa.World;
using Unity.Netcode;
using UnityEngine;

namespace Festa.Content.Arcade
{
    /// <summary>
    /// 광장 게임기 (S15P21A604-440, GitLab #56 안 1).
    ///
    /// <para>F → 카메라가 게임기 화면 앞으로 zoom-in 하고 월드 입력을 잠근 뒤 <c>WORLD_ARCADE_INTERACT {machineId}</c> 를
    /// 호스트(React)로 보낸다. 어떤 Game Studio 게임이 걸렸는지는 FE 가 machineId 로 서버에서 resolve 한다 —
    /// Unity 는 gameId 를 모르고 GameProject 를 해석하지 않는다 (spec 019 FR-017).</para>
    ///
    /// <para>FE 가 게임 오버레이를 닫으며 <c>SetInputLocked('0')</c> 을 보내면 초점이 풀린다. FE 수신부가 없어도
    /// Esc 로 언제든 나갈 수 있다 — 갇힘 방지(spec 019 FR-020).</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class ArcadeMachineInteractable : MonoBehaviour, IBoothInteractable
    {
        [Tooltip("씬이 정한 canonical id. FE 가 GET /api/v1/arcade-machines/{machineId} 로 resolve 한다.")]
        [SerializeField] string _machineId = "plaza-arcade-01";

        [Header("초점 카메라 (게임기 로컬 좌표 — 스케일 포함)")]
        // 화면 실측: 중심 로컬 (0, 1.30, 0.30), 크기 0.50 × 0.40 m. 0.5 m 앞에서 보면 화면이 시야를 거의 채운다.
        [SerializeField] Vector3 _cameraLocal = new(0f, 1.30f, 0.52f);
        [SerializeField] Vector3 _lookLocal = new(0f, 1.30f, 0.30f);

        [Header("조작 자리 (게임기 로컬 좌표 — 화면 정면이 +Z)")]
        [Tooltip("플레이어가 서는 자리. 점유 판정도 이 자리를 기준으로 한다.")]
        [SerializeField] Vector3 _standLocal = new(0f, 0f, 1.05f);
        [Tooltip("점유 판정 상자의 가로·세로 크기(m). 통로를 지나가는 사람까지 잡지 않게 좁게 잡는다.")]
        [SerializeField] Vector2 _standAreaSize = new(0.95f, 0.85f);

        public string MachineId => _machineId;

        RetroArcadeGame _localGame;

        void Awake()
        {
            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();
            if (GetComponent<BoothInteractionTarget>() == null)
                gameObject.AddComponent<BoothInteractionTarget>();
            _localGame = GetComponent<RetroArcadeGame>();
            if (_localGame == null) _localGame = gameObject.AddComponent<RetroArcadeGame>();
            _localGame.Configure(_machineId);
            BoothInteractionInput.Ensure();
        }

        /// <summary>호스트가 이 시간 안에 화면을 열지 않으면 초점을 스스로 푼다.</summary>
        const float HostResponseTimeout = 3f;

        public void Interact()
        {
            if (InteractionFocusCamera.IsFocused) return;

            var nm = NetworkManager.Singleton;
            var po = nm != null && nm.LocalClient != null ? nm.LocalClient.PlayerObject : null;
            if (IsOccupied(po, out var occupantName))
            {
                BoothInteractionInput.Toast($"{occupantName} 님이 사용 중이에요", 2f);
                return;
            }

            InteractionFocusCamera.Focus(transform, _cameraLocal, _lookLocal);
            bool openedLocally = _localGame != null && _localGame.Begin();
            if (!openedLocally) BoothInteractBridge.SendArcadeInteract(_machineId);
            if (po != null) StartCoroutine(AlignToStandSpot(po.transform));
            if (!openedLocally) StartCoroutine(ReleaseIfHostNeverAnswers());
        }

        /// <summary>조작 자리로 세우는 시간. 순간이동은 끊겨 보인다는 지적(2026-09-18)을 반영한 값이다.</summary>
        const float AlignSeconds = 0.18f;

        /// <summary>
        /// 캐릭터를 조작 자리에 세우고 화면을 보게 돌린다.
        ///
        /// <para>입력 잠금 중에는 <see cref="Festa.Network.PlayerMovement"/> 가 <c>CharacterController.Move</c> 를
        /// 건너뛰므로(T-281) 트랜스폼을 직접 옮겨도 서로 싸우지 않는다. 자리 정렬은 점유 판정의 전제이기도 하다 —
        /// 플레이 중인 사람이 이 상자 안에 있어야 다른 사람 화면에서도 "사용 중" 으로 읽힌다.</para>
        /// </summary>
        IEnumerator AlignToStandSpot(Transform player)
        {
            var target = StandPoint();
            var look = transform.position - target;
            look.y = 0f;
            var targetRot = look.sqrMagnitude > 0.0001f
                ? Quaternion.LookRotation(look.normalized, Vector3.up)
                : player.rotation;

            var fromPos = player.position;
            var fromRot = player.rotation;
            float t = 0f;
            while (t < AlignSeconds)
            {
                if (player == null || !InteractionFocusCamera.IsFocused) yield break;
                t += Time.unscaledDeltaTime;
                float k = Mathf.SmoothStep(0f, 1f, Mathf.Clamp01(t / AlignSeconds));
                var p = Vector3.Lerp(fromPos, target, k);
                p.y = player.position.y;      // 높이는 건드리지 않는다 — 바닥에 파묻히거나 뜨는 사고의 원인이다
                player.SetPositionAndRotation(p, Quaternion.Slerp(fromRot, targetRot, k));
                yield return null;
            }
        }

        /// <summary>조작 자리의 월드 좌표.</summary>
        public Vector3 StandPoint() => transform.TransformPoint(_standLocal);

        /// <summary>
        /// 이 기계를 <b>다른 사람</b>이 쓰고 있는가. 조작 자리 상자 안에서 화면을 향해 서 있으면 사용 중으로 본다.
        ///
        /// <para>위치만 보면 옆을 지나가는 사람이 자리를 막고, 방향만 보면 멀리서 기계를 바라보는 사람까지 잡힌다.
        /// 게임기에는 착석 같은 전용 이모트가 없으므로(자세가 서 있는 그대로다) 두 조건을 함께 쓴다.</para>
        /// </summary>
        public bool IsOccupied(NetworkObject self, out string occupantName)
        {
            occupantName = null;
            if (NetworkManager.Singleton == null) return false;

            float scale = Mathf.Max(transform.lossyScale.x, transform.lossyScale.z);
            float halfX = _standAreaSize.x * 0.5f * scale;
            float halfZ = _standAreaSize.y * 0.5f * scale;
            float centerY = StandPoint().y;

            foreach (var p in FindObjectsByType<Festa.Network.NetworkPlayer>(FindObjectsInactive.Exclude, FindObjectsSortMode.None))
            {
                if (p == null || (self != null && p.gameObject == self.gameObject)) continue;

                var offset = transform.InverseTransformPoint(p.transform.position) - _standLocal;
                if (Mathf.Abs(offset.x) * scale > halfX || Mathf.Abs(offset.z) * scale > halfZ) continue;
                if (Mathf.Abs(p.transform.position.y - centerY) > OccupantHeight) continue;

                // 화면을 향해 서 있는가 — 기계 정면(+Z)의 반대를 보고 있으면 마주 선 것이다.
                if (Vector3.Dot(p.transform.forward.normalized, -transform.forward) < FacingThreshold) continue;

                var nick = p.Nickname.Value.ToString();
                occupantName = string.IsNullOrWhiteSpace(nick) ? "다른 사용자" : nick;
                return true;
            }
            return false;
        }

        /// <summary>점유 판정 상자의 높이(u). 서 있는 한 사람 키다.</summary>
        const float OccupantHeight = 22.5f;

        /// <summary>화면을 향한 것으로 보는 최소 정렬도. 약 60도 안이면 마주 선 것으로 본다.</summary>
        const float FacingThreshold = 0.5f;

        /// <summary>
        /// FE 수신부가 없거나 게임 resolve 에 실패하면, 화면은 확 들어간 채 아무 일도 일어나지 않는다.
        /// 문서상 Esc 로 나갈 수 있지만 **화면에 그 안내가 없어서** 사용자는 갇혔다고 느낀다
        /// (2026-09-08 조사). 호스트가 응답하지 않으면 스스로 풀고 이유를 말한다.
        ///
        /// <para>판정 기준은 <b>호스트가 입력 잠금을 쥐었는가</b>다. 오버레이를 여는 쪽은 반드시
        /// <c>SetInputLocked('1')</c> 을 보내므로(G-8, GitLab #132), 그 주인이 나타나면 정상 진행이다.</para>
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

            Debug.LogWarning($"[Arcade] {HostResponseTimeout:F0}초 동안 호스트가 게임 화면을 열지 않았다 " +
                             $"(machineId={_machineId}). 초점을 풀고 사용자에게 알린다 — " +
                             "FE 의 WORLD_ARCADE_INTERACT 수신부를 확인해야 한다 (GitLab #135).");
            InteractionFocusCamera.Release();
            BoothInteractionInput.Toast("이 게임기는 아직 열 수 없어요", 3f);
        }
    }
}
