using System.Collections.Generic;
using System.Threading.Tasks;
using Festa.Booth;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 부스 대표색(<c>facade.primaryColor</c>)을 축제장 12칸의 셸에 칠한다 (S15P21A604-659, GitLab #171).
    ///
    /// <para><b>왜 따로 만들었나.</b> 색을 칠하는 코드(<see cref="BoothFacadeApplier"/>)와 호출부
    /// (<c>BoothRuntime.ApplyFacadeAsync</c>)는 전부터 있었는데 <b>아무도 부르지 않는 자리</b>에 있었다 —
    /// 앵커 12개가 모두 <c>_loadOnStart = false</c> 라 <c>Start()</c> 가 그 경로를 타지 않고, 부스 내용물은
    /// <c>WorldBoothPublishedBootstrap</c> 이 <c>Rebuild</c> 를 직접 불러 채운다. 그래서 스튜디오에서 고른 색이
    /// 월드에 한 번도 나온 적이 없다. <c>BoothRuntime</c> 은 동결 기준선(헌법 27조)이라 손대지 않고,
    /// 값을 넣는 쪽만 여기로 뗐다.</para>
    ///
    /// <para><b>칸 번호로 부스를 조회하지 않는다.</b> 그 경로를 그냥 켰다면 앵커의 칸 번호(1~12)로
    /// owner 엔드포인트를 불러 <b>남의 부스 색</b>을 칠했을 것이다 — 간판에서 이미 겪은 결함이다
    /// (S15P21A604-658). 색은 <see cref="BoothSlotDirectory"/> 가 들고 있는 슬롯 목록에서 온다.
    /// OCCUPIED 칸에는 <c>facade</c> 가 함께 실려 오므로(BE S15P21A604-622) <b>부스 상세 조회가 아예 필요 없다.</b></para>
    ///
    /// <para>미임대 칸·색 미지정은 기본색 그대로 둔다 — 정상 경로라 경고하지 않는다.
    /// 로컬 표현 전용이라 NetworkObject 가 없고, 데디케이티드 서버에서는 설치하지 않는다.</para>
    /// </summary>
    public class BoothFacadePresenter : MonoBehaviour
    {
        /// <summary>테스트·진단에서 끌 수 있게 열어 둔다.</summary>
        public static bool Enabled = true;

        /// <summary>
        /// 색을 칠할 머티리얼 이름. <b><c>BoothRuntime._facadeMaterialNames</c> 의 기본값과 같아야 한다</b> —
        /// 그쪽은 직렬화 필드라 런타임에서 읽을 수 없어 여기에 다시 적는다.
        /// 팩 공용 머티리얼을 넣으면 부스 밖까지 물들므로 프로젝트 소유 머티리얼만 둔다.
        /// </summary>
        static readonly string[] TargetMaterialNames = { "BoothPanelGraphic" };

        static BoothFacadePresenter _instance;

        readonly Dictionary<int, Transform> _shells = new();
        BoothFacadeApplier _applier;
        bool _started;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Install()
        {
            if (!Enabled || _instance != null) return;
            if (Application.isBatchMode) return;   // 데디케이티드 서버 — 로컬 비주얼 없음
            var go = new GameObject("@BoothFacadePresenter");
            DontDestroyOnLoad(go);
            _instance = go.AddComponent<BoothFacadePresenter>();
        }

        /// <summary>한 칸을 다시 칠한다. 임대·게시·외관 저장 뒤 호출한다.</summary>
        public static void Refresh(int slotId)
        {
            if (_instance == null) return;
            _ = _instance.ApplyOneAsync(slotId);
        }

        void Update()
        {
            // 셸은 씬에 이미 서 있다. 앵커를 찾을 수 있게 될 때까지 기다렸다가 한 번만 칠한다.
            if (_started) return;
            var anchors = FindObjectsByType<BoothRuntime>(FindObjectsInactive.Include, FindObjectsSortMode.None);
            if (anchors.Length == 0) return;

            _shells.Clear();
            foreach (var anchor in anchors)
            {
                var shell = FindShell(anchor.transform);
                if (shell != null) _shells[anchor.BoothId] = shell;
            }
            if (_shells.Count == 0) return;

            _started = true;
            _ = ApplyAllAsync();
        }

        /// <summary>셸 찾기 규칙은 <c>BoothRuntime.ResolveShell</c> 과 같다 — 이름이 BoothShell 로 시작하는 자식.</summary>
        static Transform FindShell(Transform anchor)
        {
            foreach (Transform child in anchor)
                if (child.name.StartsWith("BoothShell")) return child;
            return null;
        }

        async Task ApplyAllAsync()
        {
            var slots = await BoothSlotDirectory.GetAsync();
            if (slots == null) return;   // 실패는 Directory 가 이미 경고했다

            int painted = 0;
            foreach (var slot in slots)
            {
                if (slot == null) continue;
                if (ApplyToSlot(slot)) painted++;
            }

            if (painted > 0)
                Debug.Log($"[BoothFacadePresenter] 부스 대표색 {painted}칸 적용");
        }

        async Task ApplyOneAsync(int slotId)
        {
            var slots = await BoothSlotDirectory.GetAsync();
            if (slots == null) return;
            if (BoothSlotDirectory.TryGet(slotId, out var slot)) ApplyToSlot(slot);
        }

        bool ApplyToSlot(BoothSlotDto slot)
        {
            var hex = slot.facade?.primaryColor;
            if (string.IsNullOrWhiteSpace(hex)) return false;              // 미임대·색 미지정 — 정상 경로
            if (!_shells.TryGetValue(slot.slotId, out var shell) || shell == null) return false;

            _applier ??= new BoothFacadeApplier(TargetMaterialNames);
            int applied = _applier.Apply(shell, hex);

            if (applied == 0)
                Debug.LogWarning(
                    $"[BoothFacadePresenter] 슬롯 {slot.slotId}: primaryColor '{hex}' 를 칠할 대상을 못 찾았다 " +
                    $"(셸 '{shell.name}' 에 [{string.Join(", ", TargetMaterialNames)}] 머티리얼 없음)");
            return applied > 0;
        }
    }
}
