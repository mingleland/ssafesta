Shader "Festa/Avatar/SkinTint"
{
    Properties
    {
        _BaseMap("Body Region Mask", 2D) = "red" {}
        _SkinColor("Skin Color", Color) = (1,0.8,0.69,1)
        _UnderwearColor("Underwear Color", Color) = (0.015,0.015,0.02,1)
    }
    SubShader
    {
        Tags { "RenderType"="Opaque" "RenderPipeline"="UniversalPipeline" "Queue"="Geometry" }
        Pass
        {
            Name "ForwardLit"
            Tags { "LightMode"="UniversalForward" }
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
                float4 _SkinColor;
                float4 _UnderwearColor;
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
                // Vendor body RGB map: skin is red, underwear adds the green
                // channel. Keep underwear independent from the customizable
                // skin color so changing skin never recolors it.
                half3 regionMask=SAMPLE_TEXTURE2D(_BaseMap,sampler_BaseMap,input.uv).rgb;
                half underwearMask=smoothstep(.18h,.62h,regionMask.g);
                half3 baseColor=lerp(_SkinColor.rgb,_UnderwearColor.rgb,underwearMask);
                return half4(baseColor*lighting,1.0h);
            }
            ENDHLSL
        }
    }
}
