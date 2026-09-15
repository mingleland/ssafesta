using System.IO;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 축제장을 <b>위에서 직교 투영으로 찍어</b> 미니맵 이미지를 굽는다 (사용자 요청 2026-09-10 —
    /// "탑뷰에서 찍은 걸로 하자, 실제 게임 미니맵 하듯이").
    ///
    /// <para><b>왜 실시간 카메라가 아닌가.</b> 축제장은 정적이다. 매 프레임 한 번 더 그리면
    /// WebGL 에서 그대로 비용인데 얻는 것이 없다. 한 번 구워 두면 런타임에는 텍스처 한 장이다.
    /// 부스가 움직이면 이 메뉴를 다시 돌리면 된다.</para>
    ///
    /// <para><b>좌표 규약이 계약이다.</b> 아래 <see cref="MinX"/>~<see cref="MaxZ"/> 는
    /// <c>FestivalMapOverlay</c> 가 월드 좌표를 지도 좌표로 옮길 때 쓰는 값과 <b>같아야 한다</b> —
    /// 다르면 노란 점과 부스가 서로 어긋난 자리에 찍힌다.</para>
    ///
    /// <para><b>화면 위쪽이 월드 −x</b>다. 사람은 동쪽에서 들어와 서쪽으로 걸어가므로, 그래야
    /// 걸어 들어갈 때 점이 위로 올라간다. 카메라를 아래로 향하게 두고 up 을 (−1,0,0) 으로 주면
    /// 오른쪽이 자동으로 +z(북쪽 줄)가 된다.</para>
    /// </summary>
    public static class FestaMinimapBaker
    {
        // 범위는 런타임 공용 상수를 쓴다 — 여기에 숫자를 또 적으면 한쪽만 고치는 날 조용히 어긋난다.
        static float MinX => Festa.World.FestivalMinimapArea.MinX;
        static float MaxX => Festa.World.FestivalMinimapArea.MaxX;
        static float MinZ => Festa.World.FestivalMinimapArea.MinZ;
        static float MaxZ => Festa.World.FestivalMinimapArea.MaxZ;

        const string OutPath = "Assets/_Project/Resources/UI/FestivalMinimap.png";
        const int Height = 1400;   // 세로(월드 x 710) 기준. 가로는 비율로 따라간다.

        [MenuItem("Festa/World/축제장 미니맵 이미지 굽기 (탑뷰)")]
        public static void Bake()
        {
            if (EditorApplication.isPlaying) { Debug.LogError("[Minimap] 플레이 모드에서는 돌리지 않는다."); return; }

            float spanX = MaxX - MinX;   // 화면 세로
            float spanZ = MaxZ - MinZ;   // 화면 가로
            // 압축 포맷(DXT/ASTC)은 변의 길이가 4의 배수여야 한다 — 아니면 임포터가 통째로 리사이즈한다.
            int width = Mathf.RoundToInt(Height * (spanZ / spanX) / 4f) * 4;

            var camGo = new GameObject("__MinimapCamera");
            var cam = camGo.AddComponent<Camera>();
            GameObject fillLight = null;
            var savedAmbientMode = RenderSettings.ambientMode;
            var savedAmbientLight = RenderSettings.ambientLight;
            float savedAmbientIntensity = RenderSettings.ambientIntensity;
            try
            {
                cam.orthographic = true;
                cam.orthographicSize = spanX * 0.5f;
                cam.aspect = spanZ / spanX;
                cam.nearClipPlane = 1f;
                cam.farClipPlane = 2000f;
                cam.clearFlags = CameraClearFlags.SolidColor;
                cam.backgroundColor = new Color(0.055f, 0.063f, 0.078f, 1f);   // 오버레이 패널과 같은 먹빛
                cam.cullingMask = ~0;
                cam.allowHDR = false;
                cam.allowMSAA = true;

                // 아래를 보되 화면 위쪽이 월드 −x 가 되게. 그러면 오른쪽은 +z 다.
                camGo.transform.position = new Vector3((MinX + MaxX) * 0.5f, 900f, (MinZ + MaxZ) * 0.5f);
                camGo.transform.rotation = Quaternion.LookRotation(Vector3.down, new Vector3(-1f, 0f, 0f));

                // 축제장은 밤이라 그냥 찍으면 지도가 너무 어둡다 ("전체적으로 어두워서 보기가 힘들다",
                // 2026-09-10). 굽는 동안만 환경광을 올리고 위에서 빛을 하나 준다 — 씬은 건드리지 않는다.
                savedAmbientMode = RenderSettings.ambientMode;
                savedAmbientLight = RenderSettings.ambientLight;
                savedAmbientIntensity = RenderSettings.ambientIntensity;
                RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Flat;
                RenderSettings.ambientLight = new Color(0.62f, 0.63f, 0.68f);
                RenderSettings.ambientIntensity = 1f;

                fillLight = new GameObject("__MinimapFill");
                var l = fillLight.AddComponent<Light>();
                l.type = LightType.Directional;
                l.color = new Color(1f, 0.98f, 0.93f);
                l.intensity = 1.1f;
                l.shadows = LightShadows.None;
                fillLight.transform.rotation = Quaternion.Euler(78f, 20f, 0f);

                var rt = new RenderTexture(width, Height, 24, RenderTextureFormat.ARGB32)
                {
                    antiAliasing = 4,
                    useMipMap = false,
                };
                cam.targetTexture = rt;
                cam.Render();

                var prev = RenderTexture.active;
                RenderTexture.active = rt;
                var tex = new Texture2D(width, Height, TextureFormat.RGBA32, false);
                tex.ReadPixels(new Rect(0, 0, width, Height), 0, 0);
                tex.Apply();
                RenderTexture.active = prev;

                Directory.CreateDirectory(Path.GetDirectoryName(OutPath));
                File.WriteAllBytes(OutPath, tex.EncodeToPNG());

                cam.targetTexture = null;
                Object.DestroyImmediate(tex);
                rt.Release();
                Object.DestroyImmediate(rt);
            }
            finally
            {
                // 조명·환경광은 **반드시** 되돌린다. 굽다가 실패해도 씬이 밝아진 채로 남으면 안 된다.
                RenderSettings.ambientMode = savedAmbientMode;
                RenderSettings.ambientLight = savedAmbientLight;
                RenderSettings.ambientIntensity = savedAmbientIntensity;
                if (fillLight != null) Object.DestroyImmediate(fillLight);
                Object.DestroyImmediate(camGo);
            }

            AssetDatabase.ImportAsset(OutPath, ImportAssetOptions.ForceUpdate);
            var importer = (TextureImporter)AssetImporter.GetAtPath(OutPath);
            importer.textureType = TextureImporterType.Default;
            importer.mipmapEnabled = false;
            importer.wrapMode = TextureWrapMode.Clamp;
            importer.filterMode = FilterMode.Bilinear;
            importer.maxTextureSize = 2048;
            importer.textureCompression = TextureImporterCompression.Compressed;
            // **이게 없으면 임포터가 정사각 POT(1024×1024)로 늘려 버린다** — 실제로 그렇게 나왔고,
            // 배경 사진이 가로로 늘어나 부스 카드와 어긋났다 (2026-09-10). 비율을 지켜야 한다.
            importer.npotScale = TextureImporterNPOTScale.None;
            importer.SaveAndReimport();

            var check = AssetDatabase.LoadAssetAtPath<Texture2D>(OutPath);
            if (check != null && (check.width != width || check.height != Height))
                Debug.LogError($"[Minimap] 임포트 후 크기가 달라졌다 — 구운 것은 {width}×{Height} 인데 " +
                               $"에셋은 {check.width}×{check.height} 다. 비율이 어긋나면 카드가 부스에 안 얹힌다.");

            Debug.Log($"[Minimap] 축제장 탑뷰 {width}×{Height} 저장 — {OutPath}. " +
                      "FestivalMapOverlay 가 Resources 로 읽는다. 좌표 규약(MinX~MaxZ)이 오버레이와 같아야 한다.");
        }
    }
}
