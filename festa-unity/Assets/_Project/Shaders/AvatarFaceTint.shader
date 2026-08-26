Shader "Festa/Avatar/FaceTint"
{
    Properties
    {
        _BaseMap("Face Detail Mask", 2D) = "white" {}
        _SkinColor("Skin Color", Color) = (1,0.8,0.69,1)
        _LipColor("Lip Tint", Color) = (0.55,0.18,0.22,1)
        _EyebrowColor("Eyebrow Color", Color) = (0.12,0.08,0.06,1)
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

            struct Attributes { float4 positionOS : POSITION; float3 normalOS : NORMAL; float2 uv : TEXCOORD0; };
            struct Varyings { float4 positionHCS : SV_POSITION; float3 normalWS : TEXCOORD0; float2 uv : TEXCOORD1; };
            TEXTURE2D(_BaseMap); SAMPLER(sampler_BaseMap);
            CBUFFER_START(UnityPerMaterial)
                float4 _BaseMap_ST;
                float4 _SkinColor;
                float4 _LipColor;
                float4 _EyebrowColor;
            CBUFFER_END

            Varyings vert(Attributes input)
            {
                Varyings output;
                output.positionHCS=TransformObjectToHClip(input.positionOS.xyz);
                output.normalWS=TransformObjectToWorldNormal(input.normalOS);
                output.uv=TRANSFORM_TEX(input.uv,_BaseMap);
                return output;
            }

            half EllipseMask(half2 uv, half2 center, half2 radius)
            {
                return 1.0h-smoothstep(.72h,1.0h,length((uv-center)/radius));
            }

            half TaperedLipMask(half2 uv, half yMin, half yPeak, half yMax,
                half bottomHalfWidth, half peakHalfWidth, half topHalfWidth)
            {
                half t=saturate((uv.y-yMin)/max(.0001h,yMax-yMin));
                half peakT=saturate((yPeak-yMin)/max(.0001h,yMax-yMin));
                half lowerWidth=lerp(bottomHalfWidth,peakHalfWidth,t/max(.0001h,peakT));
                half upperWidth=lerp(peakHalfWidth,topHalfWidth,(t-peakT)/max(.0001h,1.0h-peakT));
                half halfWidth=t<peakT?lowerWidth:upperWidth;
                half vertical=smoothstep(yMin,yMin+.0015h,uv.y)*(1.0h-smoothstep(yMax-.0015h,yMax,uv.y));
                half horizontal=1.0h-smoothstep(halfWidth-.0015h,halfWidth+.0005h,abs(uv.x-.500h));
                return saturate(vertical*horizontal);
            }

            half4 frag(Varyings input) : SV_Target
            {
                half3 detailMask=SAMPLE_TEXTURE2D(_BaseMap,sampler_BaseMap,input.uv).rgb;
                // Pixel-measured silhouettes for the two stacked lip UV islands.
                // Unlike a contrast threshold, these masks cannot leak into the
                // surrounding philtrum or chin-shadow pixels.
                half upperLip=TaperedLipMask(input.uv,.2690h,.2750h,.2940h,.0080h,.0250h,.0030h);
                half lowerLip=TaperedLipMask(input.uv,.2490h,.2630h,.2700h,.0030h,.0220h,.0080h);
                half lipMask=max(upperLip,lowerLip);
                half leftBrow=EllipseMask(input.uv,half2(.365h,.433h),half2(.074h,.020h));
                half rightBrow=EllipseMask(input.uv,half2(.635h,.433h),half2(.074h,.020h));
                half browDetail=smoothstep(.025h,.22h,1.0h-detailMask.r);
                half browMask=saturate(max(leftBrow,rightBrow)*browDetail);
                half3 baseColor=lerp(_SkinColor.rgb,_EyebrowColor.rgb,browMask);
                half3 naturalLipTint=lerp(_SkinColor.rgb,_LipColor.rgb,.50h);
                baseColor=lerp(baseColor,naturalLipTint,lipMask*.70h);
                half detail=lerp(.84h,1.03h,saturate(detailMask.r));
                Light mainLight=GetMainLight();
                half ndotl=saturate(dot(normalize(input.normalWS),mainLight.direction));
                half lighting=.70h+.30h*ndotl;
                return half4(baseColor*detail*lighting,1.0h);
            }
            ENDHLSL
        }
    }
}
