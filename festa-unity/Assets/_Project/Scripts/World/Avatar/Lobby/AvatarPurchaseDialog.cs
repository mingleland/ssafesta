using System.Threading.Tasks;
using Festa.Integration;
using Festa.World.UI;
using TMPro;
using UnityEngine;
using UnityEngine.UI;

namespace Festa.Avatar
{
    /// <summary>
    /// 잠긴 파츠를 눌렀을 때 뜨는 **구매 확인 창** (사용자 지시 2026-09-10, GitLab #120 §2·§8-1).
    ///
    /// <para>계약은 이미 #120 에 확정돼 있다 — 조회 <c>GET /catalog/items?type=AVATAR_PART</c> 가
    /// 품목마다 <c>owned</c>·<c>price</c>·<c>itemId</c> 를 주고, 구매는
    /// <c>POST /catalog/items/{itemId}/purchases</c> 다. 이 화면은 그 두 호출을 잇는 UI 일 뿐이고
    /// <b>새 계약을 만들지 않는다.</b></para>
    ///
    /// <para><b>이중 차감은 서버가 막는다.</b> 지갑 <c>idempotencyKey</c> + <c>UNIQUE(user_id, catalog_item_id)</c>
    /// 두 겹이라(#120 §2) 연타로 두 번 가더라도 두 번째는 <c>ITEM_ALREADY_OWNED</c> 로 떨어지고 코인은 한 번만
    /// 빠진다. 그래도 화면에서 한 번 더 막는다 — 요청 중에는 버튼을 잠근다. 서버 보호가 있다고 해서 두 번
    /// 보내도 되는 것은 아니다(응답 순서가 엇갈리면 화면이 "실패" 를 마지막에 그린다).</para>
    ///
    /// <para><b>잔액은 서버에서 읽는다</b> — <c>GET /api/v1/wallets/me</c>. 못 읽으면 0 으로 꾸미지 않고
    /// "잔액 확인 불가" 로 두고 구매는 시도하게 둔다. 부족하면 서버가 <c>INSUFFICIENT_COIN</c> 으로 거절하고
    /// 그때 같은 창에서 부족 안내로 바뀐다 — 잔액을 못 읽었다는 이유로 살 길을 막지 않는다.</para>
    /// </summary>
    public sealed class AvatarPurchaseDialog : MonoBehaviour
    {
        const string LockOwner = "AvatarPurchaseDialog";

        long _itemId;
        int _ownershipKey;
        int _price;
        string _itemName;
        System.Action<int> _onPurchased;
        System.Action<bool> _onClosed;
        bool _purchased;

        long? _balance;          // null = 아직 모름
        bool _inFlight;
        bool _done;

        TMP_Text _priceText, _balanceText, _noticeText;
        Button _buyButton;
        TMP_Text _buyLabel;

        /// <summary>
        /// 창을 띄운다. 이미 떠 있으면 그것을 쓴다 — 잠긴 항목을 여러 번 눌러도 창이 겹치지 않는다.
        /// </summary>
        /// <param name="onPurchased">구매가 실제로 끝났을 때 소유 단위 키를 돌려준다(팔레트 새로고침용).</param>
        /// <param name="onClosed">
        /// 창이 닫힐 때 <b>반드시 한 번</b> 불린다. 인자는 구매 성공 여부다 — 호출자가 실착 미리보기를
        /// 되돌릴지 그대로 둘지 여기서 판단한다. 닫기·취소·창 파괴 어느 경로로 끝나도 불린다.
        /// </param>
        public static void Open(string itemName, int price, long itemId, int ownershipKey, System.Action<int> onPurchased,
                                System.Action<bool> onClosed = null)
        {
            var existing = FindFirstObjectByType<AvatarPurchaseDialog>();
            if (existing != null) Destroy(existing.gameObject);

            var go = new GameObject("@AvatarPurchaseDialog");
            var dialog = go.AddComponent<AvatarPurchaseDialog>();
            dialog._itemName = string.IsNullOrEmpty(itemName) ? "이 아이템" : itemName;
            dialog._price = Mathf.Max(0, price);
            dialog._itemId = itemId;
            dialog._ownershipKey = ownershipKey;
            dialog._onPurchased = onPurchased;
            dialog._onClosed = onClosed;
            dialog.Build();
            _ = dialog.LoadBalanceAsync();
        }

        /// <summary>
        /// 떠 있는 창이 있으면 <b>지금</b> 닫는다 — <c>onClosed</c> 가 이 호출 안에서 끝난다.
        ///
        /// <para>파괴는 프레임 끝으로 미뤄지므로, 새 창을 연 뒤에 옛 창이 정리되면 옛 창의 되돌리기가
        /// <b>새 미리보기를 지운다.</b> 잠긴 항목을 연달아 누를 때 나는 순서 문제라, 호출자가 새
        /// 미리보기를 입히기 전에 이것을 먼저 부른다.</para>
        /// </summary>
        public static void CloseExisting()
        {
            var existing = FindFirstObjectByType<AvatarPurchaseDialog>();
            if (existing != null) existing.Close();
        }

