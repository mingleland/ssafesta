using Festa.World;
using TMPro;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 축제장 하이 스트라이커(망치 게임)에 움직이는 부품을 세운다 (S15P21A604-585).
    ///
    /// <para>벤더 메시 <c>SM_High_Striker_Bell_Tower</c> 는 정적 한 덩이다 — 퍽도 별도 램프도 따로 없다.
    /// 그래서 퍽·별 8개·점수판을 실측 위치에 얹고 <see cref="HighStrikerMachine"/> 에 물린다.
    /// <b>벤더 프리팹은 고치지 않는다</b>(에셋 원본 수정 금지) — 부품은 형제 루트 <c>@HighStriker_01</c> 아래에 둔다.</para>
    ///
    /// <para>실측(2026-09-10, 프리팹 로컬 미터 · 월드 배율 13.26):
    /// 기둥 |x| ≤ 0.19 · 전면 z = −0.100 · y 0.6~2.7 / 안내판 |x| 0.35~0.70 · 전면 z ≈ 0.00 /
    /// 타격 레버 (0, 0.37, 0.58) / 받침 |x| ≤ 0.65. 루트 회전 Y=90 이라 <b>로컬 +z 가 월드 +x</b>,
    /// 즉 사람이 서는 쪽이다. 사람이 볼 때의 오른쪽은 로컬 −x 라 별 띠를 그쪽에 세운다.</para>
    /// </summary>
    public static class FestaHighStrikerBuilder
    {
        const string MatDir = "Assets/_Project/Art/World/Materials/";
        const string MachineName = "PF_High_Striker_Bell_Tower_Game_V1";
        const string MachineId = "plaza-high-striker-01";

        // 램프는 **기둥 면 위**에 얹는다. 기둥 옆(로컬 x −0.245)에 레일을 세워 봤더니 벽돌 벽·벽 램프와 겹쳐
        // 따로 노는 막대처럼 보였다(2026-09-10 캡처 2회). 벤더 기둥에는 이미 별·눈금(60·70·80)이 인쇄돼 있으니,
        // 그 위에 발광 원반을 겹쳐 "기둥의 등이 켜진다" 로 읽히게 한다. 퍽은 그 앞을 지나간다.
        // 램프는 **기둥 정중앙**에 얹는다. 기둥은 y=25 에서 월드 z 142.9~147.1(4.2 u)로 좁아서,
        // 로컬 x −0.08 에 두자 램프가 오른쪽 끝에 반쯤 걸쳐 벽돌 벽 위로 삐져나왔다(사용자 지적 "분리되지 않게").
        // 실측(2026-09-10 플레이 모드 화면좌표): 기둥 중심 = 메시 로컬 x 0.000, 전면 z −0.106.
        const float StarX = 0f;
        const float StarZ = -0.068f;         // 기둥 전면(−0.106)에서 4 cm 앞 — 붙이면 z-파이팅으로 검게 나온다
        const float StarDiameter = 0.22f;    // 기둥 폭 0.32 m 안에 들어가는 최대치. 0.115 는 멀리서 안 보였다
        const float StarBottomY = 0.80f, StarTopY = 2.52f;
        const int StarCount = 8;

        const float PuckRestY = 0.62f, PuckTopY = 2.55f;
        const float ColumnFrontZ = -0.03f;   // 램프보다 앞 — 퍽이 등 위를 스치며 오른다
        const float BoardY = 3.70f;          // 3.35 에서는 꼭대기 별 장식에 글자가 가렸다

        [MenuItem("Festa/World/하이 스트라이커 부품 생성")]
        public static void Rebuild()
        {
            if (Application.isPlaying) { Debug.LogWarning("[HighStriker] Play 중에는 저장 안 됨 — 종료 후 실행"); return; }

            var machine = GameObject.Find(MachineName);
            if (machine == null) { Debug.LogError($"[HighStriker] 씬에 {MachineName} 이 없다"); return; }

            var old = GameObject.Find("@HighStriker_01");
            if (old != null) Object.DestroyImmediate(old);

            var root = new GameObject("@HighStriker_01");
            root.transform.SetParent(machine.transform.parent, false);
            root.transform.position = machine.transform.position;
            root.transform.rotation = machine.transform.rotation;
            root.transform.localScale = machine.transform.localScale;   // 부품 좌표를 메시 로컬(미터)과 같게 맞춘다

            var puckMat = Mat("StrikerPuck", new Color(0.62f, 0.64f, 0.68f), 0.72f);
            var starOff = Mat("StrikerStarOff", new Color(0.30f, 0.13f, 0.11f), 0.2f);
            var starOn = EmissiveMat("StrikerStarOn", new Color(1f, 0.85f, 0.30f), 3.0f);
            var boardMat = Mat("StrikerBoard", new Color(0.09f, 0.10f, 0.14f), 0.25f);
            var frameMat = Mat("StrikerFrame", new Color(0.63f, 0.13f, 0.14f), 0.35f);   // 기계 몸통과 같은 붉은색

            // ① 퍽 — 기둥 전면을 타고 오르내린다.
            var puck = Box(root.transform, "Puck", new Vector3(0f, PuckRestY, ColumnFrontZ),
                           new Vector3(0.40f, 0.17f, 0.05f), puckMat);   // 0.09 m 두께는 멀리서 실처럼 보였다

            // ② 별 8개 — 기둥 면 위, 아래에서 위로. 켜짐/꺼짐은 머티리얼 교체다
            // (오브젝트를 껐다 켜면 배칭이 깨진다).
            var starRoot = new GameObject("Stars").transform;
            starRoot.SetParent(root.transform, false);
            starRoot.localPosition = Vector3.zero;
            for (var i = 0; i < StarCount; i++)
            {
                float y = Mathf.Lerp(StarBottomY, StarTopY, i / (float)(StarCount - 1));
                Disc(starRoot, $"Star_{i}", new Vector3(StarX, y, StarZ), StarDiameter, starOff);
            }

            // ③ 점수판 — 기계 꼭대기(별 장식 y ≈ 3.1) 위. 3.35 에서는 별 장식에 글자가 가렸다.
            // 테두리를 한 겹 둘러 검은 판때기로 보이지 않게 한다.
            // 폭 1.52 m 는 기둥(0.32 m)의 다섯 배라 공중에 뜬 검은 슬래브처럼 보였다 — 기계 폭에 맞춰 줄인다.
            Box(root.transform, "ScoreBoardFrame", new Vector3(0f, BoardY, -0.09f), new Vector3(0.98f, 0.56f, 0.05f), frameMat);
            Box(root.transform, "ScoreBoard", new Vector3(0f, BoardY, -0.07f), new Vector3(0.88f, 0.46f, 0.04f), boardMat);
            var textGo = new GameObject("ScoreText", typeof(RectTransform));
            textGo.transform.SetParent(root.transform, false);
            // 판 앞면(z −0.03)보다 **앞**에 둔다. 뒤로 보내면 판에 가려 글자가 사라진다(첫 배치의 실수).
            textGo.transform.localPosition = new Vector3(0f, BoardY, -0.04f);
            // 글자는 카메라가 글자의 -z 쪽에서 볼 때 바로 읽힌다. 사람은 로컬 +z 쪽에 서므로 **180°** —
            // identity 로 두면 거울처럼 뒤집혀 보인다(사용자 지적 2026-09-10, 부스 파샤와 같은 함정).
            textGo.transform.localRotation = Quaternion.Euler(0f, 180f, 0f);
            var tmp = textGo.AddComponent<TextMeshPro>();
            var font = Resources.Load<TMP_FontAsset>("Fonts/NotoSansKRBold_SDF");
            if (font != null) tmp.font = font;
            tmp.text = "기록 없음";
            tmp.color = new Color(1f, 0.87f, 0.45f);
            tmp.alignment = TextAlignmentOptions.Center;
            tmp.textWrappingMode = TextWrappingModes.NoWrap;
            tmp.overflowMode = TextOverflowModes.Overflow;
            tmp.fontSize = 1.5f;              // fontSize 10 = 1 unit → 0.15 m 글자 두 줄이 0.46 m 판에 들어간다
            tmp.lineSpacing = -12f;
            tmp.rectTransform.sizeDelta = new Vector2(0.84f, 0.44f);
            var tr = textGo.GetComponent<MeshRenderer>();
            tr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            tr.receiveShadows = false;

            // ④ 컴포넌트 배선
            var hsm = root.AddComponent<HighStrikerMachine>();
            var so = new SerializedObject(hsm);
            so.FindProperty("_machineId").stringValue = MachineId;
            so.FindProperty("_puckRestY").floatValue = PuckRestY;
            so.FindProperty("_puckTopY").floatValue = PuckTopY;
            so.FindProperty("_puck").objectReferenceValue = puck.transform;
            so.FindProperty("_starRoot").objectReferenceValue = starRoot;
            so.FindProperty("_scoreText").objectReferenceValue = tmp;
            so.FindProperty("_starOffMaterial").objectReferenceValue = starOff;
            so.FindProperty("_starOnMaterial").objectReferenceValue = starOn;
            so.ApplyModifiedPropertiesWithoutUndo();

            // ⑤ 상호작용은 **벤더 기계 본체**에 붙인다 — 사람이 다가가는 대상이 그것이라 사거리·외곽선이 자연스럽다.
            var interact = machine.GetComponent<HighStrikerInteractable>() ?? machine.AddComponent<HighStrikerInteractable>();
            var iso = new SerializedObject(interact);
            iso.FindProperty("_machine").objectReferenceValue = hsm;
            iso.ApplyModifiedPropertiesWithoutUndo();
            if (machine.GetComponent<Festa.Booth.BoothInteractionTarget>() == null)
                machine.AddComponent<Festa.Booth.BoothInteractionTarget>();

            EditorSceneManagerMarkDirty(root);
            Debug.Log($"[HighStriker] 부품 생성 — 퍽 y {PuckRestY:F2}~{PuckTopY:F2} m, 별 {StarCount}개(x {StarX:F2}), 점수판 y 3.35 m — 씬 저장 필요");
        }

        static void EditorSceneManagerMarkDirty(GameObject go) =>
            UnityEditor.SceneManagement.EditorSceneManager.MarkSceneDirty(go.scene);

        static GameObject Box(Transform parent, string name, Vector3 localPos, Vector3 localSize, Material mat)
        {
            var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
            go.name = name;
            Object.DestroyImmediate(go.GetComponent<Collider>());   // 부품은 전부 장식 — 사람이 걸리면 안 된다
            go.transform.SetParent(parent, false);
            go.transform.localPosition = localPos;
            go.transform.localScale = localSize;
            var r = go.GetComponent<Renderer>();
            r.sharedMaterial = mat;
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            // **정적 플래그를 달지 않는다.** OccludeeStatic 은 베이크된 오클루전 데이터에 등록된 오브젝트에만
            // 유효하다 — 베이크 뒤에 만든 오브젝트에 붙이면 데이터에 항목이 없어 **어느 카메라에서도 컬링된다**
            // (2026-09-10 실측: 퍽·램프·점수판이 전부 `Renderer.isVisible=false` 로 사라졌다). 여기 부품은
            // 퍽이 움직이고 램프가 머티리얼을 바꾸는 동적 오브젝트라 애초에 정적이 아니다.
            GameObjectUtility.SetStaticEditorFlags(go, 0);
            return go;
        }

        /// <summary>납작한 원반 램프. 사각형 램프는 벽에 붙은 검은 타일처럼 보였다.</summary>
        static GameObject Disc(Transform parent, string name, Vector3 localPos, float diameter, Material mat)
        {
            var go = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            go.name = name;
            Object.DestroyImmediate(go.GetComponent<Collider>());
            go.transform.SetParent(parent, false);
            go.transform.localPosition = localPos;
            go.transform.localRotation = Quaternion.Euler(90f, 0f, 0f);   // 원반 면이 사람(로컬 +z)을 본다
            go.transform.localScale = new Vector3(diameter, 0.012f, diameter);
            var r = go.GetComponent<Renderer>();
            r.sharedMaterial = mat;
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            GameObjectUtility.SetStaticEditorFlags(go, 0);   // 위 Box 와 같은 이유 — 베이크 밖의 정적 오클루디는 통째로 컬링된다
            return go;
        }

        static Material Mat(string name, Color c, float smooth)
        {
            var m = AssetDatabase.LoadAssetAtPath<Material>(MatDir + name + ".mat");
            if (m == null)
            {
                m = new Material(Shader.Find("Universal Render Pipeline/Lit"));
                AssetDatabase.CreateAsset(m, MatDir + name + ".mat");
            }
            m.SetColor("_BaseColor", c);
            m.SetFloat("_Smoothness", smooth);
            EditorUtility.SetDirty(m);
            return m;
        }

        static Material EmissiveMat(string name, Color c, float intensity)
        {
            var m = Mat(name, c, 0.4f);
            m.EnableKeyword("_EMISSION");
            m.globalIlluminationFlags = MaterialGlobalIlluminationFlags.RealtimeEmissive;
            m.SetColor("_EmissionColor", c * intensity);
            EditorUtility.SetDirty(m);
            return m;
        }
    }
}
