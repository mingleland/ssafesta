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
    /// </summary>
    [CreateAssetMenu(menuName = "FESTA/Booth Object Registry", fileName = "BoothObjectRegistry")]
    public class BoothObjectRegistry : ScriptableObject
    {
        [Serializable]
        public class Entry
        {
            public BoothObjectType type;
            public string assetCode;
            public GameObject prefab;
        }

        [SerializeField] List<Entry> _entries = new();

        Dictionary<BoothObjectType, GameObject> _map;
        Dictionary<string, GameObject> _assetMap;

        public GameObject GetPrefab(BoothObjectType type, string assetCode = null)
        {
            if (!string.IsNullOrEmpty(assetCode))
            {
                _assetMap ??= BuildAssetMap();
                if (_assetMap.TryGetValue(Key(type, assetCode), out var assetPrefab))
                    return assetPrefab;
            }

            _map ??= BuildMap();
            return _map.TryGetValue(type, out var prefab) ? prefab : null;
        }

        Dictionary<string, GameObject> BuildAssetMap()
        {
            var map = new Dictionary<string, GameObject>(StringComparer.OrdinalIgnoreCase);
            foreach (var e in _entries)
                if (e.prefab != null && !string.IsNullOrEmpty(e.assetCode))
                    map[Key(e.type, e.assetCode)] = e.prefab;
            return map;
        }

        static string Key(BoothObjectType type, string assetCode) => $"{type}:{assetCode}";

        Dictionary<BoothObjectType, GameObject> BuildMap()
        {
            var map = new Dictionary<BoothObjectType, GameObject>();
            foreach (var e in _entries)
                if (e.prefab != null) map[e.type] = e.prefab;
            return map;
        }
    }
}
