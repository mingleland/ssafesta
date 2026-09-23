// 대기 중인 오락기 게임 본체를 재워 두고, 화면 갱신이 필요할 때만 잠시 깨워 한 프레임을 굽는다.
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace Festa.Content.Arcade
{
    /// <summary>
    /// 오락기 20대가 각자 만든 런타임 오브젝트(스프라이트·텍스트·카메라)를 <b>대기 중에는 비활성</b>으로 둔다.
    ///
    /// <para>카메라만 꺼도 Transform·Renderer·Behaviour 는 그대로 남아 매 프레임 비용을 만든다 (T-149 후속 실측:
    /// 대기 상태에서 게임 런타임 오브젝트 738개 중 700개가 활성이었다). 캐비닛 화면은 RenderTexture 에 이미
    /// 구워진 그림이라, 오브젝트를 재워도 보이는 것은 달라지지 않는다.</para>
    ///
    /// <para><b>재우기 전 상태를 기억한다.</b> 랭킹 응답이 늦게 도착해 프리뷰를 다시 구울 때
    /// (<see cref="ArcadeRankingBoard"/>), 전부 켜 버리면 프리뷰가 숨겨 둔 오브젝트까지 화면에 나온다.
    /// <see cref="TryRenderIdle"/> 는 기억한 상태 그대로 되돌려 굽고 다시 재운다 — 같은 프레임 안에서
    /// 끝나므로 깜빡임이 없다.</para>
    /// </summary>
    static class ArcadeRuntimeSuspension
    {
        sealed class Entry
        {
            public IReadOnlyList<GameObject> Objects;
            public bool[] States;
        }

        // 키는 그 게임의 오프스크린 카메라다 — 랭킹 보드는 카메라만 들고 있어서 이 쪽으로만 찾아올 수 있다.
        static readonly Dictionary<Camera, Entry> Suspended = new();

        /// <summary>
        /// 씬이 내려가면 기억을 버린다 (S15P21A604-970). 키가 파괴된 카메라, 값이 파괴된 오브젝트 목록인
        /// 항목이 남으면 월드를 드나들 때마다 20대분이 그대로 쌓인다.
        /// </summary>
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
        static void Install()
        {
            SceneManager.sceneUnloaded -= OnSceneUnloaded;
            SceneManager.sceneUnloaded += OnSceneUnloaded;
        }

        static void OnSceneUnloaded(Scene _) => Suspended.Clear();

        /// <summary>게임을 시작할 때 본체를 모두 깨운다. 이후 구성은 각 게임의 NewGame 이 맡는다.</summary>
        public static void Resume(IReadOnlyList<GameObject> objects)
        {
            if (objects == null) return;
            var camera = FindCamera(objects);
            if (camera != null) Suspended.Remove(camera);
            for (int i = 0; i < objects.Count; i++)
                if (objects[i] != null) objects[i].SetActive(true);
        }

        /// <summary>대기·종료 화면을 구운 뒤 호출한다. 지금 켜진 것이 무엇이었는지 기억하고 전부 재운다.</summary>
        public static void Suspend(IReadOnlyList<GameObject> objects)
        {
            if (objects == null) return;

            var states = new bool[objects.Count];
            for (int i = 0; i < objects.Count; i++)
            {
                var go = objects[i];
                if (go == null) continue;
                states[i] = go.activeSelf;
                go.SetActive(false);
            }

            var camera = FindCamera(objects);
            if (camera != null) Suspended[camera] = new Entry { Objects = objects, States = states };
        }

        /// <summary>
        /// 재워 둔 게임의 화면을 한 번 다시 굽는다. 그 카메라가 재워진 적이 없으면 false —
        /// 호출한 쪽이 평소대로 <c>Camera.Render()</c> 하면 된다.
        /// </summary>
        public static bool TryRenderIdle(Camera camera)
        {
            if (camera == null || !Suspended.TryGetValue(camera, out var entry)) return false;

            var objects = entry.Objects;
            for (int i = 0; i < objects.Count; i++)
                if (objects[i] != null && entry.States[i]) objects[i].SetActive(true);

            camera.Render();

            for (int i = 0; i < objects.Count; i++)
                if (objects[i] != null) objects[i].SetActive(false);
            return true;
        }

        static Camera FindCamera(IReadOnlyList<GameObject> objects)
        {
            for (int i = 0; i < objects.Count; i++)
            {
                if (objects[i] == null) continue;
                var camera = objects[i].GetComponent<Camera>();
                if (camera != null) return camera;
            }
            return null;
        }
    }
}
