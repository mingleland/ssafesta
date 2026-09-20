using System.Collections.Generic;
using UnityEngine;

namespace Festa.Booth
{
    /// <summary>
    /// Facade 의 primaryColor 를 부스 셸에 칠한다.
    ///
    /// 머티리얼 인스턴스는 **부스당 1개**만 만든다. 렌더러마다 만들면 색 종류가 아니라
    /// 부스 수 × 패널 수만큼 늘어난다 — issue #17 에서 지적된 비용이 정확히 이것이다.
    /// 같은 색이면 인스턴스를 재사용하므로 실제 머티리얼 수는 "쓰이는 색 종류" 만큼만 늘어난다.
    ///
    /// Unity 는 색을 검증하지 않는다. 값 유효성은 서버 몫이고(헌법 16조),
    /// 여기서는 파싱 실패 시 기본색을 유지하고 경고만 남긴다.
    /// </summary>
    public sealed class BoothFacadeApplier
    {
        // 같은 (원본, 색) → 같은 머티리얼. 부스가 여러 개여도 색 종류만큼만 생성된다.
        static readonly Dictionary<(int, Color), Material> _cache = new();

        // 우리가 만든 인스턴스 → 그 원본. 재적용 시 인스턴스를 원본으로 되돌려 키를 잡아야 한다.
        // 이게 없으면 두 번째 적용부터 인스턴스가 다시 원본이 되어 무한히 중첩된다.
        static readonly Dictionary<Material, Material> _instanceToSource = new();

        readonly IReadOnlyList<string> _targetMaterialNames;
        readonly string _colorProperty;

        public BoothFacadeApplier(IReadOnlyList<string> targetMaterialNames, string colorProperty = "_BaseColor")
        {
            _targetMaterialNames = targetMaterialNames;
            _colorProperty = colorProperty;
        }

        /// <summary>
        /// 셸 하위에서 대상 머티리얼을 쓰는 슬롯을 찾아 색을 적용한다.
        /// 적용된 슬롯 수를 반환한다. 0 이면 대상을 못 찾은 것이므로 호출부가 경고할 수 있다.
        /// </summary>
        public int Apply(Transform shell, string primaryColorHex)
        {
            if (shell == null) return 0;
            if (!BoothFacadeParser.TryParseHexColor(primaryColorHex, out var color)) return 0;

            int applied = 0;
            foreach (var renderer in shell.GetComponentsInChildren<Renderer>(true))
            {
                var mats = renderer.sharedMaterials;
                bool changed = false;

                for (var i = 0; i < mats.Length; i++)
                {
                    if (mats[i] == null || !IsTarget(mats[i].name)) continue;
                    applied++;

                    // 서버는 그리지 않으므로 틴트 인스턴스를 만들지 않는다 (S15P21A604-314).
                    // 다만 **개수는 그대로 센다** — 여기서 0 을 반환해버리면 호출부가
                    // "대상 머티리얼을 못 찾았다"고 경고한다. 서버에서만 뜨는 가짜 경고를
                    // 만들면 진짜 계약 불일치를 찾을 때 방해가 된다.
                    if (Festa.Core.HeadlessRuntime.IsHeadless) continue;

                    mats[i] = GetTinted(mats[i], color);
                    changed = true;
                }

                if (changed) renderer.sharedMaterials = mats;
            }
            return applied;
        }

        bool IsTarget(string materialName)
        {
            foreach (var n in _targetMaterialNames)
                // 인스턴스는 이름이 "Foo (Instance)" 가 되므로 StartsWith 로 본다.
                if (!string.IsNullOrEmpty(n) && materialName.StartsWith(n)) return true;
            return false;
        }

        Material GetTinted(Material current, Color color)
        {
            // 이미 우리가 만든 인스턴스라면 그 원본으로 되돌려 키를 잡는다.
            // 그러지 않으면 재적용마다 인스턴스가 중첩돼 이름이 "Carpet (#A) (#B)" 로 늘어난다.
            var source = _instanceToSource.TryGetValue(current, out var origin) ? origin : current;

            var key = (source.GetInstanceID(), color);
            if (_cache.TryGetValue(key, out var cached) && cached != null) return cached;

            var instance = new Material(source) { name = $"{source.name} ({ColorKey(color)})" };
            if (instance.HasProperty(_colorProperty)) instance.SetColor(_colorProperty, color);
            if (instance.HasProperty("_Color")) instance.SetColor("_Color", color);

            _cache[key] = instance;
            _instanceToSource[instance] = source;
            return instance;
        }

        static string ColorKey(Color c) =>
            $"#{Mathf.RoundToInt(c.r * 255):X2}{Mathf.RoundToInt(c.g * 255):X2}{Mathf.RoundToInt(c.b * 255):X2}";

        /// <summary>씬 전환 등에서 캐시를 비운다. 만든 머티리얼도 함께 파괴한다.</summary>
        public static void ClearCache()
        {
            foreach (var m in _cache.Values)
            {
                if (m == null) continue;
                // 에디트 모드에서는 Destroy 가 통하지 않는다 (에디터 도구·테스트에서 호출된다).
                if (Application.isPlaying) Object.Destroy(m);
                else Object.DestroyImmediate(m);
            }
            _cache.Clear();
            _instanceToSource.Clear();
        }

        /// <summary>진단용 — 지금까지 만든 머티리얼 인스턴스 수. 색 종류만큼만 늘어야 한다.</summary>
        public static int CachedInstanceCount => _cache.Count;
    }
}
