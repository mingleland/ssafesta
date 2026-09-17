using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 플레이어 머리 위에 닉네임을 띄운다 — 본인 것도 포함한다.
    ///
    /// <para><b>본인 것도 띄우는 이유.</b> 처음에는 시야를 가린다고 보고 본인은 제외했는데,
    /// 3인칭 게임에서 자기 이름표는 "지금 조종하는 게 이 캐릭터" 를 알려주는 기본 표시다 —
    /// 여러 아바타가 같은 복장으로 서 있을 때 내 캐릭터를 잃지 않게 해준다. 이름표가 머리 위
    /// 작은 외곽선 글자라 실제로 가리는 면적도 없다 (S15P21A604-355).</para>
    ///
    /// <para>최초값은 <see cref="Festa.Network.NetworkPlayer.Nickname"/>이고, 이후 전체 길이 이름은
    /// <see cref="PlayerAppearanceController.DisplayNickname"/>에서 받는다. 둘 다 서버가 서명된
    /// world-session으로 기록한다 — 클라이언트가 보낸 문자열을 쓰지 않는다(헌법 16조).</para>
    /// </summary>
    [RequireComponent(typeof(Festa.Network.NetworkPlayer))]
    [DisallowMultipleComponent]
    public sealed class PlayerNameplate : NetworkBehaviour
    {
        [Tooltip("이 거리(월드 유닛) 안에서만 보인다. 0 = 제한 없음. "
               + "190 은 복도 건너편 이름이 안 읽혔고, 2026-09-16 사용자 결정으로 어디서든 보이게 0.")]
        [SerializeField] float _visibleDistance = 0f;

        [Tooltip("본인 이름표 색. 남들과 달라야 이름을 읽지 않고도 내 캐릭터를 찾는다.")]
        [SerializeField] Color _ownColor = new(0.66f, 0.82f, 0.28f, 1f);      // 연둣빛

        [Tooltip("다른 사용자 이름표 색.")]
        [SerializeField] Color _otherColor = new(1f, 0.99f, 0.95f, 1f);       // 흰색

        Festa.Network.NetworkPlayer _player;
        PlayerAppearanceController _appearance;
        WorldNameplate _plate;

        public override void OnNetworkSpawn()
        {
            _player = GetComponent<Festa.Network.NetworkPlayer>();
            _appearance = GetComponent<PlayerAppearanceController>();

            _plate = gameObject.GetComponent<WorldNameplate>();
            if (_plate == null) _plate = gameObject.AddComponent<WorldNameplate>();
            _plate.SetVisibleDistance(_visibleDistance);
            // 본인은 연두, 남은 흰색 — 이름을 읽기 전에 색으로 먼저 구분된다.
            _plate.SetColor(IsOwner ? _ownColor : _otherColor);

            Apply(_appearance != null && _appearance.DisplayNickname.Value.Length > 0
                ? _appearance.DisplayNickname.Value.ToString()
                : _player.Nickname.Value.ToString());
            _player.Nickname.OnValueChanged += OnNicknameChanged;
            if (_appearance != null) _appearance.DisplayNickname.OnValueChanged += OnDisplayNicknameChanged;
        }

        public override void OnNetworkDespawn()
        {
            if (_player != null) _player.Nickname.OnValueChanged -= OnNicknameChanged;
            if (_appearance != null) _appearance.DisplayNickname.OnValueChanged -= OnDisplayNicknameChanged;
        }

        void OnNicknameChanged(Unity.Collections.FixedString32Bytes _, Unity.Collections.FixedString32Bytes now)
        {
            // 전체 이름표 값이 있으면 동결된 legacy 필드의 늦은 변경으로 되돌리지 않는다.
            if (_appearance == null || _appearance.DisplayNickname.Value.Length == 0) Apply(now.ToString());
        }

        void OnDisplayNicknameChanged(Unity.Collections.FixedString128Bytes _, Unity.Collections.FixedString128Bytes now)
            => Apply(now.ToString());

        void Apply(string value)
        {
            if (_plate == null) return;
            var text = value.ToString();
            // 값이 아직 안 왔으면 비워 둔다 — 자리표시 문구를 띄우면 그게 이름인 줄 안다.
            _plate.Label = string.IsNullOrWhiteSpace(text) ? "" : text.Trim();
        }
    }
}
