// 로컬 오락기 전체화면 동안 OnGUI 기반 개발 HUD를 숨기고 종료 시 원래 상태로 복구한다.
using System.Collections.Generic;
using UnityEngine;

namespace Festa.Content.Arcade
{
    static class ArcadeOverlayHudSuppressor
    {
        static readonly HashSet<string> HiddenTypes = new()
        {
            "Festa.Network.DevConnectionHud", "Festa.Diagnostics.FixedPoseBenchmark",
            "Festa.Diagnostics.PerfHud", "Festa.Diagnostics.RenderCostProbe",
            "Festa.Diagnostics.AvatarStressSpawner", "Festa.World.AvatarCustomizationHud"
        };
        static readonly Dictionary<MonoBehaviour, bool> Previous = new();
        static int _holders;

        public static void Acquire()
        {
            if (_holders++ > 0) return;
            Previous.Clear();
            foreach (var behaviour in Object.FindObjectsByType<MonoBehaviour>(FindObjectsInactive.Include, FindObjectsSortMode.None))
            {
                if (behaviour == null || !HiddenTypes.Contains(behaviour.GetType().FullName)) continue;
                Previous[behaviour] = behaviour.enabled; behaviour.enabled = false;
            }
        }

        public static void Release()
        {
            if (_holders <= 0 || --_holders > 0) return;
            // Play Mode 종료 중에는 씬 의존성이 먼저 파괴되므로 HUD를 다시 켜지 않는다.
            if (!Application.isPlaying) { Previous.Clear(); _holders = 0; return; }
#if UNITY_EDITOR
            if (!UnityEditor.EditorApplication.isPlayingOrWillChangePlaymode)
            {
                Previous.Clear(); _holders = 0; return;
            }
#endif
            foreach (var pair in Previous) if (pair.Key != null) pair.Key.enabled = pair.Value;
            Previous.Clear();
        }
    }
}
