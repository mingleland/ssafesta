using Festa.World;
using Unity.Netcode;
using UnityEngine;
using UnityEngine.InputSystem;

namespace Festa.Network
{
    /// <summary>Alt + 좌클릭 드래그로 8방향 감정표현을 선택한다 (오버워치식 방사형 휠).</summary>
    [RequireComponent(typeof(NetworkPlayer))]
    public sealed class PlayerEmoteController : NetworkBehaviour
    {
        // 다른 HUD 와 같은 배율(Screen.height/1080, 0.75~2.0)을 따른다 — 고정 픽셀이라 고DPI 전체화면에서 휠만 절반
        // 크기였고, 작은 창에서는 ClampCenter 의 min>max 로 중심이 커서에서 튀어 엉뚱한 감정이 선택됐다(QA #68).
        static float DeadZone => 52f * Festa.World.InteractPromptUI.UiScale();      // 허브 반경과 맞춘다 — 허브 안에서는 선택되지 않는다
        static float WheelRadius => 150f * Festa.World.InteractPromptUI.UiScale();  // 링 외곽 반경(화면 픽셀)

        // 휠 8칸 — 시계 방향(위부터). Labels 와 순서가 1:1 로 맞아야 한다.
        static readonly PlayerEmoteId[] Emotes =
        {
            PlayerEmoteId.Greeting, PlayerEmoteId.Clap,
            PlayerEmoteId.Cheer,    PlayerEmoteId.Nod,
            PlayerEmoteId.Laugh,    PlayerEmoteId.SitGround,
            PlayerEmoteId.Drink,    PlayerEmoteId.Thanks,
        };

        static readonly string[] Labels =
        {
            "인사", "박수", "환호", "끄덕임",
            "웃음", "앉기", "건배", "감사"
        };

        /// <summary>
        /// 좌클릭 주먹질에 쓰는 원샷 셋 (사용자 지시 2026-09-15 — "세 개가 랜덤으로 나오게").
        ///
        /// <para><b>뽑는 것은 소유자다.</b> 뽑은 값이 <see cref="NetworkPlayer.EmoteId"/> 로 복제되므로
        /// 모두가 같은 주먹을 본다. 값 하나로 두고 각자 클립을 고르면 화면마다 다른 동작이 나간다.</para>
        /// </summary>
        static readonly PlayerEmoteId[] Punches =
        {
            // **위빙이 섞인 클립은 뺐다** (사용자 지시 2026-09-16). Punch2 에 걸린 Punching 클립은
            // 재생 구간이 1.28초라 주먹 사이에 상체를 좌우로 흘리는 동작이 들어간다 — 한 대 치는
            // 것으로 읽히지 않는다. 남긴 둘은 0.33초로 잘라낸 잽이라 위빙이 들어갈 여지가 없다.
            // 열거형과 애니메이터 상태는 건드리지 않는다 — 동결 네트워크 계약과 저장값을 지킨다.
            PlayerEmoteId.Punch1, PlayerEmoteId.Punch3
        };

        /// <summary>
        /// 주먹 한 대가 화면에 머무는 시간(초). 잘라낸 잽 길이(0.333·0.333·0.400)를 상태 speed 1.25 로 나눈 값이다.
        ///
        /// <para>애니메이터에서 읽지 않고 표로 두는 이유: 다음 한 대를 **언제 이어 칠지**를 클릭한 프레임에
        /// 정해야 하는데, 상태 길이는 크로스페이드가 끝난 뒤에야 읽을 수 있다. 클립을 다시 자르면
        /// 이 값도 같이 고친다.</para>
        /// </summary>
        static readonly float[] PunchSeconds = { 0.27f, 0.32f };

        /// <summary>마지막 주먹이 끝난 뒤 이 시간 안에 다시 누르면 콤보가 이어진다.</summary>
        const float ComboKeepAlive = 0.8f;

        int _comboStep = -1;
        float _comboExpireAt;
        float _punchBusyUntil;
        // 연타를 bool 하나로 기억하면 재생 중 두 번 이상 누른 입력이 하나로 합쳐진다.
        // 최대 한 사이클까지 개수로 보관해 누른 만큼 순서대로 이어 친다.
        int _queuedPunches;

