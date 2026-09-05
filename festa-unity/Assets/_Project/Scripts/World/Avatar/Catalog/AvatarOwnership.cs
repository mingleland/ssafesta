using System.Collections.Generic;
using UnityEngine;

namespace Festa.Avatar
{
    /// <summary>보유 정보가 어느 상태인가. 잠금 판정과 오류 표시가 이 값을 본다.</summary>
    public enum AvatarOwnershipState
    {
        /// <summary>아직 조회하지 않았다. 전부 잠긴 것으로 본다.</summary>
        NotLoaded,
        /// <summary>서버 응답을 받았다. 이 상태에서만 판정이 유효하다.</summary>
        Loaded,
        /// <summary>조회가 실패했다. 전부 잠긴 채로 두고 오류를 드러낸다.</summary>
        Failed,
        /// <summary>Mock 개발 모드 — 전부 해제. 호출자가 명시적으로 선택한다.</summary>
        DevUnlockedAll,
    }

    /// <summary>
    /// 이 사용자가 무엇을 착용할 수 있는지 판정한다 (S15P21A604-412, GitLab #120 §8-1).
    ///
    /// <para><b>정본은 서버 하나다.</b> <c>GET /api/v1/catalog/items?type=AVATAR_PART</c> 가
    /// 품목마다 <c>owned</c> 를 실어 주고, Unity 는 그 값을 그대로 읽는다. #120 §2-1 이
    /// <i>"클라이언트는 price 나 보유 목록으로 이 값을 다시 계산하지 마십시오"</i> 라고
    /// 못박았다 — 팔레트 표시·구매 거부·아바타 저장 차단이 서버의 같은 식 하나를 쓰기 때문에,
    /// 받은 값을 그대로 쓰면 <b>"팔레트에서는 열려 보였는데 저장이 거부된다"</b> 가 원리적으로
    /// 생기지 않는다. 클라이언트가 따로 계산하면 그때부터 어긋난다.</para>
    ///
    /// <para><b>그래서 <c>isDefaultUnlocked</c> 를 걷어냈다.</b> 서버 계약이 없던 동안
    /// Unity 가 자체 플래그로 잠금을 계산했는데(S15P21A604-355), 그것이 정확히 §2-1 이
    /// 금지한 재계산이다. 무료 파츠는 서버에서 <c>price 0 → owned true</c> 로 표현되므로
    /// 클라이언트에 무료 목록을 둘 이유가 없다.</para>
    ///
    /// <para><b>실패는 조여서 처리한다.</b> 조회 전·조회 실패 상태에서는 전부 잠긴 것으로
    /// 본다. 비어 있음을 "전부 해제" 로 읽으면 응답이 실패했을 때 조용히 다 풀려, 잠금이
    /// 동작하는지 아무도 모르는 채로 배포된다 (T-24 와 같은 실패 양상). 대신 로비가
    /// <see cref="State"/>·<see cref="FailureReason"/> 를 읽어 화면에 드러낸다.</para>
    /// </summary>
    public static class AvatarOwnership
    {
        /// <summary>보유한 소유 단위 키.</summary>
        static readonly HashSet<int> Owned = new();

        /// <summary>서버 카탈로그에 등록된 소유 단위 키 — 보유와 무관하게 전부.</summary>
        static readonly HashSet<int> Cataloged = new();

        public static AvatarOwnershipState State { get; private set; } = AvatarOwnershipState.NotLoaded;

        /// <summary>실패 사유. <see cref="AvatarOwnershipState.Failed"/> 일 때만 채워진다.</summary>
        public static string FailureReason { get; private set; }

        /// <summary>판정이 서버 근거를 갖고 있는가.</summary>
        public static bool HasServerData => State == AvatarOwnershipState.Loaded;

        /// <summary>
        /// 잠금 판정을 신뢰할 수 있는가 — 즉 "무엇이 잠겼는지 안다" 고 말할 수 있는가.
        ///
        /// <para><b>이 값이 false 인 동안 잠금으로 후보를 좁히면 안 된다.</b> 조회 전에는
        /// 보유가 전부 비어 있어서, 기본·무작위 아바타가 후보 0개로 떨어져 알몸이 된다.
        /// 그동안은 전체 목록에서 고르고, 판정이 준비된 뒤
        /// <c>SanitizeLocked</c> 가 잠긴 것을 교체한다. 사용자에게 보이는 것은
        /// "옷을 입은 아바타" 이고, 잠금은 판정이 도착한 시점부터 정확해진다.</para>
        /// </summary>
        public static bool JudgementReady =>
            State == AvatarOwnershipState.Loaded || State == AvatarOwnershipState.DevUnlockedAll;

