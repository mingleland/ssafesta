using Festa.Booth;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace Festa.World
{
    /// <summary>
    /// **임대되지 않은 부스는 비워 둔다** (사용자 지시 2026-09-10) — 직원·조명을 끄고 상호작용도 하이라이트도 없앤다.
    ///
    /// <para>왜 필요한가: 12실 모두에 직원 NPC 와 조명이 서 있어 <b>임대된 부스와 빈 부스가 구분되지 않았다.</b>
    /// 들어가려 하면 그제서야 "아직 준비 중" 이라는 안내가 떴다 — 걸어가서 F 를 눌러 본 뒤에야 아는 셈이다.
    /// 빈 부스는 멀리서 봐도 비어 보여야 하고, 애초에 F 가 안 걸려야 한다.</para>
    ///
    /// <para>임대 판정은 포털이 쓰는 것과 <b>같은 근거</b>다 — 내부 방 <c>Interior_NN</c> 의
    /// <see cref="BoothRuntime.IsLoaded"/>(게시본을 실제로 그렸는가). 판정 시점도 같다:
    /// <see cref="WorldBoothPublishedBootstrap.Completed"/> 전에는 아무것도 끄지 않는다 —
    /// 조회 전에는 모든 방이 미게시로 보여 멀쩡한 부스까지 꺼 버린다(S15P21A604-453 과 같은 함정).</para>
    ///
    /// <para>씬에 심지 않고 <see cref="RuntimeInitializeOnLoadMethod"/> 로 설치한다 — 씬 파일을 건드리지 않아
    /// 다른 파트의 씬 변경과 충돌하지 않는다(<see cref="WorldBoothPublishedBootstrap"/> 와 같은 방식).
    /// 게시본이 뒤늦게 채워지면(재시도 주기 20초) <see cref="WorldBoothPublishedBootstrap.BoothsRebuilt"/> 로
    /// 다시 훑어 그 부스만 켠다 — 한 번 끄고 마는 것이 아니다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class BoothVacancyPresenter : MonoBehaviour
    {
        /// <summary>비교·검증용 스위치. 끄면 전부 켜진 예전 모습으로 돌아간다.</summary>
        public static bool Enabled = true;

        public const int SlotCount = 12;

        /// <summary>게시본 조회가 끝난 뒤에도 방마다 늦게 채워질 수 있어 주기적으로 다시 본다.</summary>
        const float SweepSeconds = 5f;

        static BoothVacancyPresenter s_instance;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Install()
        {
            if (Application.isBatchMode) return;   // 데디케이티드 서버 — 로컬 비주얼이 없다
            if (s_instance != null) return;
            var go = new GameObject("@BoothVacancy");
            DontDestroyOnLoad(go);
            s_instance = go.AddComponent<BoothVacancyPresenter>();
        }

        float _nextSweep;

        /// <summary>
        /// 슬롯별로 찾아 둔 대상. <see cref="GameObject.Find"/> 는 씬 전체를 훑으므로 5초마다 48번씩
        /// 부르지 않는다 — 한 번 찾으면 들고 있다가 씬이 바뀔 때만 버린다.
        /// </summary>
        sealed class SlotRefs
        {
            public GameObject Staff;
            public Light[] SlotLights;
            public Light BoothLight;
            public BoothPortal Portal;
            public BoothRuntime Runtime;
            public bool Resolved;
        }

        static readonly SlotRefs[] s_slots = new SlotRefs[SlotCount + 1];

        static SlotRefs Refs(int slot)
        {
            var r = s_slots[slot] ?? (s_slots[slot] = new SlotRefs());
            if (r.Resolved) return r;

            var slotRoot = GameObject.Find($"/@Festival/Festival_Slots/FestivalSlot_{slot:D2}");
            var interior = GameObject.Find($"/@BoothInteriors/Interior_{slot:D2}");
            // 방이 아직 안 만들어졌으면 다음 훑기에서 다시 찾는다 — 여기서 굳히면 영영 못 찾는다.
            if (slotRoot == null || interior == null) return r;

            r.Staff = GameObject.Find($"/@Festival/Festival_Slots/FestivalSlot_{slot:D2}/Staff_{slot:D2}");
            r.SlotLights = slotRoot.GetComponentsInChildren<Light>(true);
            var boothLight = GameObject.Find($"/@Festival/Festival_BoothLights/BoothLight_FestivalSlot_{slot:D2}");
            r.BoothLight = boothLight != null ? boothLight.GetComponent<Light>() : null;
            var portal = GameObject.Find($"/@Festival/Festival_Portals/Portal_Ext_{slot:D2}");
            r.Portal = portal != null ? portal.GetComponent<BoothPortal>() : null;
            r.Runtime = interior.GetComponentInChildren<BoothRuntime>(true);
            r.Resolved = true;
            return r;
        }

        static void ForgetRefs()
        {
            for (var i = 0; i < s_slots.Length; i++) s_slots[i] = null;
        }

        void OnEnable()
        {
            WorldBoothPublishedBootstrap.BoothsRebuilt += OnBoothsRebuilt;
            SceneManager.sceneLoaded += OnSceneLoaded;
        }

        void OnDisable()
        {
            WorldBoothPublishedBootstrap.BoothsRebuilt -= OnBoothsRebuilt;
            SceneManager.sceneLoaded -= OnSceneLoaded;
        }

        void OnSceneLoaded(Scene _, LoadSceneMode __) { ForgetRefs(); _nextSweep = 0f; }

        void OnBoothsRebuilt() => _nextSweep = 0f;

        void Update()
        {
            if (Time.unscaledTime < _nextSweep) return;
            _nextSweep = Time.unscaledTime + SweepSeconds;
            Sweep();
        }

        /// <summary>슬롯 12개를 훑어 임대 여부대로 켜고 끈다. 조회 전에는 건드리지 않는다.</summary>
        public static void Sweep()
        {
            // 조회가 끝나기 전에는 **아무것도 끄지 않는다.** 이때는 모든 방이 IsLoaded false 라
            // 게시된 부스까지 꺼 버린다.
            if (!Enabled || !WorldBoothPublishedBootstrap.Completed) return;

            for (var slot = 1; slot <= SlotCount; slot++)
            {
                var r = Refs(slot);
                if (!r.Resolved) continue;
                bool rented = IsRented(slot);

                // 직원은 **오브젝트째** 끈다. 렌더러만 꺼도 캡슐 콜라이더가 남아 보이지 않는 벽이 되고,
                // 포털의 시야 판정(facingSource)이 유령을 기준으로 돈다.
                if (r.Staff != null && r.Staff.activeSelf != rented) r.Staff.SetActive(rented);

                if (r.SlotLights != null)
                    foreach (var light in r.SlotLights)
                        if (light != null && light.enabled != rented) light.enabled = rented;
                if (r.BoothLight != null && r.BoothLight.enabled != rented) r.BoothLight.enabled = rented;

                // 외부 포털은 **컴포넌트를** 끈다. BoothPortal 은 OnEnable/OnDisable 로 BoothPortal.All 에
                // 자신을 넣고 빼므로, 끄면 후보 목록에서 사라져 프롬프트·발밑 링·외곽선·F 가 한꺼번에 없어진다.
                // "준비 중" 안내조차 뜨지 않는 것이 의도다 — 걸어가서 눌러 봐야 아는 것이 문제였다.
                if (r.Portal != null && r.Portal.enabled != rented) r.Portal.enabled = rented;
            }
        }

        /// <summary>
        /// 이 슬롯을 누가 임차해 게시했는가. 포털의 입장 판정(<see cref="PortalInteractor.IsEnterable"/>)과
        /// 같은 근거를 쓴다 — 두 곳이 갈리면 "들어가지는데 직원이 없는" 부스가 생긴다.
        /// </summary>
        public static bool IsRented(int slot)
        {
            if (slot < 1 || slot > SlotCount) return true;

            // 이벤트 부스는 임대 대상이 아니라 운영 부스다 (GitLab #170). 게시본이 없는 것이 정상인데
            // 그대로 두면 "빈 부스" 로 판정돼 직원·조명이 꺼지고 포털까지 사라진다 — 눌러 볼 수조차 없다.
            var portal = Refs(slot).Portal;
            if (portal != null && portal.eventBooth) return true;

            var runtime = Refs(slot).Runtime;
            // 판단 근거가 없으면(방·런타임 미생성) 조용히 끄지 않는다.
            return runtime == null || runtime.IsLoaded;
        }
    }
}
