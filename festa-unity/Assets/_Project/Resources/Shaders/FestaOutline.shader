// 상호작용 대상 외곽선 (S15P21A604-437, 2026-09-10 최외곽 전용으로 개정).
//
// inverted hull — 같은 메시를 정점 노멀 방향으로 조금 밀어 낸 뒤 **앞면을 컬링**해 그리면
// 원본 메시 뒤로 테두리만 남는다. 원본 재질을 건드리지 않으므로(전에는 재질 인스턴스에
// 에미션을 켜서 prefab 전체가 노랗게 빛났다) 대상 부위의 렌더러에만 붙이면 그 부위만 테두리가 선다.
// SkinnedMeshRenderer 도 같은 본을 공유하는 복제 렌더러로 처리되므로 애니메이션을 그대로 따른다.
//
// **왜 스텐실이 붙었나 (2026-09-10 사용자 지시 "전체 다 하이라이트하지 말고 최외부 외곽선만").**
// 부스처럼 부품이 많은 메시에 이 방식을 그대로 쓰면 오리·풍선 같은 **내부 부품마다** 테두리가 선다 —
// 부품의 확장 껍질이 그 뒤의 부스 벽(더 먼 깊이)보다 앞이라 깊이 검사를 통과하기 때문이다.
// 그래서 두 단계로 나눈다:
//   ① 마스크 재질(_ColorMask 0, Cull Back, _Width 0, Queue Geometry+5) 이 **대상이 보이는 픽셀에 스텐실 비트 32** 를 찍고,
//   ② 껍질 재질(Cull Front, Queue Geometry+10) 은 **그 비트가 없는 픽셀에만** 그린다.
// 결과적으로 대상 자신이 덮은 자리는 전부 걸러지고 바깥 실루엣만 남는다. 비트 하나(32)만 읽고 쓰므로
// 다른 기능이 스텐실을 써도 서로 밟지 않는다.
//
// 렌더 상태를 재질 프로퍼티로 뽑아 둔 이유: 마스크와 껍질이 **같은 셰이더의 다른 재질**이면 큐 순서가
// 결정적이다. 한 재질 안에서 멀티패스로 하면 URP 가 SRPDefaultUnlit / UniversalForward 를 어떤 순서로
// 그리는지에 기대게 되고, 그건 버전에 따라 달라진다.
//
// Resources/ 아래에 두는 이유: Shader.Find 는 빌드에 포함된 셰이더만 찾는다. 재질로 미리 참조되지
// 않는 셰이더는 Resources 에 있어야 WebGL 빌드에 들어간다.
Shader "Festa/Outline"
{
    Properties
    {
        _Color ("Outline Color", Color) = (1, 0.82, 0.35, 1)
        _Width ("Outline Width (world units)", Range(0, 3)) = 0.35
        [Enum(UnityEngine.Rendering.CullMode)] _Cull ("Cull", Float) = 1                       // 1 = Front(껍질), 2 = Back(마스크)
        _ColorMask ("Color Mask", Float) = 15                                                  // 15 = RGBA, 0 = 색 안 씀(마스크)
        _ZWrite ("ZWrite", Float) = 1
        [Enum(UnityEngine.Rendering.CompareFunction)] _StencilComp ("Stencil Comp", Float) = 6 // 6 = NotEqual(껍질), 8 = Always(마스크)
        [Enum(UnityEngine.Rendering.StencilOp)] _StencilOp ("Stencil Op", Float) = 0           // 0 = Keep(껍질), 2 = Replace(마스크)
    }
    SubShader
    {
        Tags { "RenderType" = "Opaque" "RenderPipeline" = "UniversalPipeline" "Queue" = "Geometry+10" }

        Pass
        {
            Name "Outline"
            Tags { "LightMode" = "UniversalForward" }
            Cull [_Cull]
            ZWrite [_ZWrite]
            ZTest LEqual
            ColorMask [_ColorMask]
            Stencil
            {
                Ref 32
                ReadMask 32
                WriteMask 32
                Comp [_StencilComp]
                Pass [_StencilOp]
            }

            HLSLPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"

            CBUFFER_START(UnityPerMaterial)
                half4 _Color;
                float _Width;
                float _Cull;
                float _ColorMask;
                float _ZWrite;
                float _StencilComp;
                float _StencilOp;
            CBUFFER_END

            struct Attributes
            {
                float4 positionOS : POSITION;
                float3 normalOS   : NORMAL;
            };

            struct Varyings
            {
                float4 positionHCS : SV_POSITION;
            };

            Varyings vert(Attributes v)
            {
                Varyings o;
                // 월드 공간에서 밀어 낸다 — 오브젝트 스케일(아바타 12.1배 등)에 관계없이 두께가 일정하다.
                float3 positionWS = TransformObjectToWorld(v.positionOS.xyz);
                float3 normalWS = normalize(TransformObjectToWorldNormal(v.normalOS));
                positionWS += normalWS * _Width;
                o.positionHCS = TransformWorldToHClip(positionWS);
                return o;
            }

            half4 frag(Varyings i) : SV_Target
            {
                return _Color;
            }
            ENDHLSL
        }
    }
    FallBack Off
}
