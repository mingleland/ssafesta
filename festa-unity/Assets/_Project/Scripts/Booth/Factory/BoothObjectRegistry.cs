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
            public GameObject prefab;
        }

        [SerializeField] List<Entry> _entries = new();

        Dictionary<BoothObjectType, GameObject> _map;

        public GameObject GetPrefab(BoothObjectType type)
        {
            _map ??= BuildMap();
            return _map.TryGetValue(type, out var prefab) ? prefab : null;
        }

        Dictionary<BoothObjectType, GameObject> BuildMap()
        {
            var map = new Dictionary<BoothObjectType, GameObject>();
            foreach (var e in _entries)
                if (e.prefab != null) map[e.type] = e.prefab;
            return map;
        }
    }
}
