// 엘리베이터 호출 버튼의 상호작용 영역이 스폰 캡슐을 물리적으로 막지 않게 한다.
using UnityEngine;
using UnityEngine.SceneManagement;

namespace Festa.World
{
    public static class ElevatorSwitchCollisionPolicy
    {
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Apply()
        {
            var scene = SceneManager.GetActiveScene();
            if (!scene.IsValid()) return;
            foreach (var root in scene.GetRootGameObjects())
            foreach (var collider in root.GetComponentsInChildren<Collider>(true))
                if (collider.name == "elevator-switch") collider.isTrigger = true;
        }
    }
}