        NetworkPlayer _player;
        bool _wheelOpen;
        Vector2 _center;
        int _selected = -1;
        float _oneShotStopAt;
        GUIStyle _labelStyle;
        GUIStyle _selectedStyle;
        GUIStyle _titleStyle;
        GUIStyle _hubPickStyle;
        Texture2D _ringTexture;
        Texture2D _wedgeTexture;
        Texture2D _hubTexture;

        void Awake()
        {
            _player = GetComponent<NetworkPlayer>();
            useGUILayout = false;   // GUI.* 만 쓴다 — Layout 패스 제거로 OnGUI 호출·GC 절반 (QA #69)
        }

        public override void OnNetworkSpawn() => enabled = IsOwner;

        /// <summary>
        /// 휠을 거치지 않고 코드에서 원샷 이모트를 재생한다 (하이 스트라이커의 내리찍기 등, 2026-09-10).
        ///
        /// <para><b>종료를 여기에 맡겨야 한다.</b> <see cref="NetworkPlayer.EmoteId"/> 에 직접 대입하면 원샷이라도
        /// 끝나지 않는다 — 되돌리는 것은 <see cref="TrackOneShotDuration"/> 뿐이고, 그것은 휠이 예약한 것만 본다.
        /// 이 경로로 넣으면 상태가 실제로 재생을 시작한 뒤 그 길이만큼 뒤에 자동으로 None 으로 돌아간다.</para>
        /// </summary>
        public void PlayOneShot(PlayerEmoteId emote)
        {
            if (!IsOwner || emote == PlayerEmoteId.None || IsLooping(emote)) return;
            // 주먹이 아닌 연출로 넘어가면 **예약된 주먹을 버린다.** 안 버리면 스윙 한가운데에서 큐가
            // 풀려 EmoteId 를 주먹으로 덮어쓴다 — 망치를 치는 도중에 주먹이 끼어들어 동작이
            // 뒤엉킨다(사용자 영상 2026-09-16).
            if (System.Array.IndexOf(Punches, emote) < 0) CancelPendingPunches();
            _player.EmoteId.Value = emote;
            if (emote == PlayerEmoteId.Strike)
            {
                // 접촉·반동·복귀를 포함한 타임라인. 원본 대검 클립 길이와 독립적이다.
                _oneShotStopAt = Time.unscaledTime + AvatarStrikeProp.SwingDuration;
                _awaitingDuration = PlayerEmoteId.None;
                return;
            }

            int punch = System.Array.IndexOf(Punches, emote);
            if (punch >= 0)
            {
                // 주먹은 상태 길이를 기다려 끊지 않는다. TrackOneShotDuration 은 크로스페이드가 끝난
                // 뒤에야 길이를 읽고 최소 0.5초를 보장하는데, 잘라낸 잽은 0.27초라 그 바닥값이
                // 다음 한 대를 늦춘다 — 콤보가 끊겨 보이는 자리다.
                _oneShotStopAt = Time.unscaledTime + PunchSeconds[punch];
                _awaitingDuration = PlayerEmoteId.None;
                return;
            }

            _oneShotStopAt = 0f;
            _awaitingDuration = emote;
        }