        /// <summary>
        /// 소유 단위 키 — <c>avatar_code</c> 의 <c>i=</c> 칸에 실리는 값이다.
        ///
        /// <para><b>모자만 <c>familyId</c> 다</b> (#120 §4). 모자는 헤어 볼륨별 변형이 여럿인데
        /// 저장값은 변형이 아니라 family 이고, 그릴 때 <see cref="AvatarCatalog.ResolveHat"/> 가
        /// 현재 헤어에 맞는 변형을 고른다. 그래서 판매·소유 단위도 family 여야 한다 —
        /// 변형 단위로 팔면 "볼캡(버즈컷용)" 만 산 사람이 머리를 묶는 순간 안 산 변형이 골라진다.</para>
        /// </summary>
        public static int OwnershipKey(AvatarItemDefinition item)
        {
            if (item == null) return 0;
            return item.category == AvatarPartCategory.Hat ? item.familyId : item.itemId;
        }

        /// <summary>
        /// 서버 응답을 반영한다. <paramref name="catalogKeys"/> 는 응답에 실린 모든 품목의
        /// 소유 단위 키, <paramref name="ownedKeys"/> 는 그중 <c>owned</c> 인 것이다.
        /// </summary>
        public static void SetFromServer(IEnumerable<int> catalogKeys, IEnumerable<int> ownedKeys)
        {
            Cataloged.Clear();
            Owned.Clear();
            if (catalogKeys != null) foreach (var key in catalogKeys) if (key != 0) Cataloged.Add(key);
            if (ownedKeys != null) foreach (var key in ownedKeys) if (key != 0) Owned.Add(key);

            State = AvatarOwnershipState.Loaded;
            FailureReason = null;
        }

        /// <summary>
        /// 조회 실패를 기록한다. 잠금은 유지된다 — 실패를 개방으로 바꾸지 않는다.
        /// </summary>
        public static void MarkFailed(string reason)
        {
            Cataloged.Clear();
            Owned.Clear();
            State = AvatarOwnershipState.Failed;
            FailureReason = string.IsNullOrEmpty(reason) ? "알 수 없는 오류" : reason;
            Debug.LogError($"[AvatarOwnership] 파츠 보유 정보를 받지 못해 전부 잠긴 상태로 둔다 — {FailureReason}");
        }

        /// <summary>
        /// Mock·오프라인 개발용으로 전부 해제한다. <b>호출자가 명시적으로 고른다</b> —
        /// 실패 경로가 조용히 여기로 떨어지면 안 된다.
        /// </summary>
        public static void UnlockAllForDevelopment(string why)
        {
            Cataloged.Clear();
            Owned.Clear();
            State = AvatarOwnershipState.DevUnlockedAll;
            FailureReason = null;
            Debug.LogWarning($"[AvatarOwnership] 개발 모드 — 파츠를 전부 해제한다. 실서버에서는 owned 를 쓴다. ({why})");
        }

        /// <summary>착용할 수 있는 아이템인가. 서버 <c>owned</c> 하나만 본다.</summary>
        public static bool IsUnlocked(AvatarItemDefinition item)
        {
            if (item == null) return false;
            if (State == AvatarOwnershipState.DevUnlockedAll) return true;
            if (State != AvatarOwnershipState.Loaded) return false;
            return Owned.Contains(OwnershipKey(item));
        }

        /// <summary>
        /// 서버 카탈로그에 있는 아이템인가.
        ///
        /// <para>#120 §2-1 이 <b>조회 응답에 있는 품목만 그리라</b>고 권한다. 응답에 없는 파츠는
        /// 서버 시드 미등록이라, 그려 두면 사용자가 고를 수는 있어도 저장이
        /// <c>409 AVATAR_ITEM_NOT_OWNED</c> 로 거부된다 — 고를 수 있는데 저장이 안 되는 것이
        /// 애초에 안 보이는 것보다 나쁘다.</para>
        /// </summary>
        public static bool IsInCatalog(AvatarItemDefinition item)
        {
            if (item == null) return false;
            if (State == AvatarOwnershipState.DevUnlockedAll) return true;
            if (State != AvatarOwnershipState.Loaded) return true;   // 아직 모른다 — 숨기지는 않는다
            return Cataloged.Contains(OwnershipKey(item));
        }

        /// <summary>
        /// 저장된 id 로 판정한다. 카탈로그에 없는 id 는 잠긴 것으로 본다.
        /// 모자는 저장값이 <c>familyId</c> 라 변형 하나를 찾아 판정한다.
        /// </summary>
        public static bool IsUnlocked(AvatarCatalog catalog, int itemId)
        {
            if (itemId == 0) return true;              // "없음" 은 항상 고를 수 있다
            if (catalog == null) return false;
            return IsUnlocked(catalog.Get(itemId) ?? catalog.ResolveHat(itemId, HairGroup.None));
        }
    }
}
