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

        readonly List<GameObject> _spawned = new();
        BoothObjectFactory _factory;

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
            var layout = await ApiServices.Booth.GetPublishedLayoutAsync(_boothId);
            if (layout == null)
            {
                Debug.LogWarning($"[BoothRuntime] No published layout for booth {_boothId}");
                return;
            }

            Rebuild(layout);
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
