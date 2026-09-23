using System.Collections;
using System.Collections.Generic;
using Festa.Network;
using Unity.Collections;
using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 하이 스트라이커의 **판정과 전파** (S15P21A604-585). 플레이어 프리팹에 붙는다.
    ///
    /// <para>왜 플레이어에 붙는가: 씬에는 <c>NetworkObject</c> 가 하나도 없고(기계는 정적 오브젝트다),
    /// 기준선인 <see cref="Festa.Network.ConnectionManager"/> 는 동결이라 새 네트워크 프리팹을 등록하지 않는다.
    /// <see cref="PlayerAppearanceController"/> 가 외형에 쓰는 방식 그대로 — 플레이어에 붙은
    /// <see cref="NetworkBehaviour"/> 가 RPC 통로가 되고, 공유 상태는 서버 쪽 정적 표에 둔다.</para>
    ///
    /// <para>흐름: 소유자 F → <see cref="RequestSwingServerRpc"/> → <b>서버가 세기를 굴린다</b> →
    /// 전원에게 <see cref="SwingClientRpc"/>. 클라이언트는 굴리지 않는다 — 사람마다 다른 점수가 보이면 안 된다.
    /// 늦게 들어온 사람은 <see cref="RequestRecordServerRpc"/> 로 마지막 기록만 받아 점수판을 채운다.</para>
    /// </summary>
    [RequireComponent(typeof(NetworkPlayer))]
    public sealed class HighStrikerNetwork : NetworkBehaviour
    {
        const string MovementLockOwner = "HighStriker";
        /// <summary>서버 응답이 유실돼도 영구 잠금되지 않도록 두는 제한시간. 승인 전 선점 잠금도 이 길이만 쓴다.</summary>
        public const float RequestLockTimeout = 1.5f;
        /// <summary>
        /// 망치 궤적의 접촉 시점. 실제 표시에서는 AvatarStrikeProp의 접촉 프레임을 기다린다.
        /// </summary>
        public const float StrikeSpeed = 1f;
        public const float ImpactDelay = AvatarStrikeProp.ImpactTime;

        /// <summary>
        /// 한 기계가 다시 받을 때까지. <b>화면 연출이 끝나는 시점에 맞춘다</b> (S15P21A604-967).
        ///
        /// <para>예전에는 3.2초 고정이었는데 실제 연출은 임팩트 0.46 + 기계 1.64 = 2.10초에 끝났다. 그 1.1초 동안
        /// 프롬프트는 이미 돌아와 있고 F 도 받는데 서버만 조용히 거절해서, 연타하면 계속 씹히는 것으로 보였다.</para>
        ///
        /// <para>기계마다 연출 길이가 다를 수 있으므로 서버가 그 기계를 찾아 실제 값으로 잰다. 씬에서 기계를
        /// 못 찾으면 기본 연출 길이를 쓴다.</para>
        /// </summary>
        const float FallbackSequenceSeconds = 1.64f;

        /// <summary>연출이 끝난 뒤 다음 스윙까지 두는 여유. 네트워크 지연 한 틱을 흡수할 정도만 둔다.</summary>
        const float CooldownMargin = 0.1f;

        static float CooldownFor(string machineId)
        {
            var machine = HighStrikerMachine.Find(machineId);
            float sequence = machine != null ? machine.SequenceSeconds : FallbackSequenceSeconds;
            return ImpactDelay + sequence + CooldownMargin;
        }

        struct Record
        {
            public int Score;
            public string Nickname;
            public float LastSwingTime;
        }

        /// <summary>서버에만 있는 공유 상태. 기계별 마지막 기록과 쿨다운.</summary>
        static readonly Dictionary<string, Record> s_records = new();

        NetworkPlayer _player;
        Coroutine _localUnlockRoutine;

        void Awake() => _player = GetComponent<NetworkPlayer>();

        public override void OnNetworkSpawn()
        {
            // 늦게 들어와도 점수판이 비어 있지 않게 — 내 것이 스폰되면 서버에 마지막 기록을 청한다.
            if (IsOwner) RequestRecordServerRpc();
        }

        public override void OnNetworkDespawn()
        {
            ReleaseLocalInteractionLock();
            // 서버가 내려가면 기록도 사라진다(사용자 확정: 마지막 기록만, 영속 저장 없음).
            if (IsServer && NetworkManager != null && !NetworkManager.IsListening) s_records.Clear();
        }

        /// <summary>소유자가 F 를 눌렀을 때. 실패해도 조용히 넘어가지 않고 이유를 남긴다.</summary>
        public void RequestSwing(string machineId)
        {
            if (!IsOwner) return;
            RequestSwingServerRpc(new FixedString32Bytes(machineId ?? string.Empty));
        }

        /// <summary>
        /// F를 누른 즉시 소유자 이동을 멈춘다. 아직 서버 승인 전이므로 응답이 유실되거나
        /// 쿨다운으로 거절돼도 영구 잠금되지 않도록 짧은 제한시간을 건다.
        /// </summary>
        public void BeginLocalInteractionLock()
        {
            if (!IsOwner) return;
            GetComponent<PlayerMovement>()?.StopImmediatelyForInteraction();
            Festa.Integration.InputBridge.SetLocked(true, MovementLockOwner);
            RestartLocalUnlock(null, RequestLockTimeout);
        }

        [ServerRpc]
        void RequestSwingServerRpc(FixedString32Bytes machineId)
        {
            string id = machineId.ToString();
            if (string.IsNullOrEmpty(id)) return;

            Record rec;
            bool known = s_records.TryGetValue(id, out rec);
            float cooldown = CooldownFor(id);
            if (known && Time.time - rec.LastSwingTime < cooldown)
            {
                // **거절을 삼키지 않는다.** 예전에는 서버 로그만 남겨서, 누른 사람 화면은 이동이 잠긴 채
                // 아무 일도 일어나지 않았다 — 고장으로 읽힌다 (T-24 원칙, S15P21A604-967).
                float remaining = cooldown - (Time.time - rec.LastSwingTime);
                Debug.Log($"[HighStriker] 쿨다운 중 — 무시 (machineId={id}, 남은 {remaining:F1}s)");
                SwingRejectedClientRpc(machineId, remaining, RpcTargetToSender());
                return;
            }

            // 사용자 확정(2026-09-10): 점수는 **무작위**다 — 타이밍 표식 없이 F 한 번에 바로 재생된다.
            // 다만 고르게 뽑으면 만점이 8번에 한 번 나와 시시하다. 제곱으로 눌러 높은 세기를 드물게 만든다.
            float power = Mathf.Clamp(Random.value * Random.value * 0.85f + Random.value * 0.15f, 0.08f, 1f);
            int score = HighStrikerMachine.ScoreFromPower(power);
            string nickname = _player != null ? _player.Nickname.Value.ToString() : string.Empty;

            s_records[id] = new Record { Score = score, Nickname = nickname, LastSwingTime = Time.time };
            SwingClientRpc(machineId, power, score, new FixedString32Bytes(Fit(nickname)));
        }

        [ClientRpc]
        void SwingClientRpc(FixedString32Bytes machineId, float power, int score, FixedString32Bytes nickname)
        {
            var machine = HighStrikerMachine.Find(machineId.ToString()) ?? HighStrikerMachine.Any();
            if (machine == null) return;
            // 승인된 스윙만 재생한다. 같은 RPC에서 망치와 기계 타임라인을 시작한다.
            if (IsOwner) GetComponent<PlayerEmoteController>()?.PlayOneShot(PlayerEmoteId.Strike);
            var prop = GetComponent<AvatarStrikeProp>();
            bool hasVisual = prop != null && prop.BeginSwing(machine);
            if (IsOwner)
            {
                // 승인된 Strike가 끝나는 실제 시점까지 연장한다. 시간 상수만 기다리지 않아
                // 아바타 재조립 등으로 애니메이션이 일찍 끝난 경우에도 즉시 이동을 돌려준다.
                GetComponent<PlayerMovement>()?.StopImmediatelyForInteraction();
                Festa.Integration.InputBridge.SetLocked(true, MovementLockOwner);
                RestartLocalUnlock(hasVisual ? prop : null, AvatarStrikeProp.SwingDuration + 0.5f);
            }
            // 받는 즉시 잠근다 — 임팩트를 기다리는 사이에 옆 사람이 F 를 누르면 스윙이 겹친다.
            machine.BeginBusy(ImpactDelay + machine.SequenceSeconds);
            // 서버가 승인한 판만 미션에 남긴다 — 여기까지 왔다는 것은 쿨다운을 통과했다는 뜻이다.
            // 내 판만 보낸다(남의 스윙도 이 RPC 로 오고, 액세스 토큰은 소유자 클라이언트에만 있다).
            if (IsOwner) ReportPlayForMissions(machineId.ToString(), score);
            // 스윙 애니메이션이 내려찍는 순간에 퍽이 튀어 오르게 — 받은 시점부터 임팩트까지 기다린다.
            StartCoroutine(PlayAtImpact(machine, hasVisual ? prop : null, power, score, nickname.ToString()));
        }

        /// <summary>요청한 클라이언트에게만 보낸다. 남들은 거절을 알 필요가 없다.</summary>
        ClientRpcParams RpcTargetToSender() => new ClientRpcParams
        {
            Send = new ClientRpcSendParams { TargetClientIds = new[] { OwnerClientId } },
        };

        /// <summary>
        /// 쿨다운으로 거절됐다 — 누른 사람 화면만 원래대로 되돌린다 (S15P21A604-967).
        ///
        /// <para>이동 잠금을 제한시간까지 기다리지 않고 즉시 풀고, 승인 전에 걸어 둔 기계 잠금도 푼다.
        /// 그래야 프롬프트가 바로 돌아오고 "눌렀는데 아무 일도 없다" 가 사라진다.</para>
        /// </summary>
        [ClientRpc]
        void SwingRejectedClientRpc(FixedString32Bytes machineId, float remainingSeconds, ClientRpcParams rpcParams = default)
        {
            if (!IsOwner) return;
            ReleaseLocalInteractionLock();
            var machine = HighStrikerMachine.Find(machineId.ToString()) ?? HighStrikerMachine.Any();
            if (machine != null) machine.ClearBusy();
            Festa.Content.BoothInteractionInput.Toast($"{Mathf.Max(0.1f, remainingSeconds):F1}초 뒤에 다시 칠 수 있어요", 1.2f);
        }

        /// <summary>
        /// 일일 미션 <c>STRIKER_PLAY_3</c>·<c>STRIKER_SCORE</c> 의 근거를 Spring 에 남긴다 (GitLab #233).
        ///
        /// <para>이 게임은 점수를 서버 정적 표에서 굴려 RPC 로 뿌린 뒤 버린다 — 표시에는 그것으로 충분했지만
        /// 미션은 오늘 기록을 세어 판정하므로 Spring 에 남는 것이 하나도 없으면 진행도가 영원히 0 이다.</para>
        ///
        /// <para><b>결과를 기다리지 않는다.</b> 기록용 왕복이 스윙 연출이나 이동 잠금 해제를 늦추면 안 된다.
        /// 실패는 클라이언트가 로그로 남기고, 사용자에게는 아무것도 띄우지 않는다 — 이번 판이 미션에 안 세어질 뿐
        /// 게임은 정상이다.</para>
        /// </summary>
        async void ReportPlayForMissions(string machineId, int score)
        {
            var client = Festa.Integration.ApiServices.HighStriker;
            if (client == null) return;
            try { await client.ReportPlayAsync(machineId, score); }
            catch (System.Exception ex)
            {
                Debug.LogWarning($"[HighStriker] 미션 기록 보고가 예외로 끝났다 — {ex.Message}. 게임 진행에는 영향이 없다.");
            }
        }

        void RestartLocalUnlock(AvatarStrikeProp prop, float timeout)
        {
            if (_localUnlockRoutine != null) StopCoroutine(_localUnlockRoutine);
            _localUnlockRoutine = StartCoroutine(UnlockLocalMovement(prop, timeout));
        }

        IEnumerator UnlockLocalMovement(AvatarStrikeProp prop, float timeout)
        {
            float deadline = Time.time + timeout;
            if (prop == null)
            {
                while (Time.time < deadline) yield return null;
            }
            else
            {
                while (prop != null && prop.IsSwinging && Time.time < deadline) yield return null;
            }
            _localUnlockRoutine = null;
            Festa.Integration.InputBridge.SetLocked(false, MovementLockOwner);
        }

        void ReleaseLocalInteractionLock()
        {
            if (!IsOwner) return;
            if (_localUnlockRoutine != null) StopCoroutine(_localUnlockRoutine);
            _localUnlockRoutine = null;
            Festa.Integration.InputBridge.SetLocked(false, MovementLockOwner);
        }

        static IEnumerator PlayAtImpact(HighStrikerMachine machine, AvatarStrikeProp prop, float power, int score, string nickname)
        {
            if (prop != null)
            {
                // LateUpdate가 망치를 판에 붙인 다음 프레임에만 표시를 올린다.
                while (prop != null && prop.IsSwinging && !prop.HasImpacted) yield return null;
                if (prop == null || !prop.HasImpacted) yield break;
            }
            else yield return new WaitForSeconds(ImpactDelay);
            if (machine != null) machine.PlaySwing(power, score, nickname);
        }

        [ServerRpc]
        void RequestRecordServerRpc(ServerRpcParams p = default)
        {
            var only = new ClientRpcParams
            {
                Send = new ClientRpcSendParams { TargetClientIds = new[] { p.Receive.SenderClientId } }
            };
            foreach (var kv in s_records)
                RecordClientRpc(new FixedString32Bytes(kv.Key), kv.Value.Score, new FixedString32Bytes(Fit(kv.Value.Nickname)), only);
        }

        [ClientRpc]
        void RecordClientRpc(FixedString32Bytes machineId, int score, FixedString32Bytes nickname, ClientRpcParams p = default)
        {
            var machine = HighStrikerMachine.Find(machineId.ToString());
            if (machine != null) machine.ShowRecord(score, nickname.ToString());
        }

        /// <summary>
        /// FixedString32Bytes 는 UTF-8 29바이트다. 한글은 한 자 3바이트라 10자면 넘치고,
        /// 넘기면 대입에서 예외가 나 RPC 가 통째로 죽는다 (NetworkPlayer 의 같은 함정, 2026-09-08).
        /// </summary>
        static string Fit(string value)
        {
            if (string.IsNullOrEmpty(value)) return string.Empty;
            while (System.Text.Encoding.UTF8.GetByteCount(value) > 29 && value.Length > 0)
                value = value.Substring(0, value.Length - 1);
            return value;
        }
    }
}