        void Build()
        {
            var canvas = FestaUiKit.OverlayCanvas(transform, "Canvas", 700);
            var root = canvas.transform;
            // 배경을 덜 어둡게 한다 — 이 창이 떠 있는 동안 아바타가 그 옷을 입고 서 있고,
            // 그것을 보고 살지 말지 정하는 창이다. 화면을 평소만큼 덮으면 볼 것이 가려진다.
            var backdrop = FestaUiKit.Backdrop(root);
            var dim = backdrop.color;
            dim.a *= 0.45f;
            backdrop.color = dim;

            var panel = FestaUiKit.Panel(root, "Card");
            var pr = panel.rectTransform;
            // **화면 가운데가 아니라 오른쪽으로 치운다.** 가운데는 아바타가 서 있는 자리다
            // (사용자 지시 2026-09-15 — "실착용 모습을 잘 볼 수 있게 옆으로").
            FestaUiKit.Place(pr, new Vector2(1f, 0.5f), new Vector2(1f, 0.5f), new Vector2(-36f, 0f), new Vector2(560f, 430f));

            FestaUiKit.TitleBanner(pr, "아이템 구매", new Vector2(0f, 22f), new Vector2(200f, 46f), 22f);
            FestaUiKit.CloseButton(pr, new Vector2(-14f, -14f), 40f, Close);

            FestaUiKit.Title(pr, _itemName, 26f, new Vector2(0f, -86f), new Vector2(480f, 40f));

            // 가격 — 어두운 표시창에 금색 숫자. 슬롯머신·타이밍 스톱과 같은 화면 언어.
            var display = FestaUiKit.Panel(pr, "Price", FestaUiKit.Card.Charcoal, 20);
            FestaUiKit.Place(display.rectTransform, new Vector2(0.5f, 1f), new Vector2(0.5f, 1f), new Vector2(0f, -138f), new Vector2(300f, 78f));
            _priceText = FestaUiKit.Label(display.rectTransform, $"{_price:N0} 코인", 34f, Vector2.zero, Vector2.zero, FestaUiKit.Gold, FontStyles.Bold);
            FestaUiKit.Stretch(_priceText.rectTransform);

            _balanceText = FestaUiKit.Label(pr, "내 코인을 확인하는 중…", 17f, new Vector2(0f, -238f), new Vector2(480f, 30f), FestaUiKit.Muted);
            _noticeText = FestaUiKit.Label(pr, "구매하시겠습니까?", 17f, new Vector2(0f, -282f), new Vector2(480f, 34f), FestaUiKit.Text);

            _buyButton = FestaUiKit.PillButton(pr, "구매", new Vector2(84f, -362f), new Vector2(190f, 56f), OnBuy, true, 21f);
            _buyLabel = FestaUiKit.ButtonLabel(_buyButton);
            FestaUiKit.PillButton(pr, "취소", new Vector2(-84f, -362f), new Vector2(190f, 56f), Close, false, 21f);

            InputBridge.SetLocked(true, LockOwner);
        }

        /// <summary>
        /// 잔액 조회. 실패해도 0 으로 꾸미지 않는다 — "확인 불가" 로 두고 구매 시도는 막지 않는다(T-24).
        /// </summary>
        async Task LoadBalanceAsync()
        {
            try
            {
                ApiServices.EnsureInitialized();
                var wallet = ApiServices.Wallet != null ? await ApiServices.Wallet.GetMyBalanceAsync() : null;
                if (this == null) return;
                _balance = wallet?.balance;
            }
            catch (System.Exception e)
            {
                Debug.LogWarning($"[AvatarPurchase] 잔액 조회 실패 — {e.Message}");
                if (this == null) return;
                _balance = null;
            }
            RedrawBalance();
        }

        void RedrawBalance()
        {
            if (_done || _balanceText == null) return;

            if (_balance == null)
            {
                _balanceText.text = "내 코인을 확인하지 못했습니다";
                _balanceText.color = FestaUiKit.Muted;
                return;
            }

            _balanceText.text = $"내 코인  {_balance.Value:N0}";
            bool enough = _balance.Value >= _price;
            _balanceText.color = enough ? FestaUiKit.Muted : FestaUiKit.Bad;
            if (!enough) ShowInsufficient(_balance.Value);
        }

