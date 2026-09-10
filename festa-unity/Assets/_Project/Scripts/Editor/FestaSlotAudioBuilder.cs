using System;
using System.IO;
using UnityEditor;
using UnityEngine;
using ithappy.Casino;

namespace Festa.EditorTools
{
    /// <summary>
    /// 슬롯머신의 <b>돌아가는 소리</b>와 <b>릴 멈추는 소리</b>를 만들어 벤더 컴포넌트에 배선한다.
    ///
    /// <para><b>왜 만들어야 하나.</b> 벤더 킷(<c>ithappy/Casino_Free</c>)에 들어 있는 음원은
    /// 당첨 차임 두 개뿐이고, <see cref="PresetUVSlotMachine"/> 의 <c>spinAudioSource</c>·
    /// <c>reelStopAudioSource</c> 는 씬에서 <b>비어 있었다.</b> 그래서 릴이 다 멈춘 뒤에야
    /// 당첨 차임 하나가 울렸고, 그게 "슬롯머신 음악이 다 돌아간 다음에 나와서 이상하다" 로,
    /// 이어서 "그냥 안 나오게 바꿨네" 로 읽혔다 (2026-09-10). 소리를 끈 적은 없고 <b>처음부터 없었다.</b></para>
    ///
    /// <para><b>왜 합성인가.</b> 프로젝트에 릴 소리로 쓸 음원이 없다. 실물 슬롯의 스핀음은
    /// 사실상 <b>빠른 딸깍 소리의 연속</b>이라 합성이 잘 먹는다 — 외부 에셋을 새로 들이는 것보다
    /// 가볍고(둘 합쳐 60 KB 남짓), 라이선스 문제도 없다.</para>
    ///
    /// <para>루프는 <b>틱 간격의 정수 배</b> 길이로 끊는다 — 그래야 이어 붙는 지점에서 박자가 튀지 않는다.</para>
    /// </summary>
    public static class FestaSlotAudioBuilder
    {
        const string OutDir = "Assets/_Project/Audio/Generated";
        const int SampleRate = 44100;

        const float TickInterval = 0.055f;   // 18 Hz — 실물 릴이 내는 딸깍 간격
        const int TickCount = 9;             // 0.495 초 루프

        [MenuItem("Festa/World/슬롯머신 스핀·릴정지 소리 생성 및 배선")]
        public static void Build()
        {
            if (EditorApplication.isPlaying) { Debug.LogError("[SlotAudio] 플레이 모드에서는 돌리지 않는다."); return; }

            Directory.CreateDirectory(OutDir);
            var spinPath = $"{OutDir}/SFX_Slot_Spin_Loop.wav";
            var stopPath = $"{OutDir}/SFX_Slot_Reel_Stop.wav";

            WriteWav(spinPath, BuildSpinLoop());
            WriteWav(stopPath, BuildReelStop());
            AssetDatabase.ImportAsset(spinPath, ImportAssetOptions.ForceUpdate);
            AssetDatabase.ImportAsset(stopPath, ImportAssetOptions.ForceUpdate);
            Configure(spinPath, loadInBackground: false);
            Configure(stopPath, loadInBackground: false);

            var spin = AssetDatabase.LoadAssetAtPath<AudioClip>(spinPath);
            var stop = AssetDatabase.LoadAssetAtPath<AudioClip>(stopPath);

            int wired = 0;
            foreach (var machine in UnityEngine.Object.FindObjectsByType<PresetUVSlotMachine>(FindObjectsInactive.Include, FindObjectsSortMode.None))
            {
                var so = new SerializedObject(machine);
                // 스핀음은 릴이 도는 내내 이어져야 하므로 loop, 릴 정지음은 한 번씩 튄다.
                so.FindProperty("spinAudioSource").objectReferenceValue =
                    EnsureSource(machine.gameObject, "SpinAudio", spin, loop: true, volume: 0.30f);
                so.FindProperty("reelStopAudioSource").objectReferenceValue =
                    EnsureSource(machine.gameObject, "ReelStopAudio", stop, loop: false, volume: 0.38f);
                so.ApplyModifiedPropertiesWithoutUndo();
                EditorUtility.SetDirty(machine);
                wired++;
            }

            UnityEditor.SceneManagement.EditorSceneManager.MarkSceneDirty(UnityEngine.SceneManagement.SceneManager.GetActiveScene());
            UnityEditor.SceneManagement.EditorSceneManager.SaveOpenScenes();
            Debug.Log($"[SlotAudio] 스핀 루프 {spin.length:F2}초 · 릴 정지음 {stop.length:F2}초 생성, 슬롯머신 {wired}대에 배선 완료.");
        }

