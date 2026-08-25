using System;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.Serialization;

namespace Festa.World
{
    /// <summary>
    /// presetCode → 외형 프리팹 매핑 (Sidekick Character Creator로 미리 구운 프리셋).
    /// 런타임 조립이 아니라 사전 제작 프리셋 방식 — WebGL 용량·성능 통제가 쉽고
    /// Sidekick 툴이 에디터 전용이어도 문제되지 않는다.
    ///
    /// 에디터에서 Create > FESTA > Avatar Catalog 로 생성.
    /// 프리셋을 늘리려면 Sidekick으로 FBX를 Export → 프리팹화 → 여기 엔트리 추가.
    /// </summary>
    [CreateAssetMenu(menuName = "FESTA/Avatar Catalog", fileName = "AvatarCatalog")]
    public class AvatarCatalog : ScriptableObject
    {
        [Serializable]
        public class Entry
        {
            // 과거 필드명 avatarCode로 저장된 에셋 값을 그대로 복원한다.
            [FormerlySerializedAs("avatarCode")]
            [Tooltip("동기화되는 프리셋 코드 (예: sk_01)")]
            public string presetCode;

            [Tooltip("커스터마이징 UI에 표시할 이름 (비우면 presetCode 사용)")]
            public string displayName;

            public GameObject prefab;
        }

        [SerializeField] List<Entry> _entries = new();

        [Tooltip("알 수 없는 presetCode일 때 사용할 기본 외형")]
        [SerializeField] GameObject _fallbackPrefab;

        [Header("커스터마이징 UI 색상 팔레트 (RRGGBB, 첫 항목은 '원본' 의미로 비워둠)")]
        [SerializeField]
        List<string> _tintPalette = new() { "", "E85D5D", "5D8CE8", "5DE887", "E8D25D", "B45DE8" };

        Dictionary<string, GameObject> _map;

        public IReadOnlyList<Entry> Entries => _entries;
        public IReadOnlyList<string> TintPalette => _tintPalette;

        public GameObject GetPrefab(string presetCode)
        {
            _map ??= BuildMap();

            if (!string.IsNullOrEmpty(presetCode) && _map.TryGetValue(presetCode, out var prefab))
                return prefab;

            return _fallbackPrefab;
        }

        public string GetDisplayName(string presetCode)
        {
            foreach (var e in _entries)
                if (e.presetCode == presetCode)
                    return string.IsNullOrEmpty(e.displayName) ? e.presetCode : e.displayName;
            return presetCode;
        }

        /// <summary>등록된 코드 목록 (UI·진단용).</summary>
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
                if (!string.IsNullOrEmpty(e.presetCode) && e.prefab != null)
                    map[e.presetCode] = e.prefab;
            return map;
        }
    }
}
