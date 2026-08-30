using System.Collections;
using System.Collections.Generic;
using System.Text;
using UnityEngine;
using Festa.Avatar;
using Festa.World;

namespace Festa.Diagnostics
{
    /// <summary>
    /// 원격 아바타 최적화 3종의 프레임타임 기여를 **한 세션 안에서 교대로** 잰다
    /// (S15P21A604-236 완료 조건: "30기 동시 표시 시 프레임타임 개선 수치 전/후").
    ///
    /// <para><b>왜 손으로 재지 않는가.</b> 조건을 바꿔 가며 사람이 재면 세 가지가 조용히 무너진다 —
    /// ① 조건 A 를 다 재고 조건 B 를 재면 그 사이의 드리프트(열·백그라운드 부하)가 차이에 섞인다
    /// ② 아바타를 지운 <b>같은 프레임</b>에 다시 세면 <c>Destroy</c> 가 프레임 끝까지 미뤄지므로
    /// 이전 회차가 그대로 남아 누적된다 (T-228 과 같은 함정) ③ 프레임타임은 튀는 값이라
    /// 평균만 보면 스파이크 하나에 결론이 뒤집힌다.</para>
    ///
    /// <para>그래서 <b>조건을 교대로</b> 여러 회차 돌리고, 회차 사이에 반드시 <b>한 프레임을 넘기고</b>,
    /// 대표값으로 <b>중앙값</b>을 쓴다. 평균·p95 도 같이 남겨 분포가 이상하면 드러나게 한다.</para>
    ///
    /// <para>⚠ <b>에디터 측정이다.</b> WebGL 실측이 아니다. 2026-08-25 비용 분해도 에디터에서 했으므로
    /// 같은 기준끼리는 비교되지만, 절대값을 배포 성능으로 인용하면 안 된다.</para>
    /// </summary>
    public sealed class AvatarCostAb : MonoBehaviour
    {
        const int Avatars = 30;        // 완료 조건이 지정한 인원
        const int SettleFrames = 120;  // 소환 직후의 조립·GC 스파이크를 버린다
        const int SampleFrames = 300;  // 표본 구간
        const int Rounds = 3;          // 조건당 회차 — 교대로 돌려 드리프트를 상쇄한다

        /// <summary>한 조건의 손잡이 조합. 이름은 로그에 그대로 찍힌다.</summary>
        readonly struct Condition
        {
            public readonly string Name;
            public readonly bool Merge;
            public readonly bool Lod;
            public readonly AnimatorCullingMode Culling;

            public Condition(string name, bool merge, bool lod, AnimatorCullingMode culling)
            {
                Name = name; Merge = merge; Lod = lod; Culling = culling;
            }
        }

        static readonly Condition[] Conditions =
        {
            // "전" — 스트레스 스포너의 기본값이 곧 최적화 이전 상태다.
            new("전 (최적화 없음)", false, false, AnimatorCullingMode.AlwaysAnimate),
            // 손잡이를 하나씩만 켠 조건. **어느 것이 실제로 값을 내는지 추론하지 않고 재기 위해서다.**
            // 1차 측정에서 30기가 전부 카메라 58~103 unit 에 절두체 안으로 들어왔는데
            // LOD 의 근거리 경계는 220 unit 이다 — 즉 LOD·컬링은 이 조건에서 할 일이 없다.
            // 그 예상이 맞는지 숫자로 확인한다. "전과 같다" 도 결과다.
            new("병합만", true, false, AnimatorCullingMode.AlwaysAnimate),
            new("LOD만", false, true, AnimatorCullingMode.AlwaysAnimate),
            new("컬링만", false, false, AnimatorCullingMode.CullUpdateTransforms),
            // "후" — 지금 프로덕션이 켜 두는 조합.
            new("후 (병합+LOD+컬링)", true, true, AnimatorCullingMode.CullUpdateTransforms),
        };

        readonly Dictionary<string, List<float>> _medians = new();
        readonly Dictionary<string, int> _renderers = new();
        bool _running;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Install()
        {
            // 배치를 빠뜨리면 정작 필요할 때 없다 — 스스로 붙는다 (AvatarMeshMerge 와 같은 이유).
            if (!Debug.isDebugBuild && !Application.isEditor) return;
            var go = new GameObject("@AvatarCostAb");
            go.hideFlags = HideFlags.HideAndDontSave;
            DontDestroyOnLoad(go);
            go.AddComponent<AvatarCostAb>();
        }

        void Update()
        {
            if (!_running && Input.GetKeyDown(KeyCode.F9)) StartCoroutine(Run());
        }

