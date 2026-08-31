using System;
using System.IO;
using System.Linq;
using System.Text;
using UnityEditor;
using UnityEngine;
using UnityEngine.Profiling;

namespace Festa.EditorTools
{
    /// <summary>
    /// 아바타 커스터마이징 UI 텍스처를 **런타임이 실제로 쓰는 해상도**에 맞춘다 (spec 013 C-03).
    ///
    /// 배경: `Resources/Avatar/UI` 의 49개 아이콘·썸네일이 빌드에서 **65.70 MB** 를 차지했다.
    /// 배포 WebGL 빌드 138.78 MB 의 **47%** 다.
    ///
    /// 원인은 두 가지가 겹친 것이다.
    /// 1. 원본이 **1254×1254** 다 — 목록 칸에 188×156 으로 그려지는 그림인데.
    /// 2. 1254 는 2의 거듭제곱이 아니라(NPOT) **블록 압축을 쓸 수 없어 RGBA32 무압축**으로 들어간다.
    ///    1254×1254×4 = 6.0 MB — 실제 관측치 6.1 MB 와 맞는다.
    ///
    /// 그리고 결정적으로, **런타임 코드가 이미 스스로 줄여 쓰고 있다.**
    /// `CharacterLobbyController` 는 원본을 RenderTexture 로 blit 하면서 상한을 건다:
    ///
    /// | 경로 | 코드상 상한 | 소스에 필요한 해상도 |
    /// |---|---|---|
    /// | 일반 썸네일 (헤어·모자·의상) | 224 | 224 |
    /// | 색상 아이콘 `CreateTransparentIcon` | 256 | 256 |
    /// | 얼굴 시트 `CreateTransparentFaceThumbnail` | 셀당 320, scale 0.5 | **640** (셀 320) |
    ///
    /// 즉 1254 는 **화질에 기여하지 않는다** — 로드 직후 버려진다. 목표 해상도는 추측이 아니라
    /// 이 상한에서 나온다.
    ///
    /// 얼굴 시트만 1024 로 둔다 (640 보다 큰 최소 2의 거듭제곱). 나머지는 256.
    /// </summary>
    public static class AvatarUiTextureBudget
    {
        const string Root = "Assets/_Project/Resources/Avatar/UI";

        /// <summary>4칸 시트라 셀당 320 을 확보하려면 전체 640 이 필요하다 → 그 위 2의 거듭제곱.</summary>
        const string FaceSheet = Root + "/FaceThumbnails/face_shapes.png";
        const int FaceSheetMax = 1024;

        /// <summary>런타임 상한이 224(썸네일)·256(색상 아이콘)이다. 둘을 덮는 최소 2의 거듭제곱.</summary>
        const int IconMax = 256;

        [MenuItem("Festa/측정/아바타 UI 텍스처 예산 — 현황 보기")]
        public static void Report() => Run(apply: false);

        [MenuItem("Festa/측정/아바타 UI 텍스처 예산 — 적용")]
        public static void Apply() => Run(apply: true);

        static void Run(bool apply)
        {
            var guids = AssetDatabase.FindAssets("t:Texture2D", new[] { Root });
            if (guids.Length == 0)
            {
                Debug.LogError($"[UiBudget] {Root} 에서 텍스처를 찾지 못했다.");
                return;
            }

            var sb = new StringBuilder();
            sb.AppendLine("# 아바타 UI 텍스처 예산 (spec 013 C-03)");
            sb.AppendLine();
            sb.AppendLine(apply ? "**적용 실행.**" : "**현황만 본다 (변경 없음).**");
            sb.AppendLine();
            sb.AppendLine("| 파일 | 원본 | 현재 max | 목표 max | 런타임 크기 |");
            sb.AppendLine("|---|---|---:|---:|---:|");

            long runtimeTotal = 0;
            int changed = 0;

            foreach (var path in guids.Select(AssetDatabase.GUIDToAssetPath).OrderBy(p => p))
            {
                var importer = AssetImporter.GetAtPath(path) as TextureImporter;
                if (importer == null) continue;

                var texture = AssetDatabase.LoadAssetAtPath<Texture2D>(path);
                long runtime = texture != null ? Profiler.GetRuntimeMemorySizeLong(texture) : 0;
                runtimeTotal += runtime;

                int target = path.Replace('\\', '/') == FaceSheet ? FaceSheetMax : IconMax;

                sb.AppendLine($"| `{path.Substring(Root.Length + 1)}` | " +
                              $"{(texture ? $"{texture.width}×{texture.height}" : "?")} | " +
                              $"{importer.maxTextureSize} | {target} | {runtime / 1024} KB |");

                if (!apply || importer.maxTextureSize == target) continue;

                importer.maxTextureSize = target;
                // NPOT 소스를 목표 크기로 줄이면 2의 거듭제곱이 되어 블록 압축을 쓸 수 있다.
                // 압축을 켜지 않으면 줄여도 RGBA32 로 남아 절감폭이 크게 깎인다.
                importer.textureCompression = TextureImporterCompression.Compressed;
                // 목록 칸에 그리는 그림이라 밉맵이 필요 없다 — 켜면 33% 가 더 붙는다.
                importer.mipmapEnabled = false;
                importer.SaveAndReimport();
                changed++;
            }

            sb.AppendLine();
            sb.AppendLine($"- 대상 {guids.Length}개, 런타임 합계 **{runtimeTotal / 1024.0 / 1024.0:F1} MB**");
            if (apply) sb.AppendLine($"- 변경 **{changed}개**");
            sb.AppendLine();
            sb.AppendLine("> 런타임 크기는 에디터 기준이다. 배포 빌드의 최종 수치는 빌드를 다시 떠서");
            sb.AppendLine("> `Festa/측정/빌드 리포트` 로 확인해야 한다.");

            var outPath = Path.Combine(
                Directory.GetParent(Application.dataPath)!.FullName,
                apply ? "avatar-ui-budget-after.md" : "avatar-ui-budget-before.md");
            File.WriteAllText(outPath, sb.ToString(), new UTF8Encoding(false));
            Debug.Log($"[UiBudget] 완료 — {outPath}\n\n{sb}");
        }
    }
}
