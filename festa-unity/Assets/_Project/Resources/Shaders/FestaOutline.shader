// 상호작용 대상 외곽선 (S15P21A604-437).
//
// inverted hull — 같은 메시를 정점 노멀 방향으로 조금 밀어 낸 뒤 **앞면을 컬링**해 그리면
// 원본 메시 뒤로 테두리만 남는다. 원본 재질을 건드리지 않으므로(전에는 재질 인스턴스에
// 에미션을 켜서 prefab 전체가 노랗게 빛났다) 대상 부위의 렌더러에만 붙이면 그 부위만 테두리가 선다.
// SkinnedMeshRenderer 도 같은 본을 공유하는 복제 렌더러로 처리되므로 애니메이션을 그대로 따른다.
//
// Resources/ 아래에 두는 이유: Shader.Find 는 빌드에 포함된 셰이더만 찾는다. 재질로 미리 참조되지
// 않는 셰이더는 Resources 에 있어야 WebGL 빌드에 들어간다.
Shader "Festa/Outline"
{
    Properties
    {
        _Color ("Outline Color", Color) = (1, 0.82, 0.35, 1)
        _Width ("Outline Width (world units)", Range(0, 2)) = 0.35
    }
    SubShader
    {
        Tags { "RenderType" = "Opaque" "RenderPipeline" = "UniversalPipeline" "Queue" = "Geometry+10" }

        Pass
        {
            Name "Outline"
            Tags { "LightMode" = "UniversalForward" }
            Cull Front
            ZWrite On
            ZTest LEqual

            HLSLPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"

            CBUFFER_START(UnityPerMaterial)
                half4 _Color;
                float _Width;
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
