using Festa.Booth;
using Festa.Integration;
using UnityEngine;

namespace Festa.Content
{
    /// <summary>LAPTOP 상호작용(F 키)을 Unity→React 부스 이벤트로 변환한다.</summary>
    [RequireComponent(typeof(BoothRuntimeObject))]
    public sealed class LaptopInteractable : MonoBehaviour, IBoothInteractable
    {
        BoothRuntimeObject _runtimeObject;

        void Awake()
        {
            _runtimeObject = GetComponent<BoothRuntimeObject>();

            // 레이캐스트 대상이 되도록 콜라이더를 보장한다. 실제 LAPTOP 프리팹은
            // 자식(TableSquare)에 콜라이더가 있고, 디스패처가 부모에서 이 컴포넌트를 찾는다.
            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();

            // 클릭 감지는 중앙 디스패처가 한다. OnMouseDown 은 WebGL 에서 발생하지 않는다 (T-166).
            BoothInteractionInput.Ensure();
        }

        /// <summary>
        /// 등록 여부와 **무관하게** 트리거만 발생시킨다 (FR-005). 주소가 미등록이면 FE 가
        /// 안내를 띄운다 (FR-009) — 홈페이지 주소는 `booths.homepage_url` 에 있고 Layout 에는
        /// 없어서 **Unity 는 그 값을 알 수 없다**(헌법 25조, `homepage-api.md` §4 "Unity 변경 0").
        ///
        /// 그래서 여기서 "주소가 있나" 를 확인하지 않는다. 확인하려면 Unity 가 부스를 조회해야
        /// 하는데, 그건 표시 책임을 웹 레이어에 두기로 한 결정과 어긋난다.
        /// </summary>
        public void Interact()
        {
            if (_runtimeObject == null)
            {
                Debug.LogWarning("[LaptopInteractable] BoothRuntimeObject가 없어 이벤트를 건너뜁니다.", this);
                return;
            }

            // C-04(노트북 연출): 화면이 켜지는 3D 연출 대신 **초점 카메라 줌**으로 통일 — 노트북에 다가가 화면을 들여다보는 구도(-299).
            //
            // 구도는 **화면 판 하나**에 맞춘다. 대상 전체로 잡으면 받침 테이블(0.80 m)이 화면(0.19 m)보다
            // 네 배라 테이블에 맞춰지고, 그 경로의 거리 하한 8u 때문에 화면이 화면의 1/4 을 넘지 못했다.
            // FE 가 그 위에 사이트를 띄우므로 화면이 화면을 꽉 채워야 "노트북으로 본다" 가 성립한다
            // (사용자 지시 2026-09-18).
            var screen = FindScreenRenderer();
            if (screen != null) Festa.World.InteractionFocusCamera.FocusOnSurface(screen);
            else Festa.World.InteractionFocusCamera.FocusOn(gameObject, 2.0f);
            BoothInteractBridge.SendLaptopInteract(_runtimeObject.BoothId, _runtimeObject.ObjectId);
        }

        /// <summary>
        /// 노트북의 화면 판. 프리팹의 <c>screen</c> 메시다 — 이름으로 찾되, 못 찾으면 <b>가장 얇고 서 있는</b>
        /// 렌더러를 고른다. 이름이 바뀌어도 구도가 통째로 어긋나지 않게 하는 예비 경로다.
        /// </summary>
        Renderer FindScreenRenderer()
        {
            Renderer best = null;
            float bestScore = 0f;
            foreach (var r in GetComponentsInChildren<Renderer>(true))
            {
                if (r == null || r.gameObject.name.StartsWith("__Festa")) continue;
                if (r.gameObject.name.ToLowerInvariant().Contains("screen")) return r;
                var s = r.bounds.size;
                float thin = Mathf.Min(s.x, Mathf.Min(s.y, s.z));
                float wide = Mathf.Max(s.x, Mathf.Max(s.y, s.z));
                if (thin <= 0.0001f || wide <= 0.0001f) continue;
                float score = s.y * (wide / thin);        // 서 있고 얇을수록 화면일 가능성이 크다
                if (score <= bestScore) continue;
                bestScore = score; best = r;
            }
            return best;
        }
    }
}
