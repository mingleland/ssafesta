using System.Threading.Tasks;
using Festa.Integration;
using UnityEngine;

namespace Festa.Booth
{
    /// <summary>
    /// 축제장 슬롯 목록(<c>GET /api/v1/booth-slots</c>) 한 벌을 **여럿이 나눠 쓴다**.
    ///
    /// <para>이 목록이 <b>칸 번호(1~12) ↔ boothId</b> 를 잇는 정본이라 바깥 표현마다 필요하다 —
    /// 간판 문구(<c>BoothSignPresenter</c>), 부스 대표색(<c>BoothFacadePresenter</c>), 앞으로 지도까지.
    /// 각자 부르면 같은 응답을 서너 번 받게 되므로 여기서 한 번만 받아 들고 있는다.</para>
    ///
    /// <para><b>같은 순간의 중복 요청도 합친다</b>(single-flight). 진입 직후에는 표현들이 거의 동시에
    /// 깨어나서, 캐시만으로는 첫 요청이 끝나기 전에 두 번째가 또 나간다.</para>
    ///
    /// <para>임대·게시가 바뀌면 <see cref="Invalidate"/> 로 버린다 —
    /// <c>WorldBoothPublishedBootstrap.RequestReload</c> 가 그 지점이다.</para>
    /// </summary>
    public static class BoothSlotDirectory
    {
        static BoothSlotDto[] _slots;
        static Task<BoothSlotDto[]> _inFlight;

        /// <summary>슬롯 목록. 캐시가 있으면 그대로, 없으면 한 번 받아 온다. 실패하면 null.</summary>
        public static async Task<BoothSlotDto[]> GetAsync()
        {
            if (_slots != null) return _slots;
            if (_inFlight != null) return await _inFlight;

            var api = ApiServices.Booth;
            if (api == null)
            {
                Debug.LogWarning("[BoothSlotDirectory] ApiServices.Booth 가 없다 — 슬롯 목록 없이 간다.");
                return null;
            }

            try
            {
                _inFlight = api.GetSlotsAsync();
                _slots = await _inFlight;
            }
            catch (System.Exception e)
            {
                Debug.LogWarning($"[BoothSlotDirectory] 슬롯 목록 조회 실패: {e.Message}");
                _slots = null;
            }
            finally
            {
                _inFlight = null;
            }

            return _slots;
        }

        /// <summary>이미 받아 둔 목록에서 한 칸을 찾는다. 아직 안 받았으면 false.</summary>
        public static bool TryGet(int slotId, out BoothSlotDto slot)
        {
            slot = null;
            if (_slots == null) return false;
            foreach (var s in _slots)
                if (s != null && s.slotId == slotId) { slot = s; return true; }
            return false;
        }

        /// <summary>임대·게시가 바뀌었다 — 다음 조회에서 다시 받는다.</summary>
        public static void Invalidate() => _slots = null;
    }
}
