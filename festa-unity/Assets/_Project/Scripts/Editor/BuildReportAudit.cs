using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 마지막 빌드의 **실제** 에셋별 용량을 뽑는다 (spec 013 C-03).
    ///
    /// 왜 필요한가 — 디스크의 원본 PNG 크기는 빌드 용량이 아니다. 텍스처는 임포트 설정
    /// (최대 해상도·압축 포맷)대로 **재인코딩돼서** 들어간다. 3.3 MB 짜리 PNG 가 빌드에서는
    /// 700 KB 일 수도, 반대로 압축이 꺼져 있으면 더 클 수도 있다. 원본 크기로 예산을 잡으면
    /// 엉뚱한 것을 줄이게 된다.
    ///
    /// 유니티는 빌드마다 `Library/LastBuild.buildreport` 를 남기지만 **에셋으로 임포트해야만**
    /// 읽을 수 있다. 그래서 Assets 아래로 복사했다가 읽고 지운다.
    /// </summary>
    public static class BuildReportAudit
    {
        const string SourcePath = "Library/LastBuild.buildreport";
        const string TempAsset = "Assets/LastBuild.buildreport";

        [MenuItem("Festa/측정/빌드 리포트 — 에셋별 실제 용량")]
        public static void Run()
        {
            var projectRoot = Directory.GetParent(Application.dataPath)!.FullName;
            var source = Path.Combine(projectRoot, SourcePath);
            if (!File.Exists(source))
            {
                Debug.LogError($"[BuildAudit] {SourcePath} 가 없다 — 빌드를 한 번 떠야 한다.");
                return;
            }

            try
            {
                File.Copy(source, Path.Combine(projectRoot, TempAsset), overwrite: true);
                AssetDatabase.ImportAsset(TempAsset, ImportAssetOptions.ForceSynchronousImport);

                var report = AssetDatabase.LoadAssetAtPath<BuildReport>(TempAsset);
                if (report == null)
                {
                    Debug.LogError("[BuildAudit] 빌드 리포트를 읽지 못했다.");
                    return;
                }

                Write(report, projectRoot);
            }
            finally
            {
                // 남겨두면 다음 빌드에 리포트 자신이 에셋으로 딸려 들어간다.
                AssetDatabase.DeleteAsset(TempAsset);
            }
        }

        static void Write(BuildReport report, string projectRoot)
        {
            var summary = report.summary;
            var sb = new StringBuilder();

            sb.AppendLine("# 빌드 리포트 실측 — 에셋별 실제 용량");
            sb.AppendLine();
            sb.AppendLine($"- 플랫폼: **{summary.platform}**");
            sb.AppendLine($"- 결과: {summary.result}, 에러 {summary.totalErrors}");
            sb.AppendLine($"- 총 용량: **{summary.totalSize / 1024.0 / 1024.0:F2} MB**");
            sb.AppendLine($"- 소요: {summary.totalTime.TotalMinutes:F1}분");
            sb.AppendLine();
            sb.AppendLine("> 디스크 원본 PNG 크기가 아니라 **빌드에 실제로 들어간 크기**다.");
            sb.AppendLine("> 텍스처는 임포트 설정대로 재인코딩되므로 둘은 크게 다를 수 있다.");
            sb.AppendLine();

            // packedAssets 는 빌드가 실제로 담은 것만 들고 있다 — 참조되지 않은 에셋은 없다.
            var entries = report.packedAssets
                .SelectMany(p => p.contents)
                .GroupBy(c => c.sourceAssetPath)
                .Select(g => (path: g.Key, size: (long)g.Sum(c => (decimal)c.packedSize)))
                .Where(x => !string.IsNullOrEmpty(x.path))
                .ToArray();

            long total = entries.Sum(e => e.size);
            sb.AppendLine($"- 에셋 합계: **{total / 1024.0 / 1024.0:F2} MB** (엔진 코드·셰이더 제외)");
            sb.AppendLine();

            AppendGroup(sb, "## 상위 폴더별", entries
                .GroupBy(e => TopFolder(e.path))
                .Select(g => (name: g.Key, size: g.Sum(x => x.size), count: g.Count()))
                .OrderByDescending(x => x.size).Take(15));

            // 아바타가 얼마를 차지하는지 — C-03 의 핵심 수치.
            var avatar = entries.Where(e => IsAvatar(e.path)).ToArray();
            sb.AppendLine("## 아바타 소계 (spec 013 C-03)");
            sb.AppendLine();
            sb.AppendLine($"- 파일 {avatar.Length}개, **{avatar.Sum(e => e.size) / 1024.0 / 1024.0:F2} MB**");
            sb.AppendLine($"- 빌드 총량 대비 **{avatar.Sum(e => e.size) * 100.0 / Math.Max(1, total):F1}%**");
            sb.AppendLine();

            AppendTop(sb, "### 아바타 상위 20개", avatar.OrderByDescending(e => e.size).Take(20));

            // Resources 는 참조 여부와 무관하게 통째로 빌드에 들어간다 — 유니티의 고전적 함정이다.
            // 여기 있는 것은 "안 쓰니까 빠지겠지" 가 통하지 않으므로 전량을 나열한다.
            var resources = entries
                .Where(e => e.path.Replace('\\', '/').Contains("/Resources/"))
                .OrderByDescending(e => e.size).ToArray();
            sb.AppendLine("## Resources 전량 (참조와 무관하게 빌드에 포함된다)");
            sb.AppendLine();
            sb.AppendLine($"- 파일 {resources.Length}개, **{resources.Sum(e => e.size) / 1024.0 / 1024.0:F2} MB**");
            sb.AppendLine();
            AppendTop(sb, "", resources);
            AppendTop(sb, "## 전체 상위 30개", entries.OrderByDescending(e => e.size).Take(30));

            // 확장자별 — "텍스처를 줄여야 하나 메시를 줄여야 하나" 에 답한다.
            AppendGroup(sb, "## 확장자별", entries
                .GroupBy(e => Path.GetExtension(e.path).ToLowerInvariant())
                .Select(g => (name: string.IsNullOrEmpty(g.Key) ? "(없음)" : g.Key,
                              size: g.Sum(x => x.size), count: g.Count()))
                .OrderByDescending(x => x.size).Take(12));

            var outPath = Path.Combine(projectRoot, "build-report-audit.md");
            File.WriteAllText(outPath, sb.ToString(), new UTF8Encoding(false));
            Debug.Log($"[BuildAudit] 완료 — {outPath}\n\n{sb}");
        }

        static void AppendGroup(StringBuilder sb, string title,
                                IEnumerable<(string name, long size, int count)> rows)
        {
            sb.AppendLine(title);
            sb.AppendLine();
            sb.AppendLine("| 구분 | 파일 | MB |");
            sb.AppendLine("|---|---:|---:|");
            foreach (var r in rows)
                sb.AppendLine($"| {r.name} | {r.count} | {r.size / 1024.0 / 1024.0:F2} |");
            sb.AppendLine();
        }

        static void AppendTop(StringBuilder sb, string title, IEnumerable<(string path, long size)> rows)
        {
            sb.AppendLine(title);
            sb.AppendLine();
            sb.AppendLine("| 에셋 | KB |");
            sb.AppendLine("|---|---:|");
            foreach (var r in rows)
                sb.AppendLine($"| `{r.path}` | {r.size / 1024} |");
            sb.AppendLine();
        }

        static string TopFolder(string path)
        {
            var parts = path.Split('/');
            // Assets/_Project/Art/... → _Project/Art 까지 보여야 구분이 된다.
            if (parts.Length >= 3 && parts[0] == "Assets") return $"{parts[1]}/{parts[2]}";
            return parts.Length >= 2 ? parts[0] + "/" + parts[1] : path;
        }

        static bool IsAvatar(string path) =>
            path.IndexOf("avatar", StringComparison.OrdinalIgnoreCase) >= 0 ||
            path.IndexOf("Rukha93", StringComparison.OrdinalIgnoreCase) >= 0;
    }
}
