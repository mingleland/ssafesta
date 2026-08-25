using System.Collections.Generic;
using Festa.Avatar;
using Festa.World;
using Unity.Netcode;
using UnityEngine;
// Festa.World 에도 같은 이름의 (구) AvatarCatalog 가 있어 모호하다 — 모듈형 쪽으로 못박는다.
using AvatarCatalog = Festa.Avatar.AvatarCatalog;

namespace Festa.Diagnostics
{
    /// <summary>
    /// 원격 Avatar N기의 **클라이언트 측 비용**을 실제 접속자 없이 재현한다 (GitLab #89 1단계).
    ///
    /// 왜 이렇게 하나 — 30~40인의 병목은 세 갈래인데 성격이 다르다.
    ///   ① 메모리·② 드로우콜/스킨 애니메이션 : **내 화면에 아바타가 몇 기 보이는가**로 결정된다.
    ///      실제 접속자가 아니어도 같은 파이프라인으로 조립한 아바타를 세우면 그대로 측정된다.
    ///   ③ 대역폭 : 이건 진짜 클라이언트가 있어야 한다 (봇 프로세스, 2단계).
    /// ①②를 프로세스 30개 없이 먼저 재는 것이 이 도구의 목적이다. 노트북 한 대로
    /// 헤드리스 클라이언트 30개를 띄우는 것은 메모리상 현실적이지 않다.
    ///
    /// 조립 경로는 프로덕션과 동일하다 — <see cref="AvatarAssembler"/> + 카탈로그.
    /// 카탈로그는 NetworkManager 의 PlayerPrefab 에서 읽으므로 별도 배선이 필요 없다.
    ///
    /// 사용: 씬 오브젝트에 붙이고 F6(증가) / F7(감소) / F8(전체 제거).
    /// 로컬 전용 — NetworkObject 없음, 서버로 나가는 상태 없음.
    /// </summary>
    public class AvatarStressSpawner : MonoBehaviour
    {
        [SerializeField] KeyCode _addKey = KeyCode.F6;
        [SerializeField] KeyCode _removeKey = KeyCode.F7;
        [SerializeField] KeyCode _clearKey = KeyCode.F8;
        [Tooltip("키 한 번에 늘리고 줄일 인원")]
        [SerializeField] int _step = 5;
        [Tooltip("스폰 격자 중심. 기본값은 11층 스폰 격자와 같은 자리다.")]
        [SerializeField] Vector3 _center = new Vector3(-85f, 0f, -234f);
        [SerializeField] float _spacing = 6f;
        [SerializeField] int _columns = 8;
        [Tooltip("걷기 애니메이션을 재생한다. 정지 상태만 재면 스킨 비용이 과소평가된다.")]
        [SerializeField] bool _animate = true;
        [Tooltip("실제 플레이어처럼 서로 다른 외형을 조립한다. 끄면 같은 외형이라 메모리가 과소평가된다.")]
        [SerializeField] bool _varyAppearance = true;

        readonly List<GameObject> _spawned = new();
        AvatarCatalog _catalog;
        static readonly int SpeedHash = Animator.StringToHash("Speed");
        static readonly int IsWalkingHash = Animator.StringToHash("IsWalking");

        public int Count => _spawned.Count;

        void Awake()
        {
            // 릴리즈 빌드에서는 살려두지 않는다 — 실사용자가 F6 으로 아바타 40기를
            // 소환할 수 있으면 안 된다 (PerfHud.ToolsEnabled 와 같은 기준).
            if (!PerfHud.ToolsEnabled) enabled = false;
        }

        void Update()
        {
            if (Input.GetKeyDown(_addKey)) Add(_step);
            if (Input.GetKeyDown(_removeKey)) Remove(_step);
            if (Input.GetKeyDown(_clearKey)) Remove(_spawned.Count);
        }

        AvatarCatalog ResolveCatalog()
        {
            if (_catalog != null) return _catalog;
            var prefab = NetworkManager.Singleton != null
                ? NetworkManager.Singleton.NetworkConfig?.PlayerPrefab
                : null;
            var visual = prefab != null ? prefab.GetComponent<PlayerAvatarVisual>() : null;
            _catalog = visual != null ? visual.ModularCatalog : null;
            if (_catalog == null)
                Debug.LogError("[AvatarStress] 모듈 AvatarCatalog 를 찾지 못했다 — " +
                               "NetworkManager.PlayerPrefab 의 PlayerAvatarVisual 배선을 확인해라.");
            return _catalog;
        }

