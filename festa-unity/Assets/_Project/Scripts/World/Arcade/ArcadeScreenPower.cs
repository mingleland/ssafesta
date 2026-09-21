// 오락기 화면을 "켜진" 상태로 만드는 컴포넌트.
// 이 파일이 있는 이유: 캐비닛 모델의 화면은 조명을 받는 회색 Lit 머티리얼이라 실내가 어두우면 꺼진 기계로 읽힌다.
// 켜짐/꺼짐은 앞으로 FE 가 알려줄 게임 매핑 상태에 따라 달라지므로, 교체 지점을 한곳에 모아 둔다.
using UnityEngine;

namespace Festa.Content.Arcade
{
    /// <summary>
    /// 캐비닛의 화면 머티리얼 슬롯을 <b>자체발광 어트랙트 화면</b>으로 바꾼다.
    ///
    /// <para><b>왜 Unlit 인가.</b> 화면은 스스로 빛나는 물건이다. Lit 로 두면 실내 밝기에 따라
    /// 같은 기계가 켜졌다 꺼졌다 하는 것처럼 보인다. Unlit 은 광원 비용도 0 이다.</para>
    ///
    /// <para><b>실제 게임 화면은 여기에 띄울 수 없다.</b> 스튜디오 게임은 React 오버레이(웹)에서 돌고
    /// Unity WebGL 캔버스 안으로 그 픽셀을 가져올 방법이 없다. 그래서 기본값은 어트랙트 화면이고,
    /// 서버가 게임별 대표 이미지를 주기로 계약이 확정되면 <see cref="SetScreenTexture"/> 로 갈아끼운다
    /// (GitLab #256).</para>
    ///
    /// <para>머티리얼은 <b>인스턴스를 만들지 않고</b> 공유 에셋 하나를 돌려 쓴다 — 캐비닛 20대가 각자
    /// 머티리얼을 복제하면 배칭이 깨지고 드로우콜이 그만큼 늘어난다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class ArcadeScreenPower : MonoBehaviour
    {
        /// <summary>교체 대상 슬롯을 찾는 기준. 모델이 쓰는 화면 머티리얼 이름이다.</summary>
        public const string ScreenMaterialName = "Arcade_Screen_Material_URP";

        public const string OnMaterialPath = "Assets/_Project/Art/World/Materials/ArcadeScreenOn.mat";

        [SerializeField] Material _onMaterial;
        [SerializeField] bool _powered = true;

        Material _offMaterial;

        /// <summary>이 기계에 게임이 걸려 있는가. FE 매핑 계약이 붙기 전까지는 전부 켜 둔다.</summary>
        public bool Powered => _powered;

        void Awake() => Apply();

        /// <summary>FE 가 매핑 상태를 알려주면 이 기계만 켜고 끈다.</summary>
        public void SetPowered(bool powered)
        {
            if (_powered == powered) return;
            _powered = powered;
            Apply();
        }

        /// <summary>서버가 게임 대표 이미지를 주면 화면 그림만 갈아끼운다.</summary>
        public void SetScreenTexture(Texture texture)
        {
            if (_onMaterial == null || texture == null) return;
            _onMaterial.SetTexture("_BaseMap", texture);
        }

        void Apply()
        {
            if (_onMaterial == null) return;

            foreach (var renderer in GetComponentsInChildren<MeshRenderer>(true))
            {
                var mats = renderer.sharedMaterials;
                bool changed = false;
                for (int i = 0; i < mats.Length; i++)
                {
                    if (mats[i] == null) continue;
                    bool isScreenSlot = mats[i] == _onMaterial || mats[i].name.StartsWith(ScreenMaterialName);
                    if (!isScreenSlot) continue;

                    if (_powered)
                    {
                        if (mats[i] != _onMaterial) { _offMaterial = mats[i]; mats[i] = _onMaterial; changed = true; }
                    }
                    else if (_offMaterial != null && mats[i] != _offMaterial)
                    {
                        mats[i] = _offMaterial;
                        changed = true;
                    }
                }
                if (changed) renderer.sharedMaterials = mats;
            }
        }

#if UNITY_EDITOR
        /// <summary>에디터에서 배치 직후에도 켜진 화면이 보이도록 빌더가 부른다.</summary>
        public void EditorSetup(Material onMaterial)
        {
            _onMaterial = onMaterial;
            _powered = true;
            Apply();
        }
#endif
    }
}
