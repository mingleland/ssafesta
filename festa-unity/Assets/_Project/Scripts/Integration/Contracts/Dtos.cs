using System;

namespace Festa.Integration
{
    /// <summary>doc 08 기준 사용자 프로필 최소 필드 (Draft)</summary>
    [Serializable]
    public class UserProfileDto
    {
        public long userId;
        public string nickname;
        public string avatarCode;
    }

    /// <summary>
    /// 카탈로그 품목 하나 — <c>GET /api/v1/catalog/items?type=AVATAR_PART</c> 의 배열 원소
    /// (BE <c>InventoryService.CatalogItemView</c>, GitLab #120 §2).
    ///
    /// <para><b><see cref="owned"/> 가 잠금 판정의 유일한 근거다.</b> #120 §2-1 이
    /// <i>"클라이언트는 price 나 보유 목록으로 이 값을 다시 계산하지 마십시오"</i> 라고 못박았다 —
    /// 팔레트 표시·구매 거부·아바타 저장 차단이 서버의 같은 식 하나를 쓰기 때문에, 이 값을
    /// 그대로 쓰면 "팔레트에서는 열려 보였는데 저장이 거부된다" 가 원리적으로 생기지 않는다.
    /// <see cref="price"/>·<see cref="onSale"/> 는 표시용이다.</para>
    ///
    /// <para><see cref="assetKey"/> 가 Unity 아이템과의 조인 키다 — <c>avatar_code</c> 의
    /// <c>i=</c> 칸에 실리는 값이고, <b>모자만 familyId</b> 이며 나머지는 itemId 다(#120 §4).
    /// 서버가 문자열로 내려주므로 정수 변환이 필요하다.</para>
    /// </summary>
    [Serializable]
    public class CatalogItemDto
    {
        public long itemId;        // 구매 경로 POST /catalog/items/{itemId}/purchases 의 path 변수
        public string code;        // 사람이 읽는 식별자 (예: Shared_Hat.001)
        public string name;
        public string equipSlot;   // HEAD·HAIR·HAT·GLASSES·TOP·BOTTOM·OUTFIT·SHOES
        public string assetKey;
        public int price;
        public bool onSale;
        public bool owned;
    }

    /// <summary>카탈로그 조회 응답. 서버가 <c>items</c> 한 겹으로 감싼다.</summary>
    [Serializable]
    public class CatalogItemsDto
    {
        public CatalogItemDto[] items;
    }

    /// <summary>공통 오류 봉투 <c>{code, message, ...}</c>. 구매 실패 분기는 상태 코드가 아니라 이 <c>code</c> 로 한다.</summary>
    [Serializable]
    public class ApiErrorDto
    {
        public string code;
        public string message;
    }

    /// <summary>
    /// 파츠 구매 결과 — <c>POST /api/v1/catalog/items/{itemId}/purchases</c> (GitLab #120 §2·§7).
    ///
    /// <para><b>실패 사유를 코드로 들고 온다.</b> 화면이 "코인이 모자랍니다" 와 "이미 가지고 있습니다" 와
    /// "판매가 중지됐습니다" 를 다르게 말해야 하는데, HTTP 상태는 셋 다 409 라 구분이 안 된다.
    /// 서버가 주는 <c>code</c> 를 그대로 싣는다 — 문구 비교는 하지 않는다(문구는 바뀐다).</para>
    /// </summary>
    public sealed class PurchaseResult
    {
        public bool ok;

        /// <summary>실패 시 서버 오류 코드. <c>INSUFFICIENT_COIN</c>·<c>ITEM_ALREADY_OWNED</c>·
        /// <c>ITEM_NOT_ON_SALE</c>·<c>CATALOG_ITEM_NOT_FOUND</c>·<c>MEMBER_ONLY</c> 등. 통신 실패면 null.</summary>
        public string code;

        /// <summary>로그·디버그용 사유. 화면 문구는 호출자가 <see cref="code"/> 로 만든다.</summary>
        public string reason;

        /// <summary>성공(201) 본문 — 그 품목의 <c>owned: true</c>.</summary>
        public CatalogItemDto item;

        public static PurchaseResult Fail(string code, string reason) => new() { ok = false, code = code, reason = reason };
    }

}
