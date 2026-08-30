using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using UnityEngine;
using Festa.Avatar;

namespace Festa.Diagnostics
{
    /// <summary>
    /// 런타임 스킨메시 병합이 **어떤 외형 조합에서도 기하를 잃지 않는지** 검사한다
    /// (S15P21A604-236 완료 조건 "외형 조합별 시각 회귀 없음").
    ///
    /// <para><b>왜 스크린샷으로는 부족한가.</b> 30기를 세워 눈으로 보는 것은
    /// <c>AvatarStressSpawner</c> 가 색만 흔들고 <b>아이템 조합은 기본값 하나</b>라 조합을
    /// 검사하지 못한다. 그리고 사람 눈은 "허벅지 안쪽 폴리곤 한 덩이가 빠진 것" 을 못 잡는다.
    /// T-223(서브메시가 뭉개져 재질 4개가 통째로 미출력)도, T-214(옷을 갈아입으면 몸이 사라짐)도
    /// 유니티가 에러를 내지 않아 사람 눈으로만 발견됐고 그 사이 잘못된 상태가 머지됐다.</para>
    ///
    /// <para><b>대신 불변식을 검사한다.</b> 병합은 <b>같은 재질·같은 본·같은 바인드포즈</b>인
    /// 단일 서브메시들만 하나로 묶으므로, 조립 결과를 <c>재질 이름 → 그려지는 정점 수 합</c>
    /// 으로 요약하면 <b>병합 ON/OFF 가 정확히 같아야 한다.</b> 하나라도 어긋나면 그 조합에서
    /// 무언가 사라졌거나 겹쳐 그려지고 있다는 뜻이다 — 눈보다 엄격하고 자동이다.</para>
    ///
    /// <para>카탈로그의 <b>모든 아이템을 한 번씩</b> 입혀 본다. 전 조합(곱집합)은 폭발하므로
    /// 커버리지는 "아이템당 최소 1회" 다 — 특정 아이템이 병합에서 탈락하는 사고를 잡는 것이
    /// 목적이고, 그 사고는 아이템 단위로 일어난다.</para>
    /// </summary>
    public sealed class AvatarMergeIntegrity : MonoBehaviour
    {
        bool _running;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Install()
        {
            if (!Debug.isDebugBuild && !Application.isEditor) return;
            var go = new GameObject("@AvatarMergeIntegrity");
            go.hideFlags = HideFlags.HideAndDontSave;
            DontDestroyOnLoad(go);
            go.AddComponent<AvatarMergeIntegrity>();
        }

        void Update()
        {
            if (!_running && Input.GetKeyDown(KeyCode.F12)) StartCoroutine(Run());
        }

        IEnumerator Run()
        {
            var spawner = FindFirstObjectByType<AvatarStressSpawner>();
            var catalog = spawner != null ? spawner.Catalog : null;
            if (catalog == null)
            {
                Debug.LogError("[MergeIntegrity] 카탈로그를 찾지 못했다 — main 씬에서 돌려라.");
                yield break;
            }

            _running = true;
            bool restore = AvatarMeshMerge.Enabled;

            var categories = (AvatarPartCategory[])System.Enum.GetValues(typeof(AvatarPartCategory));
            var failures = new List<string>();
            int checkedCombos = 0;

            foreach (var gender in new[] { AvatarGender.Male, AvatarGender.Female })
            {
                // 기본 조합 자체도 한 번 본다 — 아이템을 갈아끼우지 않은 상태가 기준선이다.
                yield return Compare(catalog, catalog.CreateDefault(gender), $"{gender}/기본",
                                     failures, () => checkedCombos++);

                foreach (var category in categories)
                {
                    foreach (var item in catalog.GetItems(category, gender).ToList())
                    {
                        if (item == null) continue;
                        var config = catalog.CreateDefault(gender);
                        config.SetItem(category, item.itemId);
                        yield return Compare(catalog, config, $"{gender}/{category}/{item.name}",
                                             failures, () => checkedCombos++);
                    }
                }
            }

            AvatarMeshMerge.Enabled = restore;

            if (failures.Count == 0)
                Debug.Log($"[MergeIntegrity] 통과 — 조합 {checkedCombos}개에서 병합 ON/OFF 의 " +
                          "재질별 정점 수가 완전히 일치한다. 병합으로 잃은 기하가 없다.");
            else
                Debug.LogError($"[MergeIntegrity] 실패 {failures.Count}/{checkedCombos} — " +
                               "병합이 기하를 바꿨다.\n" + string.Join("\n", failures));

            _running = false;
        }

        IEnumerator Compare(AvatarCatalog catalog, AvatarConfig config, string label,
                            List<string> failures, System.Action counted)
        {
            AvatarMeshMerge.Enabled = false;
            var plain = Summarize(catalog, config, out var plainGo);

            AvatarMeshMerge.Enabled = true;
            var merged = Summarize(catalog, config, out var mergedGo);

            // Destroy 는 프레임 끝에 처리된다 — 다음 조합을 세우기 전에 프레임을 넘긴다 (T-228).
            if (plainGo) Destroy(plainGo);
            if (mergedGo) Destroy(mergedGo);
            yield return null;

            counted();
            var diff = Diff(plain, merged);
            if (diff != null) failures.Add($"{label} — {diff}");
        }

        /// <summary>조립 결과를 <c>재질 이름 → 그려지는 정점 수 합</c> 으로 요약한다.</summary>
        static Dictionary<string, int> Summarize(AvatarCatalog catalog, AvatarConfig config, out GameObject go)
        {
            go = new GameObject("MergeIntegrityProbe");
            go.transform.position = new Vector3(0f, -10000f, 0f);   // 화면 밖 — 측정 중인 화면을 건드리지 않는다
            var assembler = go.AddComponent<AvatarAssembler>();
            assembler.Catalog = catalog;
            assembler.Apply(config);

            var sum = new Dictionary<string, int>();
            foreach (var r in go.GetComponentsInChildren<SkinnedMeshRenderer>(false))
            {
                if (!r.enabled || r.sharedMesh == null) continue;
                foreach (var m in r.sharedMaterials)
                {
                    if (m == null) continue;
                    // 런타임 인스턴스라 이름에 " (Instance)" 가 붙을 수 있다 — 형태를 맞춘다.
                    var key = m.name.Replace(" (Instance)", "");
                    sum.TryGetValue(key, out var v);
                    sum[key] = v + r.sharedMesh.vertexCount;
                }
            }
            return sum;
        }

        /// <summary>다르면 사람이 읽을 설명, 같으면 null.</summary>
        static string Diff(Dictionary<string, int> plain, Dictionary<string, int> merged)
        {
            var sb = new StringBuilder();
            foreach (var kv in plain)
            {
                if (!merged.TryGetValue(kv.Key, out var got))
                    sb.Append($"[{kv.Key}] 병합 후 사라짐(정점 {kv.Value}) ");
                else if (got != kv.Value)
                    sb.Append($"[{kv.Key}] 정점 {kv.Value} → {got} ");
            }
            foreach (var kv in merged)
                if (!plain.ContainsKey(kv.Key))
                    sb.Append($"[{kv.Key}] 병합 후에만 존재(정점 {kv.Value}) ");

            return sb.Length == 0 ? null : sb.ToString().TrimEnd();
        }
    }
}