        void Update()
        {
            if (!IsOwner) return;
            // 호스트 Overlay 가 열려 있으면 이모트 입력도 읽지 않는다 (G-8, InputBridge).
            if (Festa.Integration.InputBridge.IsLocked) return;
            var keyboard = Keyboard.current;
            var mouse = Mouse.current;
            if (keyboard == null || mouse == null) return;

            bool alt = keyboard.leftAltKey.isPressed || keyboard.rightAltKey.isPressed;
            var pointer = mouse.position.ReadValue();
            if (!_wheelOpen && alt && mouse.leftButton.wasPressedThisFrame)
            {
                _wheelOpen = true;
                _center = ClampCenter(pointer);
                UpdateSelection(pointer);
            }

            if (_wheelOpen)
            {
                if (!alt) CloseWheel(false);
                else
                {
                    UpdateSelection(pointer);
                    if (mouse.leftButton.wasReleasedThisFrame) CloseWheel(true);
                }
            }
            else if (!alt && mouse.leftButton.wasPressedThisFrame && CanPunch())
            {
                // 누른 것을 버리지 않는다. 치는 중이면 예약해 두고 끝나는 순간 이어 친다 —
                // 무작위로 골라 매번 새로 시작하면 같은 동작이 겹쳐 움찔거리기만 한다(사용자 지적).
                if (Time.time >= _punchBusyUntil) FirePunch();
                else _queuedPunches = Mathf.Min(_queuedPunches + 1, Punches.Length);
            }

            if (_queuedPunches > 0 && Time.time >= _punchBusyUntil) FirePunch();

            // 점프하면 남은 예약을 버린다 — 착지하는 순간 밀린 주먹이 몰아서 터지지 않게.
            if (IsAirborne()) _queuedPunches = 0;

            TrackOneShotDuration();

            if (_oneShotStopAt > 0f && Time.unscaledTime >= _oneShotStopAt)
            {
                _oneShotStopAt = 0f;
                if (_player.EmoteId.Value != PlayerEmoteId.None && !IsLooping(_player.EmoteId.Value))
                    _player.EmoteId.Value = PlayerEmoteId.None;
            }
        }

        Vector2 ClampCenter(Vector2 pointer)
        {
            var margin = WheelRadius + 74f * Festa.World.InteractPromptUI.UiScale();
            // 화면이 여백 두 배보다 작으면 클램프의 min 이 max 를 넘어 중심이 튄다 — 그때는 클램프하지 않는다 (QA #68)
            if (Screen.width < margin * 2f || Screen.height < margin * 2f) return pointer;
            return new Vector2(
                Mathf.Clamp(pointer.x, margin, Screen.width - margin),
                Mathf.Clamp(pointer.y, margin, Screen.height - margin));
        }

        /// <summary>
        /// 지금 좌클릭을 주먹질로 읽어도 되는가.
        ///
        /// <para>좌클릭은 부스 상호작용과 겹치지 않는다 — 그쪽 실행 입력은 F 하나이고 클릭은 조준·호버에만 쓴다
        /// (<c>BoothInteractionInput.InteractKeyPressedThisFrame</c>). 남는 충돌원은 UI 클릭과, 이미 재생 중인
        /// 연출이다. 루프 이모트(앉기·눕기)는 <see cref="PlayOneShot"/> 가 걸러 주지만 그건 "넣으려는 값" 기준이라
        /// <b>지금 재생 중인 것</b>은 여기서 본다 — 눕거나 앉은 채로 주먹이 나가면 자세가 튄다.</para>
        /// </summary>
        bool CanPunch()
        {
            var es = UnityEngine.EventSystems.EventSystem.current;
            if (es != null && es.IsPointerOverGameObject()) return false;
            var current = _player.EmoteId.Value;
            if (current != PlayerEmoteId.None && IsLooping(current)) return false;
            // 망치 스윙은 원샷이라 IsLooping 에 걸리지 않는다. 그대로 두면 스윙 도중 좌클릭이
            // 주먹으로 읽혀 두 연출이 겹친다 (사용자 영상 2026-09-16).
            if (current == PlayerEmoteId.Strike) return false;
            // 공중에서는 치지 않는다. 상체 레이어만 바뀌므로 다리는 점프 클립 그대로인데
            // 상체만 복싱이라 자세가 어긋나 보인다 (사용자 지시 2026-09-16).
            if (IsAirborne()) return false;
            return true;
        }

        /// <summary>
        /// 도약·체공 중인가. 착지 복귀(<see cref="PlayerAnimState.JumpLand"/>)는 이미 발이 땅에 있으므로
        /// 포함하지 않는다 — 내려서자마자 칠 수 있어야 조작이 답답하지 않다.
        /// </summary>
        bool IsAirborne()
        {
            var state = _player.AnimState.Value;
            return state == PlayerAnimState.Jump || state == PlayerAnimState.JumpLaunch;
        }

        /// <summary>예약과 콤보를 한꺼번에 버린다. 다른 연출이 끼어들 때 주먹의 잔여 일정이 살아남지 않게 한다.</summary>
        void CancelPendingPunches()
        {
            _queuedPunches = 0;
            _punchBusyUntil = 0f;
            _comboStep = -1;
            _comboExpireAt = 0f;
        }

