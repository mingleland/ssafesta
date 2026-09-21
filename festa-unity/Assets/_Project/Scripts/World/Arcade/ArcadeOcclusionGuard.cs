// 서쪽 벽의 기존 오클루전 베이크가 새 오락실 개구부를 잘못 가리는 동안, 입구 주변에서만 카메라 컬링을 우회한다.
using UnityEngine;

namespace Festa.Content.Arcade
{
    [DisallowMultipleComponent]
    public sealed class ArcadeOcclusionGuard : MonoBehaviour
    {
        [SerializeField] float _approachMaxX = -800f;
        [SerializeField] float _arcadeMinX = -1270f;
        [SerializeField] float _minZ = -85f;
        [SerializeField] float _maxZ = 115f;

        Camera _camera;
        bool _original;
        bool _overriding;

        void LateUpdate()
        {
            if (_camera == null)
            {
                _camera = Camera.main;
                if (_camera == null) return;
                _original = _camera.useOcclusionCulling;
            }

            Vector3 p = _camera.transform.position;
            bool shouldDisable = p.x <= _approachMaxX && p.x >= _arcadeMinX && p.z >= _minZ && p.z <= _maxZ;
            if (shouldDisable == _overriding) return;

            _overriding = shouldDisable;
            _camera.useOcclusionCulling = shouldDisable ? false : _original;
        }

        void OnDisable()
        {
            if (_camera != null && _overriding)
                _camera.useOcclusionCulling = _original;
            _overriding = false;
        }
    }
}