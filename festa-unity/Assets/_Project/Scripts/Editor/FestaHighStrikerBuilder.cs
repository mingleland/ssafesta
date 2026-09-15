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
    /// <b>벤더 프리팹의 에셋 원본은 고치지 않는다</b> — 부품은 씬 인스턴스의 메시 자식 아래
    /// <c>@HighStriker_01</c> 루트에 붙인다(기계를 옮기면 같이 따라간다).</para>
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
        // 게이지는 **기둥 정중앙**에 얹는다. 실측(2026-09-10): 기둥 중심 = 메시 로컬 x 0.000, 전면 z −0.106,
        // 기둥 폭 월드 4.2 u(로컬 0.32 m).
        //
        // 모양은 사용자 레퍼런스("King of the Hammer")를 따른다 — **어두운 베젤에 박힌 사각 LED 사다리**.
        // 처음에는 둥근 램프 + 흰 사각 퍽이었는데 "네모랑 동그라미가 이상하다" 는 지적을 받았다.
        // 실제 기계에는 물리적으로 오르내리는 퍽이 없고, 눈금이 아래에서 위로 차오른다.
        const float StarX = 0f;
        const float StarZ = -0.062f;         // 기둥 전면(−0.106)에서 4 cm 앞 — 붙이면 z-파이팅으로 검게 나온다
        const float SegWidth = 0.245f;       // 기둥 폭(0.32) 안쪽
        const float SegHeight = 0.088f;
        const float StarBottomY = 0.72f, StarTopY = 2.56f;
        const int StarCount = 14;            // 레퍼런스는 15칸 — 기둥 길이에 맞춰 14칸

        const float BoardY = 3.70f;          // 3.35 에서는 꼭대기 별 장식에 글자가 가렸다

        [MenuItem("Festa/World/하이 스트라이커 부품 생성")]
        public static void Rebuild()
        {
            if (Application.isPlaying) { Debug.LogWarning("[HighStriker] Play 중에는 저장 안 됨 — 종료 후 실행"); return; }

            var machine = GameObject.Find(MachineName);
            if (machine == null) { Debug.LogError($"[HighStriker] 씬에 {MachineName} 이 없다"); return; }

            var old = GameObject.Find("@HighStriker_01");
            if (old != null) Object.DestroyImmediate(old);

            // 부품은 **메시 자식의 자식**으로 넣는다. 처음에는 형제로 두고 월드 위치·회전·localScale 을 베껴
            // "같은 공간" 을 흉내 냈는데, 기계를 옮길 때마다 어긋나고 실제로 부품이 벽 쪽으로 떨어져 나갔다
            // (사용자 지적 2026-09-10, 캡처 3회). 메시 자식 아래에 identity 로 붙이면 아래 좌표가
            // **정점 실측과 같은 공간**이 되고, 기계를 어디로 옮기든 따라간다.
            var meshChild = machine.transform.Find("SM_High_Striker_Bell_Tower");
            if (meshChild == null) { Debug.LogError("[HighStriker] SM_High_Striker_Bell_Tower 없음"); return; }
            var root = new GameObject("@HighStriker_01");
            root.transform.SetParent(meshChild, false);
            root.transform.localPosition = Vector3.zero;
            root.transform.localRotation = Quaternion.identity;
            root.transform.localScale = Vector3.one;

            var starOff = Mat("StrikerStarOff", new Color(0.055f, 0.05f, 0.06f), 0.55f);   // 꺼진 LED — 검은 유리
            // 켜진 색은 높이에 따라 초록 → 호박 → 빨강 (레퍼런스 사다리와 같은 문법)
            var onLow = EmissiveMat("StrikerLedGreen", new Color(0.25f, 1f, 0.42f), 3.2f);
            var onMid = EmissiveMat("StrikerLedAmber", new Color(1f, 0.72f, 0.12f), 3.4f);
            var onHigh = EmissiveMat("StrikerLedRed", new Color(1f, 0.22f, 0.16f), 3.6f);
            var bezelMat = Mat("StrikerBezel", new Color(0.045f, 0.045f, 0.055f), 0.45f);
            var boardMat = Mat("StrikerBoard", new Color(0.09f, 0.10f, 0.14f), 0.25f);
            var frameMat = Mat("StrikerFrame", new Color(0.63f, 0.13f, 0.14f), 0.35f);   // 기계 몸통과 같은 붉은색

            // ① 베젤 — LED 를 박아 넣을 어두운 띠. 이게 있어야 램프가 기둥에 붙은 계기로 읽힌다.
            float ladderMid = (StarBottomY + StarTopY) * 0.5f;
            Box(root.transform, "LedBezel", new Vector3(StarX, ladderMid, StarZ - 0.018f),
                new Vector3(SegWidth + 0.05f, StarTopY - StarBottomY + SegHeight + 0.06f, 0.035f), bezelMat);

            // ② LED 사다리 — 아래에서 위로 차오른다. 켜짐/꺼짐은 머티리얼 교체다
            // (오브젝트를 껐다 켜면 배칭이 깨진다).
            var starRoot = new GameObject("Stars").transform;
            starRoot.SetParent(root.transform, false);
            starRoot.localPosition = Vector3.zero;
            for (var i = 0; i < StarCount; i++)
            {
                float y = Mathf.Lerp(StarBottomY, StarTopY, i / (float)(StarCount - 1));
                Box(starRoot, $"Star_{i}", new Vector3(StarX, y, StarZ), new Vector3(SegWidth, SegHeight, 0.03f), starOff);
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
            so.FindProperty("_starRoot").objectReferenceValue = starRoot;
            so.FindProperty("_scoreText").objectReferenceValue = tmp;
            so.FindProperty("_starOffMaterial").objectReferenceValue = starOff;
            var tiers = so.FindProperty("_starOnMaterials");
            tiers.arraySize = 3;
            tiers.GetArrayElementAtIndex(0).objectReferenceValue = onLow;
            tiers.GetArrayElementAtIndex(1).objectReferenceValue = onMid;
            tiers.GetArrayElementAtIndex(2).objectReferenceValue = onHigh;
            so.ApplyModifiedPropertiesWithoutUndo();

            // ⑤ 상호작용은 **벤더 기계 본체**에 붙인다 — 사람이 다가가는 대상이 그것이라 사거리·외곽선이 자연스럽다.
            var interact = machine.GetComponent<HighStrikerInteractable>() ?? machine.AddComponent<HighStrikerInteractable>();
            var iso = new SerializedObject(interact);
            iso.FindProperty("_machine").objectReferenceValue = hsm;
            iso.ApplyModifiedPropertiesWithoutUndo();
            if (machine.GetComponent<Festa.Booth.BoothInteractionTarget>() == null)
                machine.AddComponent<Festa.Booth.BoothInteractionTarget>();

            EditorSceneManagerMarkDirty(root);
            Debug.Log($"[HighStriker] 부품 생성 — LED {StarCount}칸 (y {StarBottomY:F2}~{StarTopY:F2} m), 점수판 y {BoardY:F2} m — 씬 저장 필요");
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
