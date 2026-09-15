using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 축제장 미니맵이 담는 <b>월드 범위</b>. 굽는 쪽(<c>FestaMinimapBaker</c>)과 그리는 쪽
    /// (<see cref="FestivalMapOverlay"/>)이 <b>반드시 같은 값</b>을 써야 한다 — 다르면 배경 그림과
    /// 부스 카드·내 위치가 서로 다른 자리에 찍힌다.
    ///
    /// <para>그래서 상수를 에디터 스크립트에 두지 않고 여기(런타임)에 뒀다. 에디터 쪽에 두면
    /// 런타임에서 참조할 수 없어 같은 숫자를 두 번 적게 되고, 한쪽만 고치는 날 조용히 어긋난다.</para>
    ///
    /// <para><b>범위.</b> 벽 안쪽은 x −930~−220 · z −40~330 인데 부스는 x −875~−300 에만 있다.
    /// 세로를 벽 끝까지 담으면 그림이 370:710 로 길쭉해지고, 같은 높이에서 가로가 좁아져 부스
    /// 카드가 서로 겹친다. 반대로 너무 좁히면 양 끝 줄 카드가 그림 <b>테두리를 밟는다</b> —
    /// 640 으로 잡았을 때 실제로 11~18 px 씩 걸쳤다(2026-09-10 지적). 690 이 두 조건을 다 만족한다:
    /// 카드 사이 56 px 이 남고 끝 줄도 그림 안에 완전히 들어온다. 가로(z)는 그대로다 — 좁히면
    /// 오히려 더 길쭉해진다.</para>
    /// </summary>
    public static class FestivalMinimapArea
    {
        public const float MinX = -930f, MaxX = -240f;
        public const float MinZ = -40f, MaxZ = 330f;

        /// <summary>담는 그림의 가로:세로 비. 세로가 월드 x, 가로가 월드 z 다.</summary>
        public static float Aspect => (MaxZ - MinZ) / (MaxX - MinX);

        /// <summary>미니맵에서 쓰는 텍스처 경로 (Resources 기준).</summary>
        public const string ResourcePath = "UI/FestivalMinimap";

        /// <summary>
        /// 월드 좌표 → 0~1 비율. <b>x 는 뒤집는다</b> — 사람은 동쪽(입구)에서 서쪽(안쪽)으로
        /// 걸으므로 화면 위쪽이 월드 −x 여야 걸어 들어갈 때 점이 올라간다.
        /// 굽는 카메라의 up 을 (−1,0,0) 으로 준 것과 같은 규약이다.
        /// </summary>
        public static Vector2 Normalized(Vector3 world)
        {
            float u = Mathf.InverseLerp(MinZ, MaxZ, world.z);          // 0 남쪽 줄 … 1 북쪽 줄
            float v = 1f - Mathf.InverseLerp(MinX, MaxX, world.x);     // 0 입구(아래) … 1 안쪽(위)
            return new Vector2(u, v);
        }
    }
}
