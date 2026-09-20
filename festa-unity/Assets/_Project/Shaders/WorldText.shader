// 월드 공간 TextMesh 용 폰트 셰이더.
//
// 내장 GUI/Text Shader 는 ZTest Always 라 벽 너머에서도 글자가 그려진다 —
// 내부 부스 표지가 축제 담장을 뚫고 보이던 원인. 이 셰이더는 ZTest LEqual 로
// 일반 지오메트리처럼 가려진다. 언릿 CG 패스라 URP 에서도 렌더링된다.
Shader "Festa/WorldText"
{
    Properties
    {
        _MainTex ("Font Texture", 2D) = "white" {}
        _Color ("Text Color", Color) = (1,1,1,1)
    }
    SubShader
    {
        Tags { "Queue"="Transparent" "RenderType"="Transparent" "IgnoreProjector"="True" }
        Cull Off
        ZWrite Off
        ZTest LEqual
        Blend SrcAlpha OneMinusSrcAlpha

        Pass
        {
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #include "UnityCG.cginc"

            sampler2D _MainTex;
            float4 _MainTex_ST;
            fixed4 _Color;

            struct v2f
            {
                float4 pos : SV_POSITION;
                float2 uv : TEXCOORD0;
                fixed4 color : COLOR;
            };

            v2f vert(appdata_full v)
            {
                v2f o;
                o.pos = UnityObjectToClipPos(v.vertex);
                o.uv = TRANSFORM_TEX(v.texcoord, _MainTex);
                o.color = v.color * _Color;   // TextMesh 는 정점색으로 글자색을 전달한다
                return o;
            }

            fixed4 frag(v2f i) : SV_Target
            {
                fixed a = tex2D(_MainTex, i.uv).a;
                return fixed4(i.color.rgb, i.color.a * a);
            }
            ENDCG
        }
    }
}
