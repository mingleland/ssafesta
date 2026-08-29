using System;
using System.IO;
using System.Linq;
using System.Text;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// **내 변경만** 분리해서 화질 차이를 잰다.
    ///
    /// 앞선 비교(`AvatarUiTextureDiff`)는 "원본 PNG vs 현재 임포트 결과" 였다. 그 값에는
    /// 내가 손대기 **전부터 있던 차이**가 섞여 있다 — 예를 들어 `CategoryIcons` 는 원래부터
    /// 256 이었고 내가 바꾸지 않았는데도 차이가 나온다. 그걸 내 변경 탓으로 읽으면 오판이다.
    ///
    /// 그래서 같은 파일을 **옛 설정으로 임포트 → 캡처 → 새 설정으로 임포트 → 캡처** 한 뒤
    /// 둘을 비교한다. 이러면 남는 차이는 정확히 내가 만든 것이다.
    ///
    /// 캡처는 화면에 그려지는 크기(188×156)에서 한다 — 사용자가 보는 크기가 아니면
    /// 판정에 의미가 없다 (T-222).
    /// </summary>
    public static class AvatarUiTextureAb
    {
        const int DisplayWidth = 188;
        const int DisplayHeight = 156;

        /// <summary>대표 표본. 감축폭이 가장 큰 것들을 고른다.</summary>
        static readonly string[] Samples =
        {
            "Assets/_Project/Resources/Avatar/UI/HatThumbnails/hat_helmet.png",
            "Assets/_Project/Resources/Avatar/UI/HatThumbnails/hat_ballcap.png",
            "Assets/_Project/Resources/Avatar/UI/HairThumbnails/Individual/hair_01.png",
            "Assets/_Project/Resources/Avatar/UI/HairThumbnails/Individual/hair_17.png",
            "Assets/_Project/Resources/Avatar/UI/FaceThumbnails/face_shapes.png",
        };

        [MenuItem("Festa/측정/아바타 UI 텍스처 — 변경 전후 A/B")]
        public static void Run()
        {
            var projectRoot = Directory.GetParent(Application.dataPath)!.FullName;
            var sb = new StringBuilder();
            sb.AppendLine("# UI 텍스처 A/B — 변경 전 설정 vs 변경 후 설정");
            sb.AppendLine();
            sb.AppendLine($"같은 파일을 옛 설정과 새 설정으로 각각 임포트해 **{DisplayWidth}×{DisplayHeight}**");
            sb.AppendLine("(실제 표시 크기)로 비교한다. 남는 차이는 오직 내 변경이 만든 것이다.");
            sb.AppendLine();
            sb.AppendLine("| 파일 | 평균 차이 | 최대 차이 | 판정 |");
            sb.AppendLine("|---|---:|---:|---|");

            foreach (var path in Samples)
            {
                var importer = AssetImporter.GetAtPath(path) as TextureImporter;
                if (importer == null) { sb.AppendLine($"| `{path}` | — | — | 없음 |"); continue; }

                int newMax = importer.maxTextureSize;
                var newCompression = importer.textureCompression;
                bool newMip = importer.mipmapEnabled;

                Texture2D before = null, after = null;
                try
                {
                    // 옛 설정 — 이 폴더는 전부 2048·무압축이었다.
                    SetAndReimport(importer, 2048, TextureImporterCompression.Uncompressed, newMip);
                    before = Capture(path);

                    SetAndReimport(importer, newMax, newCompression, newMip);
                    after = Capture(path);

                    Compare(before, after, out double mean, out int max);
                    var verdict = mean < 1.0 ? "차이 없음" : mean < 3.0 ? "미미" : "**확인 필요**";
                    sb.AppendLine($"| `{Path.GetFileName(path)}` | {mean:F2} | {max} | {verdict} |");
                }
                finally
                {
                    if (before) UnityEngine.Object.DestroyImmediate(before);
                    if (after) UnityEngine.Object.DestroyImmediate(after);
                    // 어떤 경로로 빠져나가도 새 설정으로 되돌려 놓는다 — 여기서 옛 설정이
                    // 남으면 측정하려던 개선이 조용히 사라진다.
                    SetAndReimport(importer, newMax, newCompression, newMip);
                }
            }

            sb.AppendLine();
            sb.AppendLine("> 평균 차이는 RGBA 채널값(0~255) 기준. 8비트 채널에서 평균 1 미만이면");
            sb.AppendLine("> 육안 구분이 사실상 불가능하다.");

            var outPath = Path.Combine(projectRoot, "avatar-ui-ab.md");
            File.WriteAllText(outPath, sb.ToString(), new UTF8Encoding(false));
            Debug.Log($"[UiAb] 완료 — {outPath}\n\n{sb}");
        }

        static void SetAndReimport(TextureImporter importer, int max,
                                   TextureImporterCompression compression, bool mip)
        {
            importer.maxTextureSize = max;
            importer.textureCompression = compression;
            importer.mipmapEnabled = mip;
            importer.SaveAndReimport();
        }

        static Texture2D Capture(string path)
        {
            var source = AssetDatabase.LoadAssetAtPath<Texture2D>(path);
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
            for (int i = 0; i < pa.Length; i++)
            {
                int d = Math.Abs(pa[i].r - pb[i].r) + Math.Abs(pa[i].g - pb[i].g)
                      + Math.Abs(pa[i].b - pb[i].b) + Math.Abs(pa[i].a - pb[i].a);
                sum += d;
                if (d > max) max = d;
            }
            mean = sum / (double)pa.Length / 4.0;
        }
    }
}
