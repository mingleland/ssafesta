using System.Collections;
using System.Collections.Generic;
using TMPro;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 축제장 하이 스트라이커(망치 게임)의 **보이는 부분** — 퍽 상승·별 점등·점수판 (사용자 지시 2026-09-10, S15P21A604-585).
    ///
    /// <para>여기에는 판정이 없다. 판정은 서버가 하고(<see cref="HighStrikerNetwork"/>), 이 컴포넌트는
    /// 전달받은 세기(0~1)와 점수를 그리기만 한다 — 그래서 같은 채널의 모든 사용자가 같은 장면을 본다.
    /// 씬 오브젝트라 <c>NetworkObject</c> 가 없다(부스 정적 오브젝트 Local Spawn 원칙과 같다).</para>
    ///
    /// <para>벤더 메시(<c>SM_High_Striker_Bell_Tower</c>)는 정적 한 덩이라 움직일 수 있는 부품이 없다.
    /// 그래서 퍽·별·점수판을 <see cref="Festa.EditorTools.FestaHighStrikerBuilder"/> 가 실측 위치에 따로 세우고,
    /// 이 컴포넌트가 그것들을 이름으로 잡아 움직인다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class HighStrikerMachine : MonoBehaviour
    {
        /// <summary>씬이 정한 canonical id. RPC 는 이 값으로 기계를 찾는다.</summary>
        [SerializeField] string _machineId = "plaza-high-striker-01";

        // 타격감이 나려면 **때린 순간에 이미 올라가 있어야 한다** (사용자 지적 2026-09-10 — "빨리 쾅 하고
        // 점수도 빠르게"). 0.45초에 걸쳐 올리자 망치가 닿은 뒤 퍽이 슬슬 따라가는 것처럼 보였다.
        [Header("연출 시간(초)")]
        [SerializeField] float _riseSeconds = 0.14f;
        [SerializeField] float _holdSeconds = 1.1f;
        [SerializeField] float _fallSeconds = 0.4f;

        [SerializeField] Transform _starRoot;
        [SerializeField] TextMeshPro _scoreText;
        [SerializeField] Material _starOffMaterial;

        /// <summary>켜진 색을 높이에 따라 나눈다 — 아래 초록, 가운데 호박, 위 빨강 (레퍼런스 사다리).</summary>
        [SerializeField] Material[] _starOnMaterials = new Material[0];

        readonly List<Renderer> _stars = new();
        Coroutine _running;

        public string MachineId => _machineId;

        // CarnivalKit 메시 정점 실측: 버튼 중앙 (0, 0.409, 0.580).
        // 기계의 이동·회전·배율을 포함해 망치의 실제 접촉점을 계산한다.
        public Vector3 StrikeContact => transform.TransformPoint(new Vector3(0f, 0.409f, 0.580f));
        public Vector3 PlayerFacing => -transform.forward;

        float _busyUntil;

        /// <summary>
        /// 연출이 도는 동안 새 스윙을 받지 않는다 — **모든 사용자에게** 막힌다 (사용자 지시 2026-09-10).
        ///
        /// <para>코루틴이 도는지만 보면 부족하다. 스윙은 서버 브로드캐스트 뒤 임팩트까지 기다렸다 시작하므로,
        /// 그 사이(0.45초)에 옆 사람이 F 를 누르면 두 번째 스윙이 끼어든다. 그래서 브로드캐스트를 받는
        /// 순간부터 <see cref="BeginBusy"/> 로 창을 열어 둔다.</para>
        /// </summary>
        public bool IsBusy => _running != null || Time.time < _busyUntil;

        /// <summary>지금부터 <paramref name="seconds"/> 동안 이 기계를 잠근다.</summary>
        public void BeginBusy(float seconds) => _busyUntil = Mathf.Max(_busyUntil, Time.time + seconds);

        /// <summary>임팩트 대기까지 포함한 한 스윙의 총 길이. 잠금 창을 이 값으로 연다.</summary>
        public float SequenceSeconds => _riseSeconds + _holdSeconds + _fallSeconds;

        static readonly Dictionary<string, HighStrikerMachine> s_machines = new();

        /// <summary>id 로 기계를 찾는다. 없으면 null — 씬에 없는 id 를 RPC 가 실어 와도 터지지 않게.</summary>
        public static HighStrikerMachine Find(string machineId)
        {
            if (string.IsNullOrEmpty(machineId)) return null;
            HighStrikerMachine m;
            return s_machines.TryGetValue(machineId, out m) && m != null ? m : null;
        }

        /// <summary>씬에 있는 기계 하나(첫 번째). 상호작용 쪽이 id 를 모를 때의 대체 경로.</summary>
        public static HighStrikerMachine Any()
        {
            foreach (var kv in s_machines) if (kv.Value != null) return kv.Value;
            return null;
        }

        void OnEnable()
        {
            s_machines[_machineId] = this;
            CollectStars();
            SetStars(0);
        }

        void OnDisable()
        {
            HighStrikerMachine mine;
            if (s_machines.TryGetValue(_machineId, out mine) && mine == this) s_machines.Remove(_machineId);
        }

        void CollectStars()
        {
            _stars.Clear();
            if (_starRoot == null) return;
            foreach (Transform t in _starRoot)
            {
                var r = t.GetComponent<Renderer>();
                if (r != null) _stars.Add(r);
            }
        }

        /// <summary>별 개수(1~8)에 해당하는 점수. 무작위 세기를 그대로 999점 만점으로 편다.</summary>
        public static int ScoreFromPower(float power) => Mathf.Clamp(Mathf.RoundToInt(power * 999f), 1, 999);

        /// <summary>세기(0~1)에 켜지는 별 개수. 최대면 종을 울린 것으로 본다.</summary>
        public int StarsFromPower(float power) =>
            Mathf.Clamp(Mathf.CeilToInt(power * Mathf.Max(1, _stars.Count)), 1, Mathf.Max(1, _stars.Count));

        /// <summary>연출을 재생한다. 서버 판정값이 그대로 들어온다 — 여기서 다시 굴리지 않는다.</summary>
        public void PlaySwing(float power, int score, string nickname)
        {
            if (_running != null) StopCoroutine(_running);
            _running = StartCoroutine(SwingRoutine(Mathf.Clamp01(power), score, nickname));
        }

        /// <summary>애니메이션 없이 마지막 기록만 표시한다 — 늦게 들어온 사람용.</summary>
        public void ShowRecord(int score, string nickname)
        {
            SetStars(0);
            WriteScoreboard(score, nickname, false);
        }

        IEnumerator SwingRoutine(float power, int score, string nickname)
        {
            int stars = StarsFromPower(power);
            bool bell = stars >= _stars.Count && _stars.Count > 0;

            WriteScoreboard(score, nickname, bell);

            // 차오른다 — 처음이 폭발적이고 끝이 느린 감속(실제 기계의 관성). 세제곱으로 눌러 "쾅" 이 나게.
            float t = 0f;
            while (t < _riseSeconds)
            {
                t += Time.deltaTime;
                float k = Mathf.Clamp01(t / _riseSeconds);
                float eased = 1f - (1f - k) * (1f - k) * (1f - k);
                SetStars(Mathf.CeilToInt(eased * stars));
                yield return null;
            }
            SetStars(stars);

            yield return new WaitForSeconds(_holdSeconds);

            // 위에서부터 꺼지며 내려온다.
            t = 0f;
            while (t < _fallSeconds)
            {
                t += Time.deltaTime;
                float k = Mathf.Clamp01(t / _fallSeconds);
                SetStars(Mathf.CeilToInt(Mathf.Lerp(stars, 0f, k * k)));
                yield return null;
            }
            SetStars(0);
            _running = null;
            _busyUntil = 0f;
        }

        /// <summary>켜진 칸 색 — 높이에 따라 초록 → 호박 → 빨강. 재료가 하나뿐이면 그것만 쓴다.</summary>
        Material OnMaterial(int index)
        {
            if (_starOnMaterials == null || _starOnMaterials.Length == 0) return null;
            if (_starOnMaterials.Length == 1 || _stars.Count <= 1) return _starOnMaterials[0];
            int tier = Mathf.Clamp(Mathf.FloorToInt(index / (float)_stars.Count * _starOnMaterials.Length), 0, _starOnMaterials.Length - 1);
            return _starOnMaterials[tier];
        }

        void SetStars(int litCount)
        {
            for (var i = 0; i < _stars.Count; i++)
            {
                if (_stars[i] == null) continue;
                var wanted = i < litCount ? OnMaterial(i) : _starOffMaterial;
                if (wanted != null && _stars[i].sharedMaterial != wanted) _stars[i].sharedMaterial = wanted;
            }
        }

        void WriteScoreboard(int score, string nickname, bool bell)
        {
            if (_scoreText == null) return;
            string who = string.IsNullOrEmpty(nickname) ? "누군가" : nickname;
            _scoreText.text = bell
                ? $"{who}\n{score}점 · 종을 울렸다!"
                : $"{who}\n{score}점";
        }
    }
}
