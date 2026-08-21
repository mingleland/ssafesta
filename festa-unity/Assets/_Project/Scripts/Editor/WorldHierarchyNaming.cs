using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 씬에 있는 월드 모델 인스턴스에 계층 이름 정리를 수동으로 적용한다.
    ///
    /// **평소에는 쓸 일이 없다.** 정상 경로는 <see cref="WorldModelPostprocessor"/> 로,
    /// 모델을 임포트할 때 애셋 자체가 정리된다. 이 메뉴는 두 경우를 위해 남긴다:
    ///   - 후처리기가 없던 시절에 임포트된 모델을 지금 씬에서 구제할 때
    ///   - 무엇이 어떻게 바뀔지 미리 볼 때 (미리보기 메뉴)
    ///
    /// 여기서 바꾼 이름은 **프리팹 오버라이드**라 모델을 교체하면 사라진다.
    /// 애셋을 고치고 싶으면 모델을 재임포트해서 후처리기를 태우는 쪽이 낫다
    /// (Project 창에서 `.skp` 우클릭 → Reimport).
    /// </summary>
    public static class WorldHierarchyNaming
    {
        const string WorldRootName = "@World_11F";

        [MenuItem("Festa/World/계층 이름 정리 — 씬 인스턴스에 수동 적용", false, 110)]
        static void Apply() => Run(false);

        [MenuItem("Festa/World/계층 이름 정리 — 미리보기만", false, 111)]
        static void Preview() => Run(true);

        static void Run(bool dryRun)
        {
            var world = GameObject.Find(WorldRootName);
            if (world == null) { Debug.LogError($"[WorldHierarchyNaming] '{WorldRootName}' 이 씬에 없다."); return; }

            Transform model = null;
            foreach (Transform c in world.transform)
                if (WorldModelNaming.IsWorldModel(c)) { model = c; break; }

            if (model == null)
            {
                Debug.LogError($"[WorldHierarchyNaming] '{WorldModelNaming.RoomName}' 을 가진 모델 인스턴스를 찾지 못했다.");
                return;
            }

            System.Action<GameObject> record = go => Undo.RecordObject(go, "world hierarchy naming");

            string report = WorldModelNaming.Apply(model, dryRun, record);
            int markers = WorldModelNaming.DisableScaleMarkers(model, dryRun, record);

            if (!dryRun) EditorSceneManager.MarkSceneDirty(world.scene);

            Debug.Log(
                $"[WorldHierarchyNaming] {(dryRun ? "미리보기" : "씬 인스턴스에 적용")} — {model.name}\n" +
                report +
                $"  스케일 마커 비활성 : {markers}개 (mock-floor / mock-wall)\n" +
                "  메시 이름 유일화는 애셋 작업이라 여기서 하지 않는다 — 재임포트로 후처리기를 태운다.\n" +
                (dryRun ? "" : "  씬은 저장하지 않았다 — 확인 후 직접 저장한다.\n") +
                "  여기서 바꾼 이름은 프리팹 오버라이드다. 모델 교체 시 사라진다.");
        }
    }
}
