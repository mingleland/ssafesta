using System.Linq;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 축제 연출물 정리 — 스크린샷 피드백 반영 (2026-08-23).
    ///
    /// 1) 화로: 나무 큐브 + 관통 기둥 → 금속 받침(기단·기둥·화분) 3단 구성으로 재설계,
    ///    입구 가로등과 겹치던 위치를 개구부 양옆 담장 쪽으로 이동.
    /// 2) 서치라이트: 민짜 검은 실린더 → 드럼 기단 + 기둥 + 발광 하우징. 부스 줄을
    ///    썰고 지나가던 빔이 닿지 않게 부지 모서리로 이동 + 상향각 28→38도.
    /// 3) 빔 텍스처: 밑동까지 밝아 회색 판으로 보이던 것 → 아래 15% 페이드 + 전체 감광.
    /// </summary>
    public static class FestaAmbienceFixups
    {
        const string MatDir = "Assets/_Project/Art/World/Materials/";
        const string BeamTexPath = "Assets/_Project/Art/World/Textures/BeamGradient.png";

        [MenuItem("Festa/World/축제 연출 정리 (서치라이트·화로)")]
        public static void Run()
        {
            if (Application.isPlaying) { Debug.LogWarning("[Fixups] Play 중에는 저장되지 않는다 — 종료 후 실행"); return; }

            var fest = GameObject.Find("/@Festival");
            if (fest == null) { Debug.LogError("[Fixups] @Festival 없음"); return; }

            RegenerateBeamTexture();
            RebuildSearchlights(fest.transform);
            RebuildBraziers(fest.transform);

            UnityEditor.SceneManagement.EditorSceneManager.MarkSceneDirty(fest.scene);
            Debug.Log("[Fixups] 서치라이트 2기·화로 3기 재구성 완료 — 씬 저장 필요");
        }

        /// <summary>빔 그라데이션: 아래 15% 도 페이드시켜 근거리 회색 판 현상 제거, 전체 감광.</summary>
        static void RegenerateBeamTexture()
        {
            const int W = 64, H = 256;
            var tex = new Texture2D(W, H, TextureFormat.RGBA32, false);
            for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
            {
                float v = 1f - y / (float)H;                       // 1=아래(광원) 0=위(하늘)
                float body = Mathf.Pow(v, 1.7f);
                float baseFade = Mathf.Clamp01((1f - v) / 0.15f);  // 밑동 15% 는 서서히
                float edge = 1f - Mathf.Abs(x - W / 2f) / (W / 2f);
                float a = body * baseFade * Mathf.Pow(Mathf.Clamp01(edge * 1.6f), 1.3f) * 0.32f;
                tex.SetPixel(x, y, new Color(a, a, a, a));
            }
            tex.Apply();
            System.IO.File.WriteAllBytes(BeamTexPath, tex.EncodeToPNG());
            AssetDatabase.ImportAsset(BeamTexPath);
        }

        static void RebuildSearchlights(Transform fest)
        {
            var amb = fest.Find("Festival_Ambience");
            if (amb == null) { Debug.LogWarning("[Fixups] Festival_Ambience 없음"); return; }

            // 기존 타워 제거
            foreach (var t in amb.Cast<Transform>().Where(t => t.name == "SearchlightTower").ToArray())
                Object.DestroyImmediate(t.gameObject);

            var beamMat = AssetDatabase.LoadAssetAtPath<Material>(MatDir + "SearchBeam.mat");
            var poleMat = AssetDatabase.LoadAssetAtPath<Material>(MatDir + "FestivalPole.mat");
            var lampMat = AssetDatabase.LoadAssetAtPath<Material>(MatDir + "FestivalLamp.mat");
            var beams = new System.Collections.Generic.List<Transform>();

            // 부스 줄에서 떨어진 부지 모서리 — 빔이 어떤 부스도 지나지 않는다
            foreach (var pos in new[] { new Vector3(-250f, 0f, 305f), new Vector3(-900f, 0f, 8f) })
            {
                var tower = new GameObject("SearchlightTower");
                tower.transform.SetParent(amb, false);
                tower.transform.position = pos;

                // 드럼 기단 (넓고 낮게) + 기둥 + 상단 발광 하우징 — 민짜 실린더 탈출
                Cyl(tower.transform, "Base", new Vector3(0, 2.5f, 0), new Vector3(14f, 2.5f, 14f), poleMat);
                Cyl(tower.transform, "Pole", new Vector3(0, 21f, 0), new Vector3(4.5f, 17f, 4.5f), poleMat);
                var housing = GameObject.CreatePrimitive(PrimitiveType.Cube);
                housing.name = "Housing";
                housing.transform.SetParent(tower.transform, false);
                housing.transform.localPosition = new Vector3(0, 41f, 0);
                housing.transform.localScale = new Vector3(9f, 7f, 11f);
                housing.GetComponent<Renderer>().sharedMaterial = poleMat;
                var lens = GameObject.CreatePrimitive(PrimitiveType.Quad);
                Object.DestroyImmediate(lens.GetComponent<Collider>());
                lens.name = "Lens";
                lens.transform.SetParent(housing.transform, false);
                lens.transform.localPosition = new Vector3(0, 0.3f, 0.51f);
                lens.transform.localScale = new Vector3(0.7f, 0.6f, 1f);
                lens.GetComponent<Renderer>().sharedMaterial = lampMat;   // 발광 렌즈
                lens.GetComponent<Renderer>().shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;

                var pivot = new GameObject("BeamPivot");
                pivot.transform.SetParent(tower.transform, false);
                pivot.transform.localPosition = new Vector3(0, 44f, 0);
                pivot.transform.localRotation = Quaternion.Euler(38f, 0f, 0f);   // 더 하늘로
                for (int q = 0; q < 2; q++)
                {
                    var quad = GameObject.CreatePrimitive(PrimitiveType.Quad);
                    Object.DestroyImmediate(quad.GetComponent<Collider>());
                    quad.transform.SetParent(pivot.transform, false);
                    quad.transform.localPosition = new Vector3(0, 200f, 0);
                    quad.transform.localScale = new Vector3(26f, 420f, 1f);
                    quad.transform.localRotation = Quaternion.Euler(90f, q * 90f, 0f) * Quaternion.Euler(-90f, 0f, 0f);
                    var r = quad.GetComponent<Renderer>();
                    r.sharedMaterial = beamMat;
                    r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                }
                beams.Add(pivot.transform);
            }

            // FestivalAmbience 의 빔 배열 재배선
            var ambComp = amb.GetComponent("FestivalAmbience");
            if (ambComp != null)
            {
                var so = new SerializedObject(ambComp);
                var sp = so.FindProperty("_beams");
                sp.arraySize = beams.Count;
                for (int k = 0; k < beams.Count; k++)
                    sp.GetArrayElementAtIndex(k).objectReferenceValue = beams[k];
                so.ApplyModifiedPropertiesWithoutUndo();
            }
        }

        static void RebuildBraziers(Transform fest)
        {
            var fires = fest.Find("Festival_Fires");
            if (fires == null) { Debug.LogWarning("[Fixups] Festival_Fires 없음"); return; }

            var poleMat = AssetDatabase.LoadAssetAtPath<Material>(MatDir + "FestivalPole.mat");

            // 나무 큐브 받침 제거
            foreach (var t in fires.Cast<Transform>().Where(t => t.name == "BrazierStand").ToArray())
                Object.DestroyImmediate(t.gameObject);

            // 불(VFX_TorchLight) 위치도 새 받침 위로 옮긴다.
            // 입구 가로등(-236, 92/200)과 겹치던 배치 → 개구부(z 80~210) 양옆 담장 쪽으로.
            var torches = fires.Cast<Transform>().Where(t => t.name.StartsWith("VFX_TorchLight")).ToArray();
            var spots = new[] { new Vector3(-232f, 0f, 72f), new Vector3(-232f, 0f, 218f), new Vector3(-132f, 0f, 88f) };
            for (int k = 0; k < torches.Length && k < spots.Length; k++)
            {
                var basePos = spots[k];
                // 3단 받침: 기단 → 기둥 → 화분(볼)
                var stand = new GameObject("BrazierStand");
                stand.transform.SetParent(fires, false);
                stand.transform.position = basePos;
                Cyl(stand.transform, "Plinth", new Vector3(0, 1f, 0), new Vector3(8f, 1f, 8f), poleMat);
                Cyl(stand.transform, "Column", new Vector3(0, 5.5f, 0), new Vector3(3f, 4.5f, 3f), poleMat);
                var bowl = GameObject.CreatePrimitive(PrimitiveType.Sphere);
                bowl.name = "Bowl";
                bowl.transform.SetParent(stand.transform, false);
                bowl.transform.localPosition = new Vector3(0, 10.5f, 0);
                bowl.transform.localScale = new Vector3(7.5f, 3.2f, 7.5f);
                bowl.GetComponent<Renderer>().sharedMaterial = poleMat;

                torches[k].position = basePos + Vector3.up * 11.5f;   // 화분 위에서 타오른다
            }
        }

        static void Cyl(Transform parent, string name, Vector3 pos, Vector3 scale, Material m)
        {
            var c = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            c.name = name;
            c.transform.SetParent(parent, false);
            c.transform.localPosition = pos;
            c.transform.localScale = scale;
            c.GetComponent<Renderer>().sharedMaterial = m;
        }
    }
}
