Shader "Festa/Avatar/HairTint"
{
    // 헤어 전용 셰이더 (T-212).
    //
    // 이전에는 헤어 재질을 런타임에 URP Lit 으로 만들었다. URP Lit 은 Always Included
    // 에 넣을 수 없고(배리언트 폭발) 빌드 포함 여부가 다른 에셋에 좌우되므로, 벤더 폴더를
    // 정리한 순간 스트리핑돼 헤어가 "존재하지만 그려지지 않는" 상태가 됐다 — 에디터에서는
    // 전 셰이더가 살아 있어 절대 재현되지 않는다.
    //
    // 그래서 Skin/Face/Garment 틴트와 같은 패턴으로 간다: 배리언트가 하나뿐인 전용
    // 셰이더를 만들고 Always Included Shaders 에 등록한다 — 빌드 포함이 보장된다.
    Properties
    {
        _BaseMap("Hair RGB Map", 2D) = "white" {}
        _BaseColor("Hair Color", Color) = (0.18,0.11,0.06,1)
    }
    SubShader
    {
        Tags { "RenderType"="Opaque" "RenderPipeline"="UniversalPipeline" "Queue"="Geometry" }
        Pass
        {
            Name "ForwardLit"
            Tags { "LightMode"="UniversalForward" }
            // 벤더 헤어 메시에는 안쪽이 보이는 카드형 다발이 있다 — 뒷면을 자르면
            // 특정 각도에서 구멍이 뚫려 보이므로 양면으로 그린다.
            Cull Off
            HLSLPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"
            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Lighting.hlsl"

            struct Attributes
            {
                float4 positionOS : POSITION;
                float3 normalOS : NORMAL;
                float2 uv : TEXCOORD0;
            };

            struct Varyings
            {
                float4 positionHCS : SV_POSITION;
                float3 normalWS : TEXCOORD0;
                float2 uv : TEXCOORD1;
            };

            CBUFFER_START(UnityPerMaterial)
                float4 _BaseMap_ST;
                float4 _BaseColor;
            CBUFFER_END
            TEXTURE2D(_BaseMap);
            SAMPLER(sampler_BaseMap);

            Varyings vert(Attributes input)
            {
                Varyings output;
                output.positionHCS=TransformObjectToHClip(input.positionOS.xyz);
                output.normalWS=TransformObjectToWorldNormal(input.normalOS);
                output.uv=TRANSFORM_TEX(input.uv,_BaseMap);
                return output;
            }

            half4 frag(Varyings input) : SV_Target
            {
                Light mainLight=GetMainLight();
                half ndotl=saturate(dot(normalize(input.normalWS),mainLight.direction));
                half lighting=.70h+.30h*ndotl;
                // 벤더 RGB 맵은 다발의 음영을 담는다 — 염색 색과 곱해 결을 살린다.
                half3 detail=SAMPLE_TEXTURE2D(_BaseMap,sampler_BaseMap,input.uv).rgb;
                return half4(detail*_BaseColor.rgb*lighting,1.0h);
            }
            ENDHLSL
        }
    }
}