        public void Add(int n)
        {
            var catalog = ResolveCatalog();
            if (catalog == null) return;

            for (int i = 0; i < n; i++)
            {
                int index = _spawned.Count;
                var go = new GameObject($"StressAvatar_{index:D2}");
                go.transform.SetParent(transform, false);
                go.transform.position = GridPosition(index);
                go.transform.rotation = Quaternion.Euler(0f, (index * 37f) % 360f, 0f);

                var assembler = go.AddComponent<AvatarAssembler>();
                assembler.Catalog = catalog;
                assembler.Apply(BuildConfig(index));

                if (!string.IsNullOrEmpty(assembler.LastError))
                    Debug.LogWarning($"[AvatarStress] 조립 경고: {assembler.LastError}");

                // 순서 주의: 애니메이터를 먼저 세팅해야 실제 재생 포즈를 베이크해 접지할 수 있다.
                // (바인드 포즈로 접지하면 걷기 포즈에서 발이 뜨거나 묻힌다)
                SetupAnimator(go, index);
                FitHeight(go);
                MatchProductionShadows(go);
                _spawned.Add(go);
            }
            Debug.Log($"[AvatarStress] 아바타 {_spawned.Count}기");
        }

        public void Remove(int n)
        {
            for (int i = 0; i < n && _spawned.Count > 0; i++)
            {
                int last = _spawned.Count - 1;
                if (_spawned[last] != null) Destroy(_spawned[last]);
                _spawned.RemoveAt(last);
            }
            Debug.Log($"[AvatarStress] 아바타 {_spawned.Count}기");
        }

        AvatarConfig BuildConfig(int index)
        {
            var catalog = _catalog;
            var gender = (index % 2 == 0) ? AvatarGender.Female : AvatarGender.Male;
            var config = catalog.CreateDefault(gender);
            if (!_varyAppearance) return config;

            // 색만 흔들어도 머티리얼 인스턴스가 갈라져 메모리·SetPass 비용이 실제에 가까워진다.
            // 아이템까지 바꾸면 메시 종류가 늘어 더 정확하지만, 조합 유효성 규칙이 있어
            // 잘못 섞으면 조립이 실패한다 — 색 변주로 충분히 보수적인 측정이 된다.
            var rng = new System.Random(index * 7919);
            byte Pick() => (byte)rng.Next(0, 8);
            config.skinColorId = Pick();
            config.hairColorId = Pick();
            config.topColorId = Pick();
            config.bottomColorId = Pick();
            return config;
        }

        Vector3 GridPosition(int index)
        {
            int col = index % _columns;
            int row = index / _columns;
            float x = _center.x + (col - (_columns - 1) * 0.5f) * _spacing;
            float z = _center.z + row * _spacing;
            var probe = new Vector3(x, _center.y + 30f, z);
            if (Physics.Raycast(probe, Vector3.down, out var hit, 60f))
                return new Vector3(x, hit.point.y + 0.1f, z);
            return new Vector3(x, _center.y + 0.5f, z);
        }

        /// <summary>
        /// 프로덕션과 같은 목표 높이(22.375 unit)로 맞추고 발을 지면에 붙인다.
        ///
        /// ⚠ 접지에 <c>SkinnedMeshRenderer.bounds</c> 를 쓰면 안 된다 — 그 값은 현재 포즈가
        /// 아니라 **바인드포즈 골격 전체 범위**라서 실제 신발 위치를 주지 않는다.
        /// 그대로 믿었더니 아바타가 1.30 m 씩 지면에 묻혀 하반신이 가려졌고, 그 상태로 잰
        /// 픽셀 비용은 과소평가된 값이었다 (T-201). 프로덕션
        /// <see cref="Festa.World.PlayerAvatarVisual"/> 은 같은 함정을 BakeMesh 로 피하고 있다.
        /// 스케일 산정은 프로덕션과 동일하게 렌더러 bounds 를 쓴다(같은 기준이라야 크기가 같다).
        /// </summary>
        static void FitHeight(GameObject go)
        {
            const float TargetHeight = 22.375f;
            var renderers = go.GetComponentsInChildren<Renderer>(false);
            if (renderers.Length == 0) return;
            var b = renderers[0].bounds;
            foreach (var r in renderers) b.Encapsulate(r.bounds);
            if (b.size.y < 0.001f) return;
            go.transform.localScale *= TargetHeight / b.size.y;

            if (TryGetPosedLowestY(go, out float lowest))
                go.transform.position += Vector3.up * (GroundY(go.transform.position) - lowest);
        }

