using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 다른 사용자의 머리 위에 닉네임을 띄운다.
    ///
    /// <para><b>본인 것은 띄우지 않는다.</b> 3인칭이라 본인 아바타가 화면 한가운데 있어,
    /// 자기 이름표가 시야를 계속 가린다. 남이 누구인지가 필요한 정보다.</para>
    ///
    /// <para>닉네임은 <see cref="Festa.Network.NetworkPlayer.Nickname"/> 이 정본이며 서버가
    /// 승인된 세션에서만 기록한다 — 클라이언트가 보낸 값을 쓰지 않는다(헌법 16조).
    /// 값이 늦게 도착할 수 있으므로 변경 이벤트를 구독해 그때 갱신한다.</para>
    /// </summary>
    [RequireComponent(typeof(Festa.Network.NetworkPlayer))]
    [DisallowMultipleComponent]
    public sealed class PlayerNameplate : NetworkBehaviour
    {
        [Tooltip("이 거리(월드 유닛) 안에서만 보인다. 부스 이름표보다 짧게 둔다 — 사람은 많고 겹치기 쉽다.")]
        [SerializeField] float _visibleDistance = 190f;

        Festa.Network.NetworkPlayer _player;
        WorldNameplate _plate;

        public override void OnNetworkSpawn()
        {
            _player = GetComponent<Festa.Network.NetworkPlayer>();

            // 본인 아바타에는 붙이지 않는다 (위 주석 참조).
            if (IsOwner) { enabled = false; return; }

            _plate = gameObject.AddComponent<WorldNameplate>();
            _plate.SetVisibleDistance(_visibleDistance);
            Apply(_player.Nickname.Value);
            _player.Nickname.OnValueChanged += OnNicknameChanged;
        }

        public override void OnNetworkDespawn()
        {
            if (_player != null) _player.Nickname.OnValueChanged -= OnNicknameChanged;
        }

        void OnNicknameChanged(Unity.Collections.FixedString32Bytes _, Unity.Collections.FixedString32Bytes now)
            => Apply(now);

        void Apply(Unity.Collections.FixedString32Bytes value)
        {
            if (_plate == null) return;
            var text = value.ToString();
            _plate.Label = string.IsNullOrWhiteSpace(text) ? "" : text;
        }
    }
}
