using System.Collections.Generic;

namespace Festa.Avatar
{
    /// <summary>
    /// 이 사용자가 무엇을 착용할 수 있는지 판정한다 (S15P21A604-355).
    ///
    /// <para><b>정본은 서버다.</b> 소유 목록은 백엔드가 내려주고 Unity 는 읽기만 한다
    /// (헌법 16조). 여기 있는 것은 그 응답을 담아 두는 자리와, 응답을 받기 전에도
    /// 로비가 정상 동작하게 하는 판정 규칙뿐이다 — 클라이언트가 소유를 만들어 내지 않는다.</para>
    ///
    /// <para><b>계약이 아직 없다.</b> "어떤 아이템을 가졌는가" 를 주는 엔드포인트는 백엔드에
    /// 요청해 둔 상태이고, 스키마가 정해지면 <see cref="SetOwned"/> 를 부르는 쪽만 붙이면
    /// 된다. 그때까지 소유 목록은 비어 있고, 기본 제공 항목만 사용할 수 있다 —
    /// 잠금 UI 와 무작위·기본 아바타 생성은 지금 상태로도 전부 검증할 수 있다.</para>
    ///
    /// <para><b>비어 있음을 "전부 해제" 로 해석하지 않는다.</b> 그렇게 두면 응답이 실패했을 때
    /// 조용히 전부 풀려 버려, 잠금이 동작하는지 아무도 모르는 채로 배포된다 — 조용히
    /// 기본값으로 되돌리지 말라는 규칙과 같은 이유다(T-24).</para>
    /// </summary>
    public static class AvatarOwnership
    {
        static readonly HashSet<int> Owned = new();

        /// <summary>서버가 내려준 소유 아이템 id 목록을 갈아끼운다.</summary>
        public static void SetOwned(IEnumerable<int> itemIds)
        {
            Owned.Clear();
            if (itemIds == null) return;
            foreach (var id in itemIds) if (id != 0) Owned.Add(id);
        }

        /// <summary>서버 응답을 아직 못 받았는가. 진단·표시 용도다.</summary>
        public static bool HasServerData => Owned.Count > 0;

        /// <summary>착용할 수 있는 아이템인가 — 기본 제공이거나 소유했거나.</summary>
        public static bool IsUnlocked(AvatarItemDefinition item)
        {
            if (item == null) return false;
            return item.isDefaultUnlocked || Owned.Contains(item.itemId);
        }

        /// <summary>id 로 판정한다. 카탈로그에 없는 id 는 잠긴 것으로 본다.</summary>
        public static bool IsUnlocked(AvatarCatalog catalog, int itemId)
        {
            if (itemId == 0) return true;              // "없음" 은 항상 고를 수 있다
            return catalog != null && IsUnlocked(catalog.Get(itemId));
        }
    }
}
