// 주먹이 닿은 사람을 아주 살짝 밀어내는 경로 (사용자 지시 2026-09-15).
// 이 파일이 있는 이유: 이동은 클라이언트 권위라 **남의 몸을 내가 직접 옮길 수 없다.** 맞은 사람의
// 소유 클라이언트가 자기 컨트롤러로 밀려나야 양쪽 화면이 갈라지지 않는다. 그 왕복만 여기서 맡는다.
using Unity.Netcode;
using UnityEngine;

namespace Festa.Network
{
    /// <summary>
    /// 주먹질 적중 판정과 밀림 전달.
    ///
    /// <para><b>피해는 없다.</b> 사용자가 요구한 것은 "앞에 사람이 있으면 아주 살짝 밀린다" 하나이고,
    /// 체력·판정·경직은 만들지 않는다. 밀림은 0.12초 동안 18 u/s — 약 2.2 u(17 cm)다.</para>
    ///
    /// <para><b>왕복이 필요한 이유.</b> 때린 사람이 맞은 사람의 위치를 직접 바꾸면 그 순간만 내 화면에서
    /// 밀리고 정작 맞은 사람 화면에서는 아무 일도 없다 — <c>NetworkTransform</c> 이 소유자 값으로 곧
    /// 되돌린다. 그래서 서버를 거쳐 <b>맞은 사람의 소유 클라이언트</b>에게 밀림을 부탁한다.</para>
    ///
    /// <para>서버는 거리만 다시 본다. 클라이언트가 보낸 대상·방향을 그대로 믿으면 멀리서도 남을 밀 수 있다 —
    /// 판정 자체를 서버로 옮기는 것은 이 기능의 값어치에 비해 과하고, 거리 검사 한 줄이면 그 남용은 막힌다.</para>
    /// </summary>
    [RequireComponent(typeof(NetworkPlayer))]
    [DisallowMultipleComponent]
    public sealed class PlayerPunchImpact : NetworkBehaviour
    {
        /// <summary>주먹이 닿는다고 볼 거리(u). 캡슐 반지름이 2.5 라 서로 붙어 선 거리가 5 안팎이다.</summary>
        const float Reach = 11f;

        /// <summary>정면으로 인정할 각도(도). 옆·뒤 사람까지 밀면 "왜 밀렸는지" 를 알 수 없다.</summary>
        const float ReachAngle = 55f;

        /// <summary>서버가 다시 보는 최대 거리(u). 지연을 감안해 판정 거리보다 넉넉히 둔다.</summary>
        const float ServerMaxReach = 26f;

        const float PushSpeed = 18f;      // u/s — 1 m = 13.26 u 이므로 초속 1.4 m 짜리 가벼운 밀림
        const float PushSeconds = 0.12f;  // 합쳐서 약 2.2 u(17 cm)

        /// <summary>가슴 높이(u). 발밑에서 재면 계단·턱에 선 사람이 빠진다.</summary>
        const float ChestHeight = 11f;

        /// <summary>소유자가 주먹의 임팩트 순간에 부른다. 앞에 사람이 있으면 그 사람을 살짝 민다.</summary>
        public void Strike()
        {
            if (!IsOwner) return;

            var origin = transform.position + Vector3.up * ChestHeight;
            var forward = transform.forward;

            PlayerPunchImpact best = null;
            float bestDistance = float.MaxValue;

            foreach (var other in FindObjectsByType<PlayerPunchImpact>(FindObjectsSortMode.None))
            {
                if (other == null || other == this) continue;
                var delta = (other.transform.position + Vector3.up * ChestHeight) - origin;
                float distance = delta.magnitude;
                if (distance > Reach || distance < 0.01f) continue;

                var flat = delta; flat.y = 0f;
                if (flat.sqrMagnitude < 1e-4f) continue;
                if (Vector3.Angle(forward, flat.normalized) > ReachAngle) continue;

                if (distance < bestDistance) { bestDistance = distance; best = other; }
            }

            if (best == null) return;

            var push = best.transform.position - transform.position;
            push.y = 0f;
            if (push.sqrMagnitude < 1e-4f) push = forward;
            PushServerRpc(best.NetworkObjectId, push.normalized);
        }

        [ServerRpc]
        void PushServerRpc(ulong targetObjectId, Vector3 direction, ServerRpcParams _ = default)
        {
            if (NetworkManager == null || NetworkManager.SpawnManager == null) return;
            if (!NetworkManager.SpawnManager.SpawnedObjects.TryGetValue(targetObjectId, out var target) || target == null) return;

            // 거리만 다시 본다 — 멀리서 남을 미는 것을 막는 최소한의 확인이다.
            if (Vector3.Distance(target.transform.position, transform.position) > ServerMaxReach) return;

            var impact = target.GetComponent<PlayerPunchImpact>();
            if (impact == null) return;

            direction.y = 0f;
            if (direction.sqrMagnitude < 1e-4f) return;

            impact.PushClientRpc(direction.normalized, new ClientRpcParams
            {
                Send = new ClientRpcSendParams { TargetClientIds = new[] { target.OwnerClientId } }
            });
        }

        [ClientRpc]
        void PushClientRpc(Vector3 direction, ClientRpcParams _ = default)
        {
            if (!IsOwner) return;   // 밀리는 것은 자기 컨트롤러로만 한다
            GetComponent<PlayerMovement>()?.ApplyExternalPush(direction, PushSpeed, PushSeconds);
        }
    }
}
