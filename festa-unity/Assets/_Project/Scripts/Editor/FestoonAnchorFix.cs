using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 앞마당의 줄전구 두 가닥을 다시 켜고, 열린 서쪽 가장자리에 벽-벽 지지선을 단다.
    ///
    /// <para><b>무엇이 문제였나.</b> 전구 메시는 최적화용 통합 메시라 계속 보이지만 두 원본 루트를
    /// 끄면서 전선만 사라졌다. 또 <c>Festoon_low1</c>의 전선은 월드 좌표를 써 부모의
    /// (-11, 0, -38) 오프셋을 무시해 높은 줄과 겹쳤다.</para>
    ///
    /// <para><b>배치 원칙.</b> 높은 줄은 z=150, 낮은 줄은 z=112에서 동쪽 목재 벽에 걸고,
    /// 열린 서쪽 끝은 앞벽(z=80)과 끝벽(z=210)을 잇는 검은 지지선에 매단다.
    /// 지지선은 바닥 가장자리 위에만 놓아 통행과 시야를 막지 않는다.</para>
    /// </summary>
    public static class FestoonAnchorFix
    {
        const string ScenePath = "Assets/_Project/Scenes/main.unity";
        const string SupportName = "Festoon_West_WallSupport";
        static readonly string[] Hanging = { "Festoon_05", "Festoon_low1" };

        [MenuItem("Festa/전구 — 앞마당 줄 켜기 + 벽 지지선")]
        public static void Run()
        {
            var scene = EditorSceneManager.OpenScene(ScenePath, OpenSceneMode.Single);

            var root = FindDeep("Corridor_Festoons");
            if (root == null)
            {
                Debug.LogError("FIX: 'Corridor_Festoons'를 못 찾았다 — 씬 구조를 확인하라");
                return;
            }

            foreach (var name in Hanging)
            {
                var t = FindDeep(name);
                if (t == null)
                {
                    Debug.LogError($"FIX: '{name}'을 못 찾았다 — 씬 구조를 확인하라");
                    return;
                }

                Undo.RecordObject(t.gameObject, "Festoon enable");
                t.gameObject.SetActive(true);
                EditorUtility.SetDirty(t.gameObject);
            }

            var lowWire = FindDeep("Festoon_low1")?.Find("StringWire")?.GetComponent<LineRenderer>();
            if (lowWire == null)
            {
                Debug.LogError("FIX: 'Festoon_low1/StringWire'를 못 찾았다");
                return;
            }

            // 이 줄의 점은 원래 부모 기준 좌표다. 로컬 좌표로 해석해야 전구 통합 메시와 맞는다.
            Undo.RecordObject(lowWire, "Align low festoon wire");
            lowWire.useWorldSpace = false;
            EditorUtility.SetDirty(lowWire);

            var sourceWire = FindDeep("Festoon_05")?.Find("StringWire")?.GetComponent<LineRenderer>();
            if (sourceWire == null)
            {
                Debug.LogError("FIX: 'Festoon_05/StringWire'를 못 찾았다");
                return;
            }

            var support = root.Find(SupportName);
            if (support == null)
            {
                var go = new GameObject(SupportName);
                Undo.RegisterCreatedObjectUndo(go, "Create festoon wall support");
                support = go.transform;
                support.SetParent(root, false);
            }

            var line = support.GetComponent<LineRenderer>();
            if (line == null) line = Undo.AddComponent<LineRenderer>(support.gameObject);
            Undo.RecordObject(line, "Configure festoon wall support");
            line.useWorldSpace = true;
            line.sharedMaterial = sourceWire.sharedMaterial;
            line.startWidth = sourceWire.startWidth;
            line.endWidth = sourceWire.endWidth;
            line.alignment = sourceWire.alignment;
            line.textureMode = sourceWire.textureMode;
            line.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            line.receiveShadows = false;
            line.positionCount = 7;
            line.SetPositions(new[]
            {
                new Vector3(-219.5f, 59.5f, 79.5f),   // 앞쪽 목재 벽
                new Vector3(-222.7f, 54.0f, 88.0f),
                new Vector3(-222.7f, 46.55f, 112.0f), // 낮은 줄 매듭
                new Vector3(-222.7f, 47.5f, 131.0f),
                new Vector3(-222.7f, 57.55f, 150.0f), // 높은 줄 매듭
                new Vector3(-222.7f, 55.0f, 185.0f),
                new Vector3(-219.5f, 59.5f, 210.5f),  // 끝쪽 목재 벽
            });
            EditorUtility.SetDirty(line);
            EditorUtility.SetDirty(support.gameObject);

            // z=185 줄은 기존 시작점이 지지선보다 4.6유닛 짧아 화면에서 살짝 끊겨 보였다.
            // 같은 자리에 겹쳐 보존된 두 루트 모두 같은 매듭으로 맞춰 재실행 후에도 틈이 생기지 않게 한다.
            JoinFirstPointToSupport("Festoon_re4", new Vector3(-222.7f, 55.0f, 185.0f));
            JoinFirstPointToSupport("Festoon_low2", new Vector3(-222.7f, 55.0f, 185.0f));

            EditorSceneManager.MarkSceneDirty(scene);
            EditorSceneManager.SaveScene(scene);
            Debug.Log("FIX: 앞마당 줄전구를 켜고 낮은 전선·z=185 끝점을 정렬한 뒤 서쪽 벽 지지선을 저장했다");
        }

        /// <summary>배치 모드 진입점.</summary>
        public static void RunBatch() { Run(); EditorApplication.Exit(0); }

        static void JoinFirstPointToSupport(string rootName, Vector3 knot)
        {
            var wire = FindDeep(rootName)?.Find("StringWire")?.GetComponent<LineRenderer>();
            if (wire == null || wire.positionCount == 0)
            {
                Debug.LogError($"FIX: '{rootName}/StringWire'를 못 찾았다");
                return;
            }

            Undo.RecordObject(wire, "Join festoon to wall support");
            wire.SetPosition(0, wire.useWorldSpace ? knot : wire.transform.InverseTransformPoint(knot));
            EditorUtility.SetDirty(wire);
        }

        static Transform FindDeep(string name)
        {
            foreach (var t in Object.FindObjectsByType<Transform>(FindObjectsInactive.Include, FindObjectsSortMode.None))
                if (t.name == name) return t;
            return null;
        }
    }
}
