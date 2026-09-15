using System;
using System.Collections.Generic;
using UnityEngine;

namespace Festa.Booth
{
    /// <summary>
    /// BoothObjectType → Prefab 매핑 (VampireSurvivors의 데이터 기반 리소스 매핑 방식 차용,
    /// Addressables 도입 전에는 직접 참조로 단순 유지).
    /// 에디터에서 Create > FESTA > Booth Object Registry 로 생성해 프리팹을 연결한다.
    /// 매핑이 없는 타입은 Factory가 placeholder primitive로 대체한다.
    ///
    /// <para><b>타입 기본 자산은 배열 순서로 정해지지 않는다</b> (T-148, S15P21A604-104).
    /// 예전 <c>BuildMap</c> 은 같은 타입의 엔트리를 last-wins 로 덮어써서, 한 타입에 자산이
    /// 둘 이상 되는 순간 <b>배열 끝에 있는 것</b>이 기본이 됐다. 오타 fallback(T-145)과 겹치면
    /// 오타 하나로 예측 불가능한 자산이 경고 없이 나온다. 지금은 아래 순서로 정한다:</para>
    ///
    /// <list type="number">
    /// <item><c>assetCode</c> 가 비어 있는 엔트리 = <b>명시적 타입 기본</b>. 이게 있으면 그것을 쓴다.</item>
    /// <item>없고 그 타입의 엔트리가 <b>하나뿐</b>이면 그것을 쓴다 — 모호하지 않다.
    ///       (현재 <c>Furniture</c>·<c>Decoration</c> 이 이 경우다. 코드가 붙어 있지만 후보가 하나다.)</item>
    /// <item>없고 후보가 <b>둘 이상</b>이면 <b>경고하고</b> 첫 엔트리를 쓴다.
    ///       조용히 순서에 맡기지 않는다 — 고치는 방법까지 로그에 적는다.</item>
    /// </list>
    /// </summary>
    [CreateAssetMenu(menuName = "FESTA/Booth Object Registry", fileName = "BoothObjectRegistry")]
    public class BoothObjectRegistry : ScriptableObject
    {
        [Serializable]
        public class Entry
        {
            public BoothObjectType type;

            [Tooltip("비워 두면 이 타입의 기본 자산이 된다. 값이 있으면 그 코드로만 선택된다.")]
            public string assetCode;

            public GameObject prefab;
        }

        [SerializeField] List<Entry> _entries = new();

        Dictionary<BoothObjectType, GameObject> _defaultMap;
        Dictionary<string, GameObject> _assetMap;

        public GameObject GetPrefab(BoothObjectType type, string assetCode = null)
        {
            if (!string.IsNullOrEmpty(assetCode))
            {
                _assetMap ??= BuildAssetMap();
                if (_assetMap.TryGetValue(Key(type, assetCode), out var assetPrefab))
                    return assetPrefab;

                // 값이 있는데 못 찾은 경우는 대부분 오타다. 미지정(정상 경로)과 구분해 알린다.
                Debug.LogWarning(
                    $"[BoothObjectRegistry] Unknown assetCode '{assetCode}' for type {type} — 타입 기본 자산으로 대체");
            }

            _defaultMap ??= BuildDefaultMap();
            return _defaultMap.TryGetValue(type, out var prefab) ? prefab : null;
        }

        /// <summary>
        /// 캐시를 버린다.
        ///
        /// 예전에는 <c>??=</c> 로 최초 1회만 만들고 무효화 수단이 없었다. 엔트리를 추가한 직후
        /// 조회하면 <b>옛 결과가 나왔고</b>, 리플렉션으로 캐시를 비워야 새 엔트리가 잡혔다
        /// (T-148 증상 2). 캐시를 만들 때는 버리는 경로도 같이 만들어야 한다.
        /// </summary>
        public void Invalidate()
        {
            _defaultMap = null;
            _assetMap = null;
        }

        void OnEnable() => Invalidate();

#if UNITY_EDITOR
        // 인스펙터에서 엔트리를 고치면 즉시 반영된다.
        void OnValidate() => Invalidate();
#endif

        Dictionary<string, GameObject> BuildAssetMap()
        {
            var map = new Dictionary<string, GameObject>(StringComparer.OrdinalIgnoreCase);
            foreach (var e in _entries)
            {
                if (e?.prefab == null || string.IsNullOrEmpty(e.assetCode)) continue;

                var key = Key(e.type, e.assetCode);
                if (map.ContainsKey(key))
                {
                    // 같은 코드가 둘이면 어느 쪽이 나올지가 순서에 달린다.
                    Debug.LogWarning(
                        $"[BoothObjectRegistry] assetCode 중복 — {e.type}:{e.assetCode} 가 2개 이상이다. " +
                        "먼저 나온 것을 쓴다. 레지스트리에서 중복을 지워라.");
                    continue;
                }
                map[key] = e.prefab;
            }
            return map;
        }

        static string Key(BoothObjectType type, string assetCode) => $"{type}:{assetCode}";

        Dictionary<BoothObjectType, GameObject> BuildDefaultMap()
        {
            // 타입별로 후보를 모은 뒤 정한다. 순회하며 대입하면 그 자체가 last-wins 다.
            var byType = new Dictionary<BoothObjectType, List<Entry>>();
            foreach (var e in _entries)
            {
                if (e?.prefab == null) continue;
                if (!byType.TryGetValue(e.type, out var list))
                    byType[e.type] = list = new List<Entry>();
                list.Add(e);
            }

            var map = new Dictionary<BoothObjectType, GameObject>();
            foreach (var pair in byType)
            {
                map[pair.Key] = ResolveDefault(pair.Key, pair.Value);
            }
            return map;
        }

        /// <summary>후보 중 타입 기본을 고른다. 고를 수 없으면 그 사실을 로그로 드러낸다.</summary>
        static GameObject ResolveDefault(BoothObjectType type, List<Entry> candidates)
        {
            Entry explicitDefault = null;
            int blanks = 0;
            foreach (var e in candidates)
            {
                if (!string.IsNullOrEmpty(e.assetCode)) continue;
                blanks++;
                explicitDefault ??= e;
            }

            if (blanks > 1)
                Debug.LogWarning(
                    $"[BoothObjectRegistry] {type} 의 기본 자산이 {blanks}개 선언됐다 " +
                    "(assetCode 가 빈 엔트리). 먼저 나온 것을 쓴다 — 하나만 남겨라.");

            if (explicitDefault != null) return explicitDefault.prefab;

            // 빈 코드 엔트리가 없다. 후보가 하나면 모호하지 않으므로 그대로 쓴다 —
            // Furniture·Decoration 이 이 경우다(코드가 붙어 있지만 후보가 하나).
            if (candidates.Count == 1) return candidates[0].prefab;

            // 여기가 T-148 의 실재 위험 지점이다. 배열 순서에 맡기지 않고 드러낸다.
            Debug.LogWarning(
                $"[BoothObjectRegistry] {type} 의 타입 기본 자산이 정해지지 않았다 — " +
                $"assetCode 가 빈 엔트리가 없고 후보가 {candidates.Count}개다. " +
                $"'{candidates[0].assetCode}' 를 쓰지만 이는 배열 순서일 뿐이다. " +
                "기본으로 쓸 엔트리의 assetCode 를 비워라.");
            return candidates[0].prefab;
        }
    }
}
