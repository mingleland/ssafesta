using System;
using System.Collections.Generic;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// avatarCode → 외형 프리팹 매핑 (Sidekick Character Creator로 미리 구운 프리셋).
    /// 런타임 조립이 아니라 사전 제작 프리셋 방식 — WebGL 용량·성능 통제가 쉽고
    /// Sidekick 툴이 에디터 전용이어도 문제되지 않는다.
    ///
    /// 에디터에서 Create > FESTA > Avatar Catalog 로 생성.
    /// </summary>
    [CreateAssetMenu(menuName = "FESTA/Avatar Catalog", fileName = "AvatarCatalog")]
    public class AvatarCatalog : ScriptableObject
    {
        [Serializable]
        public class Entry
        {
            [Tooltip("Spring User 프로필의 avatarCode와 동일한 문자열 (예: sk_01)")]
            public string avatarCode;
            public GameObject prefab;
        }

        [SerializeField] List<Entry> _entries = new();

        [Tooltip("알 수 없는 avatarCode일 때 사용할 기본 외형")]
        [SerializeField] GameObject _fallbackPrefab;

        Dictionary<string, GameObject> _map;

        public GameObject GetPrefab(string avatarCode)
        {
            _map ??= BuildMap();

            if (!string.IsNullOrEmpty(avatarCode) && _map.TryGetValue(avatarCode, out var prefab))
                return prefab;

            return _fallbackPrefab;
        }

        /// <summary>등록된 코드 목록 (프로필 UI·검증용).</summary>
        public IEnumerable<string> AllCodes
        {
            get
            {
                _map ??= BuildMap();
                return _map.Keys;
            }
        }

        Dictionary<string, GameObject> BuildMap()
        {
            var map = new Dictionary<string, GameObject>();
            foreach (var e in _entries)
                if (!string.IsNullOrEmpty(e.avatarCode) && e.prefab != null)
                    map[e.avatarCode] = e.prefab;
            return map;
        }
    }
}