        /// <summary>현재 포즈를 베이크해 실제로 보이는 최하단 y 를 구한다.</summary>
        static bool TryGetPosedLowestY(GameObject go, out float lowest)
        {
            lowest = 0f;
            bool found = false;
            foreach (var smr in go.GetComponentsInChildren<SkinnedMeshRenderer>(false))
            {
                if (smr == null || !smr.enabled || smr.sharedMesh == null) continue;
                var baked = new Mesh();
                smr.BakeMesh(baked);
                var lb = baked.bounds;
                Destroy(baked);

                // BakeMesh 결과에는 렌더러 스케일이 이미 반영돼 있다 — TransformPoint 를 쓰면
                // 스케일이 한 번 더 곱해진다. 위치·회전만 적용한다 (프로덕션과 같은 처리).
                var center = lb.center;
                var extents = lb.extents;
                for (int x = -1; x <= 1; x += 2)
                for (int y = -1; y <= 1; y += 2)
                for (int z = -1; z <= 1; z += 2)
                {
                    var corner = smr.transform.position +
                                 smr.transform.rotation * (center + Vector3.Scale(extents, new Vector3(x, y, z)));
                    if (!found || corner.y < lowest) { lowest = corner.y; found = true; }
                }
            }
            return found;
        }

        static float GroundY(Vector3 pos)
        {
            var probe = new Vector3(pos.x, pos.y + 30f, pos.z);
            return Physics.Raycast(probe, Vector3.down, out var hit, 60f) ? hit.point.y + 0.1f : pos.y;
        }

        void SetupAnimator(GameObject go, int index)
        {
            var animator = go.GetComponentInChildren<Animator>();
            if (animator == null || _catalog.animatorController == null) return;
            animator.runtimeAnimatorController = _catalog.animatorController;

            // 프로덕션(PlayerAvatarVisual)과 같은 설정이어야 측정이 유효하다.
            // ① 루트 모션 OFF — 켜져 있으면 걷기 클립의 루트 이동이 아바타를 계속
            //    끌어내려 접지가 무너지고(측정 중 실제로 1.2 m 씩 가라앉았다) 픽셀 비용이
            //    과소평가된다. 프로덕션은 이동 권한이 PlayerMovement 에 있어 항상 false 다.
            animator.applyRootMotion = false;

            if (!_animate)
            {
                animator.cullingMode = AnimatorCullingMode.CullCompletely;
                return;
            }
            // 화면 밖 아바타를 어떻게 처리하느냐가 #90 의 핵심 변수다. 기본값(AlwaysAnimate)으로
            // 두어야 "최적화 전" 비용이 정직하게 찍힌다.
            animator.cullingMode = AnimatorCullingMode.AlwaysAnimate;
            animator.SetBool(IsWalkingHash, true);
            animator.SetFloat(SpeedHash, 1f);
            // 같은 프레임에 모두 같은 포즈면 비현실적이다 — 재생 위상을 흩는다.
            animator.Update(index * 0.13f);
        }

        /// <summary>
        /// ② 실시간 그림자 OFF — 프로덕션은 스킨 메시 그림자를 끄고 접지용 블롭 하나만 쓴다
        /// (<see cref="Festa.World.PlayerAvatarVisual"/>.ConfigureAvatarShadows).
        /// 켜 둔 채로 재면 축제 40기에서 드로우콜이 1,154 → 1,311 로 부풀어 실제보다 무겁게 나온다.
        /// </summary>
        static void MatchProductionShadows(GameObject go)
        {
            foreach (var r in go.GetComponentsInChildren<Renderer>(true))
                r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
        }

        void OnGUI()
        {
            if (_spawned.Count == 0) return;
            var rect = new Rect(10f, Screen.height - 40f, 460f, 30f);
            GUI.Label(rect, $"[스트레스] 아바타 {_spawned.Count}기 — F6 +{_step} / F7 -{_step} / F8 전체 제거");
        }
    }
}
