using Festa.Booth;
using UnityEngine;

namespace Festa.Content
{
    /// <summary>
    /// Video Screen POC placeholder. configId로 Spring에서 영상 URL을 받아
    /// VideoPlayer로 재생하는 정식 구현은 해당 기능 spec 작성 후 진행한다.
    /// </summary>
    [RequireComponent(typeof(BoothRuntimeObject))]
    public class VideoScreenPlaceholder : MonoBehaviour
    {
        void Start()
        {
            var runtimeObject = GetComponent<BoothRuntimeObject>();
            Debug.Log($"[VideoScreen] booth={runtimeObject.BoothId} configId={runtimeObject.ConfigId} ready (placeholder)");
        }
    }
}
