using Festa.Network;
using Unity.Netcode;
using UnityEngine;
using UnityEngine.InputSystem;

namespace Festa.World
{
    /// <summary>
    /// Owner 플레이어의 포털 상호작용.
    ///
    /// - 부스 실물 경계 기준 거리로 가장 가까운 <see cref="BoothPortal"/> 을 찾고,
    /// - 대상 부스 위에 **키캡 스타일 프롬프트**([F] + 행동 문구)를 띄우고,
    /// - 대상 발밑에 **하이라이트 링**을 깐다 — 로컬 렌더링 전용이라 다른 접속자에게는
    ///   보이지 않는다 (네트워크로 나가는 상태 없음),
    /// - F 입력 시 목적지로 텔레포트한다 (스폰과 같은 절차 — T-177).
    /// </summary>
    public class PortalInteractor : NetworkBehaviour
    {
        [SerializeField] float _cooldown = 0.6f;   // 도착 직후 반대편 포털 즉시 재발동 방지

        PlayerMovement _movement;
        PlayerCameraFollow _camera;
        BoothPortal _nearest;
        float _lastTeleportTime = -10f;

        // ── 하이라이트 링 (Owner 로컬 전용) ──
        GameObject _ring;
        Material _ringMat;
        static Texture2D _ringTex;
        static Texture2D _panelTex;
        static Texture2D _capTex;

        public override void OnNetworkSpawn()
        {
            enabled = IsOwner;
            if (!IsOwner) return;
            _movement = GetComponent<PlayerMovement>();
            _camera = GetComponent<PlayerCameraFollow>();
        }

        public override void OnNetworkDespawn()
        {
            if (_ring != null) Destroy(_ring);
            if (_ringMat != null) Destroy(_ringMat);
        }

        void Update()
        {
            _nearest = FindNearest();
            UpdateHighlight();
            if (_nearest == null) return;
            if (Time.time - _lastTeleportTime < _cooldown) return;

            var kb = Keyboard.current;
            if (kb == null || !kb.fKey.wasPressedThisFrame) return;
            if (_nearest.destination == null) return;

            _movement.TeleportTo(_nearest.destination.position);
            if (_camera != null) _camera.SnapBehind();   // 카메라가 맵을 가로질러 날아오지 않게
            _lastTeleportTime = Time.time;
        }

        BoothPortal FindNearest()
        {
            BoothPortal best = null;
            float bestDist = float.MaxValue;
            var pos = transform.position;
            foreach (var p in BoothPortal.All)
            {
                float d = p.DistanceFrom(pos);
                if (d > p.interactRadius || d >= bestDist) continue;
                bestDist = d;
                best = p;
            }
            return best;
        }

        // ── 하이라이트: 대상 발밑의 부드러운 링. 로컬 오브젝트라 본인 화면에만 보인다 ──

        void UpdateHighlight()
        {
            bool show = _nearest != null && Time.time - _lastTeleportTime >= _cooldown;
            if (!show)
            {
                if (_ring != null) _ring.SetActive(false);
                return;
            }
            if (_ring == null) CreateRing();
            var (pos, radius) = _nearest.HighlightFootprint();
            _ring.SetActive(true);
            _ring.transform.position = pos;
            float pulse = 1f + 0.06f * Mathf.Sin(Time.time * 4.2f);
            _ring.transform.localScale = new Vector3(radius * 2f * pulse, 1f, radius * 2f * pulse);
        }

        void CreateRing()
        {
            _ring = GameObject.CreatePrimitive(PrimitiveType.Quad);
            Destroy(_ring.GetComponent<Collider>());
            _ring.name = "InteractHighlight (local)";
            _ring.transform.rotation = Quaternion.Euler(90f, 0f, 0f);
            _ringMat = new Material(Shader.Find("Mobile/Particles/Additive"));
            _ringMat.mainTexture = RingTexture();
            var r = _ring.GetComponent<Renderer>();
            r.sharedMaterial = _ringMat;
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
        }

        static Texture2D RingTexture()
        {
            if (_ringTex != null) return _ringTex;
            const int S = 128;
            _ringTex = new Texture2D(S, S, TextureFormat.RGBA32, false);
            for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++)
            {
                float d = Vector2.Distance(new Vector2(x, y), new Vector2(S / 2f, S / 2f)) / (S / 2f);
                // 가장자리 링 + 안쪽 은은한 채움
                float ring = Mathf.Exp(-Mathf.Pow((d - 0.82f) / 0.08f, 2f));
                float fill = d < 0.82f ? 0.10f * (1f - d) : 0f;
                float a = Mathf.Clamp01(ring * 0.85f + fill);
                _ringTex.SetPixel(x, y, new Color(1f, 0.82f, 0.35f, 1f) * a);
            }
            _ringTex.Apply();
            return _ringTex;
        }

        // ── 프롬프트: 대상 부스 위 화면 좌표에 키캡 스타일로 ──

        void OnGUI()
        {
            if (_nearest == null || Time.time - _lastTeleportTime < _cooldown) return;
            // 위치는 화면 중앙 약간 아래 고정 — 부스 높이·카메라 각도와 무관하게
            // 항상 보인다 (부스 상단 월드 앵커 방식은 3인칭 하향 카메라에서 화면 밖으로 나갔다).
            float ui = Screen.height / 1080f;

            var label = _nearest.promptText;
            var labelStyle = new GUIStyle(GUI.skin.label)
            {
                alignment = TextAnchor.MiddleLeft,
                fontSize = Mathf.RoundToInt(19f * ui),
                fontStyle = FontStyle.Bold,
            };
            labelStyle.normal.textColor = Color.white;
            var capStyle = new GUIStyle(GUI.skin.label)
            {
                alignment = TextAnchor.MiddleCenter,
                fontSize = Mathf.RoundToInt(18f * ui),
                fontStyle = FontStyle.Bold,
            };
            capStyle.normal.textColor = new Color(0.12f, 0.12f, 0.12f);

            float cap = 30f * ui;                                    // 키캡 한 변
            float labelW = labelStyle.CalcSize(new GUIContent(label)).x;
            float pad = 10f * ui;
            float w = cap + pad * 3f + labelW;
            float h = cap + pad * 1.4f;
            float x = (Screen.width - w) / 2f;
            float y = Screen.height * 0.52f;   // 화면 중앙 살짝 아래 — 60% 는 너무 낮았다

            GUI.DrawTexture(new Rect(x, y, w, h), PanelTexture(), ScaleMode.StretchToFill);
            GUI.DrawTexture(new Rect(x + pad, y + (h - cap) / 2f, cap, cap), CapTexture(), ScaleMode.StretchToFill);
            GUI.Label(new Rect(x + pad, y + (h - cap) / 2f, cap, cap), "F", capStyle);
            GUI.Label(new Rect(x + pad * 2f + cap, y, labelW + pad, h), label, labelStyle);
        }

        static Texture2D PanelTexture()
        {
            if (_panelTex != null) return _panelTex;
            _panelTex = Solid(new Color(0.07f, 0.07f, 0.09f, 0.82f));
            return _panelTex;
        }

        static Texture2D CapTexture()
        {
            if (_capTex != null) return _capTex;
            _capTex = Solid(new Color(0.93f, 0.93f, 0.9f, 0.98f));
            return _capTex;
        }

        static Texture2D Solid(Color c)
        {
            var t = new Texture2D(2, 2, TextureFormat.RGBA32, false);
            t.SetPixels(new[] { c, c, c, c });
            t.Apply();
            return t;
        }
    }
}