        /// <summary>
        /// 기계마다 자기 AudioSource 를 둔다 — 하나를 돌려 쓰면 두 대가 동시에 돌 때 소리가 겹쳐 끊긴다.
        /// 거리 감쇠는 기계에 이미 붙어 있는 당첨 차임 소스와 같은 값을 베낀다(광장 안에서만 들려야 한다).
        /// </summary>
        static AudioSource EnsureSource(GameObject machine, string name, AudioClip clip, bool loop, float volume)
        {
            var t = machine.transform.Find(name);
            var go = t != null ? t.gameObject : new GameObject(name);
            if (t == null) go.transform.SetParent(machine.transform, false);

            // `??` 를 쓰면 안 된다 — UnityEngine.Object 의 가짜 null 은 `??` 가 못 걸러서
            // 파괴된 참조를 그대로 통과시키고 MissingComponentException 이 난다(여기서 실제로 났다).
            var src = go.GetComponent<AudioSource>();
            if (src == null) src = go.AddComponent<AudioSource>();
            src.clip = clip;
            src.loop = loop;
            src.playOnAwake = false;
            src.volume = volume;

            var reference = machine.GetComponent<AudioSource>();
            if (reference != null)
            {
                src.spatialBlend = reference.spatialBlend;
                src.rolloffMode = reference.rolloffMode;
                src.minDistance = reference.minDistance;
                src.maxDistance = reference.maxDistance;
            }
            else
            {
                src.spatialBlend = 1f;
            }
            EditorUtility.SetDirty(go);
            return src;
        }

        /// <summary>돌아가는 소리 — 딸깍 9개를 정확히 이어 붙인 루프.</summary>
        static float[] BuildSpinLoop()
        {
            int total = Mathf.RoundToInt(TickInterval * TickCount * SampleRate);
            var data = new float[total];
            var rng = new System.Random(20260910);   // 결정적으로 — 다시 돌려도 같은 파일이 나와야 한다

            for (int tick = 0; tick < TickCount; tick++)
            {
                int start = Mathf.RoundToInt(tick * TickInterval * SampleRate);
                int len = Mathf.RoundToInt(0.030f * SampleRate);
                for (int i = 0; i < len && start + i < total; i++)
                {
                    float t = i / (float)SampleRate;
                    float env = Mathf.Exp(-t * 130f);
                    // 금속 딸깍 = 높은 사인 두 개 + 잡음 한 꼬집
                    float body = Mathf.Sin(2f * Mathf.PI * 1250f * t) * 0.55f
                               + Mathf.Sin(2f * Mathf.PI * 1870f * t) * 0.25f
                               + (float)(rng.NextDouble() * 2.0 - 1.0) * 0.20f;
                    data[start + i] += body * env * 0.5f;
                }
            }

            // 루프 이음매의 클릭을 없앤다 — 앞뒤 2 ms 만 살짝 눕힌다.
            int fade = Mathf.RoundToInt(0.002f * SampleRate);
            for (int i = 0; i < fade; i++)
            {
                float k = i / (float)fade;
                data[i] *= k;
                data[total - 1 - i] *= k;
            }
            return data;
        }

        /// <summary>릴이 멈출 때의 둔탁한 정지음.</summary>
        static float[] BuildReelStop()
        {
            int total = Mathf.RoundToInt(0.14f * SampleRate);
            var data = new float[total];
            var rng = new System.Random(613);
            for (int i = 0; i < total; i++)
            {
                float t = i / (float)SampleRate;
                float env = Mathf.Exp(-t * 46f);
                float body = Mathf.Sin(2f * Mathf.PI * 175f * t) * 0.7f
                           + Mathf.Sin(2f * Mathf.PI * 320f * t) * 0.25f
                           + (float)(rng.NextDouble() * 2.0 - 1.0) * Mathf.Exp(-t * 220f) * 0.4f;
                data[i] = body * env * 0.85f;
            }
            return data;
        }

        static void Configure(string path, bool loadInBackground)
        {
            var importer = (AudioImporter)AssetImporter.GetAtPath(path);
            var settings = importer.defaultSampleSettings;
            // 짧고 자주 울리는 효과음이다 — 스트리밍이면 재생 시점이 밀린다(BGM 실측에서 겪었다).
            settings.loadType = AudioClipLoadType.DecompressOnLoad;
            settings.compressionFormat = AudioCompressionFormat.Vorbis;
            settings.quality = 0.7f;
            settings.preloadAudioData = true;
            importer.defaultSampleSettings = settings;
            importer.loadInBackground = loadInBackground;
            importer.forceToMono = true;
            importer.SaveAndReimport();
        }

        /// <summary>16-bit PCM mono WAV. Unity 가 그대로 읽는 최소 헤더다.</summary>
        static void WriteWav(string path, float[] samples)
        {
            using var stream = new FileStream(path, FileMode.Create);
            using var w = new BinaryWriter(stream);
            int dataBytes = samples.Length * 2;

            w.Write(new[] { 'R', 'I', 'F', 'F' });
            w.Write(36 + dataBytes);
            w.Write(new[] { 'W', 'A', 'V', 'E' });
            w.Write(new[] { 'f', 'm', 't', ' ' });
            w.Write(16);                       // PCM 헤더 길이
            w.Write((short)1);                 // PCM
            w.Write((short)1);                 // mono
            w.Write(SampleRate);
            w.Write(SampleRate * 2);           // byte rate
            w.Write((short)2);                 // block align
            w.Write((short)16);                // bits
            w.Write(new[] { 'd', 'a', 't', 'a' });
            w.Write(dataBytes);
            foreach (var s in samples)
                w.Write((short)(Mathf.Clamp(s, -1f, 1f) * short.MaxValue));
        }
    }
}
