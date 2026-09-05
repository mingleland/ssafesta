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
            var pos = t.position + t.forward * 20f;   // 1 m ≈ 13.26 유닛
            pos.y = t.position.y + 0.2f;
            Move(pos, objectName);
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