        /// <summary>
        /// 콤보 한 대를 친다. 이어서 누르면 1 → 2 → 1 로 돌고, 끊기면 다시 1 부터다.
        ///
        /// <para>무작위가 아니라 <b>순서</b>인 이유: 무작위는 같은 동작이 연달아 나올 수 있고, 그러면
        /// 두 번 친 것이 한 번 친 것처럼 보인다. 순서로 돌리면 세 번이 서로 다른 동작이라 콤보로 읽힌다.
        /// 고른 값은 <c>EmoteId</c> 로 복제되므로 남의 화면에서도 같은 순서로 나간다.</para>
        /// </summary>
        void FirePunch()
        {
            if (_queuedPunches > 0) _queuedPunches--;

            bool continues = _comboStep >= 0 && Time.time <= _comboExpireAt;
            _comboStep = continues ? (_comboStep + 1) % Punches.Length : 0;

            float seconds = PunchSeconds[_comboStep];
            _punchBusyUntil = Time.time + seconds;
            _comboExpireAt = _punchBusyUntil + ComboKeepAlive;
            // 피격 밀림은 걷어냈다 (사용자 지시 2026-09-16) — 주먹은 연출이고 남을 움직이지 않는다.

            PlayOneShot(Punches[_comboStep]);
        }

        void UpdateSelection(Vector2 pointer)
        {
            var delta = pointer - _center;
            if (delta.magnitude < DeadZone) { _selected = -1; return; }
            var clockwise = Mathf.Repeat(90f - Mathf.Atan2(delta.y, delta.x) * Mathf.Rad2Deg, 360f);
            _selected = Mathf.FloorToInt((clockwise + 22.5f) / 45f) % 8;
        }

        void CloseWheel(bool apply)
        {
            if (apply && _selected >= 0)
            {
                var emote = Emotes[_selected];
                _player.EmoteId.Value = emote;
                // 길이는 상태가 실제로 재생되기 시작한 뒤에 읽는다 (TrackOneShotDuration).
                _oneShotStopAt = 0f;
                _awaitingDuration = IsLooping(emote) ? PlayerEmoteId.None : emote;
            }
            _wheelOpen = false;
            _selected = -1;
        }

        // 루프 이모트는 자동으로 끝나지 않는다 — 다시 선택하거나 이동하면 해제된다
        // (PlayerMovement 가 이동 시 EmoteId 를 None 으로 되돌린다).
        static bool IsLooping(PlayerEmoteId emote) =>
            emote == PlayerEmoteId.SitGround || emote == PlayerEmoteId.Drink
            || Festa.World.LiePoseTable.IsLie(emote) || Festa.World.SitPoseTable.IsSit(emote);

        // ── 원샷 이모트 종료 시점 ──────────────────────────────────
        // 클립 이름으로 길이를 찾을 수 없다. 애니메이터 상태 이름은 `Emote_{enum}` 규약이지만
        // **클립 파일 이름은 다르다**(예: 상태 `Emote_Greeting` ← 클립 `HumanF@HandWave01`).
        // 이전 구현은 클립 이름이 상태 이름과 같다고 가정해, 클립을 교체한 순간 조용히
        // 폴백 3초를 쓰게 됐다. 그래서 **실제로 재생 중인 상태의 길이**를 읽는다 —
        // 클립을 어떻게 바꿔도 따라오고, 상태 speed 까지 반영된 값이다.
        PlayerEmoteId _awaitingDuration;

        void TrackOneShotDuration()
        {
            if (_awaitingDuration == PlayerEmoteId.None) return;

            var animator = GetComponent<PlayerAvatarVisual>()?.CurrentAnimator;
            if (animator == null) { _awaitingDuration = PlayerEmoteId.None; return; }

            int wanted = Animator.StringToHash($"Emote_{_awaitingDuration}");
            var cur = animator.GetCurrentAnimatorStateInfo(0);
            if (cur.shortNameHash != wanted) return;   // 아직 크로스페이드 중

            // length 는 speed 가 반영된 재생 시간이다. 끝에서 살짝 앞서 끊어 로코모션으로 넘긴다.
            _oneShotStopAt = Time.unscaledTime + Mathf.Max(0.5f, cur.length - 0.15f);
            _awaitingDuration = PlayerEmoteId.None;
        }

