Shader "Festa/Avatar/MouthTint"
{
    Properties
    {
        _BaseMap("Mouth RGB Mask", 2D) = "white" {}
        _LipColor("Lip Color", Color) = (0.55,0.18,0.22,1)
        _InnerMouthColor("Inner Mouth Color", Color) = (0.24,0.035,0.055,1)
        _TeethColor("Teeth Color", Color) = (0.92,0.88,0.82,1)
    }
    SubShader
    {
        Tags { "RenderType"="Opaque" "RenderPipeline"="UniversalPipeline" "Queue"="Geometry" }
        Pass
        {
            Name "ForwardUnlit"
            Tags { "LightMode"="UniversalForward" }
            HLSLPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"

            struct Attributes { float4 positionOS : POSITION; float2 uv : TEXCOORD0; };
            struct Varyings { float4 positionHCS : SV_POSITION; float2 uv : TEXCOORD0; };
            TEXTURE2D(_BaseMap); SAMPLER(sampler_BaseMap);
            CBUFFER_START(UnityPerMaterial)
                float4 _BaseMap_ST;
                float4 _LipColor;
                float4 _InnerMouthColor;
                float4 _TeethColor;
            CBUFFER_END

            Varyings vert(Attributes input)
            {
                Varyings output;
                output.positionHCS=TransformObjectToHClip(input.positionOS.xyz);
                output.uv=TRANSFORM_TEX(input.uv,_BaseMap);
                return output;
            }

            half4 frag(Varyings input) : SV_Target
            {
                half3 mask=SAMPLE_TEXTURE2D(_BaseMap,sampler_BaseMap,input.uv).rgb;
                half3 weights=max(mask,half3(0.001h,0.001h,0.001h));
                weights/=weights.r+weights.g+weights.b;
                half3 color=weights.r*_LipColor.rgb+weights.g*_InnerMouthColor.rgb+weights.b*_TeethColor.rgb;
                half shade=.78h+.22h*saturate(max(mask.r,max(mask.g,mask.b)));
                return half4(color*shade,1.0h);
            }
            ENDHLSL
        }
    }
}
