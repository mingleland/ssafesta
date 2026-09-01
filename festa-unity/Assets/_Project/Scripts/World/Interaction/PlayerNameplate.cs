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
    /// <para>닉네임은 <see cref="Festa.Network.NetworkPlayer.Nickname"/> 이 정본이며 서버가
    /// 승인된 세션에서만 기록한다 — 클라이언트가 보낸 값을 쓰지 않는다(헌법 16조).
    /// 값이 늦게 도착할 수 있으므로 변경 이벤트를 구독해 그때 갱신한다.</para>
    /// </summary>
    [RequireComponent(typeof(Festa.Network.NetworkPlayer))]
    [DisallowMultipleComponent]
    public sealed class PlayerNameplate : NetworkBehaviour
    {
        [Tooltip("이 거리(월드 유닛) 안에서만 보인다. 사람은 많고 겹치기 쉬워 짧게 둔다.")]
        [SerializeField] float _visibleDistance = 190f;

        Festa.Network.NetworkPlayer _player;
        WorldNameplate _plate;

        public override void OnNetworkSpawn()
        {
            _player = GetComponent<Festa.Network.NetworkPlayer>();

            _plate = gameObject.GetComponent<WorldNameplate>();
            if (_plate == null) _plate = gameObject.AddComponent<WorldNameplate>();
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
            // 값이 아직 안 왔으면 비워 둔다 — 자리표시 문구를 띄우면 그게 이름인 줄 안다.
            _plate.Label = string.IsNullOrWhiteSpace(text) ? "" : text.Trim();
        }
    }
}
