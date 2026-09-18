using Festa.Booth;
using UnityEngine;

namespace Festa.Content
{
    /// <summary>
    /// 부스 영상 스크린 — <b>장식이다</b> (GitLab #194 ② B안, 2026-09-17). 영상은 재생하지 않는다.
    ///
    /// <para><b>왜 영상이 아닌가.</b> 기능화하려면 영상 호스팅부터 정해야 한다. 프로젝트의
    /// <c>videoUrl</c> 은 YouTube 링크가 들어오는 자리인데 Unity <c>VideoPlayer</c> 는 그것을 재생하지 못한다.</para>
    ///
    /// <para>화면에 무엇을 띄울지는 <see cref="BoothScreenSurface"/> 가 정한다 — 이 컴포넌트는 배치
    /// 진단 로그만 남긴다. 그 로그(<c>configId=0 ready</c>)가 #194 결정의 근거였다.</para>
    /// </summary>
    [RequireComponent(typeof(BoothRuntimeObject))]
    public class VideoScreenPlaceholder : MonoBehaviour
    {
        void Start()
        {
            var runtimeObject = GetComponent<BoothRuntimeObject>();
            Debug.Log($"[VideoScreen] booth={runtimeObject.BoothId} configId={runtimeObject.ConfigId} ready (decorative)");
        }
    }
}
