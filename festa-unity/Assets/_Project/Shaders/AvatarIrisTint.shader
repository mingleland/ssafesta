Shader "Festa/Avatar/IrisTint"
{
    Properties
    {
        _BaseMap("Eye Texture", 2D) = "white" {}
        _IrisColor("Iris Color", Color) = (0.25,0.55,0.85,1)
        _ScleraColor("Sclera Color", Color) = (1,1,1,1)
        _PupilColor("Pupil Color", Color) = (0.025,0.018,0.02,1)
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
            struct Varyings { float4 positionHCS : SV_POSITION; float2 uv : TEXCOORD0; half eyeSide : TEXCOORD1; };
            TEXTURE2D(_BaseMap); SAMPLER(sampler_BaseMap);
            CBUFFER_START(UnityPerMaterial)
                float4 _BaseMap_ST;
                float4 _IrisColor;
                float4 _ScleraColor;
                float4 _PupilColor;
            CBUFFER_END
            Varyings vert(Attributes input)
            {
                Varyings output;
                output.positionHCS=TransformObjectToHClip(input.positionOS.xyz);
                output.uv=TRANSFORM_TEX(input.uv,_BaseMap);
                output.eyeSide=input.positionOS.x>=0.0?1.0h:-1.0h;
                return output;
            }
            half4 frag(Varyings input) : SV_Target
            {
                half4 eye=SAMPLE_TEXTURE2D(_BaseMap,sampler_BaseMap,input.uv);
                // The vendor sphere UVs are shared by both eyes. Their visual centre
                // is slightly offset from the texture-space pole, so use the measured
                // eye-opening centre rather than the raw material-mask centre.
                // Increasing U moves the mirrored UV islands away from the nose on
                // both eyeballs, keeping the gaze parallel instead of cross-eyed.
                half2 eyeUv=(input.uv-half2(.445h,.440h))/half2(.185h,.285h);
                half radius=length(eyeUv);
                half irisMask=1.0h-smoothstep(.82h,1.02h,radius);
                half pupilMask=1.0h-smoothstep(.30h,.42h,radius);
                half limbalRing=smoothstep(.70h,.92h,radius)*(1.0h-smoothstep(.94h,1.02h,radius));
                half angle=atan2(eyeUv.y,eyeUv.x);
                half irisRays=.88h+.10h*sin(angle*18.0h+radius*12.0h);
                half3 sclera=_ScleraColor.rgb*lerp(.82h,1.0h,saturate(1.25h-radius*.18h));
                half3 iris=_IrisColor.rgb*irisRays;
                iris=lerp(iris,iris*.42h,limbalRing);
                half3 pupil=_PupilColor.rgb*lerp(.72h,.94h,saturate(radius/.42h));
                iris=lerp(iris,pupil,pupilMask);
                half3 eyeColor=lerp(sclera,iris,irisMask);
                // Keep the catchlight attached to the pupil and on the same
                // screen-side for both mirrored eyes.
                half2 catchlightCenter=half2(input.eyeSide*.14h,.16h);
                half catchlight=(1.0h-smoothstep(.065h,.115h,length(eyeUv-catchlightCenter)))*pupilMask;
                eyeColor=lerp(eyeColor,half3(1.0h,.98h,.94h),catchlight);
                return half4(eyeColor,eye.a);
            }
            ENDHLSL
        }
    }
}