        // ── 방사형 휠 렌더 (오버워치식) ─────────────────────────────
        // 이전에는 8개의 사각 버튼이 공중에 흩어져 있어 "개별 UI" 로 보였다. 방사형 휠은
        // 하나의 링을 8칸으로 나눠 보여주므로 **어디로 끌면 무엇이 나오는지** 한눈에 읽힌다.
        //
        // 링·웨지·허브는 **런타임에 한 번 절차적으로 굽는다.** 스프라이트 에셋을 만들지 않아도
        // 부드러운 곡선이 나오고, 색만 바꿔 테마를 맞출 수 있다. 매 프레임 생성하면 GC 를
        // 때리므로 `EnsureGuiAssets` 에서 한 번만 만든다.

        const int TexSize = 384;
        const float TexOuter = 188f;   // 텍스처 공간 반경 — 화면 반경(WheelRadius)으로 스케일된다
        const float TexInner = 66f;

        void OnGUI()
        {
            if (!_wheelOpen || !IsOwner) return;
            EnsureGuiAssets();

            var center = new Vector2(_center.x, Screen.height - _center.y);
            float scale = WheelRadius / TexOuter;
            float side = TexSize * scale;
            var ringRect = new Rect(center.x - side * 0.5f, center.y - side * 0.5f, side, side);

            GUI.DrawTexture(ringRect, _ringTexture);

            if (_selected >= 0)
            {
                var prev = GUI.matrix;
                GUIUtility.RotateAroundPivot(_selected * 45f, center);   // 위(0번)에서 시계 방향
                GUI.DrawTexture(ringRect, _wedgeTexture);
                GUI.matrix = prev;
            }

            float hub = TexInner * scale * 2f;
            GUI.DrawTexture(new Rect(center.x - hub * 0.5f, center.y - hub * 0.5f, hub, hub), _hubTexture);

            bool picked = _selected >= 0;
            GUI.Label(new Rect(center.x - 90f, center.y - 26f, 180f, 26f),
                      picked ? Labels[_selected] : "감정 표현", picked ? _hubPickStyle : _titleStyle);
            GUI.Label(new Rect(center.x - 90f, center.y + 2f, 180f, 22f),
                      picked ? "놓아서 실행" : "방향으로 끌기", _labelStyle);

            float labelRadius = (TexInner + TexOuter) * 0.5f * scale;
            for (var i = 0; i < 8; i++)
            {
                float radians = (90f - i * 45f) * Mathf.Deg2Rad;
                var point = center + new Vector2(Mathf.Cos(radians), -Mathf.Sin(radians)) * labelRadius;
                GUI.Label(new Rect(point.x - 52f, point.y - 12f, 104f, 24f),
                          Labels[i], i == _selected ? _selectedStyle : _labelStyle);
            }
        }

        float _builtScale = -1f;

        void EnsureGuiAssets()
        {
            // 배율이 바뀌면(창 크기·모니터 이동) 글꼴 크기를 다시 만든다 — 다른 HUD 와 같은 크기로 보이게 (QA #68)
            float s = Festa.World.InteractPromptUI.UiScale();
            if (_labelStyle != null && Mathf.Abs(s - _builtScale) < 0.05f) return;
            _builtScale = s;
            var font = Resources.Load<Font>("Fonts/Jua-Regular") ?? Resources.Load<Font>("Fonts/MalgunGothicLight");   // 2026-09-06 디스플레이 글꼴(주아)
            _labelStyle = new GUIStyle(GUI.skin.label)
            {
                alignment = TextAnchor.MiddleCenter,
                font = font,
                fontSize = Mathf.RoundToInt(15f * s),
                fontStyle = FontStyle.Bold,
                normal = { textColor = new Color(0.80f, 0.83f, 0.90f) }
            };
            _selectedStyle = new GUIStyle(_labelStyle)
            {
                fontSize = Mathf.RoundToInt(17f * s),
                normal = { textColor = new Color(1f, 0.97f, 0.88f) }
            };
            _titleStyle = new GUIStyle(_labelStyle) { fontSize = Mathf.RoundToInt(18f * s) };
            _hubPickStyle = new GUIStyle(_labelStyle)
            {
                fontSize = Mathf.RoundToInt(20f * s),
                normal = { textColor = new Color(1f, 0.78f, 0.28f) }
            };

            _ringTexture = BuildRing(new Color(0.05f, 0.07f, 0.11f, 0.80f),
                                     new Color(0.30f, 0.36f, 0.48f, 0.85f));
            _wedgeTexture = BuildWedge(new Color(0.95f, 0.62f, 0.14f, 1f));
            _hubTexture = BuildDisc(new Color(0.03f, 0.04f, 0.07f, 0.90f),
                                    new Color(0.45f, 0.52f, 0.66f, 0.9f));
        }

