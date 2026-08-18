using Unity.Collections;
using Unity.Netcode;
using UnityEngine;

namespace Festa.Network
{
    public enum PlayerAnimState : byte
    {
        Idle = 0,
        Walk = 1,
        Run = 2,
    }

    /// <summary>
    /// 네트워크로 동기화되는 Player 상태 (doc 16 §4 NetworkVariables 후보 기준).
    /// nickname/avatarCode는 서버가 승인된 세션 데이터로만 기록한다 —
    /// 클라이언트가 임의로 다른 userId/닉네임을 주장할 수 없다 (doc 12 §12).
    /// </summary>
    public class NetworkPlayer : NetworkBehaviour
    {
        // NGO 제약: NetworkVariable은 필드로 선언 (프로퍼티는 ILPP 인식 불가)
        public readonly NetworkVariable<long> UserId =
            new(0, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Server);

        public readonly NetworkVariable<FixedString32Bytes> Nickname =
            new(default, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Server);

        public readonly NetworkVariable<FixedString32Bytes> AvatarCode =
            new(default, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Server);

        public readonly NetworkVariable<int> CurrentBoothId =
            new(-1, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Server);

        public readonly NetworkVariable<PlayerAnimState> AnimState =
            new(PlayerAnimState.Idle, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Owner);

        public readonly NetworkVariable<byte> EmoteId =
            new(0, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Owner);

        TextMesh _nameLabel;

        public override void OnNetworkSpawn()
        {
            if (IsServer)
            {
                var session = SessionDataStore.Get(OwnerClientId);
                if (session != null)
                {
                    UserId.Value = session.userId;
                    Nickname.Value = session.nickname ?? "Unknown";
                    AvatarCode.Value = session.avatarCode ?? "default";
                }
            }

            CreateNameLabel();
            TintBody();
            Nickname.OnValueChanged += OnNicknameChanged;
            RefreshNameLabel();

            Debug.Log($"[NetworkPlayer] Spawned owner={OwnerClientId} isOwner={IsOwner}");
        }

        public override void OnNetworkDespawn()
        {
            Nickname.OnValueChanged -= OnNicknameChanged;
            Debug.Log($"[NetworkPlayer] Despawned owner={OwnerClientId}");
        }

        // ---------- Booth Entry (doc 16 §4 Server RPC 후보) ----------

        [Rpc(SendTo.Server)]
        public void EnterBoothServerRpc(int boothId)
        {
            // TODO(P1): booth 존재/거리 검증
            CurrentBoothId.Value = boothId;
        }

        [Rpc(SendTo.Server)]
        public void ExitBoothServerRpc()
        {
            CurrentBoothId.Value = -1;
        }

        // ---------- 이름표 (POC용 — 정식 UI 확정 시 교체) ----------

        void CreateNameLabel()
        {
            var go = new GameObject("NameLabel");
            go.transform.SetParent(transform, false);
            go.transform.localPosition = new Vector3(0f, 1.6f, 0f);
            _nameLabel = go.AddComponent<TextMesh>();

            // Unity 6: 런타임 생성 TextMesh는 폰트를 직접 지정해야 한다 (미지정 시 글리치 렌더링)
            var font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
            _nameLabel.font = font;
            go.GetComponent<MeshRenderer>().material = font.material;

            _nameLabel.characterSize = 0.18f;
            _nameLabel.fontSize = 48;
            _nameLabel.anchor = TextAnchor.MiddleCenter;
            _nameLabel.color = IsOwner ? Color.yellow : Color.white;
        }

        /// <summary>POC용 몸통 색 구분: 자신=노랑, 원격=흰색. 아바타 시스템 도입 시 제거.</summary>
        void TintBody()
        {
            var renderer = GetComponentInChildren<MeshRenderer>();
            if (renderer != null)
                renderer.material.color = IsOwner
                    ? new Color(1f, 0.85f, 0.2f)   // 자신: 노랑
                    : new Color(0.9f, 0.9f, 0.9f); // 원격: 흰색
        }

        void OnNicknameChanged(FixedString32Bytes _, FixedString32Bytes __) => RefreshNameLabel();

        void RefreshNameLabel()
        {
            if (_nameLabel != null) _nameLabel.text = Nickname.Value.ToString();
        }

        void LateUpdate()
        {
            // 이름표 빌보드
            if (_nameLabel != null && Camera.main != null)
                _nameLabel.transform.rotation = Camera.main.transform.rotation;
        }
    }
}
