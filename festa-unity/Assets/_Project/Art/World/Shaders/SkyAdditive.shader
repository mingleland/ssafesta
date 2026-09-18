// 하늘에 붙는 가산 스프라이트 전용 (달 등) — **안개를 받지 않는다.**
//
// 왜 따로 두나. 달 스프라이트는 카메라에서 1,900 u 쯤 떨어져 있어서 URP 안개
// (ExponentialSquared, density 0.00045)가 51% 나 얹힌다. 안개는 쿼드 표면 전체에
// 균일하게 더해지므로 텍스처가 완전한 검정인 테두리까지 안개색으로 물들고,
// 그 결과 하늘에 **쿼드 경계가 사각형으로 드러난다** (사용자 지적 2026-09-11).
// 텍스처를 0 으로 깎아도 사라지지 않는다 — 원인이 텍스처가 아니라 안개라서다.
//
// 하늘 요소는 애초에 대기 원근을 받을 이유가 없다(이미 "먼 하늘" 을 그린 그림이다).
// 그래서 안개 계산을 아예 빼고, 가산 합성만 남긴다.
Shader "Festa/SkyAdditive"
{
    Properties
    {
        _MainTex ("Texture", 2D) = "black" {}
        _Tint    ("Tint", Color) = (1,1,1,1)
    }

    SubShader
    {
        Tags { "RenderType"="Transparent" "Queue"="Transparent" "RenderPipeline"="UniversalPipeline" "IgnoreProjector"="True" }

        Blend One One        // 가산 — 검정은 보이지 않는다
        ZWrite Off
        Cull Off
        Lighting Off

        Pass
        {
            HLSLPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"

            TEXTURE2D(_MainTex);
            SAMPLER(sampler_MainTex);

            CBUFFER_START(UnityPerMaterial)
                float4 _MainTex_ST;
                half4  _Tint;
            CBUFFER_END

            struct Attributes { float4 positionOS : POSITION; float2 uv : TEXCOORD0; };
            struct Varyings   { float4 positionCS : SV_POSITION; float2 uv : TEXCOORD0; };

            Varyings vert (Attributes v)
            {
                Varyings o;
                o.positionCS = TransformObjectToHClip(v.positionOS.xyz);
                o.uv = TRANSFORM_TEX(v.uv, _MainTex);
                return o;   // fogCoord 를 만들지 않는다 — 이 셰이더의 존재 이유다
            }

            half4 frag (Varyings i) : SV_Target
            {
                half4 c = SAMPLE_TEXTURE2D(_MainTex, sampler_MainTex, i.uv) * _Tint;
                return half4(c.rgb, 1);   // 가산이라 알파는 쓰이지 않는다
            }
            ENDHLSL
        }
    }
    FallBack Off
}
