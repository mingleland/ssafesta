// 셰이더 변형을 로딩 뒤 프레임에 나눠 미리 컴파일한다.
// 왜 있는가: GraphicsSettings 프리로드는 시작 시점에 192 변형을 한 번에 컴파일한다 — WebGL 은 셰이더 컴파일이
// 메인 스레드 동기라 로딩 화면이 1~2분 굳었다(2026-09-09 릴리스 d1165eb3~664bc74e 크롬 실측, T-245).
// 그래서 프리로드 목록에서 빼고, 월드 씬이 뜬 뒤 프레임당 몇 개씩 굽는다. 첫 시야 힛치(QA #29)는 여전히 줄고
// 로딩은 굳지 않는다. 변형 목록은 Resources/FestaTrackedVariants.shadervariants (에디터 추적 저장).
using UnityEngine;

namespace Festa.Core
{
    public sealed class ShaderWarmupSpreader : MonoBehaviour
    {
        const string ResourcePath = "FestaTrackedVariants";
        const int PerFrame = 4;            // 프레임당 변형 수 — 4개면 60fps 에서 192개가 ~0.8초, 프레임 예산 안
        const float StartDelaySeconds = 2f; // 스폰·첫 프레임이 자리 잡은 뒤 시작한다

        ShaderVariantCollection _collection;
        float _startAt;
        bool _done;
        int _frames;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Install()
        {
#if UNITY_SERVER
            return;
#else
            if (Application.isBatchMode || HeadlessRuntime.IsHeadless) return;
            if (Object.FindFirstObjectByType<ShaderWarmupSpreader>() != null) return;
            var go = new GameObject("@ShaderWarmupSpreader");
            go.hideFlags = HideFlags.HideAndDontSave;
            DontDestroyOnLoad(go);
            go.AddComponent<ShaderWarmupSpreader>();
#endif
        }

        void Start()
        {
            _collection = Resources.Load<ShaderVariantCollection>(ResourcePath);
            if (_collection == null)
            {
                Debug.LogWarning($"[ShaderWarmup] Resources/{ResourcePath} 가 없다 — 프리웜 없이 진행한다");
                _done = true;
                return;
            }
            if (_collection.isWarmedUp) { _done = true; return; }
            _startAt = Time.unscaledTime + StartDelaySeconds;
            Debug.Log($"[ShaderWarmup] {_collection.shaderCount} 셰이더 / {_collection.variantCount} 변형을 {StartDelaySeconds:F0}초 뒤부터 프레임당 {PerFrame}개씩 굽는다");
        }

        void Update()
        {
            if (_done || Time.unscaledTime < _startAt) return;
            _frames++;
            // true 를 돌려주면 전부 끝났다. 한 프레임에 PerFrame 개만 컴파일한다.
            if (_collection.WarmUpProgressively(PerFrame))
            {
                _done = true;
                Debug.Log($"[ShaderWarmup] 완료 — {_collection.variantCount} 변형, {_frames} 프레임");
                Destroy(gameObject);
            }
        }
    }
}
