using System.Collections.Generic;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 부스 출입 포털. 외부 부스 앞과 내부 공간 출구에 하나씩 놓이며,
    /// <see cref="PortalInteractor"/> 가 근접한 포털의 프롬프트를 띄우고 F 로 이동시킨다.
    ///
    /// 정적 씬 오브젝트다 — NetworkObject 를 붙이지 않는다 (아키텍처 원칙: Booth 정적
    /// 오브젝트는 Local Spawn). 이동 자체는 client-authoritative NetworkTransform 이
    /// 동기화하므로 다른 접속자에게도 이동 결과가 그대로 보인다.
    ///
    /// boothId 는 외부 FestivalSlot_XX ↔ 내부 Interior_XX 를 잇는 번호로,
    /// 백엔드 연동 시 이 번호를 부스 식별자로 쓴다.
    /// </summary>
    public class BoothPortal : MonoBehaviour
    {
        public static readonly List<BoothPortal> All = new();

        [Tooltip("외부 슬롯·내부 공간 공통 번호 (1~12). 백엔드 부스 식별자와 1:1.")]
        public int boothId;

        [Tooltip("F 를 눌렀을 때 이동할 지점")]
        public Transform destination;

        [Tooltip("프롬프트에 표시할 행동 문구 (예: '3번 부스 입장')")]
        public string promptText;

        [Tooltip("프롬프트가 뜨는 반경 (world unit, 1 m = 10)")]
        public float interactRadius = 30f;

        void OnEnable() => All.Add(this);
        void OnDisable() => All.Remove(this);
    }
}
