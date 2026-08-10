using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// Owner 플레이어를 Main Camera가 뒤에서 따라가게 한다 (POC용 3인칭 고정 추적).
    /// Player Prefab에 부착. 자신(Owner)일 때만 동작하므로
    /// 원격 플레이어가 카메라를 빼앗는 일은 없다.
    /// 정식 카메라 워크(Cinemachine 등)는 월드 기능 spec 확정 후 교체.
    /// </summary>
    public class PlayerCameraFollow : NetworkBehaviour
    {
        [SerializeField] Vector3 _offset = new(0f, 4f, -6f);
        [SerializeField] float _followLerp = 8f;

        Camera _cam;

        public override void OnNetworkSpawn()
        {
            enabled = IsOwner;
            if (IsOwner) _cam = Camera.main;
        }

        void LateUpdate()
        {
            if (_cam == null)
            {
                _cam = Camera.main;
                if (_cam == null) return;
            }

            var targetPos = transform.position + _offset;
            _cam.transform.position = Vector3.Lerp(
                _cam.transform.position, targetPos, _followLerp * Time.deltaTime);
            _cam.transform.LookAt(transform.position + Vector3.up * 1f);
        }
    }
}
