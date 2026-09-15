using System;
using System.IO;
using System.Linq;
using System.Text;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// UI 텍스처 예산 조정(S15P21A604 아바타 실측)이 **화면에 보이는 차이를 만들었는지** 잰다.
    ///
    /// 해상도를 줄이고 압축을 켰다. 해상도 쪽은 런타임 상한(224·256·320)보다 여전히 크므로
    /// 손실이 없다는 게 산술로 증명되지만, **압축은 다르다** — DXT/ETC 는 알파가 있는 UI 아트에
    /// 아티팩트를 만들 수 있다. 수치만 보고 "괜찮다" 고 말하지 않으려면 실제로 비교해야 한다.
    ///
    /// 비교 방법: 원본 PNG(디스크 파일은 그대로다)와 임포트된 텍스처를 **화면에 그려지는 크기**
    /// (188×156)로 각각 축소한 뒤 픽셀 차이를 잰다. 원본 해상도에서 비교하면 의미가 없다 —
    /// 사용자는 그 크기로 보지 않는다. 예전에 480×270 광각으로 라이트맵 얼룩을 판정했다가
    /// 놓친 적이 있다 (T-222). **검증 해상도는 사용자가 보는 해상도여야 한다.**
    /// </summary>
    public static class AvatarUiTextureDiff
    {
        const string Root = "Assets/_Project/Resources/Avatar/UI";

        /// <summary>CharacterLobbyController 가 목록 칸을 그리는 실제 크기.</summary>
        const int DisplayWidth = 188;
        const int DisplayHeight = 156;

        [MenuItem("Festa/측정/아바타 UI 텍스처 — 원본 대비 화질 차이")]
        public static void Run()
        {
            var projectRoot = Directory.GetParent(Application.dataPath)!.FullName;
            var sb = new StringBuilder();
            sb.AppendLine("# UI 텍스처 화질 차이 — 원본 PNG vs 임포트 결과");
            sb.AppendLine();
            sb.AppendLine($"표시 크기 **{DisplayWidth}×{DisplayHeight}** 로 양쪽을 축소해 비교한다.");
            sb.AppendLine("사용자가 보는 크기에서 재야 의미가 있다 (T-222).");
            sb.AppendLine();
            sb.AppendLine("| 파일 | 평균 차이 | 최대 차이 | 판정 |");
            sb.AppendLine("|---|---:|---:|---|");

            var paths = AssetDatabase.FindAssets("t:Texture2D", new[] { Root })
                .Select(AssetDatabase.GUIDToAssetPath)
                .OrderBy(p => p)
                .ToArray();

            double worstMean = 0;
            foreach (var path in paths)
            {
                var imported = AssetDatabase.LoadAssetAtPath<Texture2D>(path);
                if (imported == null) continue;

                // 디스크의 원본 PNG 를 임포트 설정과 무관하게 그대로 읽는다 — 이게 기준선이다.
                var raw = new Texture2D(2, 2, TextureFormat.RGBA32, false);
                try
                {
                    if (!raw.LoadImage(File.ReadAllBytes(Path.Combine(projectRoot, path)))) continue;

                    var a = Downscale(raw);
                    var b = Downscale(imported);
                    Compare(a, b, out double mean, out int max);
                    worstMean = Math.Max(worstMean, mean);

                    // 8비트 채널에서 평균 1 미만이면 육안으로 구분 불가에 가깝다.
                    var verdict = mean < 1.0 ? "차이 없음" : mean < 3.0 ? "미미" : "**확인 필요**";
                    sb.AppendLine($"| `{path.Substring(Root.Length + 1)}` | {mean:F2} | {max} | {verdict} |");

                    UnityEngine.Object.DestroyImmediate(a);
                    UnityEngine.Object.DestroyImmediate(b);
                }
                finally { UnityEngine.Object.DestroyImmediate(raw); }
            }

            sb.AppendLine();
            sb.AppendLine($"- 최악 평균 차이: **{worstMean:F2} / 255**");
            sb.AppendLine();
            sb.AppendLine("> 평균 차이는 RGBA 채널값(0~255) 기준이다. 원본 PNG 자체를 같은 크기로");
            sb.AppendLine("> 줄인 것과 비교하므로, 축소로 인한 차이는 양쪽에 똑같이 들어가 상쇄된다.");
            sb.AppendLine("> 즉 여기 남는 차이는 **해상도 감축과 압축이 만든 것**이다.");

            var outPath = Path.Combine(projectRoot, "avatar-ui-quality-diff.md");
            File.WriteAllText(outPath, sb.ToString(), new UTF8Encoding(false));
            Debug.Log($"[UiDiff] 완료 — {outPath}\n\n{sb}");
        }

        /// <summary>표시 크기로 줄인다. GPU 블릿이라 임포트 설정(압축·해상도)이 그대로 반영된다.</summary>
        static Texture2D Downscale(Texture source)
        {
            var previous = RenderTexture.active;
            var temp = RenderTexture.GetTemporary(
                DisplayWidth, DisplayHeight, 0, RenderTextureFormat.ARGB32, RenderTextureReadWrite.sRGB);
            Graphics.Blit(source, temp);
            RenderTexture.active = temp;

            var result = new Texture2D(DisplayWidth, DisplayHeight, TextureFormat.RGBA32, false);
            result.ReadPixels(new Rect(0, 0, DisplayWidth, DisplayHeight), 0, 0, false);
            result.Apply();

            RenderTexture.active = previous;
            RenderTexture.ReleaseTemporary(temp);
            return result;
        }

        static void Compare(Texture2D a, Texture2D b, out double mean, out int max)
        {
            var pa = a.GetPixels32();
            var pb = b.GetPixels32();
            long sum = 0;
            max = 0;
            int n = Math.Min(pa.Length, pb.Length);
            for (int i = 0; i < n; i++)
            {
                // 알파를 포함해서 본다 — UI 아트는 알파 아티팩트가 가장 눈에 띈다.
                int d = Math.Abs(pa[i].r - pb[i].r) + Math.Abs(pa[i].g - pb[i].g)
                      + Math.Abs(pa[i].b - pb[i].b) + Math.Abs(pa[i].a - pb[i].a);
                sum += d;
                if (d > max) max = d;
            }
            mean = n == 0 ? 0 : sum / (double)n / 4.0;
        }
    }
}
