using System.Collections.Generic;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 재질 그룹(서브메시)을 보존하는 데시메이터.
    ///
    /// S15P21A604-281 의 데시메이션은 서브메시 5개를 1개로 뭉개서, 렌더러의 재질 슬롯
    /// 1~4(거울·알루미늄·doorlock-gray·black)가 그릴 게 없어졌다. 사물함 자물쇠가
    /// 화면에서 통째로 사라진 원인이다. 정점 수만 보고 판정하면 이걸 놓친다.
    ///
    /// 여기서는 **서브메시마다 독립적으로** 정점 클러스터링을 돌린다. 그룹 간 정점을
    /// 섞지 않으므로 서브메시 개수와 순서가 그대로 유지된다.
    /// </summary>
    public static class SubmeshPreservingDecimator
    {
        /// <summary>서브메시별 목표 삼각형 수를 정하는 비율. 작은 그룹은 MinTriangles 로 보호한다.</summary>
        const int MinTriangles = 150;
        const int SearchIterations = 20;

        public static Mesh Decimate(Mesh src, float ratio)
        {
            var srcVerts = src.vertices;
            var srcUv = src.uv;
            var srcUv2 = src.uv2;
            bool hasUv = srcUv != null && srcUv.Length == srcVerts.Length;
            bool hasUv2 = srcUv2 != null && srcUv2.Length == srcVerts.Length;

            var outVerts = new List<Vector3>();
            var outUv = new List<Vector2>();
            var outUv2 = new List<Vector2>();
            var outIndices = new List<int[]>();

            for (int s = 0; s < src.subMeshCount; s++)
            {
                var tris = src.GetTriangles(s);
                int target = Mathf.Max(MinTriangles, Mathf.RoundToInt(tris.Length / 3f * ratio));

                // 이 서브메시만의 바운즈로 셀 크기 범위를 잡는다 — 그룹마다 크기가 천차만별이다.
                var bounds = SubmeshBounds(srcVerts, tris);
                float lo = 0f, hi = bounds.size.magnitude * 0.25f;
                if (hi <= 0f) hi = 0.001f;

                List<Vector3> bestV = null; List<Vector2> bestU = null, bestU2 = null; int[] bestT = null;

                // 셀 크기를 이분 탐색해 목표 삼각형 수에 맞춘다. 클수록 많이 줄어든다.
                for (int it = 0; it < SearchIterations; it++)
                {
                    float cell = (lo + hi) * 0.5f;
                    List<Vector3> v; List<Vector2> u, u2; int[] t;
                    ClusterSubmesh(srcVerts, srcUv, srcUv2, hasUv, hasUv2, tris, cell, out v, out u, out u2, out t);

                    int triCount = t.Length / 3;
                    if (bestT == null || Mathf.Abs(triCount - target) < Mathf.Abs(bestT.Length / 3 - target))
                    {
                        bestV = v; bestU = u; bestU2 = u2; bestT = t;
                    }
                    if (triCount > target) lo = cell; else hi = cell;
                }

                // 원본보다 늘어날 수는 없다. 목표를 못 맞추면 원본을 그대로 쓴다.
                if (bestT == null || bestT.Length >= tris.Length)
                {
                    List<Vector3> v; List<Vector2> u, u2; int[] t;
                    ClusterSubmesh(srcVerts, srcUv, srcUv2, hasUv, hasUv2, tris, 0f, out v, out u, out u2, out t);
                    bestV = v; bestU = u; bestU2 = u2; bestT = t;
                }

                int offset = outVerts.Count;
                outVerts.AddRange(bestV);
                if (hasUv) outUv.AddRange(bestU);
                if (hasUv2) outUv2.AddRange(bestU2);
                var shifted = new int[bestT.Length];
                for (int i = 0; i < bestT.Length; i++) shifted[i] = bestT[i] + offset;
                outIndices.Add(shifted);
            }

            var mesh = new Mesh();
            mesh.name = src.name + "_submeshDec";
            mesh.indexFormat = outVerts.Count > 65000
                ? UnityEngine.Rendering.IndexFormat.UInt32
                : UnityEngine.Rendering.IndexFormat.UInt16;
            mesh.SetVertices(outVerts);
            if (hasUv) mesh.SetUVs(0, outUv);
            if (hasUv2) mesh.SetUVs(1, outUv2);
            mesh.subMeshCount = outIndices.Count;
            for (int s = 0; s < outIndices.Count; s++) mesh.SetTriangles(outIndices[s], s, true);
            mesh.RecalculateNormals();
            mesh.RecalculateTangents();
            mesh.RecalculateBounds();
            return mesh;
        }

        static Bounds SubmeshBounds(Vector3[] verts, int[] tris)
        {
            if (tris.Length == 0) return new Bounds();
            var b = new Bounds(verts[tris[0]], Vector3.zero);
            for (int i = 0; i < tris.Length; i++) b.Encapsulate(verts[tris[i]]);
            return b;
        }

        /// <summary>셀 크기 0 이면 병합 없이 인덱스만 재배열한다 (원본 보존 경로).</summary>
        static void ClusterSubmesh(
            Vector3[] verts, Vector2[] uv, Vector2[] uv2, bool hasUv, bool hasUv2,
            int[] tris, float cell,
            out List<Vector3> outVerts, out List<Vector2> outUv, out List<Vector2> outUv2, out int[] outTris)
        {
            outVerts = new List<Vector3>();
            outUv = new List<Vector2>();
            outUv2 = new List<Vector2>();

            var map = new Dictionary<long, int>();
            var local = new Dictionary<int, int>();
            float inv = cell > 0f ? 1f / cell : 0f;

            for (int i = 0; i < tris.Length; i++)
            {
                int src = tris[i];
                if (local.ContainsKey(src)) continue;

                int slot;
                if (cell <= 0f)
                {
                    slot = outVerts.Count;
                    outVerts.Add(verts[src]);
                    if (hasUv) outUv.Add(uv[src]);
                    if (hasUv2) outUv2.Add(uv2[src]);
                }
                else
                {
                    var p = verts[src];
                    long kx = (long)Mathf.Floor(p.x * inv);
                    long ky = (long)Mathf.Floor(p.y * inv);
                    long kz = (long)Mathf.Floor(p.z * inv);
                    long key = (kx * 73856093L) ^ (ky * 19349663L) ^ (kz * 83492791L);
                    if (!map.TryGetValue(key, out slot))
                    {
                        slot = outVerts.Count;
                        map[key] = slot;
                        // 셀 중심으로 스냅하면 표면이 계단처럼 뭉개진다. 첫 정점을 대표로 쓴다.
                        outVerts.Add(p);
                        if (hasUv) outUv.Add(uv[src]);
                        if (hasUv2) outUv2.Add(uv2[src]);
                    }
                }
                local[src] = slot;
            }

            var keep = new List<int>(tris.Length);
            for (int i = 0; i + 2 < tris.Length; i += 3)
            {
                int a = local[tris[i]], b = local[tris[i + 1]], c = local[tris[i + 2]];
                if (a == b || b == c || a == c) continue;   // 병합으로 찌그러진 삼각형은 버린다
                keep.Add(a); keep.Add(b); keep.Add(c);
            }
            outTris = keep.ToArray();
        }
    }
}
