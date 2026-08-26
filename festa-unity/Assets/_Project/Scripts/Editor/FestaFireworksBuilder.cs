using System.Linq;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 축제 불꽃놀이 생성기. Play 모드에서 실측 튜닝한 확정 수치를 박제한 것 —
    /// 만개 지름 16~24 m(실측), 폭발 고도 24 m — 서쪽 담장 뒤에서 발사해
    /// 복도 꺾임 시점의 정면, 거리에서는 앙각 25도 앞하늘에서 터진다 (플레이 실측 튜닝).
    ///
    /// 에셋스토어 프리팹(Basic Fireworks)을 스케일 14로 키워 쓰던 방식은 폐기했다.
    /// Hierarchy 스케일이 로켓 속도까지 14배로 키워 "레이저처럼 솟는 빨간 줄" 이 됐다.
    /// 속도·수명·감속을 월드 스케일(1 m = 10 unit)에 맞게 직접 지정한다.
    /// </summary>
    public static class FestaFireworksBuilder
    {
        const string MatPath = "Assets/_Project/Art/World/Materials/FestaFireworkAdd.mat";

        [MenuItem("Festa/World/불꽃놀이 재생성")]
        public static void Rebuild()
        {
            if (Application.isPlaying)
            {
                Debug.LogWarning("[Fireworks] Play 모드에서는 씬에 저장되지 않는다 — 종료 후 실행할 것");
                return;
            }

            var fest = GameObject.Find("/@Festival");
            if (fest == null) { Debug.LogError("[Fireworks] @Festival 없음"); return; }

            var old = fest.transform.Find("Festival_Fireworks");
            if (old != null) Object.DestroyImmediate(old.gameObject);

            var mat = AssetDatabase.LoadAssetAtPath<Material>(MatPath);
            if (mat == null)
            {
                mat = new Material(Shader.Find("Mobile/Particles/Additive"));
                mat.mainTexture = AssetDatabase.GetBuiltinExtraResource<Texture2D>("Default-Particle.psd");
                AssetDatabase.CreateAsset(mat, MatPath);
            }

            var group = new GameObject("Festival_Fireworks");
            group.transform.SetParent(fest.transform, false);

            var colors = new[]
            {
                new Color(1f, 0.45f, 0.5f), new Color(0.45f, 0.85f, 1f), new Color(1f, 0.85f, 0.35f),
                new Color(0.8f, 0.5f, 1f), new Color(1f, 0.6f, 0.25f),
            };
            float[] zs = { 40f, 95f, 150f, 205f, 260f };   // 서쪽 담장 뒤, 남북으로 분산
            float[] delays = { 0f, 2.2f, 4.4f, 6.6f, 8.8f };

            for (int i = 0; i < 5; i++)
                BuildShell(group.transform, zs[i], delays[i], colors[i], mat, i);

            // 대형 불꽃 — 자동 루프에서 제외. GrandFireworkLauncher.Fire() 로만 쏜다
            // (부스 인터랙션 예약분). 더 멀리·높이·크게.
            var grand = BuildShellObject(group.transform, new Vector3(-1150f, 0f, 150f),
                new Color(1f, 0.92f, 0.55f), mat, "FestaFirework_Grand",
                rocketSpeed: 150f, rocketLife: 2.4f,      // 상승 36 m
                burstCount: 420, burstSpeedMin: 170f, burstSpeedMax: 240f,
                burstSizeMin: 6.5f, burstSizeMax: 9.5f, flashSize: 220f,
                autoLoop: false);
            grand.AddComponent<Festa.World.GrandFireworkLauncher>();

            // MainModule.playOnAwake 세터가 새로 만든 PS 에 안 먹는 경우가 있다(실측) —
            // 직렬화 레벨로 강제한다. 대형(Grand)만 수동 발사로 남긴다.
            foreach (var ps in group.GetComponentsInChildren<ParticleSystem>(true))
            {
                bool auto = ps.name == "Rocket" && !ps.transform.parent.name.Contains("Grand");
                var so = new SerializedObject(ps);
                so.FindProperty("playOnAwake").boolValue = auto;
                so.ApplyModifiedPropertiesWithoutUndo();
            }

            UnityEditor.SceneManagement.EditorSceneManager.MarkSceneDirty(group.scene);
            Debug.Log("[Fireworks] 5기 재생성 완료 — 씬 저장 필요");
        }

        static void BuildShell(Transform parent, float z, float delay, Color col, Material mat, int idx)
        {
            var go = BuildShellObject(parent, new Vector3(-1020f, 0f, z), col, mat, $"FestaFirework_{idx}",
                rocketSpeed: 120f, rocketLife: 2.0f,
                burstCount: 260, burstSpeedMin: 120f, burstSpeedMax: 170f,
                burstSizeMin: 4.6f, burstSizeMax: 6.8f, flashSize: 130f,
                autoLoop: true, delay: delay);
        }

        static GameObject BuildShellObject(Transform parent, Vector3 pos, Color col, Material mat, string name,
            float rocketSpeed, float rocketLife, int burstCount, float burstSpeedMin, float burstSpeedMax,
            float burstSizeMin, float burstSizeMax, float flashSize, bool autoLoop, float delay = 0f)
        {
            var shell = new GameObject(name);
            shell.transform.SetParent(parent, false);
            shell.transform.position = pos;   // 서쪽 담장 뒤 지면 — 꺾은 시선의 정면

            // 로켓 — 1.6초 동안 34 m 상승. 트레일이 혜성 꼬리를 만든다.
            var rGo = new GameObject("Rocket");
            rGo.transform.SetParent(shell.transform, false);
            var rocket = rGo.AddComponent<ParticleSystem>();
            var rm = rocket.main;
            rm.duration = 11f; rm.loop = autoLoop; rm.startDelay = delay;   // 5기 시차 → 약 2.2초에 한 발
            rm.playOnAwake = autoLoop;
            rm.startLifetime = rocketLife; rm.startSpeed = rocketSpeed;   // 일반 24 m / 대형 36 m — 나무 위
            rm.startSize = 3.2f; rm.startColor = new Color(1f, 0.9f, 0.75f);
            rm.simulationSpace = ParticleSystemSimulationSpace.World;
            rm.maxParticles = 4;
            var re = rocket.emission;
            re.rateOverTime = 0f;
            re.SetBursts(new[] { new ParticleSystem.Burst(0f, 1) });
            var rs = rocket.shape;
            rs.shapeType = ParticleSystemShapeType.Cone;
            rs.angle = 3f; rs.radius = 0.5f;
            rs.rotation = new Vector3(-90f, 0f, 0f);
            var rt = rocket.trails;
            rt.enabled = true; rt.lifetime = 0.22f; rt.widthOverTrail = 1.3f;
            rt.dieWithParticles = true; rt.inheritParticleColor = true;
            Renderer(rGo, mat);

            // 폭발 — 실측 튜닝값: 만개 지름 16~24 m
            var bGo = new GameObject("Burst");
            bGo.transform.SetParent(rGo.transform, false);
            var burst = bGo.AddComponent<ParticleSystem>();
            var bm = burst.main;
            bm.duration = 3f; bm.loop = false; bm.playOnAwake = false;
            bm.startLifetime = new ParticleSystem.MinMaxCurve(1.8f, 2.4f);
            bm.startSpeed = new ParticleSystem.MinMaxCurve(burstSpeedMin, burstSpeedMax);
            bm.startSize = new ParticleSystem.MinMaxCurve(burstSizeMin, burstSizeMax);
            bm.startColor = col;
            bm.gravityModifier = 2.2f;
            bm.simulationSpace = ParticleSystemSimulationSpace.World;
            bm.maxParticles = burstCount + 60;
            var be = burst.emission;
            be.rateOverTime = 0f;
            be.SetBursts(new[] { new ParticleSystem.Burst(0f, (short)burstCount) });
            var bs = burst.shape;
            bs.shapeType = ParticleSystemShapeType.Sphere; bs.radius = 1f;
            var lv = burst.limitVelocityOverLifetime;
            lv.enabled = true; lv.limit = 14f; lv.dampen = 0.042f;
            var colModule = burst.colorOverLifetime;
            colModule.enabled = true;
            var grad = new Gradient();
            grad.SetKeys(
                new[] { new GradientColorKey(Color.white, 0f), new GradientColorKey(col, 0.25f), new GradientColorKey(col * 0.7f, 1f) },
                new[] { new GradientAlphaKey(1f, 0f), new GradientAlphaKey(0.9f, 0.5f), new GradientAlphaKey(0f, 1f) });
            colModule.color = grad;
            var szl = burst.sizeOverLifetime;
            szl.enabled = true;
            szl.size = new ParticleSystem.MinMaxCurve(1f, AnimationCurve.EaseInOut(0f, 1f, 1f, 0.25f));
            var bt = burst.trails;
            bt.enabled = true; bt.lifetime = 0.3f; bt.widthOverTrail = 0.9f;
            bt.dieWithParticles = true; bt.inheritParticleColor = true; bt.ratio = 0.55f;
            Renderer(bGo, mat);

            // 섬광 — 터지는 순간의 흰 플래시
            var fGo = new GameObject("Flash");
            fGo.transform.SetParent(rGo.transform, false);
            var flash = fGo.AddComponent<ParticleSystem>();
            var fm = flash.main;
            fm.duration = 1f; fm.loop = false; fm.playOnAwake = false;
            fm.startLifetime = 0.3f; fm.startSpeed = 0f; fm.startSize = flashSize;
            fm.startColor = new Color(1f, 1f, 0.95f, 1f);
            fm.simulationSpace = ParticleSystemSimulationSpace.World;
            fm.maxParticles = 2;
            var fe = flash.emission;
            fe.rateOverTime = 0f;
            fe.SetBursts(new[] { new ParticleSystem.Burst(0f, 1) });
            var fcol = flash.colorOverLifetime;
            fcol.enabled = true;
            var fg = new Gradient();
            fg.SetKeys(new[] { new GradientColorKey(Color.white, 0f) },
                       new[] { new GradientAlphaKey(1f, 0f), new GradientAlphaKey(0f, 1f) });
            fcol.color = fg;
            Renderer(fGo, mat);

            var sub = rocket.subEmitters;
            sub.enabled = true;
            sub.AddSubEmitter(burst, ParticleSystemSubEmitterType.Death, ParticleSystemSubEmitterProperties.InheritNothing);
            sub.AddSubEmitter(flash, ParticleSystemSubEmitterType.Death, ParticleSystemSubEmitterProperties.InheritNothing);
            return shell;
        }

        static void Renderer(GameObject go, Material mat)
        {
            var r = go.GetComponent<ParticleSystemRenderer>();
            r.renderMode = ParticleSystemRenderMode.Billboard;
            r.sharedMaterial = mat;
            r.trailMaterial = mat;
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
        }
    }
}