        IEnumerator Run()
        {
            var spawner = FindFirstObjectByType<AvatarStressSpawner>();
            if (spawner == null)
            {
                Debug.LogError("[AvatarCostAb] AvatarStressSpawner 가 씬에 없다 — main 씬에서 돌려라.");
                yield break;
            }

            _running = true;
            _medians.Clear();
            _renderers.Clear();
            Debug.Log($"[AvatarCostAb] 시작 — {Avatars}기 × 조건 {Conditions.Length}개 × {Rounds}회차 교대");

            // 회차를 바깥 루프에 둔다: A,B,A,B,A,B 순서라야 드리프트가 두 조건에 고르게 실린다.
            for (int round = 0; round < Rounds; round++)
                foreach (var c in Conditions)
                    yield return Measure(spawner, c, round);

            Report();
            _running = false;
        }

        IEnumerator Measure(AvatarStressSpawner spawner, Condition c, int round)
        {
            // ① 비운다. **반드시 프레임을 넘긴 뒤** 다음 회차를 세운다 —
            //    Destroy 는 프레임 끝까지 미뤄지므로 같은 프레임에 세면 이전 회차가 살아 있다.
            spawner.Remove(spawner.Count);
            yield return null;

            // ② 손잡이는 소환 **전에** 건다 — 병합은 조립 시점에 결정된다.
            AvatarMeshMerge.Enabled = c.Merge;
            AvatarAnimationLod.Enabled = c.Lod;

            spawner.Add(Avatars);

            // ③ 컬링 모드는 소환된 애니메이터에 직접 건다. 스포너는 "최적화 전" 을 정직하게
            //    재려고 항상 AlwaysAnimate 로 두기 때문이다.
            foreach (var a in spawner.GetComponentsInChildren<Animator>(true))
                a.cullingMode = c.Culling;

            for (int i = 0; i < SettleFrames; i++) yield return null;

            _renderers[c.Name] = CountActiveSkins();

            var samples = new List<float>(SampleFrames);
            for (int i = 0; i < SampleFrames; i++)
            {
                samples.Add(Time.unscaledDeltaTime * 1000f);
                yield return null;
            }
            samples.Sort();

            float median = samples[samples.Count / 2];
            if (!_medians.TryGetValue(c.Name, out var list))
                _medians[c.Name] = list = new List<float>();
            list.Add(median);

            Debug.Log($"[AvatarCostAb] {round + 1}회차 · {c.Name} — 중앙값 {median:F2} ms " +
                      $"(평균 {Mean(samples):F2}, p95 {samples[(int)(samples.Count * 0.95f)]:F2}), " +
                      $"활성 스킨메시 {_renderers[c.Name]}");
        }

        void Report()
        {
            var sb = new StringBuilder();
            sb.AppendLine($"[AvatarCostAb] 결과 — {Avatars}기, 회차당 {SampleFrames}프레임, {Rounds}회차 교대");
            sb.AppendLine("| 조건 | 회차별 중앙값 (ms) | 대표(중앙값의 중앙값) | 활성 스킨메시 |");
            sb.AppendLine("|---|---|---:|---:|");

            float baseline = float.NaN;
            foreach (var c in Conditions)
            {
                var list = _medians[c.Name];
                var sorted = new List<float>(list);
                sorted.Sort();
                float rep = sorted[sorted.Count / 2];
                if (float.IsNaN(baseline)) baseline = rep;

                sb.AppendLine($"| {c.Name} | {string.Join(" / ", list.ConvertAll(v => v.ToString("F2")))} " +
                              $"| **{rep:F2}** | {_renderers[c.Name]} |");
            }

            var after = _medians[Conditions[Conditions.Length - 1].Name];
            var afterSorted = new List<float>(after);
            afterSorted.Sort();
            float afterRep = afterSorted[afterSorted.Count / 2];

            sb.AppendLine();
            sb.AppendLine($"차이: {baseline:F2} → {afterRep:F2} ms " +
                          $"({baseline - afterRep:+0.00;-0.00} ms, {(baseline - afterRep) / baseline * 100f:F1}%)");
            sb.AppendLine("⚠ 에디터 측정이다 — 절대값을 배포 성능으로 인용하지 마라.");
            Debug.Log(sb.ToString());
        }

        int CountActiveSkins()
        {
            int n = 0;
            foreach (var r in FindObjectsByType<SkinnedMeshRenderer>(FindObjectsSortMode.None))
                if (r.enabled && r.gameObject.activeInHierarchy) n++;
            return n;
        }

        static float Mean(List<float> v)
        {
            float sum = 0f;
            foreach (var x in v) sum += x;
            return sum / v.Count;
        }
    }
}
