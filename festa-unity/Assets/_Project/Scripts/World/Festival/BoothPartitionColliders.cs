// 부스 파티션(칸막이)에 콜라이더를 보장하는 자리. 없으면 사람이 벽을 그대로 통과한다.
using UnityEngine;
using UnityEngine.SceneManagement;

namespace Festa.World
{
    /// <summary>
    /// 부스 파티션 패널에 상자 콜라이더를 채워 넣는다 (사용자 지적 2026-09-16 — 파티션을 뚫고 지나간다).
    ///
    /// <para><b>왜 런타임인가.</b> 12개 부스는 씬에 직접 저작돼 있고(<c>Panel_Back_*</c>·<c>Panel_Side_*</c>·
    /// <c>Panel_Island</c>, 87장), 벤더 프리팹 <c>Panel02bCloth</c> 에는 콜라이더가 하나도 없다. 씬·프리팹 YAML 을
    /// 텍스트로 고치면 GUID 참조가 끊기므로 손대지 않는다. 대신 씬이 올라온 뒤 한 번 훑어 빠진 것만 채운다 —
    /// 이미 붙어 있으면 건드리지 않으니 나중에 에디터에서 제대로 붙여도 충돌하지 않는다.</para>
    ///
    /// <para>두께와 판정 방식은 <c>FestaInteriorBuilder</c> 가 예전 셸에 쓰던 것과 같다. 천 패널은 두께가 거의 0
    /// 이라 메시 그대로 쓰면 빠르게 달릴 때 뚫린다 — 최소 두께를 준다.</para>
    /// </summary>
    public static class BoothPartitionColliders
    {
        /// <summary>천 패널의 최소 두께(로컬 단위). 0 두께 상자는 빠른 이동에서 통과된다.</summary>
        const float MinThickness = 0.08f;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Hook()
        {
            SceneManager.sceneLoaded -= OnSceneLoaded;
            SceneManager.sceneLoaded += OnSceneLoaded;
            EnsureAll();
        }

        static void OnSceneLoaded(Scene scene, LoadSceneMode mode) => EnsureAll();

        /// <summary>이름으로 파티션을 가른다. LED 게시판(<c>ProjectPanel_LED</c>)은 전시물이라 제외한다.</summary>
        static bool IsPartition(string name) =>
            name.StartsWith("Panel_Back", System.StringComparison.Ordinal) ||
            name.StartsWith("Panel_Side", System.StringComparison.Ordinal) ||
            name.StartsWith("Panel_Island", System.StringComparison.Ordinal);

        static void EnsureAll()
        {
            int added = 0;
            foreach (var filter in Object.FindObjectsByType<MeshFilter>(FindObjectsInactive.Include, FindObjectsSortMode.None))
            {
                if (filter == null) continue;
                var go = filter.gameObject;
                if (!IsPartition(go.name)) continue;
                if (go.GetComponent<Collider>() != null) continue;
                var mesh = filter.sharedMesh;
                if (mesh == null) continue;

                var bounds = mesh.bounds;
                var size = bounds.size;
                size.x = Mathf.Max(size.x, MinThickness);
                size.y = Mathf.Max(size.y, MinThickness);
                size.z = Mathf.Max(size.z, MinThickness);

                var box = go.AddComponent<BoxCollider>();
                box.center = bounds.center;
                box.size = size;
                added++;
            }

            if (added > 0)
                Debug.Log($"[BoothPartitionColliders] 파티션 콜라이더 {added}개를 채웠다 — 씬에 저작된 패널에 콜라이더가 없었다.");
        }
    }
}
