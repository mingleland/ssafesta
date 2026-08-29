using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using Festa.Avatar;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 아바타 카탈로그 실측 (spec 013 C-01·C-03).
    ///
    /// spec 013 은 **확정 상태**지만 두 항목은 "값"이 아니라 "산출 방법"으로 확정됐다:
    /// C-01 카테고리·항목 수는 *에셋 실조사 산출물로*, C-03 용량 목표는 *현재 Web 빌드(약 87MB)를
    /// 넘지 않도록 역산*. 즉 남은 것은 결정이 아니라 **실측 실행**이다. 이 도구가 그 실측을 한다.
    ///
    /// **핵심 수치는 "고유 용량" 이다.** 항목별 의존 에셋을 그냥 더하면 공유 텍스처·머티리얼이
    /// 항목 수만큼 중복 계산돼 실제보다 몇 배로 부풀려진다. 빌드는 공유 에셋을 한 번만 담는다.
    /// 그래서 **다른 항목과 공유하지 않는 의존만** 고유 용량으로 센다 — 항목 하나를 더할 때
    /// 실제로 늘어나는 양이 그것이고, "몇 개까지 넣을 수 있나" 에 답하는 숫자도 그것이다.
    ///
    /// 디스크 원본 크기를 쓴다. 빌드 후 압축·텍스처 재인코딩을 거치면 값이 달라지므로
    /// **절대치가 아니라 항목 간 비교·상대 예산**으로 읽어야 한다. 절대치가 필요하면
    /// 빌드 리포트를 봐야 한다.
    /// </summary>
    public static class AvatarCatalogAudit
    {
        [MenuItem("Festa/측정/아바타 카탈로그 실측")]
        public static void Run()
        {
            var catalog = FindCatalog();
            if (catalog == null)
            {
                Debug.LogError("[AvatarAudit] Festa.Avatar.AvatarCatalog 에셋을 찾지 못했다.");
                return;
            }

            var items = catalog.items.Where(x => x != null).ToArray();
            if (items.Length == 0)
            {
                Debug.LogError("[AvatarAudit] 카탈로그에 항목이 없다.");
                return;
            }

            // 의존 에셋별 참조 횟수를 먼저 센다 — 공유 여부를 알아야 고유 용량을 낼 수 있다.
            var refCount = new Dictionary<string, int>();
            var perItemDeps = new Dictionary<AvatarItemDefinition, string[]>();
            foreach (var item in items)
            {
                var deps = Dependencies(item);
                perItemDeps[item] = deps;
                foreach (var d in deps)
                    refCount[d] = refCount.TryGetValue(d, out var n) ? n + 1 : 1;
            }

            var report = new StringBuilder();
            report.AppendLine("# 아바타 카탈로그 실측 (spec 013 C-01·C-03)");
            report.AppendLine();
            report.AppendLine($"- 카탈로그: `{AssetDatabase.GetAssetPath(catalog)}`");
            report.AppendLine($"- 항목 총계: **{items.Length}개**");
            report.AppendLine($"- 팔레트 색상: {catalog.palette?.Length ?? 0}개");
            report.AppendLine();

            AppendCategoryTable(report, items);
            AppendSizeTable(report, items, perItemDeps, refCount);
            AppendThumbnailGap(report, items);
            AppendSharedAssets(report, refCount);

            var path = Path.Combine(
                Directory.GetParent(Application.dataPath)!.FullName, "avatar-catalog-audit.md");
            File.WriteAllText(path, report.ToString(), new UTF8Encoding(false));
            Debug.Log($"[AvatarAudit] 완료 — {path}\n\n{report}");
        }

        // ---------- 카테고리 분포 (C-01) ----------

        static void AppendCategoryTable(StringBuilder report, AvatarItemDefinition[] items)
        {
            report.AppendLine("## C-01 — 카테고리·항목 수 (실조사)");
            report.AppendLine();
            report.AppendLine("| 카테고리 | 남 | 여 | 공용 | 합계 | 기본값 |");
            report.AppendLine("|---|---:|---:|---:|---:|---|");

            foreach (AvatarPartCategory category in Enum.GetValues(typeof(AvatarPartCategory)))
            {
                var inCategory = items.Where(x => x.category == category).ToArray();
                int male = inCategory.Count(x => x.gender == AvatarGender.Male);
                int female = inCategory.Count(x => x.gender == AvatarGender.Female);
                int both = inCategory.Count(x => x.gender == AvatarGender.Both);

                // 기본값이 없으면 그 카테고리는 첫 항목이 임의로 선택된다 — 조용한 결함이다.
                var defaults = inCategory.Where(x => x.isDefault).Select(x => x.displayName).ToArray();
                var defaultCell = defaults.Length == 0
                    ? "**없음 ⚠**"
                    : string.Join(", ", defaults);

                report.AppendLine($"| {category} | {male} | {female} | {both} | " +
                                  $"**{inCategory.Length}** | {defaultCell} |");
            }
            report.AppendLine();
        }

        // ---------- 용량 (C-03) ----------

        static void AppendSizeTable(StringBuilder report, AvatarItemDefinition[] items,
                                    Dictionary<AvatarItemDefinition, string[]> perItemDeps,
                                    Dictionary<string, int> refCount)
        {
            report.AppendLine("## C-03 — 용량 기여 (디스크 원본 기준)");
            report.AppendLine();
            report.AppendLine("**고유 = 이 항목만 참조하는 에셋.** 항목 하나를 더할 때 실제로 늘어나는 양이다.");
            report.AppendLine("공유분을 항목마다 더하면 중복 계산이라 실제보다 몇 배로 부풀려진다.");
            report.AppendLine();
            report.AppendLine("| 카테고리 | 항목 | 고유 KB | 항목당 평균 고유 KB |");
            report.AppendLine("|---|---:|---:|---:|");

            long grandUnique = 0;
            foreach (AvatarPartCategory category in Enum.GetValues(typeof(AvatarPartCategory)))
            {
                var inCategory = items.Where(x => x.category == category).ToArray();
                if (inCategory.Length == 0) continue;

                long unique = 0;
                foreach (var item in inCategory)
                    foreach (var dep in perItemDeps[item])
                        if (refCount[dep] == 1) unique += FileSize(dep);

                grandUnique += unique;
                report.AppendLine($"| {category} | {inCategory.Length} | {unique / 1024} | " +
                                  $"{unique / 1024 / Math.Max(1, inCategory.Length)} |");
            }

            long shared = refCount.Where(kv => kv.Value > 1).Sum(kv => FileSize(kv.Key));
            report.AppendLine($"| **고유 합계** | {items.Length} | **{grandUnique / 1024}** | |");
            report.AppendLine();
            report.AppendLine($"- 공유 에셋 합계: **{shared / 1024} KB** (항목 수와 무관하게 한 번만 든다)");
            report.AppendLine($"- 아바타 소계: **{(grandUnique + shared) / 1024 / 1024.0:F1} MB**");
            report.AppendLine();

            // 개별 항목 상위 — 어디를 줄여야 효과가 큰지.
            report.AppendLine("### 고유 용량 상위 10개");
            report.AppendLine();
            report.AppendLine("| 항목 | 카테고리 | 고유 KB |");
            report.AppendLine("|---|---|---:|");
            var ranked = items
                .Select(x => (item: x, size: perItemDeps[x].Where(d => refCount[d] == 1).Sum(FileSize)))
                .OrderByDescending(x => x.size).Take(10);
            foreach (var (item, size) in ranked)
                report.AppendLine($"| {item.displayName} | {item.category} | {size / 1024} |");
            report.AppendLine();
        }

        // ---------- 썸네일 (C-05) ----------

        static void AppendThumbnailGap(StringBuilder report, AvatarItemDefinition[] items)
        {
            var missing = items.Where(x => x.thumbnail == null).ToArray();
            report.AppendLine("## C-05 — 썸네일 보유 현황");
            report.AppendLine();
            report.AppendLine($"- 보유 {items.Length - missing.Length} / {items.Length}");

            if (missing.Length == 0)
            {
                report.AppendLine("- 누락 없음");
            }
            else
            {
                // 썸네일이 없으면 사용자는 이름 텍스트만 보고 골라야 한다 (spec 013 C-05).
                report.AppendLine($"- **누락 {missing.Length}개** — 이 항목들은 이름 텍스트로만 고르게 된다");
                report.AppendLine();
                foreach (var group in missing.GroupBy(x => x.category))
                    report.AppendLine($"  - {group.Key}: {string.Join(", ", group.Select(x => x.displayName))}");
            }
            report.AppendLine();
        }

        static void AppendSharedAssets(StringBuilder report, Dictionary<string, int> refCount)
        {
            var shared = refCount.Where(kv => kv.Value > 1)
                                 .OrderByDescending(kv => FileSize(kv.Key)).Take(10).ToArray();
            if (shared.Length == 0) return;

            report.AppendLine("## 공유 에셋 상위 10개");
            report.AppendLine();
            report.AppendLine("항목 수를 늘려도 이 용량은 늘지 않는다. 반대로 여기를 줄이면 전체가 줄어든다.");
            report.AppendLine();
            report.AppendLine("| 에셋 | 참조 항목 수 | KB |");
            report.AppendLine("|---|---:|---:|");
            foreach (var kv in shared)
                report.AppendLine($"| `{Path.GetFileName(kv.Key)}` | {kv.Value} | {FileSize(kv.Key) / 1024} |");
            report.AppendLine();
        }

        // ---------- 도구 ----------

        static AvatarCatalog FindCatalog()
        {
            var guids = AssetDatabase.FindAssets("t:" + nameof(AvatarCatalog));
            // 같은 이름의 레거시 카탈로그(Festa.World)가 따로 있어 타입으로 걸러야 한다.
            return guids
                .Select(g => AssetDatabase.LoadAssetAtPath<AvatarCatalog>(AssetDatabase.GUIDToAssetPath(g)))
                .FirstOrDefault(c => c != null && c.items != null && c.items.Length > 0);
        }

        /// <summary>항목이 끌고 오는 에셋 경로들. 자기 자신(.asset)은 뺀다 — 정의 파일은 무시할 만큼 작다.</summary>
        static string[] Dependencies(AvatarItemDefinition item)
        {
            var self = AssetDatabase.GetAssetPath(item);
            return AssetDatabase.GetDependencies(self, recursive: true)
                .Where(p => p != self && !p.EndsWith(".cs", StringComparison.OrdinalIgnoreCase))
                .Distinct()
                .ToArray();
        }

        static long FileSize(string assetPath)
        {
            try
            {
                var full = Path.Combine(Directory.GetParent(Application.dataPath)!.FullName, assetPath);
                return File.Exists(full) ? new FileInfo(full).Length : 0;
            }
            catch { return 0; }
        }
    }
}
