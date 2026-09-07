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

    /// <summary>
    /// World 접속 endpoint (deployment-handoff §3 계약).
    /// full URI가 아닌 구조화 필드 — UnityTransport API(host/port)에 직접 매핑된다.
    /// path는 UnityTransport WebSocket이 지원하지 않으므로 계약에서 제외.
    /// </summary>
    [Serializable]
    public class WorldEndpointDto
    {
        public string scheme; // "ws"(로컬/개발) | "wss"(배포 — LB에서 TLS 종료)
        public string host;
        public int port;
    }

    /// <summary>
    /// doc 16 §3 World Session 응답 계약.
    /// MVP에서는 항상 단일 채널(11F-01)이 반환되지만,
    /// Unity는 endpoint를 하드코딩하지 않고 항상 이 응답을 사용한다 (Channel 확장 대비).
    /// </summary>
    [Serializable]
    public class WorldSessionDto
    {
        public string sessionId;
        public string worldId;
        public string channelId;
        public WorldEndpointDto endpoint;
        public string connectionToken;
        public string expiresAt;
    }
}
