using Festa.World;
using Festa.World.UI;
using TMPro;
using UnityEngine;
using UnityEngine.UI;

namespace Festa.Minigame.Slot
{
    /// <summary>
    /// slot machine 조작 화면 (S15P21A604-439). <see cref="FestaUiKit"/> v3(밝은 카드)로 그린다.
    ///
    /// <para>초점 카메라가 화면 가운데에 게임 화면을 크게 잡으므로 HUD 는 <b>오른쪽 세로 카드</b> 하나다:
    /// 제목 태그 → 코인 잔액(어두운 표시창, 크게) → 결과 → 코랄 버튼 → 안내. 코인 부족 등은 가운데 카드 팝업.
    /// Esc(초점 해제)나 ✕ 로 닫히며 닫힐 때 <c>onClosed</c> 를 한 번 부른다.</para>
    ///
    /// <para>판정이 Mock(<see cref="SlotMachineSession.Simulated"/>)이면 "체험판 · 코인 미반영" 배지를 항상 단다 — 실서버인 척하지 않는다.</para>
    /// </summary>
    public sealed class SlotMachineHud : MonoBehaviour
    {
        static SlotMachineHud s_open;
        public static bool IsOpen => s_open != null;

        SlotMachineSession _session;
        System.Action _onClosed;
        TMP_Text _balance, _balanceNote, _result, _popupTitle, _popupText;
        Button _spin;
        GameObject _badge, _popup;
        bool _closed;

        public static SlotMachineHud Open(SlotMachineSession session, System.Action onClosed)
        {
            if (s_open != null) return s_open;
            var go = new GameObject("@SlotMachineHud");
            var hud = go.AddComponent<SlotMachineHud>();
            s_open = hud;
            hud._session = session;
            hud._onClosed = onClosed;
            hud.Build();
            session.Changed += hud.Redraw;
            InteractionFocusCamera.Released += hud.Close;
            hud.Redraw();
            return hud;
        }

        public void Close()
        {
            if (_closed) return;
            _closed = true;
            if (_session != null) _session.Changed -= Redraw;
            InteractionFocusCamera.Released -= Close;
            if (s_open == this) s_open = null;
            _onClosed?.Invoke();
            Destroy(gameObject);
        }

        void OnDestroy()
        {
            if (!_closed) Close();
        }