        /// <summary>가장자리 1.5px 를 부드럽게 — 절차적 텍스처의 계단을 없앤다.</summary>
        static float Feather(float distance) => Mathf.Clamp01(distance / 1.5f);

        /// <summary>도넛 링 + 8칸 구분선.</summary>
        static Texture2D BuildRing(Color fill, Color divider)
        {
            return Bake((dx, dy, radius, angle) =>
            {
                float band = Mathf.Min(Feather(radius - TexInner), Feather(TexOuter - radius));
                if (band <= 0f) return Color.clear;

                // 가장 가까운 칸 경계(22.5° + 45°k)까지의 호 길이 — 픽셀 단위로 재야 두께가 균일하다.
                float boundary = Mathf.Round((angle - 22.5f) / 45f) * 45f + 22.5f;
                float arc = Mathf.Abs(Mathf.DeltaAngle(angle, boundary)) * Mathf.Deg2Rad * radius;
                var color = Color.Lerp(fill, divider, Feather(1.4f - arc));
                color.a *= band;
                return color;
            });
        }

        /// <summary>위쪽 한 칸(±22.5°)만 채운 하이라이트. 선택 칸으로 회전시켜 쓴다.</summary>
        static Texture2D BuildWedge(Color accent)
        {
            return Bake((dx, dy, radius, angle) =>
            {
                float band = Mathf.Min(Feather(radius - TexInner - 1f), Feather(TexOuter - 1f - radius));
                if (band <= 0f) return Color.clear;

                float halfArc = (22.5f - Mathf.Abs(angle)) * Mathf.Deg2Rad * radius;
                float wedge = Feather(halfArc - 1f);
                if (wedge <= 0f) return Color.clear;

                // 바깥으로 갈수록 진해진다 — 선택 방향이 시선을 끌게.
                float t = Mathf.InverseLerp(TexInner, TexOuter, radius);
                var color = accent;
                color.a *= band * wedge * Mathf.Lerp(0.45f, 0.92f, t);
                return color;
            });
        }

        /// <summary>중앙 허브 — 채운 원 + 얇은 테두리.</summary>
        static Texture2D BuildDisc(Color fill, Color rim)
        {
            return Bake((dx, dy, radius, angle) =>
            {
                float inside = Feather(TexInner - radius);
                if (inside <= 0f) return Color.clear;
                var color = Color.Lerp(fill, rim, Feather(radius - (TexInner - 2.5f)));
                color.a *= inside;
                return color;
            });
        }

        /// <summary>각 픽셀의 극좌표(반경·위에서 시계 방향 각도)를 넘겨 텍스처를 굽는다.</summary>
        static Texture2D Bake(System.Func<float, float, float, float, Color> shade)
        {
            var texture = new Texture2D(TexSize, TexSize, TextureFormat.RGBA32, false);
            var pixels = new Color[TexSize * TexSize];
            float c = TexSize * 0.5f;
            for (var y = 0; y < TexSize; y++)
            {
                for (var x = 0; x < TexSize; x++)
                {
                    float dx = x - c + 0.5f;
                    float dy = y - c + 0.5f;
                    float radius = Mathf.Sqrt(dx * dx + dy * dy);
                    float angle = Mathf.Atan2(dx, dy) * Mathf.Rad2Deg;   // 0 = 위, + = 오른쪽
                    pixels[y * TexSize + x] = shade(dx, dy, radius, angle);
                }
            }
            texture.SetPixels(pixels);
            texture.Apply(false, true);
            texture.wrapMode = TextureWrapMode.Clamp;
            return texture;
        }
    }
}
