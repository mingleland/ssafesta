using Festa.Network;
using UnityEngine;

namespace Festa.Diagnostics
{
    /// <summary>
    /// Development 빌드·에디터 전용 텔레포트 수신기 (S15P21A604-207 QA 도구).
    ///
    /// <para>광장(x≈-905)은 스폰에서 900 유닛 떨어져 있어 브라우저 키 주입으로 걸어가 검증하기 어렵다.
    /// 검증 페이지(probe.html)에서 <c>unityInstance.SendMessage('DevTeleport','TeleportTo','-905,0.2,140')</c> 또는
    /// <c>SendMessage('DevTeleport','TeleportNear','Portal_Ext_01')</c> 로 로컬 플레이어를 옮긴다. 이동은
    /// <see cref="PlayerMovement.TeleportTo"/>(포털과 같은 경로: CC 끔 → NetworkTransform.Teleport → CC 켬) 를 쓴다.</para>
    ///
    /// <para><b>릴리스 빌드에는 없다.</b> <c>Debug.isDebugBuild</c> 가 아니면 오브젝트를 만들지 않으므로 실사용자가 SendMessage 를
    /// 보내도 받을 대상이 없다(PerfHud·StressSpawner 와 같은 가드).</para>
    /// </summary>
    public sealed class DevTeleport : MonoBehaviour
    {
        public const string ObjectName = "DevTeleport";

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void AutoRegister()
        {
#if UNITY_SERVER
            return;
#else
            if (!Debug.isDebugBuild && !Application.isEditor) return;
            if (GameObject.Find(ObjectName) != null) return;
            var go = new GameObject(ObjectName);
            go.AddComponent<DevTeleport>();
            DontDestroyOnLoad(go);
            Debug.Log("[DevTeleport] 등록 — SendMessage('DevTeleport','TeleportTo','x,y,z') / ('TeleportNear','오브젝트명')");
#endif
        }

        /// <summary>"x,y,z" 월드 좌표로.</summary>
        public void TeleportTo(string csv)
        {
            var parts = (csv ?? string.Empty).Split(',');
            if (parts.Length != 3
                || !float.TryParse(parts[0], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var x)
                || !float.TryParse(parts[1], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var y)
                || !float.TryParse(parts[2], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var z))
            {
                Debug.LogWarning($"[DevTeleport] 좌표 형식 오류: '{csv}' (x,y,z)");
                return;
            }
            Move(new Vector3(x, y, z), "좌표");
        }

        /// <summary>이름으로 찾은 오브젝트 앞 1.5 m(오브젝트 forward 방향)로.</summary>
        public void TeleportNear(string objectName)
        {
            var target = GameObject.Find(objectName);
            if (target == null) { Debug.LogWarning($"[DevTeleport] 오브젝트 없음: '{objectName}'"); return; }
            var t = target.transform;
            // 포털은 상대(직원 facingSource)의 시야 안에 서야 프롬프트가 뜬다 — 그 사람 정면 1.5 m 로. (Portal_Ext_01: 포털 forward 는 +Z 인데 직원은 -Z 를 본다)
            var portal = target.GetComponent<Festa.World.BoothPortal>();
            Vector3 pos;
            if (portal != null && portal.facingSource == null)
            {
                // 상대가 없는 포털(부스 내부 출구)은 forward 가 벽 밖을 향할 수 있어 앞으로 나가면 바닥이 없다 — 포털 위에 그대로 선다(반경 안).
                pos = t.position;
            }
            else
            {
                if (portal != null) t = portal.facingSource;
                // 직원 앞 1.5 m 는 부스 구조물 BoxCollider(FestivalSlot_NN, 7 m 깊이) 안쪽이라 CC 가 아래로 밀려 바닥을 뚫는다
                // (2026-09-06 클라이언트 모드 실측 — 첫 프레임에 y -17). 앞으로 나가며 겹치는 구조물이 없는 첫 자리에 선다.
                pos = FirstFreeSpotAlong(t.position, t.forward, target.transform.position.y + 0.2f);
            }
            pos.y = target.transform.position.y + 0.2f;
            Move(pos, objectName);
        }

        /// <summary>플레이어 좌표를 콘솔에 남긴다 — WebGL 검증에서 이동·입장 결과를 읽는 유일한 창구.</summary>
        public void LogPosition(string _ = null)
        {
            PlayerMovement owner = null;
            foreach (var pm in FindObjectsByType<PlayerMovement>(FindObjectsSortMode.None))
                if (pm.IsOwner) { owner = pm; break; }
            Debug.Log(owner == null ? "[DevTeleport] 로컬 플레이어 없음" : $"[DevTeleport] pos={owner.transform.position:F1} yaw={owner.transform.eulerAngles.y:F0}");
        }

        static readonly Collider[] s_overlap = new Collider[16];

        /// <summary>origin 에서 forward 로 1.2 m 부터 6 m 까지 0.25 m 씩 나가며, 플레이어 캡슐이 바닥 외의 콜라이더와 겹치지 않는 첫 자리.</summary>
        static Vector3 FirstFreeSpotAlong(Vector3 origin, Vector3 forward, float footY)
        {
            forward.y = 0f; forward.Normalize();
            const float M = 13.26f;
            System.Func<Vector3, bool> blockedAt = (p) =>
            {
                // 플레이어 CC: 높이 16.9, 반지름 2.75 (center y 8.44)
                var a = p + Vector3.up * (8.44f - (16.88f / 2f - 2.75f)); var b = p + Vector3.up * (8.44f + (16.88f / 2f - 2.75f));
                int n = Physics.OverlapCapsuleNonAlloc(a, b, 2.75f + 0.05f, s_overlap);
                for (int i = 0; i < n; i++)
                {
                    var c = s_overlap[i];
                    if (c.isTrigger || c.bounds.size.y < 1f * M) continue;   // 바닥판은 얇다
                    if (c.GetComponentInParent<PlayerMovement>() != null) continue;
                    return true;
                }
                return false;
            };
            for (float d = 1.2f; d <= 6f; d += 0.25f)
            {
                var p = origin + forward * (d * M); p.y = footY;
                if (blockedAt(p)) continue;
                // 빈 자리를 찾았으면 구조물에 닿을 때까지 되돌아와 **가장 가까운** 빈 자리에 선다 — 상호작용 반경(직원 기준 3.1 m) 안에 들어야 한다.
                float dd = d;
                while (dd - 0.05f >= 0.5f) { var q = origin + forward * ((dd - 0.05f) * M); q.y = footY; if (blockedAt(q)) break; dd -= 0.05f; }
                var best = origin + forward * (dd * M); best.y = footY;
                return best;
            }
            var fallback = origin + forward * (6f * M); fallback.y = footY;
            Debug.LogWarning("[DevTeleport] 6 m 안에 빈 자리가 없다 — 6 m 지점으로");
            return fallback;
        }

        static void Move(Vector3 pos, string what)
        {
            PlayerMovement owner = null;
            foreach (var pm in FindObjectsByType<PlayerMovement>(FindObjectsSortMode.None))
                if (pm.IsOwner) { owner = pm; break; }
            if (owner == null) { Debug.LogWarning("[DevTeleport] 로컬 플레이어가 없다(접속 전?)"); return; }
            owner.TeleportTo(pos);
            Debug.Log($"[DevTeleport] → {what} {pos:F1}");
        }
    }
}
