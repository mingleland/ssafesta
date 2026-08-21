using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Synty.SidekickCharacters.API;
using Synty.SidekickCharacters.Database;
using Synty.SidekickCharacters.Database.DTO;
using Synty.SidekickCharacters.Enums;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// Sidekick Runtime API 래퍼 (싱글턴).
    /// DB 로드와 파츠 라이브러리 준비를 한 번만 수행하고, 이후 모든 아바타 생성이 공유한다.
    /// 초기화는 무겁고(수백 ms~) 비동기이므로 월드 진입 시 미리 시작한다.
    ///
    /// Sidekick 의존은 이 클래스 안에만 둔다 — 다른 코드는 IAvatarVisualProvider만 안다.
    /// </summary>
    public class SidekickRuntimeService
    {
        static SidekickRuntimeService s_instance;
        public static SidekickRuntimeService Instance => s_instance ??= new SidekickRuntimeService();

        /// <summary>커스터마이징 UI에 노출할 파츠 종류 (전신 38종은 과하므로 핵심만).</summary>
        public static readonly CharacterPartType[] EditableParts =
        {
            CharacterPartType.Head,
            CharacterPartType.Hair,
            CharacterPartType.FacialHair,
            CharacterPartType.Torso,
            CharacterPartType.ArmUpperLeft,
            CharacterPartType.ArmUpperRight,
            CharacterPartType.ArmLowerLeft,
            CharacterPartType.ArmLowerRight,
            CharacterPartType.HandLeft,
            CharacterPartType.HandRight,
            CharacterPartType.Hips,
            CharacterPartType.LegLeft,
            CharacterPartType.LegRight,
            CharacterPartType.FootLeft,
            CharacterPartType.FootRight,
            CharacterPartType.AttachmentHead,
        };

        /// <summary>좌우가 짝지어 움직여야 자연스러운 파츠 (UI에서 한 번에 바꿈).</summary>
        public static readonly Dictionary<CharacterPartType, CharacterPartType> MirrorPairs = new()
        {
            { CharacterPartType.ArmUpperLeft, CharacterPartType.ArmUpperRight },
            { CharacterPartType.ArmLowerLeft, CharacterPartType.ArmLowerRight },
            { CharacterPartType.HandLeft, CharacterPartType.HandRight },
            { CharacterPartType.LegLeft, CharacterPartType.LegRight },
            { CharacterPartType.FootLeft, CharacterPartType.FootRight },
        };

        DatabaseManager _db;
        SidekickRuntime _runtime;
        Task _initTask;

        public bool IsReady { get; private set; }
        public string LastError { get; private set; }

        /// <summary>CharacterPartType → (파츠 이름 목록). UI가 선택지를 그릴 때 사용.</summary>
        public Dictionary<CharacterPartType, List<string>> PartNames { get; private set; } = new();

        Dictionary<CharacterPartType, Dictionary<string, SidekickPart>> _partLibrary;

        /// <summary>여러 번 호출해도 초기화는 한 번만 수행된다.</summary>
        public Task EnsureInitializedAsync() => _initTask ??= InitializeAsync();

        async Task InitializeAsync()
        {
            try
            {
                _db = new DatabaseManager();

                var baseModel = Resources.Load<GameObject>("Meshes/SK_BaseModel");
                var material = Resources.Load<Material>("Materials/M_BaseMaterial");

                if (baseModel == null || material == null)
                {
                    LastError = "Sidekick 기본 모델/머티리얼을 Resources에서 찾지 못했습니다.";
                    Debug.LogError($"[SidekickRuntime] {LastError}");
                    return;
                }

                _runtime = new SidekickRuntime(baseModel, material, null, _db);
                await SidekickRuntime.PopulateToolData(_runtime);

                _partLibrary = _runtime.MappedPartDictionary;

                PartNames = new Dictionary<CharacterPartType, List<string>>();
                foreach (var type in EditableParts)
                {
                    if (_partLibrary.TryGetValue(type, out var parts) && parts.Count > 0)
                        PartNames[type] = parts.Keys.ToList();
                }

                IsReady = true;
                Debug.Log($"[SidekickRuntime] 준비 완료 — 편집 가능 파츠 {PartNames.Count}종");
            }
            catch (Exception e)
            {
                LastError = e.Message;
                Debug.LogError($"[SidekickRuntime] 초기화 실패: {e}");
            }
        }

        /// <summary>파츠 맵으로 캐릭터 GameObject를 생성한다. 실패 시 null.</summary>
        public GameObject BuildCharacter(Dictionary<int, string> parts, string modelName)
        {
            if (!IsReady || _partLibrary == null)
            {
                Debug.LogWarning("[SidekickRuntime] 아직 준비되지 않음");
                return null;
            }

            var renderers = new List<SkinnedMeshRenderer>();

            foreach (var kv in parts)
            {
                var type = (CharacterPartType)kv.Key;
                if (!_partLibrary.TryGetValue(type, out var byName)) continue;
                if (!byName.TryGetValue(kv.Value, out var part)) continue;

                var container = part.GetPartModel();
                if (container == null) continue;

                var smr = container.GetComponentInChildren<SkinnedMeshRenderer>();
                if (smr != null) renderers.Add(smr);
            }

            if (renderers.Count == 0)
            {
                Debug.LogWarning("[SidekickRuntime] 유효한 파츠가 없어 생성 취소");
                return null;
            }

            // combineMesh=true → 드로우콜 1개로 병합 (30~40명 월드에 유리)
            return _runtime.CreateCharacter(modelName, renderers, true, true);
        }

        /// <summary>랜덤 외형 (커스터마이징 창 초기값·"랜덤" 버튼용).</summary>
        public Dictionary<int, string> RandomParts()
        {
            var result = new Dictionary<int, string>();
            if (!IsReady) return result;

            foreach (var kv in PartNames)
            {
                if (kv.Value.Count == 0) continue;
                result[(int)kv.Key] = kv.Value[UnityEngine.Random.Range(0, kv.Value.Count)];
            }

            SyncMirrorParts(result);
            return result;
        }

        /// <summary>좌우 짝 파츠를 같은 값으로 맞춘다.
        /// 이름 치환이 실제 파츠와 매칭되지 않으면 (팔·다리 한쪽이 사라지는 문제)
        /// 왼쪽 파츠의 목록 인덱스로 오른쪽 파츠를 골라 항상 유효한 이름을 보장한다.</summary>
        public static void SyncMirrorParts(Dictionary<int, string> parts)
        {
            var svc = Instance;

            foreach (var pair in MirrorPairs)
            {
                if (!parts.TryGetValue((int)pair.Key, out var leftName)) continue;

                var candidate = leftName.Replace("Left", "Right").Replace("_L", "_R");

                if (svc.IsReady &&
                    svc.PartNames.TryGetValue(pair.Value, out var rightNames) && rightNames.Count > 0 &&
                    !rightNames.Contains(candidate))
                {
                    int idx = svc.PartNames.TryGetValue(pair.Key, out var leftNames)
                        ? Mathf.Max(0, leftNames.IndexOf(leftName))
                        : 0;
                    candidate = rightNames[idx % rightNames.Count];
                }

                parts[(int)pair.Value] = candidate;
            }
        }
    }
}
