// 월드 말풍선 배경판 (GitLab #242).
//
// 왜 셰이더가 따로 필요한가: 글자는 WorldText 오버레이 셰이더로 **벽 위에** 그려진다(ZTest Always).
// 배경판이 보통 재질이면 같은 자리에서 벽에 가려져, 글자만 뜨고 판은 반쯤 잘리는 그림이 된다.
// 그래서 판도 같은 깊이 규칙을 쓴다.
//
// Resources/ 아래에 두는 이유: Shader.Find 는 빌드에 포함된 셰이더만 찾는다. 재질로 미리 참조되지
// 않는 셰이더는 Resources 에 있어야 WebGL 빌드에 들어간다 (FestaOutline.shader 와 같은 이유, T-227).
Shader "Festa/WorldChatPanel"
{
    Properties
    {
        _Color ("Panel Color", Color) = (0.055, 0.075, 0.115, 0.88)
    }
    SubShader
    {
        Tags { "RenderType" = "Transparent" "RenderPipeline" = "UniversalPipeline" "Queue" = "Transparent" }

        Pass
        {
            Name "ChatPanel"
            Blend SrcAlpha OneMinusSrcAlpha
            ZWrite Off
            ZTest Always      // 글자와 같은 규칙 — 벽에 반쯤 잘리지 않는다
            Cull Off

            HLSLPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"

            struct Attributes
            {
                float4 positionOS : POSITION;
                float4 color      : COLOR;
            };

            struct Varyings
            {
                float4 positionHCS : SV_POSITION;
                float4 color       : COLOR;
            };

            CBUFFER_START(UnityPerMaterial)
                float4 _Color;
            CBUFFER_END

            Varyings vert(Attributes IN)
            {
                Varyings OUT;
                OUT.positionHCS = TransformObjectToHClip(IN.positionOS.xyz);
                OUT.color = IN.color;
                return OUT;
            }

            // 정점 알파로 가장자리를 부드럽게 만든다 — 메시가 이미 둥근 사각형이라
            // 여기서는 색과 투명도만 곱한다. 테두리 한 겹은 메시 쪽에서 알파 0 으로 둘러 준다.
            half4 frag(Varyings IN) : SV_Target
            {
                return half4(_Color.rgb, _Color.a * IN.color.a);
            }
            ENDHLSL
        }
    }
    Fallback Off
}
