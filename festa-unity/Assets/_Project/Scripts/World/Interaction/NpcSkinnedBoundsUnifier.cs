// NPC 옷이 화면 가장자리에서 먼저 사라지는 것을 막는 런타임 패스 (사용자 지적 2026-09-15·16).
// 이 파일이 있는 이유: NPC 시각물은 벤더 프리팹(ithappy Casino_Free)이라 원본을 고칠 수 없다. 씬이 뜰 때
// 렌더러 bounds 만 한 번 손봐 옷과 몸이 **같은 상자**로 컬링되게 한다 — 사라질 때는 통째로 사라진다.
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace Festa.World
{
    /// <summary>
    /// 한 NPC 안의 스킨 렌더러들이 하나의 bounds 를 공유하게 만든다.
    ///
    /// <para><b>실측 원인.</b> 벤더 프리팹의 옷·얼굴·머리 렌더러 <c>localBounds</c> 가 (0.15, 0.12, 0.03) —
    /// 15 cm 짜리 상자다. 몸 렌더러만 (1.46, 1.85, 0.43) 로 제대로 돼 있다. 그래서 그 작은 상자가 화면을
    /// 벗어나는 순간 옷만 컬링되고 몸은 남아 "옷이 벗겨진" 것처럼 보였다. 카메라 각도·거리에 따라 나타나는
    /// 이유도 이것이다 — 상자가 어디에 걸리느냐의 문제라 재현이 들쭉날쭉했다.</para>
    ///
    /// <para><b>고치는 법.</b> 같은 <c>rootBone</c> 을 쓰는 렌더러들의 bounds 를 합쳐 15% 여유를 주고 전부에
    /// 같은 값을 씌운다. 그러면 컬링 판정이 렌더러마다 같아져 함께 보이고 함께 사라진다.
    /// <c>updateWhenOffscreen</c> 으로도 막을 수 있지만 그건 매 프레임 스키닝을 강제한다 — 값 하나 맞추는
    /// 것으로 충분한 일에 그 비용을 낼 이유가 없다.</para>
    ///
    /// <para>플레이어 아바타는 건드리지 않는다 — 그쪽은 조립기가 자기 bounds 를 관리한다(S15P21A604-749·803).</para>
    /// </summary>
    public static class NpcSkinnedBoundsUnifier
    {
        const float Padding = 0.15f;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Hook()
        {
            SceneManager.sceneLoaded -= OnSceneLoaded;
            SceneManager.sceneLoaded += OnSceneLoaded;
            UnifyAll();
        }

        static void OnSceneLoaded(Scene _, LoadSceneMode __) => UnifyAll();

        /// <summary>씬의 모든 NPC 시각물을 훑는다. 스킨 렌더러가 둘 이상인 Animator 루트가 대상이다.</summary>
        public static int UnifyAll()
        {
            var roots = new HashSet<Animator>();
            foreach (var smr in Object.FindObjectsByType<SkinnedMeshRenderer>(FindObjectsInactive.Include, FindObjectsSortMode.None))
            {
                if (smr == null) continue;
                if (smr.GetComponentInParent<Festa.Network.NetworkPlayer>() != null) continue;
                var anim = smr.GetComponentInParent<Animator>();
                if (anim != null) roots.Add(anim);
            }

            int touched = 0;
            foreach (var anim in roots)
                if (Unify(anim.gameObject)) touched++;
            if (touched > 0) Debug.Log($"[NpcSkinnedBoundsUnifier] NPC {touched}기의 스킨 렌더러 bounds 를 통일했다");
            return touched;
        }

        /// <summary>NPC 하나. 같은 rootBone 그룹 안에서 bounds 를 합쳐 전부에 씌운다.</summary>
        public static bool Unify(GameObject root)
        {
            var smrs = root.GetComponentsInChildren<SkinnedMeshRenderer>(true);
            if (smrs.Length < 2) return false;

            var groups = new Dictionary<Transform, Bounds>();
            foreach (var s in smrs)
            {
                var key = s.rootBone != null ? s.rootBone : s.transform;
                if (!groups.TryGetValue(key, out var b)) groups[key] = s.localBounds;
                else { b.Encapsulate(s.localBounds); groups[key] = b; }
            }

            foreach (var s in smrs)
            {
                var key = s.rootBone != null ? s.rootBone : s.transform;
                var b = groups[key];
                b.Expand(b.size * Padding);   // 팔을 들거나 숙이는 자세 여유
                s.localBounds = b;
            }
            return true;
        }
    }
}
