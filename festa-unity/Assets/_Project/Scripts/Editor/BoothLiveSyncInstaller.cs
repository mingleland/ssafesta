using Festa.Booth;
using Festa.Network;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 플레이어 프리팹에 <see cref="BoothLiveSyncNetwork"/> 를 붙인다 (S15P21A604-716).
    ///
    /// <para><c>NetworkBehaviour</c> 는 스폰 시점에 <c>NetworkObject</c> 위에 있어야 한다 — ILPP 가
    /// 프리팹의 컴포넌트 순서로 RPC 색인을 굽기 때문에 런타임 <c>AddComponent</c> 로는 대체할 수 없다.
    /// 그래서 프리팹 자산을 고쳐야 하고, 에디터를 띄우지 않고도 되도록 배치 진입점을 둔다.</para>
    ///
    /// <para><b>있으면 아무 일도 하지 않는다.</b> 두 번 돌려도 컴포넌트가 둘로 늘지 않는다 —
    /// 늘면 같은 RPC 가 두 번 도착한다.</para>
    /// </summary>
    public static class BoothLiveSyncInstaller
    {
        const string PrefabPath = "Assets/_Project/Prefabs/Player/PlayerAvatar.prefab";

        [MenuItem("Festa/부스 — 플레이어 프리팹에 라이브 동기화 붙이기")]
        public static void Run()
        {
            var go = AssetDatabase.LoadAssetAtPath<GameObject>(PrefabPath);
            if (go == null) { Debug.LogError($"INSTALL: 프리팹을 못 찾음 — {PrefabPath}"); return; }

            if (go.GetComponent<NetworkPlayer>() == null)
            {
                Debug.LogError("INSTALL: 루트에 NetworkPlayer 가 없다 — 붙일 자리가 맞는지 확인하라");
                return;
            }

            if (go.GetComponent<BoothLiveSyncNetwork>() != null)
            {
                Debug.Log("INSTALL: BoothLiveSyncNetwork 가 이미 붙어 있다 — 그대로 둔다");
                return;
            }

            var root = PrefabUtility.LoadPrefabContents(PrefabPath);
            try
            {
                if (root.GetComponent<BoothLiveSyncNetwork>() == null) root.AddComponent<BoothLiveSyncNetwork>();
                PrefabUtility.SaveAsPrefabAsset(root, PrefabPath);
            }
            finally { PrefabUtility.UnloadPrefabContents(root); }

            AssetDatabase.SaveAssets();

            var check = AssetDatabase.LoadAssetAtPath<GameObject>(PrefabPath);
            bool ok = check != null && check.GetComponent<BoothLiveSyncNetwork>() != null;
            Debug.Log(ok
                ? "INSTALL: BoothLiveSyncNetwork 를 PlayerAvatar 프리팹에 붙이고 저장했다"
                : "INSTALL: 저장했는데 다시 읽으니 없다 — 실패다");
        }

        /// <summary>배치 진입점. 붙는 데 실패하면 0 이 아닌 코드로 끝낸다.</summary>
        public static void RunBatch()
        {
            Run();
            var check = AssetDatabase.LoadAssetAtPath<GameObject>(PrefabPath);
            bool ok = check != null && check.GetComponent<BoothLiveSyncNetwork>() != null;
            EditorApplication.Exit(ok ? 0 : 1);
        }
    }
}
