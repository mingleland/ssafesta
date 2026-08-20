using System.Collections.Generic;
using System.Threading.Tasks;
using Festa.Integration;
using UnityEngine;

namespace Festa.Booth
{
    /// <summary>
    /// 부스 슬롯 하나의 런타임. 씬의 부스 앵커(빈 오브젝트)에 부착한다.
    /// Published Layout을 조회해 자식으로 Local Prefab을 생성한다.
    /// 데이터 흐름: Spring(Published Layout) → IBoothApiClient → Parser → Factory → Local Spawn
    /// </summary>
    public class BoothRuntime : MonoBehaviour
    {
        [SerializeField] int _boothId = 7;
        [SerializeField] BoothObjectRegistry _registry;
        [SerializeField] bool _loadOnStart = true;

        [Header("Facade — 부스 대표색을 칠할 셸")]
        [Tooltip("셸 오브젝트. 비우면 이 앵커의 자식 중 이름이 BoothShell 인 것을 찾는다.")]
        [SerializeField] Transform _shell;
        [Tooltip("primaryColor 를 적용할 머티리얼 이름. 셸에서 이 이름으로 시작하는 슬롯만 칠한다.\n" +
                 "팩 공용 머티리얼(Aluminium 등)을 넣으면 부스 밖까지 물들므로 프로젝트 소유 머티리얼만 지정한다.")]
        [SerializeField] string[] _facadeMaterialNames = { "BoothCarpet" };

        readonly List<GameObject> _spawned = new();
        BoothObjectFactory _factory;
        BoothFacadeApplier _facadeApplier;

        public int BoothId => _boothId;
        public bool IsLoaded { get; private set; }

        async void Start()
        {
            if (_loadOnStart) await LoadAndBuildAsync();
        }

        public async Task LoadAndBuildAsync()
        {
            _factory ??= new BoothObjectFactory(_registry);

            ApiServices.EnsureInitialized(); // Bootstrap 없는 단독 씬 실행 대비

            // Facade 는 레이아웃과 독립이다. 실패해도 부스는 그려야 하므로 먼저 시도하고 넘어간다.
            await ApplyFacadeAsync();

            var layout = await ApiServices.Booth.GetPublishedLayoutAsync(_boothId);
            if (layout == null)
            {
                Debug.LogWarning($"[BoothRuntime] No published layout for booth {_boothId}");
                return;
            }

            Rebuild(layout);
        }

        /// <summary>
        /// 부스 대표색을 셸에 적용한다. Unity 는 읽기만 한다 — 값 검증은 서버 몫(헌법 16조).
        /// 조회 실패·색 미지정·파싱 실패는 모두 기본색 유지로 처리하고 부스 생성을 막지 않는다.
        /// </summary>
        public async Task ApplyFacadeAsync()
        {
            var shell = ResolveShell();
            if (shell == null) return; // 셸이 없는 부스도 있을 수 있다 — 정상 경로

            var detail = await ApiServices.Booth.GetBoothDetailAsync(_boothId);
            var hex = detail?.facade?.primaryColor;
            if (string.IsNullOrWhiteSpace(hex)) return; // 미지정 — 기본색 유지

            _facadeApplier ??= new BoothFacadeApplier(_facadeMaterialNames);
            var applied = _facadeApplier.Apply(shell, hex);

            if (applied == 0)
                Debug.LogWarning(
                    $"[BoothRuntime] Booth {_boothId}: primaryColor '{hex}' 를 적용할 대상을 못 찾았다 " +
                    $"(셸 '{shell.name}' 에서 [{string.Join(", ", _facadeMaterialNames)}] 머티리얼 없음)");
            else
                Debug.Log($"[BoothRuntime] Booth {_boothId}: facade primaryColor {hex} → {applied}개 슬롯 적용");
        }

        Transform ResolveShell()
        {
            if (_shell != null) return _shell;
            foreach (Transform child in transform)
                if (child.name.StartsWith("BoothShell")) return child;
            return null;
        }

        public void Rebuild(BoothLayoutDto layout)
        {
            Clear();

            foreach (var dto in layout.objects)
            {
                var go = _factory.Create(layout.boothId, dto, transform);
                if (go != null) _spawned.Add(go);
            }

            IsLoaded = true;
            Debug.Log($"[BoothRuntime] Booth {layout.boothId} built: {_spawned.Count} objects (template={layout.template})");
        }

        public void Clear()
        {
            foreach (var go in _spawned)
                if (go != null) Destroy(go);
            _spawned.Clear();
            IsLoaded = false;
        }
    }
}
