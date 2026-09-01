using System.Threading.Tasks;
using Festa.Integration;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 부스 이름표에 **실제 부스 이름**을 채운다. 값이 없으면 이름표를 아예 띄우지 않는다.
    ///
    /// <para><b>왜 자리표시 문구를 쓰지 않나.</b> 처음에 "1번 부스" 같은 번호를 띄웠는데,
    /// 그건 디버그 문구지 제품 문구가 아니다 — 방문자에게 슬롯 번호는 아무 의미가 없다
    /// (S15P21A604-355 사용자 지적). 표시할 이름이 없다는 것은 **아직 임대되지 않았거나
    /// 간판을 안 정했다는 뜻**이고, 그럴 때 번호를 대신 띄우면 빈 부스를 채워진 것처럼
    /// 보이게 만든다. 없으면 비워 두는 쪽이 정직하다.</para>
    ///
    /// <para>이름의 정본은 서버다 — <c>facade.signText</c>(부스가 정한 간판 문구)를 먼저 쓰고,
    /// 없으면 <c>name</c>(부스명)으로 떨어진다. Unity 는 읽기만 한다(헌법 16조).</para>
    /// </summary>
    [RequireComponent(typeof(WorldNameplate))]
    [DisallowMultipleComponent]
    public sealed class BoothNameplateSource : MonoBehaviour
    {
        [Tooltip("이 슬롯이 보여줄 부스 id. 0 이면 같은 슬롯의 BoothPortal 에서 찾는다.")]
        [SerializeField] int _boothId;

        async void Start()
        {
            var plate = GetComponent<WorldNameplate>();
            plate.Label = "";                       // 이름을 받기 전에는 아무것도 띄우지 않는다

            int boothId = _boothId != 0 ? _boothId : FindBoothId();
            if (boothId <= 0) return;

            var name = await FetchName(boothId);
            if (!string.IsNullOrWhiteSpace(name)) plate.Label = name.Trim();
        }

        /// <summary>슬롯에 걸린 포털에서 부스 id 를 얻는다 — 씬에 이미 배선된 값이다.</summary>
        int FindBoothId()
        {
            foreach (var p in BoothPortal.All)
                if (p != null && p.boothId > 0 &&
                    Vector3.Distance(p.transform.position, transform.position) < 400f)
                    return p.boothId;
            return 0;
        }

        static async Task<string> FetchName(int boothId)
        {
            ApiServices.EnsureInitialized();
            try
            {
                var detail = await ApiServices.Booth.GetBoothDetailAsync(boothId);
                if (detail == null) return null;
                // 간판 문구가 부스가 직접 정한 표시 이름이다. 없으면 부스명으로 떨어진다.
                var sign = detail.facade?.signText;
                return !string.IsNullOrWhiteSpace(sign) ? sign : detail.name;
            }
            catch (System.Exception e)
            {
                // 이름을 못 받는 것은 치명적이지 않다 — 조용히 비워 두고 이유만 남긴다.
                Debug.LogWarning($"[BoothNameplate] booth {boothId} 이름 조회 실패 — {e.Message}");
                return null;
            }
        }
    }
}
