using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 앞마당에서 <b>허공에 끊긴 채 매달린 줄전구</b>를 끈다 (2026-09-14 사용자 지적).
    ///
    /// <para><b>무엇이 문제였나.</b> <c>Corridor_Festoons</c> 의 <c>Festoon_05</c>·<c>Festoon_low1</c> 은
    /// 앞마당 통로 <b>중앙선(z 150)을 따라 세로로</b> 뻗는다. 앞마당은 양옆이 뚫려 있어 이 방향으로는
    /// 줄 끝에 걸 것이 없고, 그래서 바닥에서 45유닛(3.4 m) 뜬 허공에서 줄이 그냥 끊긴다.</para>
    ///
    /// <para><b>길이 조절로는 못 고친다 — 둘 다 해 보고 되돌렸다.</b>
    /// ① 서쪽 가로등 줄(x=-236)까지 <b>늘리기</b>: 쏴 보니 z=150 지점에는 메시가 없었다(합쳐진 메시의
    /// bounds 만 그 z 를 덮고 있었다) — 늘려도 허공이다.
    /// ② 복도 안(x=-133)까지 <b>줄이기</b>: 적용해 렌더로 확인했더니 끝나는 자리가 103유닛 앞에서
    /// 13유닛 앞으로 당겨졌을 뿐 <b>여전히 허공에서 끊겼다</b> — 오히려 더 눈에 띈다.
    /// 세로로 뻗는 줄은 길이를 어떻게 바꿔도 양 끝이 허공이라는 것이 결론이다.</para>
    ///
    /// <para><b>기둥을 세우지 않는 이유.</b> 기둥은 z=92·105·185·200 에 서 있고 z=150 은 통로 한가운데다.
    /// 거기 세우면 지나다니는 길을 막는다(사용자 조건: "통로랑 시야 안 막으면서").</para>
    ///
    /// <para><b>그래서 끈다.</b> 이 구역은 <b>통로를 가로질러</b> 양옆 기둥 사이에 매인 줄들
    /// (<c>Festival_Festoons</c>)이 이미 밝히고 있어 없어도 허전하지 않다. 게다가 두 개는
    /// <b>월드 bounds 가 완전히 동일하다</b> — 같은 자리에 겹친 복제본이다.</para>
    ///
    /// <para><b>지우지 않고 비활성화한다.</b> 되돌릴 때 체크박스 하나면 되고, 판단이 틀렸을 때
    /// 원본을 잃지 않는다.</para>
    /// </summary>
    public static class FestoonAnchorFix
    {
        const string ScenePath = "Assets/_Project/Scenes/main.unity";
        static readonly string[] Floating = { "Festoon_05", "Festoon_low1" };

        [MenuItem("Festa/전구 — 허공에 끊긴 앞마당 줄 끄기")]
        public static void Run()
        {
            var scene = EditorSceneManager.OpenScene(ScenePath, OpenSceneMode.Single);

            int done = 0;
            foreach (var name in Floating)
            {
                var t = FindDeep(name);
                if (t == null) { Debug.LogWarning($"FIX: '{name}' 못 찾음 — 이름이 바뀌었는지 확인하라"); continue; }
                if (!t.gameObject.activeSelf) { Debug.Log($"FIX: '{name}' 이미 꺼져 있다"); continue; }

                Bounds? bb = null;
                foreach (var r in t.GetComponentsInChildren<Renderer>(true))
                { if (bb == null) bb = r.bounds; else { var v = bb.Value; v.Encapsulate(r.bounds); bb = v; } }

                Undo.RecordObject(t.gameObject, "Festoon disable");
                t.gameObject.SetActive(false);
                EditorUtility.SetDirty(t.gameObject);
                done++;
                Debug.Log($"FIX: '{name}' 껐다" + (bb == null ? "" : $" (bounds {bb.Value.min} ~ {bb.Value.max})"));
            }

            if (done > 0)
            {
                EditorSceneManager.MarkSceneDirty(scene);
                EditorSceneManager.SaveScene(scene);
                Debug.Log($"FIX: {done}개 끄고 씬을 저장했다");
            }
            else Debug.Log("FIX: 바꾼 것이 없다");
        }

        /// <summary>배치 모드 진입점.</summary>
        public static void RunBatch() { Run(); EditorApplication.Exit(0); }

        static Transform FindDeep(string name)
        {
            foreach (var t in Object.FindObjectsByType<Transform>(FindObjectsSortMode.None))
                if (t.name == name) return t;
            return null;
        }
    }
}