        void Build()
        {
            var canvas = FestaUiKit.OverlayCanvas(transform, "Canvas", 500);
            var root = canvas.transform;

            var card = FestaUiKit.Panel(root, "Card");
            var cr = card.rectTransform;
            FestaUiKit.Place(cr, new Vector2(1f, 0.5f), new Vector2(1f, 0.5f), new Vector2(-48f, 0f), new Vector2(380f, 520f));

            FestaUiKit.TitleBanner(cr, "슬롯머신", new Vector2(0f, 22f), new Vector2(200f, 46f), 22f);
            FestaUiKit.CloseButton(cr, new Vector2(-14f, -14f), 40f, Close);

            _badge = FestaUiKit.Chip(cr, "체험판 · 코인 미반영", new Vector2(0f, -54f)).gameObject;

            // 잔액 — 어두운 표시창에 코인 아이콘 + 큰 숫자
            var display = FestaUiKit.Panel(cr, "Balance", FestaUiKit.Card.Charcoal, 20);
            FestaUiKit.Place(display.rectTransform, new Vector2(0.5f, 1f), new Vector2(0.5f, 1f), new Vector2(0f, -96f), new Vector2(316f, 104f));
            FestaUiKit.Icon(display.rectTransform, UiSprite.IconCoin, new Vector2(-104f, -22f), 58f);
            _balance = FestaUiKit.Label(display.rectTransform, "…", 46f, new Vector2(26f, -14f), new Vector2(200f, 62f), FestaUiKit.Gold,
                                        FontStyles.Bold, TextAlignmentOptions.MidlineLeft);
            _balanceNote = FestaUiKit.Label(display.rectTransform, "보유 코인", 14f, new Vector2(26f, -74f), new Vector2(240f, 22f),
                                            new Color(0.75f, 0.76f, 0.82f, 1f), FontStyles.Normal, TextAlignmentOptions.MidlineLeft);

            _result = FestaUiKit.Label(cr, "", 26f, new Vector2(0f, -222f), new Vector2(320f, 80f), FestaUiKit.Text, FontStyles.Bold);

            _spin = FestaUiKit.PillButton(cr, $"{SlotMachineSession.Bet}코인 넣고 돌리기", new Vector2(0f, -324f), new Vector2(300f, 66f),
                                          () => _session.Spin(), true, 22f);

            FestaUiKit.Label(cr, "최대 50코인 · 결과는 서버가 판정합니다", 13f, new Vector2(0f, -410f), new Vector2(320f, 22f), FestaUiKit.Muted);
            FestaUiKit.Label(cr, "Esc  나가기", 14f, new Vector2(0f, -470f), new Vector2(320f, 22f), FestaUiKit.Muted);

            // 팝업 — 코인 부족 등. 필요할 때만.
            var popup = FestaUiKit.Panel(root, "Popup");
            var pr = popup.rectTransform;
            FestaUiKit.Place(pr, new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f), new Vector2(-110f, 40f), new Vector2(520f, 240f));
            _popupTitle = FestaUiKit.Title(pr, "알림", 26f, new Vector2(0f, -40f), new Vector2(460f, 40f));
            _popupText = FestaUiKit.Label(pr, "", 19f, new Vector2(0f, -92f), new Vector2(440f, 60f), FestaUiKit.Muted);
            FestaUiKit.PillButton(pr, "확인", new Vector2(0f, -168f), new Vector2(180f, 52f), () => _session.DismissPopup(), true, 19f);
            _popup = popup.gameObject;
            _popup.SetActive(false);
        }

        void Redraw()
        {
            if (_closed || _session == null) return;

            _badge.SetActive(_session.Simulated);

            if (_session.Balance.HasValue)
            {
                _balance.text = _session.Balance.Value.ToString("N0");
                _balanceNote.text = "보유 코인";
            }
            else
            {
                _balance.text = "—";
                _balanceNote.text = _session.Current == SlotMachineSession.Phase.Loading ? "잔액 불러오는 중…" : _session.BalanceNote;
            }

            switch (_session.Current)
            {
                case SlotMachineSession.Phase.Loading:
                    _result.text = "";
                    _spin.interactable = false;
                    break;
                case SlotMachineSession.Phase.Spinning:
                    _result.text = "돌아가는 중…";
                    _result.color = FestaUiKit.Muted;
                    _spin.interactable = false;
                    break;
                case SlotMachineSession.Phase.Result:
                    var r = _session.Last;
                    if (r != null && r.payout > 0)
                    {
                        _result.text = r.tier >= 3 ? $"잭팟!\n+{r.payout} 코인" : $"당첨!\n+{r.payout} 코인";
                        _result.color = FestaUiKit.Good;
                    }
                    else
                    {
                        _result.text = $"아쉽네요\n−{SlotMachineSession.Bet} 코인";
                        _result.color = FestaUiKit.Bad;
                    }
                    _spin.interactable = true;
                    break;
                default:
                    _result.text = "10코인으로 한 판!";
                    _result.color = FestaUiKit.Text;
                    _spin.interactable = true;
                    break;
            }

            bool hasPopup = !string.IsNullOrEmpty(_session.Popup);
            _popup.SetActive(hasPopup);
            if (hasPopup)
            {
                bool insufficient = _session.Popup.StartsWith("코인이 부족");
                _popupTitle.text = insufficient ? "코인이 부족합니다" : "알림";
                _popupText.text = insufficient ? _session.Popup.Replace("코인이 부족합니다 — ", "") : _session.Popup;
            }
        }
    }
}
