// 엘리베이터 호출 버튼의 상호작용 영역이 스폰 캡슐을 물리적으로 막지 않게 한다.
using UnityEngine;
using UnityEngine.SceneManagement;

namespace Festa.World
{
    /// <summary>
    /// 엘리베이터 호출 버튼(<c>elevator-switch</c>)의 콜라이더를 Trigger 로 바꾼다 (T-148).
    ///
    /// <para>버튼은 누르라고 둔 것이지 몸을 막으라고 둔 것이 아니다. 앞 스폰 격자 두 칸이 이 상자를
    /// 물어서 캐릭터 캡슐이 벽 쪽에 끼었다.</para>
    ///
    /// <para><b>씬이 로드될 때마다 건다.</b> 월드(main)는 시작 씬이 아니라 로비에서
    /// <c>SceneManager.LoadScene</c> 으로 들어온다 — 시작 시 한 번만 도는 초기화로 두면 활성 씬이
    /// 로비라 버튼을 영원히 만나지 못하고, 에디터에서 main 을 직접 Play 할 때만 고쳐진 것처럼 보인다.</para>
    /// </summary>
    public static class ElevatorSwitchCollisionPolicy
    {
        const string SwitchName = "elevator-switch";

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Install()
        {
            SceneManager.sceneLoaded -= OnSceneLoaded;
            SceneManager.sceneLoaded += OnSceneLoaded;
            Apply(SceneManager.GetActiveScene());
        }

        static void OnSceneLoaded(Scene scene, LoadSceneMode mode) => Apply(scene);

        static void Apply(Scene scene)
        {
            if (!scene.IsValid() || !scene.isLoaded) return;

            int changed = 0;
            foreach (var root in scene.GetRootGameObjects())
            foreach (var collider in root.GetComponentsInChildren<Collider>(true))
            {
                if (collider.name != SwitchName || collider.isTrigger) continue;
                collider.isTrigger = true;
                changed++;
            }

            // 조용히 지나가면 "적용된 줄 알았는데 아니었다" 를 또 만든다 (T-24 원칙).
            if (changed > 0)
                Debug.Log($"[ElevatorSwitch] 호출 버튼 콜라이더 {changed}개를 Trigger 로 바꿨다 — 스폰 캡슐이 끼지 않는다 ({scene.name})");
        }
    }
}
