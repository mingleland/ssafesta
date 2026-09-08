using System.Collections.Generic;
using UnityEngine;

namespace Festa.Diagnostics
{
    /// <summary>
    /// 진단 도구 단축키의 <b>단일 등록처</b>. 각 도구가 자기 키를 여기에 신고하면,
    /// 중복 배정과 브라우저 예약키를 <b>스스로 잡아서</b> 에러 로그로 드러낸다.
    ///
    /// <para><b>왜 만들었나 (2026-09-08).</b> 키 충돌로 실측을 두 번 통째로 날렸다.
    /// 한 번은 F9 가 RenderCostProbe 스윕과 PerfHud 아바타 LOD 에 겹쳐서 스윕을 돌릴 때마다
    /// LOD 가 토글됐고, 한 번은 F2 를 히스토그램에 배정했는데 <c>DevConnectionHud</c> 가
    /// <b>신규 Input System</b>(<c>kb.f2Key</c>)으로 같은 키를 이미 쓰고 있어서 개발 패널이 열리고
    /// 씬이 다시 로드됐다. 두 번 다 "grep 으로 <c>KeyCode.F&lt;n&gt;</c> 만 찾아본" 탓이다 —
    /// 이 프로젝트는 레거시 <c>Input</c> 과 신규 <c>Keyboard.current</c> 가 공존하고
    /// <b>같은 물리 키가 양쪽에 다 들어간다.</b> 사람이 매번 두 체계를 다 뒤지는 것에 의존하면 또 틀린다.</para>
    ///
    /// <para><b>규칙 1 — 진단에는 함수키를 쓰지 않는다.</b> WebGL 이 주 배포 대상인데
    /// F1(도움말)·F3(찾기)·F5(새로고침)·F6(툴바)·F7(캐럿)·F11(전체화면)·F12(개발자도구)가
    /// 브라우저 예약이다. F11·F12 는 <c>preventDefault</c> 로도 못 막는다.
    /// 남는 F4·F8·F9·F10 도 OS/확장에 따라 잡히므로 통째로 뺐다.
    /// 진단 키는 <b>구두점 키</b>에서만 고른다 — 게임플레이(F·C·H·Space·Esc·Alt)와도 겹치지 않는다.</para>
    ///
    /// <para><b>규칙 2 — 신규 Input System 쪽도 신고한다.</b> <c>Keyboard.current.xKey</c> 로 읽는 곳도
    /// <see cref="ClaimExternal"/> 로 등록해야 중복 검사가 의미를 갖는다.</para>
    /// </summary>
    public static class DiagnosticKeys
    {
        static readonly Dictionary<KeyCode, string> Owners = new();

        /// <summary>WebGL 에서 브라우저가 가져가는 키. F11·F12 는 preventDefault 로도 못 막는다.</summary>
        static readonly HashSet<KeyCode> BrowserReserved = new()
        {
            KeyCode.F1, KeyCode.F3, KeyCode.F4, KeyCode.F5, KeyCode.F6, KeyCode.F7, KeyCode.F8, KeyCode.F9, KeyCode.F10, KeyCode.F11, KeyCode.F12,
            // F4 Alt+F4 / F8·F9·F10 은 브라우저·OS 메뉴바가 먹는 경우가 있어 예약으로 본다 (QA 2026-09-08 #64)
        };

        /// <summary>
        /// 키를 등록하고 그대로 돌려준다. 중복이거나 브라우저 예약키면 <b>에러</b>를 남긴다 —
        /// 경고가 아니라 에러다. 조용히 넘어가면 다음 사람이 같은 함정을 밟는다 (T-24 원칙).
        /// </summary>
        public static KeyCode Claim(string owner, KeyCode key)
        {
            if (key == KeyCode.None) return key;

            if (Owners.TryGetValue(key, out var existing) && existing != owner)
            {
                Debug.LogError($"[DiagnosticKeys] 키 중복 — {key} 를 '{existing}' 와 '{owner}' 가 함께 쓴다. " +
                               "한 번 누르면 둘 다 실행되어 측정이 오염된다. 둘 중 하나를 구두점 키로 옮겨라.");
                return key;
            }

            Owners[key] = owner;

            // 에디터·개발 빌드에서도 검사한다 — WebGL 이 주 배포 대상이라 배정 시점에 바로 드러나야 한다 (QA #64)
            if (BrowserReserved.Contains(key))
                Debug.LogError($"[DiagnosticKeys] {key} 는 브라우저 예약키다 ('{owner}' 가 요청). " +
                               "누르면 브라우저 기능이 먼저 동작해 테스트가 날아간다. 구두점 키로 옮겨라.");

            return key;
        }

        /// <summary>
        /// 진단 도구가 아닌 곳(게임플레이·개발 HUD)이 이미 쓰고 있는 키를 등록한다.
        /// 신규 Input System 으로 읽는 키도 여기로 넣어야 중복 검사가 성립한다.
        /// </summary>
        public static void ClaimExternal(string owner, KeyCode key) => Claim(owner, key);

        /// <summary>현재 배정표. 도구가 "무슨 키였더라" 를 물어볼 때 쓴다.</summary>
        public static string Dump()
        {
            var sb = new System.Text.StringBuilder("[DiagnosticKeys] 배정표\n");
            foreach (var kv in Owners) sb.AppendLine($"  {kv.Key,-14} {kv.Value}");
            return sb.ToString();
        }

        /// <summary>
        /// 진단 도구가 아닌 사용처를 미리 등록한다. 이 목록이 있어야 진단 키가 게임플레이와
        /// 겹칠 때 바로 잡힌다. 신규 Input System 으로 읽는 곳도 전부 넣는다.
        /// </summary>
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void RegisterNonDiagnosticUsers()
        {
            ClaimExternal("DevConnectionHud(신규 IS f2Key)", KeyCode.F2);
            ClaimExternal("PlayerMovement 점프(신규 IS spaceKey)", KeyCode.Space);
            ClaimExternal("AvatarCustomizationHud(신규 IS cKey)", KeyCode.C);
            ClaimExternal("PortalInteractor·부스 상호작용(신규 IS fKey)", KeyCode.F);
            ClaimExternal("InteractionFocusCamera 닫기(신규 IS escapeKey)", KeyCode.Escape);
            ClaimExternal("ControlsHintHud(신규 IS hKey)", KeyCode.H);
        }
    }
}
