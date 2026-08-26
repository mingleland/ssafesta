Shader "Festa/Avatar/GarmentTint"
{
    Properties
    {
        _BaseMap("RGB Mask", 2D) = "white" {}
        _BumpMap("Normal Map", 2D) = "bump" {}
        _Color_A_1("Area A Dark", Color) = (.12,.12,.16,1)
        _Color_A_2("Area A Light", Color) = (.55,.55,.65,1)
        _Color_B_1("Area B Dark", Color) = (.12,.12,.16,1)
        _Color_B_2("Area B Light", Color) = (.55,.55,.65,1)
        _Color_C_1("Area C Dark", Color) = (.12,.12,.16,1)
        _Color_C_2("Area C Light", Color) = (.55,.55,.65,1)
        _MaskRemap("Mask Remap", Vector) = (.2,1,0,0)
        _Mask_Factor("Mask Factor", Range(0.001,1)) = .65
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
            #include "Packages/com.unity.render-pipelines.core/ShaderLibrary/Packing.hlsl"

            struct Attributes
            {
                float4 positionOS : POSITION;
                float3 normalOS : NORMAL;
                float4 tangentOS : TANGENT;
                float2 uv : TEXCOORD0;
            };

            struct Varyings
            {
                float4 positionHCS : SV_POSITION;
                float3 normalWS : TEXCOORD0;
                float3 tangentWS : TEXCOORD1;
                float3 bitangentWS : TEXCOORD2;
                float2 uv : TEXCOORD3;
            };

            TEXTURE2D(_BaseMap); SAMPLER(sampler_BaseMap);
            TEXTURE2D(_BumpMap); SAMPLER(sampler_BumpMap);
            CBUFFER_START(UnityPerMaterial)
                float4 _BaseMap_ST;
                float4 _Color_A_1, _Color_A_2;
                float4 _Color_B_1, _Color_B_2;
                float4 _Color_C_1, _Color_C_2;
                float4 _MaskRemap;
                float _Mask_Factor;
            CBUFFER_END

            Varyings vert(Attributes input)
            {
                Varyings output;
                output.positionHCS=TransformObjectToHClip(input.positionOS.xyz);
                output.normalWS=TransformObjectToWorldNormal(input.normalOS);
                output.tangentWS=TransformObjectToWorldDir(input.tangentOS.xyz);
                output.bitangentWS=cross(output.normalWS,output.tangentWS)*input.tangentOS.w*GetOddNegativeScale();
                output.uv=TRANSFORM_TEX(input.uv,_BaseMap);
                return output;
            }

            half4 frag(Varyings input) : SV_Target
            {
                half3 mask=SAMPLE_TEXTURE2D(_BaseMap,sampler_BaseMap,input.uv).rgb;
                half remapRange=max(.0001h,_MaskRemap.y-_MaskRemap.x);
                half3 remapped=saturate((mask-_MaskRemap.x)/remapRange);

                // Exact ordering used by the vendor color-customization subgraph:
                // red builds A, green replaces it with B, then blue replaces it
                // with C. Each region uses its own channel for the dark/light
                // gradient, so yellow/white mask pixels never average two areas.
                half3 areaA=lerp(_Color_A_1.rgb,_Color_A_2.rgb,remapped.r);
                half3 areaB=lerp(_Color_B_1.rgb,_Color_B_2.rgb,remapped.g);
                half3 areaC=lerp(_Color_C_1.rgb,_Color_C_2.rgb,remapped.b);
                half gateA=smoothstep(0.0h,_Mask_Factor,mask.r);
                half gateB=smoothstep(0.0h,_Mask_Factor,mask.g);
                half gateC=smoothstep(0.0h,_Mask_Factor,mask.b);
                half3 baseColor=lerp(_Color_A_1.rgb,areaA,gateA);
                baseColor=lerp(baseColor,areaB,gateB);
                baseColor=lerp(baseColor,areaC,gateC);

                half3 normalTS=UnpackNormal(SAMPLE_TEXTURE2D(_BumpMap,sampler_BumpMap,input.uv));
                half3 normalWS=normalize(normalTS.x*normalize(input.tangentWS)+normalTS.y*normalize(input.bitangentWS)+normalTS.z*normalize(input.normalWS));
                Light mainLight=GetMainLight();
                half ndotl=saturate(dot(normalWS,mainLight.direction));
                half lighting=.62h+.38h*ndotl;
                return half4(baseColor*lighting,1.0h);
            }
            ENDHLSL
        }
    }
}