        /// <summary>코인 부족 — 살 수 없다는 것과 <b>얼마가 모자란지</b>를 같이 말한다.</summary>
        void ShowInsufficient(long balance)
        {
            long short_ = System.Math.Max(0, _price - balance);
            _noticeText.text = $"코인이 부족합니다 — {short_:N0} 코인 더 필요해요";
            _noticeText.color = FestaUiKit.Bad;
            SetBuy("코인 부족", false);
        }

        void SetBuy(string label, bool interactable)
        {
            if (_buyLabel != null) _buyLabel.text = label;
            if (_buyButton != null) _buyButton.interactable = interactable;
        }

        void OnBuy()
        {
            if (_inFlight || _done) return;   // 연타 방지 — 서버 보호(idempotency)에 기대지 않는다
            _ = BuyAsync();
        }

        async Task BuyAsync()
        {
            _inFlight = true;
            SetBuy("구매 중…", false);
            _noticeText.text = "결제를 처리하고 있습니다…";
            _noticeText.color = FestaUiKit.Muted;

            PurchaseResult result;
            try
            {
                ApiServices.EnsureInitialized();
                result = await ApiServices.User.PurchaseAvatarPartAsync(_itemId);
            }
            catch (System.Exception e)
            {
                result = PurchaseResult.Fail(null, e.Message);
            }
            if (this == null) return;
            _inFlight = false;

            if (result != null && result.ok)
            {
                _done = true;
                AvatarOwnership.MarkOwned(_ownershipKey);
                _noticeText.text = "구매가 완료됐어요. 바로 입어 볼 수 있어요.";
                _noticeText.color = FestaUiKit.Good;
                SetBuy("완료", false);
                _purchased = true;
                if (_balance != null) { _balance -= _price; _balanceText.text = $"내 코인  {_balance.Value:N0}"; }
                _onPurchased?.Invoke(_ownershipKey);
                await CloseAfterAsync(1.1f);
                return;
            }

            // 실패 — **상태 코드가 아니라 서버 code 로 문구를 고른다** (셋 다 409 라 구분이 안 된다).
            switch (result?.code)
            {
                case "INSUFFICIENT_COIN":
                    // 창을 연 뒤 잔액이 줄었을 수 있다 — 다시 읽어 정확한 부족분을 보여 준다.
                    _noticeText.text = "코인이 부족합니다";
                    _noticeText.color = FestaUiKit.Bad;
                    SetBuy("코인 부족", false);
                    await LoadBalanceAsync();
                    break;
                case "ITEM_ALREADY_OWNED":
                    // 연타로 두 번째 요청이 갔거나 다른 창에서 이미 샀다. 코인은 한 번만 빠졌다.
                    _done = true;
                    AvatarOwnership.MarkOwned(_ownershipKey);
                    _noticeText.text = "이미 보유한 아이템이에요.";
                    _noticeText.color = FestaUiKit.Good;
                    SetBuy("완료", false);
                    _purchased = true;
                    _onPurchased?.Invoke(_ownershipKey);
                    await CloseAfterAsync(1.1f);
                    break;
                case "ITEM_NOT_ON_SALE":
                    _noticeText.text = "지금은 판매하지 않는 아이템이에요.";
                    _noticeText.color = FestaUiKit.Bad;
                    SetBuy("구매 불가", false);
                    break;
                case "CATALOG_ITEM_NOT_FOUND":
                    _noticeText.text = "상점에서 찾을 수 없는 아이템이에요.";
                    _noticeText.color = FestaUiKit.Bad;
                    SetBuy("구매 불가", false);
                    break;
                case "MEMBER_ONLY":
                case "UNAUTHORIZED":
                    _noticeText.text = "로그인하면 구매할 수 있어요.";
                    _noticeText.color = FestaUiKit.Bad;
                    SetBuy("로그인 필요", false);
                    break;
                default:
                    // 원인을 모르는 실패를 "성공했을지도" 로 남기지 않는다. 다시 시도할 수 있게 열어 둔다.
                    _noticeText.text = "구매하지 못했습니다. 잠시 후 다시 시도해 주세요.";
                    _noticeText.color = FestaUiKit.Bad;
                    SetBuy("다시 시도", true);
                    Debug.LogError($"[AvatarPurchase] 알 수 없는 실패 — code={result?.code} reason={result?.reason}");
                    break;
            }
        }

        async Task CloseAfterAsync(float seconds)
        {
            await Awaitable.WaitForSecondsAsync(seconds);
            if (this != null) Close();
        }

        bool _released;

        public void Close()
        {
            Release();
            Destroy(gameObject);
        }

        void OnDestroy() => Release();

        void Release()
        {
            if (_released) return;
            _released = true;
            InputBridge.SetLocked(false, LockOwner);
            var closed = _onClosed;
            _onClosed = null;
            closed?.Invoke(_purchased);
        }
    }
}
