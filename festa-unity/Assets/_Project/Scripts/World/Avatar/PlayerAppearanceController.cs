using System.Collections.Generic;
using System.Text;
using Festa.Integration;
using Festa.Network;
using Unity.Collections;
using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 외형 상태의 소유자이자 변경 경로.
    ///
    /// NetworkPlayer.AvatarCode(FixedString32)는 런타임 조립 문자열을 담기엔 작으므로,
    /// 여기에 FixedString512 필드를 따로 둔다. NetworkPlayer는 수정하지 않는다(기준선 보존).
    /// 최초값은 접속 페이로드의 avatarCode에서 가져온다.
    ///
    /// 흐름: Owner 요청 → ServerRpc → 서버가 NetworkVariable 기록 → 전원 전파 → 각자 로컬 생성
    /// </summary>
    [RequireComponent(typeof(NetworkPlayer))]
    public class PlayerAppearanceController : NetworkBehaviour
    {
        /// <summary>인코딩된 AvatarAppearance. 서버만 쓰고 모두가 읽는다.
        /// Sidekick 파츠 이름이 길어 512바이트로는 부족할 수 있어 4096으로 잡는다 (T-24).
        /// 변경 시에만 전송되므로 대역폭 부담은 미미하다.</summary>
        public readonly NetworkVariable<FixedString4096Bytes> Encoded =
            new(default, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Server);

        /// <summary>
        /// 월드에서 보이는 전체 닉네임. 동결된 NetworkPlayer.Nickname은 FixedString32라 30자 한글을
        /// 담지 못하므로, 이미 프리팹에 붙은 이 확장 컨트롤러에서 서버 권위 값으로 별도 동기화한다.
        /// </summary>
        public readonly NetworkVariable<FixedString128Bytes> DisplayNickname =
            new(default, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Server);

        /// <summary>마지막 변경 요청이 거부된 이유. HUD가 표시한다. 성공 시 null.</summary>
        public string LastRequestError { get; private set; }

        const float ServerApplyTimeout = 2f;
        const float SceneHandoffRetryInterval = .35f;
        const float SceneHandoffRetryDeadline = 8f;
        const int MaxNicknameUtf8Bytes = 120;

        NetworkPlayer _player;
        string _pendingEncoded;
        float _pendingSince;
        string _sceneHandoffEncoded;
        float _nextSceneHandoffAttempt;
        float _sceneHandoffDeadline;

        void Awake() => _player = GetComponent<NetworkPlayer>();

        public override void OnNetworkSpawn()
        {
            if (IsServer && Encoded.Value.Length == 0)
            {
                // 접속 시 전달된 avatarCode(프리셋)를 초기값으로
                // NetworkPlayer.AvatarCode is a legacy short field. Use the
                // approved payload so the complete modular appearance reaches
                // the first world spawn too.
                var initial = SessionDataStore.Get(OwnerClientId)?.avatarCode;
                if (string.IsNullOrEmpty(initial)) initial = _player.AvatarCode.Value.ToString();
                if (!string.IsNullOrEmpty(initial) && initial.Length > AvatarAppearance.MaxEncodedLength)
                    initial = AvatarAppearance.DefaultPreset;
                Encoded.Value = string.IsNullOrEmpty(initial)
                    ? AvatarAppearance.DefaultPreset
                    : initial;
            }

            if (IsServer && DisplayNickname.Value.Length == 0)
            {
                var initialNickname = SessionDataStore.Get(OwnerClientId)?.nickname;
                if (string.IsNullOrWhiteSpace(initialNickname)) initialNickname = _player.Nickname.Value.ToString();
                if (IsValidNickname(initialNickname)) DisplayNickname.Value = initialNickname.Trim();
                else Debug.LogError("[Appearance] 접속 페이로드의 닉네임이 유효하지 않아 이름표를 비워 둔다.");
            }

            // Connection approval is intentionally kept small and carries only a
            // legacy preset.  The local lobby's full modular payload is applied
            // after ownership is established, through the 4096-byte appearance
            // NetworkVariable.  This avoids the transport/legacy 32-byte limits
            // dropping clothing and hair item ids during world entry.
            if (IsOwner)
            {
                BeginSceneHandoff();
                ProfileNicknameBridge.TryApplyPending(this);
            }
        }

        void BeginSceneHandoff()
        {
            var encoded = AvatarSceneHandoff.GetEncodedOrFallback(string.Empty);
            if (string.IsNullOrEmpty(encoded) || encoded.Length > AvatarAppearance.MaxEncodedLength) return;

            _sceneHandoffEncoded = encoded;
            _nextSceneHandoffAttempt = Time.unscaledTime;
            _sceneHandoffDeadline = Time.unscaledTime + SceneHandoffRetryDeadline;
            TryApplySceneHandoff();
        }

        bool _handoffSaved;

        void TryApplySceneHandoff()
        {
            if (string.IsNullOrEmpty(_sceneHandoffEncoded)) return;
            if (Encoded.Value.ToString() == _sceneHandoffEncoded)
            {
                _sceneHandoffEncoded = null;
                return;
            }

            // **로비에서 고른 외형을 프로필에도 저장한다** (S15P21A604-168, T-124).
            // 전에는 월드 진입 시 서버 RPC 로 동기화만 하고 PUT /users/me/avatar 는 월드 안 "적용" 버튼 경로에서만 불렀다 —
            // 정상 흐름(로비 커스터마이징 → 월드 입장)으로는 외형이 영영 저장되지 않아 다음 로그인에 기본 외형이 떴다.
            // 회원 토큰이 있을 때만(게스트 403·미주입 401 은 뻔한 실패라 호출하지 않는다), 진입당 한 번.
            if (!_handoffSaved && Festa.Integration.AuthBridge.HasToken && !Festa.Integration.AuthBridge.IsGuest)
            {
                _handoffSaved = true;
                SaveToProfileAsync(_sceneHandoffEncoded);
            }

            // A host owns both sides of the connection.  Writing directly here
            // avoids waiting for an owner-to-server RPC that is unnecessary in
            // that configuration, while remote WebGL clients still use the RPC.
            if (IsServer)
            {
                Encoded.Value = _sceneHandoffEncoded;
                _sceneHandoffEncoded = null;
                return;
            }

            _pendingEncoded = _sceneHandoffEncoded;
            _pendingSince = Time.time;
            RequestChangeServerRpc(_sceneHandoffEncoded);
        }

        public AvatarAppearance Current => AvatarAppearance.Decode(Encoded.Value.ToString());

        /// <summary>Owner가 호출. 서버 반영 + 프로필 저장 시도.</summary>
        public void RequestChange(AvatarAppearance appearance)
        {
            if (!IsOwner)
            {
                LastRequestError = "Owner만 자신의 외형을 변경할 수 있습니다";
                Debug.LogWarning($"[Appearance] {LastRequestError}");
                return;
            }

            var encoded = appearance.Encode();
            if (encoded.Length > AvatarAppearance.MaxEncodedLength)
            {
                LastRequestError = $"encoded too long ({encoded.Length}/{AvatarAppearance.MaxEncodedLength})";
                Debug.LogError($"[Appearance] {LastRequestError} — 요청 무시");
                return;
            }

            LastRequestError = null;
            _pendingEncoded = encoded;
            _pendingSince = Time.time;

            RequestChangeServerRpc(encoded);
            SaveToProfileAsync(encoded);
        }

        /// <summary>
        /// 프로필 저장 성공 뒤에 호출된다. React가 이름 문자열을 서버 RPC로 넘기지 않고, 이 클라이언트가
        /// Spring에서 새로 발급받은 서명된 world-session의 nickname 클레임만 게임 서버가 받아 쓴다.
        /// </summary>
        public void RequestNicknameChange()
        {
            if (!IsOwner)
            {
                Debug.LogWarning("[Appearance] Owner가 아닌 닉네임 변경 요청을 무시한다.");
                return;
            }
            RequestNicknameGrantAsync();
        }

        async void RequestNicknameGrantAsync()
        {
            ApiServices.EnsureInitialized();
            var session = await ApiServices.User.CreateWorldSessionAsync();
            if (session == null || string.IsNullOrEmpty(session.connectionToken))
            {
                Debug.LogError("[Appearance] 닉네임 동기화용 world-session 발급 실패 — 이름표는 이전 값을 유지한다.");
                return;
            }
            RequestNicknameChangeServerRpc(session.connectionToken);
        }

        /// <summary>
        /// 서버가 요청을 반영했는지 감시한다.
        /// 서버 빌드가 낡아 요청을 조용히 버리는 상황(T-24)을 사용자에게 드러내기 위함.
        /// </summary>
        void Update()
        {
            // WebGL/WebSocket clients can receive their player spawn before the
            // server accepts an RPC for that player object. Retry the local lobby
            // handoff until the replicated value acknowledges it, so the initial
            // fallback body cannot remain bald and unclothed.
            if (!string.IsNullOrEmpty(_sceneHandoffEncoded))
            {
                if (Encoded.Value.ToString() == _sceneHandoffEncoded)
                {
                    _sceneHandoffEncoded = null;
                }
                else if (Time.unscaledTime <= _sceneHandoffDeadline && Time.unscaledTime >= _nextSceneHandoffAttempt)
                {
                    _nextSceneHandoffAttempt = Time.unscaledTime + SceneHandoffRetryInterval;
                    TryApplySceneHandoff();
                }
                else if (Time.unscaledTime > _sceneHandoffDeadline)
                {
                    Debug.LogError("[Appearance] World-entry appearance synchronization timed out.");
                    _sceneHandoffEncoded = null;
                }
            }

            if (_pendingEncoded == null) return;

            if (Encoded.Value.ToString() == _pendingEncoded)
            {
                _pendingEncoded = null;
                return;
            }

            if (Time.time - _pendingSince < ServerApplyTimeout) return;

            _pendingEncoded = null;
            LastRequestError = "server did not apply the change — 서버(Docker) 빌드가 최신인지 확인";
            Debug.LogError($"[Appearance] {LastRequestError}");
        }

        [Rpc(SendTo.Server)]
        void RequestChangeServerRpc(string encoded)
        {
            // TODO(P1): 보유하지 않은 파츠 차단 등 소유권 검증
            //
            // 받은 문자열을 그대로 기록한다. 예전에는 Decode→Encode 왕복으로 "정규화"했는데,
            // 그 과정에서 길이 초과 시 조용히 sk_01로 바뀌어 값이 안 변하는 무반응 버그가 났다 (T-24).
            if (string.IsNullOrEmpty(encoded) || encoded.Length > AvatarAppearance.MaxEncodedLength)
            {
                Debug.LogWarning($"[Appearance] 거부: client={OwnerClientId} len={encoded?.Length ?? 0}");
                return;
            }

            Encoded.Value = encoded;
            Debug.Log($"[Appearance] 서버 반영: client={OwnerClientId} len={encoded.Length}");
        }

        [Rpc(SendTo.Server)]
        void RequestNicknameChangeServerRpc(string connectionToken)
        {
            // 클라이언트가 보낸 nickname을 믿지 않는다. Spring이 현재 프로필에서 발급한 서명 grant만
            // 검증하고, 그 grant의 playerId가 이 NetworkObject 소유자와 같을 때만 쓴다(헌법 16조).
            if (!WorldEntryTokenVerifier.Verify(connectionToken, out var grant, out var reason)
                || !long.TryParse(grant.PlayerId, out var playerId)
                || playerId != _player.UserId.Value
                || !IsValidNickname(grant.Nickname))
            {
                Debug.LogWarning($"[Appearance] 서명된 닉네임 변경 grant 거부: client={OwnerClientId} reason={reason}");
                return;
            }

            DisplayNickname.Value = grant.Nickname.Trim();
            Debug.Log($"[Appearance] 이름표 닉네임 서버 반영: client={OwnerClientId}");
        }

        static bool IsValidNickname(string nickname) =>
            !string.IsNullOrWhiteSpace(nickname) && Encoding.UTF8.GetByteCount(nickname.Trim()) <= MaxNicknameUtf8Bytes;

        /// <summary>
        /// 영구 저장. 실패해도 월드 내 외형은 유지되지만, <b>그 사실을 사용자에게 알린다.</b>
        ///
        /// <para>예전에는 <c>LogWarning</c> 한 줄이 전부였다. 사용자는 저장된 줄 알고 나가고,
        /// 다음 로그인에 외형이 사라진 것을 보고서야 안다 — 그때는 무엇이 잘못됐는지 알 수 없다.
        /// 조용한 실패를 만들지 않는다는 것이 이 프로젝트의 규칙이다 (헌법, T-24). 게스트는
        /// 애초에 프로필이 없으므로 알릴 것도 없다.</para>
        /// </summary>
        async void SaveToProfileAsync(string encoded)
        {
            ApiServices.EnsureInitialized();
            bool ok = await ApiServices.User.UpdateMyAvatarAsync(encoded);
            if (ok) return;

            if (AuthBridge.IsGuest)
            {
                Debug.Log("[Appearance] 게스트라 프로필 저장을 건너뛴다 — 이번 세션에만 적용");
                return;
            }

            Debug.LogError("[Appearance] 프로필 저장 실패 — 이번 세션에만 적용된다. " +
                           "다음 접속에는 이전 외형으로 돌아간다.");
            Festa.Content.BoothInteractionInput.Toast(
                "아바타를 저장하지 못했어요. 이번 접속에만 적용됩니다.", 4f);
        }
    }
}
