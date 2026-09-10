using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 캐릭터 로비의 **촬영 스튜디오 배경**을 세운다 (사용자 지시 2026-09-10).
    ///
    /// <para>전에는 아바타 뒤에 <c>Quad</c> 판 한 장(8 × 4.5, z = 1.35)만 있었다. 카메라를 돌리면
    /// 판 옆·뒤가 그대로 드러나고 그 너머는 카메라 배경색(거의 검정)이라 <b>화면이 통째로 검게</b> 보였다.
    /// 판 한 장으로는 회전을 감당할 수 없다 — 사진 스튜디오가 배경지를 <b>둥글게</b> 두르는 이유와 같다.</para>
    ///
    /// <para>그래서 <b>사이클로라마</b>(원통 배경)로 바꾼다. 안쪽을 보도록 뒤집힌 원통이 아바타를 감싸고,
    /// 바닥·천장 캡이 위아래를 막는다. 어느 각도로 돌려도 배경지가 보이고 검은 구멍이 없다.</para>
    ///
    /// <para>색은 세로 그라데이션으로 굽는다 — 단색 벽은 조명이 없어도 평평해 보인다. 위는 어둡고
    /// 눈높이가 밝은 스튜디오 배경지의 문법이다. 바닥은 가운데가 밝은 방사형이라 아바타 발밑에
    /// 자연스럽게 시선이 모인다.</para>
    /// </summary>
    public static class FestaLobbyStageBuilder
    {
        const string RootName = "@LobbyStage";
        const string TexDir = "Assets/_Project/Art/Lobby/";

        // 카메라는 (0, 1.05, −4.5)에서 FOV 35 로 본다. 원통은 그보다 넉넉히 커야 벽이 화면에 닿지 않는다.
        const float Radius = 9f;
        const float Height = 11f;

        [MenuItem("Festa/World/캐릭터 로비 스튜디오 배경 생성")]
        public static void Rebuild()
        {
            if (Application.isPlaying) { Debug.LogWarning("[LobbyStage] Play 중에는 저장 안 됨 — 종료 후 실행"); return; }

            var scene = UnityEngine.SceneManagement.SceneManager.GetActiveScene();
            if (!scene.name.Contains("CharacterLobby"))
            { Debug.LogError($"[LobbyStage] CharacterLobby 씬에서 실행해야 한다 (현재 {scene.name})"); return; }

            EnsureFolder();
            var old = GameObject.Find(RootName);
            if (old != null) Object.DestroyImmediate(old);

            // 판 한 장짜리 옛 배경은 원통에 가려 보이지 않지만, 남겨 두면 다음 사람이 어느 쪽이 진짜인지 모른다.
            var legacy = GameObject.Find("Cinematic Backdrop");
            if (legacy != null) { legacy.SetActive(false); Debug.Log("[LobbyStage] 옛 평면 배경(Cinematic Backdrop)을 껐다 — 원통이 대신한다"); }

            var root = new GameObject(RootName);
            root.transform.SetParent(null);
            root.transform.position = Vector3.zero;

            var wallMat = UnlitMat("LobbyCyclorama", VerticalGradient());
            var floorMat = LitMat("LobbyFloor", RadialGradient(), 0.35f);
            var capMat = UnlitMat("LobbyCap", Solid(new Color(0.045f, 0.055f, 0.085f)));

            // ① 사이클로라마 — 안쪽을 보는 원통. 기본 실린더는 바깥만 보이므로 컬링을 끈다.
            var wall = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            wall.name = "Cyclorama";
            Object.DestroyImmediate(wall.GetComponent<Collider>());
            wall.transform.SetParent(root.transform, false);
            wall.transform.localPosition = new Vector3(0f, Height * 0.5f - 1.2f, 0f);
            wall.transform.localScale = new Vector3(Radius * 2f, Height * 0.5f, Radius * 2f);
            Paint(wall, wallMat);

            // ② 바닥 — **Plane 이다.** 처음에는 납작한 원통을 썼는데, 실린더 캡의 UV 가 부채꼴로 늘어나
            // 방사형 그라데이션이 바퀴살처럼 줄무늬로 찍혔다(2026-09-10 캡처). Plane 은 0~1 이 평평하게 깔린다.
            // Plane 은 스케일 1 이 10 m 라, 지름 18 m 원통을 덮으려면 2.2 면 충분하다.
            var floor = GameObject.CreatePrimitive(PrimitiveType.Plane);
            floor.name = "StudioFloor";
            Object.DestroyImmediate(floor.GetComponent<Collider>());
            floor.transform.SetParent(root.transform, false);
            floor.transform.localPosition = new Vector3(0f, -1.24f, 0f);
            floor.transform.localScale = new Vector3(Radius * 0.24f, 1f, Radius * 0.24f);
            Paint(floor, floorMat);

            // ③ 천장 캡 — 올려다봐도 검은 구멍이 없게. 뒤집어 아래를 보게 한다.
            var cap = GameObject.CreatePrimitive(PrimitiveType.Plane);
            cap.name = "Ceiling";
            Object.DestroyImmediate(cap.GetComponent<Collider>());
            cap.transform.SetParent(root.transform, false);
            cap.transform.localPosition = new Vector3(0f, Height - 1.24f, 0f);
            cap.transform.localRotation = Quaternion.Euler(180f, 0f, 0f);
            cap.transform.localScale = new Vector3(Radius * 0.24f, 1f, Radius * 0.24f);
            Paint(cap, capMat);

            // ④ 무대 링 — 발밑에 코랄 테를 둘러 시선을 모은다 (UI 강조색과 같은 색).
            var ring = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            ring.name = "StageRim";
            Object.DestroyImmediate(ring.GetComponent<Collider>());
            ring.transform.SetParent(root.transform, false);
            ring.transform.localPosition = new Vector3(0f, -0.152f, 0f);
            ring.transform.localScale = new Vector3(1.42f, 0.012f, 1.42f);
            // 발광 2.2 는 하얗게 날아갔다 — 색이 남을 만큼만 올린다.
            Paint(ring, EmissiveMat("LobbyStageRim", new Color(1f, 0.44f, 0.38f), 0.8f));

            // ⑤ 바닥 조명 풀 — 무대 아래로 번지는 빛. 어느 각도에서 봐도 보이므로 회전에도 깨지지 않는다.
            var pool = GameObject.CreatePrimitive(PrimitiveType.Quad);
            pool.name = "FloorPool";
            Object.DestroyImmediate(pool.GetComponent<Collider>());
            pool.transform.SetParent(root.transform, false);
            pool.transform.localPosition = new Vector3(0f, -1.22f, 0f);
            pool.transform.localRotation = Quaternion.Euler(90f, 0f, 0f);
            pool.transform.localScale = new Vector3(7.5f, 7.5f, 1f);
            Paint(pool, AdditiveMat("LobbyFloorPool", SoftDisc()));

            // ⑥ 채움광 — Key(따뜻)·Rim(차가움) 둘만으로는 정면 그림자가 깊다. 카메라 쪽에서 부드럽게 채운다.
            var fill = new GameObject("Fill Light");
            fill.transform.SetParent(root.transform, false);
            fill.transform.position = new Vector3(-1.6f, 2.2f, -3.2f);
            var fl = fill.AddComponent<Light>();
            fl.type = LightType.Point;
            fl.color = new Color(0.72f, 0.80f, 1f);
            fl.intensity = 2.6f;
            fl.range = 12f;
            fl.shadows = LightShadows.None;

            // ⑧ 전시 조명 — 머리 위에서 아바타를 내리비춘다 (사용자 지시 2026-09-10 "전시하듯이").
            //
            // 기구는 y 2.65 에 둔다. 기본 구도(preset 0: 거리 3.55 · 시선 0.92 · FOV 35)에서 보이는 위쪽 한계가
            // y ≈ 2.04 라 **기구 자체는 화면 밖**이고, 뒤로 물리면(줌 아웃 최대 6) 그때 들어온다.
            // 대신 빛기둥이 화면 안까지 내려와 "위에서 비추고 있다" 가 항상 읽힌다.
            BuildExhibitLight(root.transform);

            // ⑦ 바닥 반사광 — 무대 아래에서 살짝 올려 비춰 발과 바닥이 붙어 보이게.
            var bounce = new GameObject("Bounce Light");
            bounce.transform.SetParent(root.transform, false);
            bounce.transform.position = new Vector3(0f, 0.25f, -1.1f);
            var bl = bounce.AddComponent<Light>();
            bl.type = LightType.Point;
            bl.color = new Color(1f, 0.62f, 0.48f);
            bl.intensity = 1.5f;
            bl.range = 4.5f;
            bl.shadows = LightShadows.None;

            // 주변광이 거의 검정이면 원통 안쪽이 죽는다 — 배경지 톤에 맞춰 살짝 올린다.
            RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Flat;
            RenderSettings.ambientLight = new Color(0.17f, 0.19f, 0.26f);

            UnityEditor.SceneManagement.EditorSceneManager.MarkSceneDirty(scene);
            Debug.Log($"[LobbyStage] 스튜디오 배경 생성 — 원통 반지름 {Radius} m · 높이 {Height} m, 바닥·천장 캡, 무대 링, 채움광 2 — 씬 저장 필요");
        }

        const float FixtureY = 2.65f;

        /// <summary>천장 스포트 — 기구 + 스포트 라이트 + 눈에 보이는 빛기둥.</summary>
        static void BuildExhibitLight(Transform parent)
        {
            var rig = new GameObject("ExhibitLight").transform;
            rig.SetParent(parent, false);
            rig.localPosition = new Vector3(0f, FixtureY, 0f);

            var metal = LitMat("LobbyFixtureMetal", Solid(new Color(0.10f, 0.11f, 0.14f)), 0.55f);

            // 매달린 봉 — 기구가 허공에 떠 있으면 소품이 아니라 오류로 보인다.
            var rod = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            rod.name = "Rod";
            Object.DestroyImmediate(rod.GetComponent<Collider>());
            rod.transform.SetParent(rig, false);
            rod.transform.localPosition = new Vector3(0f, 0.62f, 0f);
            rod.transform.localScale = new Vector3(0.035f, 0.62f, 0.035f);
            Paint(rod, metal);

            var housing = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            housing.name = "Housing";
            Object.DestroyImmediate(housing.GetComponent<Collider>());
            housing.transform.SetParent(rig, false);
            housing.transform.localPosition = Vector3.zero;
            housing.transform.localScale = new Vector3(0.34f, 0.17f, 0.34f);
            Paint(housing, metal);

            // 렌즈 — 기구 아래에 붙은 밝은 원. 빛이 나오는 곳이 보여야 조명으로 읽힌다.
            var lens = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            lens.name = "Lens";
            Object.DestroyImmediate(lens.GetComponent<Collider>());
            lens.transform.SetParent(rig, false);
            lens.transform.localPosition = new Vector3(0f, -0.175f, 0f);
            lens.transform.localScale = new Vector3(0.29f, 0.012f, 0.29f);
            Paint(lens, EmissiveMat("LobbyFixtureLens", new Color(1f, 0.93f, 0.78f), 3.2f));

            // 빛기둥 — 위가 좁고 아래가 넓은 원뿔. 위가 밝고 아래로 갈수록 사라진다.
            var beam = new GameObject("Beam");
            beam.transform.SetParent(rig, false);
            beam.transform.localPosition = new Vector3(0f, -0.19f, 0f);
            var mf = beam.AddComponent<MeshFilter>();
            mf.sharedMesh = ConeMesh(0.26f, 1.15f, FixtureY - 0.19f + 1.25f, 40);
            var mr = beam.AddComponent<MeshRenderer>();
            mr.sharedMaterial = AdditiveMat("LobbyBeam", BeamGradient());
            mr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            mr.receiveShadows = false;
            GameObjectUtility.SetStaticEditorFlags(beam, 0);

            var spot = new GameObject("Spot");
            spot.transform.SetParent(rig, false);
            spot.transform.localPosition = new Vector3(0f, -0.18f, 0f);
            spot.transform.localRotation = Quaternion.Euler(90f, 0f, 0f);   // 아래를 본다
            var sl = spot.AddComponent<Light>();
            sl.type = LightType.Spot;
            sl.color = new Color(1f, 0.93f, 0.82f);
            sl.intensity = 34f;
            sl.range = 8f;
            sl.spotAngle = 48f;
            sl.innerSpotAngle = 26f;
            sl.shadows = LightShadows.Soft;
            sl.shadowStrength = 0.55f;
        }

        /// <summary>
        /// 위가 좁고 아래가 넓은 뿔대(뚜껑 없음). UV 의 v 는 <b>아래 0 · 위 1</b> 이라
        /// 세로 그라데이션으로 아래쪽을 흐리게 만들 수 있다. 양면이라 안에서도 보인다.
        /// </summary>
        static Mesh ConeMesh(float topRadius, float bottomRadius, float height, int segments)
        {
            var verts = new Vector3[(segments + 1) * 2];
            var uvs = new Vector2[verts.Length];
            for (var i = 0; i <= segments; i++)
            {
                float t = i / (float)segments;
                float a = t * Mathf.PI * 2f;
                float cos = Mathf.Cos(a), sin = Mathf.Sin(a);
                verts[i] = new Vector3(cos * bottomRadius, -height, sin * bottomRadius);
                verts[segments + 1 + i] = new Vector3(cos * topRadius, 0f, sin * topRadius);
                uvs[i] = new Vector2(t, 0f);
                uvs[segments + 1 + i] = new Vector2(t, 1f);
            }
            var tris = new int[segments * 6];
            for (var i = 0; i < segments; i++)
            {
                int b0 = i, b1 = i + 1, t0 = segments + 1 + i, t1 = segments + 2 + i;
                int k = i * 6;
                tris[k] = b0; tris[k + 1] = t0; tris[k + 2] = b1;
                tris[k + 3] = b1; tris[k + 4] = t0; tris[k + 5] = t1;
            }
            var mesh = new Mesh { name = "LobbyBeamCone" };
            mesh.vertices = verts;
            mesh.uv = uvs;
            mesh.triangles = tris;
            mesh.RecalculateNormals();
            mesh.RecalculateBounds();
            var path = TexDir + "LobbyBeamCone.asset";
            var existing = AssetDatabase.LoadAssetAtPath<Mesh>(path);
            if (existing != null) { EditorUtility.CopySerialized(mesh, existing); Object.DestroyImmediate(mesh); return existing; }
            AssetDatabase.CreateAsset(mesh, path);
            return mesh;
        }

        /// <summary>빛기둥 세로 그라데이션 — 위(v=1)가 밝고 아래로 사라진다.</summary>
        static Texture2D BeamGradient()
        {
            var tint = new Color(1f, 0.94f, 0.80f);
            return Bake("LobbyBeamGradient", 2, 128, (u, v) =>
            {
                // 0.55 는 우유처럼 뿌옇게 껴 아바타를 덮었다 — 빛으로 읽힐 만큼만 남긴다.
                float a = Mathf.Pow(v, 2.4f) * 0.34f;
                return new Color(tint.r, tint.g, tint.b, a);
            });
        }

        static void Paint(GameObject go, Material mat)
        {
            var r = go.GetComponent<Renderer>();
            r.sharedMaterial = mat;
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            // 로비는 오브젝트가 몇 개뿐이라 정적 배칭 이득이 없고, 정적 플래그는 오클루전 베이크 밖에서
            // 통째로 컬링되는 사고를 부른다 (T-253).
            GameObjectUtility.SetStaticEditorFlags(go, 0);
        }

        // ── 머티리얼 ─────────────────────────────────────────────

        /// <summary>배경지는 Unlit 이다 — 조명에 반응하면 아바타 조명을 만질 때마다 배경 톤이 같이 흔들린다.</summary>
        static Material UnlitMat(string name, Texture2D tex)
        {
            var m = LoadOrCreate(name, "Universal Render Pipeline/Unlit");
            m.mainTexture = tex;
            m.SetTexture("_BaseMap", tex);
            m.SetColor("_BaseColor", Color.white);
            m.SetFloat("_Cull", 0f);   // 양면 — 원통 안쪽에서 봐야 한다
            EditorUtility.SetDirty(m);
            return m;
        }

        static Material LitMat(string name, Texture2D tex, float smoothness)
        {
            var m = LoadOrCreate(name, "Universal Render Pipeline/Lit");
            m.mainTexture = tex;
            m.SetTexture("_BaseMap", tex);
            m.SetColor("_BaseColor", Color.white);
            m.SetFloat("_Smoothness", smoothness);
            EditorUtility.SetDirty(m);
            return m;
        }

        static Material EmissiveMat(string name, Color c, float intensity)
        {
            var m = LoadOrCreate(name, "Universal Render Pipeline/Lit");
            m.SetColor("_BaseColor", c);
            m.EnableKeyword("_EMISSION");
            m.globalIlluminationFlags = MaterialGlobalIlluminationFlags.RealtimeEmissive;
            m.SetColor("_EmissionColor", c * intensity);
            EditorUtility.SetDirty(m);
            return m;
        }

        static Material LoadOrCreate(string name, string shader)
        {
            var path = TexDir + name + ".mat";
            var m = AssetDatabase.LoadAssetAtPath<Material>(path);
            if (m == null)
            {
                m = new Material(Shader.Find(shader));
                AssetDatabase.CreateAsset(m, path);
            }
            return m;
        }

        // ── 그라데이션 텍스처 ─────────────────────────────────────

        /// <summary>
        /// 위는 어둡고 눈높이가 밝은 배경지. 단색 벽은 조명 없이는 평평해 보인다.
        ///
        /// <para>가로로도 변화를 준다 — 원통은 u 가 한 바퀴를 도므로, 한쪽에 따뜻한 빛 웅덩이를 구우면
        /// <b>카메라를 돌릴 때 배경이 같이 흐른다.</b> 세로 그라데이션만 있으면 어느 각도에서 봐도 똑같아
        /// 회전한 느낌이 안 난다. 웅덩이는 Key Light 쪽(따뜻)과 그 반대(차가움) 두 군데다.</para>
        /// </summary>
        static Texture2D VerticalGradient()
        {
            var top = new Color(0.055f, 0.065f, 0.105f);
            var mid = new Color(0.185f, 0.215f, 0.325f);
            var bottom = new Color(0.075f, 0.088f, 0.135f);
            var warm = new Color(0.26f, 0.15f, 0.14f);    // 코랄 계열 — UI 강조색과 같은 방향
            var cool = new Color(0.10f, 0.16f, 0.30f);
            return Bake("LobbyCycloramaGradient", 512, 256, (u, v) =>
            {
                var baseColor = v < 0.42f ? Color.Lerp(bottom, mid, v / 0.42f)
                                          : Color.Lerp(mid, top, (v - 0.42f) / 0.58f);
                // 눈높이 근처에서만 웅덩이가 보이게 — 위아래로 갈수록 사라진다.
                float band = Mathf.Exp(-Mathf.Pow((v - 0.44f) / 0.26f, 2f));
                float warmPool = Mathf.Exp(-Mathf.Pow(Wrap(u - 0.22f) / 0.15f, 2f));
                float coolPool = Mathf.Exp(-Mathf.Pow(Wrap(u - 0.72f) / 0.18f, 2f));
                return baseColor + warm * (warmPool * band * 0.85f) + cool * (coolPool * band * 0.7f);
            });
        }

        /// <summary>원통 u 는 순환한다 — 0 과 1 이 이어지므로 최단 거리를 쓴다.</summary>
        static float Wrap(float d)
        {
            d = Mathf.Repeat(d, 1f);
            return d > 0.5f ? d - 1f : d;
        }

        /// <summary>가운데가 밝은 바닥 — 아바타 발밑으로 시선이 모인다.</summary>
        static Texture2D RadialGradient()
        {
            var center = new Color(0.26f, 0.30f, 0.42f);
            var edge = new Color(0.075f, 0.088f, 0.125f);
            return Bake("LobbyFloorGradient", 256, 256, (u, v) =>
            {
                float d = Mathf.Clamp01(Vector2.Distance(new Vector2(u, v), new Vector2(0.5f, 0.5f)) / 0.5f);
                return Color.Lerp(center, edge, d * d);
            });
        }

        static Texture2D Solid(Color c) => Bake("LobbyCapSolid", 2, 2, (_, __) => c);

        /// <summary>가운데가 밝고 가장자리가 완전히 투명한 원반 — 바닥에 번지는 빛으로 쓴다.</summary>
        static Texture2D SoftDisc()
        {
            var tint = new Color(0.55f, 0.62f, 0.95f);
            return Bake("LobbyFloorPoolDisc", 256, 256, (u, v) =>
            {
                float d = Mathf.Clamp01(Vector2.Distance(new Vector2(u, v), new Vector2(0.5f, 0.5f)) / 0.5f);
                float a = Mathf.Pow(1f - d, 2.6f);
                return new Color(tint.r, tint.g, tint.b, a);
            });
        }

        /// <summary>가산 합성 — 바닥 위에 빛을 더하기만 하고 어둡게 만들지 않는다.</summary>
        static Material AdditiveMat(string name, Texture2D tex)
        {
            var m = LoadOrCreate(name, "Universal Render Pipeline/Unlit");
            m.mainTexture = tex;
            m.SetTexture("_BaseMap", tex);
            m.SetColor("_BaseColor", new Color(1f, 1f, 1f, 0.55f));
            m.SetFloat("_Surface", 1f);                 // Transparent
            m.SetFloat("_SrcBlend", (float)UnityEngine.Rendering.BlendMode.SrcAlpha);
            m.SetFloat("_DstBlend", (float)UnityEngine.Rendering.BlendMode.One);   // 가산
            m.SetFloat("_ZWrite", 0f);
            m.SetFloat("_Cull", 0f);
            m.EnableKeyword("_SURFACE_TYPE_TRANSPARENT");
            m.renderQueue = (int)UnityEngine.Rendering.RenderQueue.Transparent;
            EditorUtility.SetDirty(m);
            return m;
        }

        static Texture2D Bake(string name, int w, int h, System.Func<float, float, Color> shade)
        {
            var path = TexDir + name + ".asset";
            var tex = new Texture2D(w, h, TextureFormat.RGBA32, false) { name = name, wrapMode = TextureWrapMode.Clamp };
            var px = new Color[w * h];
            for (var y = 0; y < h; y++)
                for (var x = 0; x < w; x++)
                    px[y * w + x] = shade(w == 1 ? 0.5f : x / (float)(w - 1), h == 1 ? 0.5f : y / (float)(h - 1));
            tex.SetPixels(px);
            tex.Apply(false, false);

            var existing = AssetDatabase.LoadAssetAtPath<Texture2D>(path);
            if (existing != null) { EditorUtility.CopySerialized(tex, existing); Object.DestroyImmediate(tex); EditorUtility.SetDirty(existing); return existing; }
            AssetDatabase.CreateAsset(tex, path);
            return tex;
        }

        static void EnsureFolder()
        {
            if (!AssetDatabase.IsValidFolder("Assets/_Project/Art/Lobby"))
                AssetDatabase.CreateFolder("Assets/_Project/Art", "Lobby");
        }
    }
}
