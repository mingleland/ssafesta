import { useEffect, useMemo, useState } from 'react';
import type { DialogueChoice, DialogueScene, GameProject } from '../../contracts/gameProject.ts';
import {
  addDialogueChoice,
  addDialogueNode,
  dialogueNodeRemovalReason,
  removeDialogueChoice,
  removeDialogueNode,
  renameScene,
  reorderDialogueNode,
  setDialogueBackground,
  setStartNode,
  startNodeChangeReason,
  updateDialogueChoice,
  updateDialogueNode,
} from '../model/authoringCommands.ts';
import { resolveStaticImageVisual, staticImageBackgroundStyle } from '../assets/staticImageVisual.ts';
import { assetDisplayLabel, isAssetForRole } from '../assets/builtinAssetCatalog.ts';
import { CommitInput } from './CommitInput.tsx';
import { analyzeDialogueFlow } from '../model/dialogueFlow.ts';

interface DialogueEditorProps {
  readonly project: GameProject;
  readonly scene: DialogueScene;
  readonly assetUrls: Readonly<Record<string, string>>;
  readonly onApply: (project: GameProject) => void;
}

const choiceOutcome = (choice: DialogueChoice): string => {
  if (choice.nextNodeId !== undefined) return `next:${choice.nextNodeId}`;
  const terminal = choice.actions.at(-1);
  if (terminal?.type === 'CLOSE_DIALOGUE') return 'close';
  if (terminal?.type === 'COMPLETE_GAME') return 'complete';
  if (terminal?.type === 'GO_TO_SCENE') return `scene:${terminal.sceneId}`;
  return 'none';
};

export const DialogueEditor = ({ project, scene, assetUrls, onApply }: DialogueEditorProps) => {
  const [selectedNodeId, setSelectedNodeId] = useState(scene.startNodeId);
  useEffect(() => setSelectedNodeId(scene.startNodeId), [scene.id, scene.startNodeId]);
  // S15P21A604-494 — 씬 목록의 드래그 정렬(draggedSceneIndex/dragOverSceneIndex)과 동일한 패턴.
  const [draggedNodeIndex, setDraggedNodeIndex] = useState<number | null>(null);
  const [dragOverNodeIndex, setDragOverNodeIndex] = useState<number | null>(null);
  const selectedNode = scene.nodes.find((node) => node.id === selectedNodeId) ?? scene.nodes[0];
  const dialogueFlow = useMemo(() => analyzeDialogueFlow(scene), [scene]);

  // 커밋 전(blur/Enter 이전) 실시간 입력값 — LIVE PREVIEW·좌측 노드 요약을 즉시 갱신하기 위함.
  // 실제 project 상태(undo 히스토리)는 여전히 CommitInput의 onCommit(blur/Enter)에서만 바뀐다.
  const [speakerDraft, setSpeakerDraft] = useState<string | null>(null);
  const [textDraft, setTextDraft] = useState<string | null>(null);
  const [choiceDrafts, setChoiceDrafts] = useState<Record<string, string>>({});
  useEffect(() => {
    setSpeakerDraft(null);
    setTextDraft(null);
    setChoiceDrafts({});
  }, [selectedNodeId, scene.id]);

  if (selectedNode === undefined) return null;
  const previewSpeaker = speakerDraft ?? selectedNode.speaker;
  const previewText = textDraft ?? selectedNode.text;
  const backgroundVisual = resolveStaticImageVisual(
    project.assets.find((asset) => asset.id === scene.backgroundAssetId),
    assetUrls,
  );
  const portraitVisual = resolveStaticImageVisual(
    project.assets.find((asset) => asset.id === selectedNode.portraitAssetId),
    assetUrls,
  );

  const setOutcome = (choice: DialogueChoice, outcome: string) => {
    onApply(updateDialogueChoice(project, scene.id, selectedNode.id, choice.id, (current) => {
      if (outcome.startsWith('next:')) {
        return { ...current, nextNodeId: outcome.slice(5), actions: [] };
      }
      if (outcome.startsWith('scene:')) {
        return { ...current, nextNodeId: undefined, actions: [{ type: 'GO_TO_SCENE', sceneId: outcome.slice(6) }] };
      }
      if (outcome === 'close') return { ...current, nextNodeId: undefined, actions: [{ type: 'CLOSE_DIALOGUE' }] };
      return { ...current, nextNodeId: undefined, actions: [{ type: 'COMPLETE_GAME' }] };
    }));
  };

  return (
    <div className="gss-dialogue-workspace">
      <div className="gss-dialogue-sticky">
        {/* S15P21A604-512 — scene.presentation 원시값 배지 제거. 캔버스 상단 툴바가 이미
            describeSceneType()으로 같은 정보를 "대화-Overlay"/"대화-Fullscreen"처럼
            친숙하게 보여주고 있어서 여기서 다시 원시값("OVERLAY"/"FULL_SCREEN")을
            보여주는 건 정보 중복이었다. */}
        <div className="gss-dialogue-scene-header">
          <div>
            <span className="gss-eyebrow">DIALOGUE SCENE</span>
            <CommitInput
              label="Scene 이름"
              onCommit={(name) => onApply(renameScene(project, scene.id, name))}
              value={scene.name}
            />
          </div>
        </div>
        {/* S15P21A604-513 — NODES rail이 보여주던 정보(화자/대사 요약, START 배지)가
            FLOW OVERVIEW가 보여주는 정보(같은 요약 + 도달 가능 여부 + 선택 후 결과)의
            부분집합이라 여기 하나로 합쳤다. NODES rail의 드래그 정렬·"시작으로 설정"·삭제
            기능을 그대로 이식한다 — analyzeDialogueFlow가 돌려주는 dialogueFlow.nodes는
            scene.nodes와 정확히 같은 순서(reachable 여부만 그래프 탐색으로 계산할 뿐 순서
            자체는 안 바꿈)라 같은 배열 인덱스를 그대로 재사용할 수 있다. 번호 배지는
            NODES rail 때와 같은 이유(드래그로 바뀌는 목록에 고정 번호는 오인의 소지)로
            달지 않는다. 목록 자체는 원래 FLOW OVERVIEW처럼 가로 스크롤 고정폭 카드로
            두고(씬 헤더와 함께 화면 상단에 고정되므로, 노드가 많아져도(최대 300개)
            세로로 무한정 커지지 않는다), 카드 하나(.gss-flow-node-card)가 곧 드래그
            대상이자 선택 버튼이다 — 별도 ☰ 핸들 없이 카드 아무 곳이나 잡아 드래그하면
            순서가 바뀐다. ★/×는 카드 우측 상단에 겹쳐서(overlay) 넣었다. 카드 안에 진짜
            <button>(★/×)을 넣어야 해서 카드 루트는 <button>이 아니라 role="button" +
            tabIndex의 <div>로 만들었다 — <button> 안에 <button>은 HTML 표준상 중첩이
            안 되기 때문(브라우저가 파싱 중 바깥 버튼을 미리 닫아버림). ★/× 클릭은
            stopPropagation으로 카드 선택(onClick)까지 같이 발동하지 않게 막는다. */}
        <section className="gss-dialogue-flow" aria-label="대화 흐름 개요">
          <header>
            <div><span className="gss-eyebrow">FLOW OVERVIEW</span><strong>대화 흐름</strong></div>
            {/* S15P21A604-513 (4차) — header가 justify-content:space-between인 채로
                자식이 3개(제목/메트릭/+ 버튼)면, 양 끝은 붙지만 가운데 메트릭은 좌우
                여백이 똑같이 벌어진 자리라 박스 정중앙 부근에 어중간하게 떠 보였다.
                메트릭 + + 버튼을 한 그룹으로 묶어 "제목(왼쪽) vs 나머지(오른쪽 묶음)"
                2그룹 배치로 바꿔서, 메트릭이 + 버튼 바로 왼쪽에 붙게 한다. */}
            <div className="gss-dialogue-flow-header-right">
              <div className="gss-dialogue-flow-metrics">
                <span>{dialogueFlow.reachableCount}/{scene.nodes.length} 도달</span>
                {dialogueFlow.unreachableCount > 0 && <em>미연결 {dialogueFlow.unreachableCount}</em>}
                {dialogueFlow.incompleteOutcomeCount > 0 && <em>결과 확인 {dialogueFlow.incompleteOutcomeCount}</em>}
              </div>
              <button
                aria-label="대화 노드 추가"
                className="gss-icon-button"
                disabled={scene.nodes.length >= 300}
                onClick={() => {
                  const result = addDialogueNode(project, scene.id);
                  onApply(result.project);
                  setSelectedNodeId(result.nodeId);
                }}
                type="button"
              >+</button>
            </div>
          </header>
          <div className="gss-dialogue-flow-list">
            {dialogueFlow.nodes.map((flow, index) => {
              const node = scene.nodes.find((candidate) => candidate.id === flow.nodeId);
              if (node === undefined) return null;
              const removalReason = dialogueNodeRemovalReason(project, scene.id, node.id);
              const startReason = startNodeChangeReason(project, scene.id, node.id);
              const isSelected = node.id === selectedNode.id;
              const cardClassName = ['gss-flow-node-card',
                isSelected ? 'is-active' : '',
                flow.reachable ? '' : 'is-unreachable',
                flow.hasIncompleteOutcome ? 'has-incomplete-outcome' : '',
                draggedNodeIndex === index ? 'is-dragging' : '',
                dragOverNodeIndex === index && draggedNodeIndex !== index ? 'is-drag-over' : '']
                .filter(Boolean).join(' ');
              const selectThisNode = () => setSelectedNodeId(node.id);
              return (
                <div
                  aria-current={isSelected ? 'step' : undefined}
                  className={cardClassName}
                  draggable
                  key={node.id}
                  onClick={selectThisNode}
                  onDragEnd={() => { setDraggedNodeIndex(null); setDragOverNodeIndex(null); }}
                  onDragOver={(dragEvent) => {
                    if (draggedNodeIndex === null) return;
                    dragEvent.preventDefault();
                    if (dragOverNodeIndex !== index) setDragOverNodeIndex(index);
                  }}
                  onDragStart={(dragEvent) => {
                    dragEvent.dataTransfer?.setData('text/plain', String(index));
                    setDraggedNodeIndex(index);
                  }}
                  onDrop={(dragEvent) => {
                    dragEvent.preventDefault();
                    if (draggedNodeIndex !== null && draggedNodeIndex !== index) {
                      onApply(reorderDialogueNode(project, scene.id, scene.nodes[draggedNodeIndex]!.id, index));
                    }
                    setDraggedNodeIndex(null);
                    setDragOverNodeIndex(null);
                  }}
                  onKeyDown={(keyEvent) => {
                    if (keyEvent.key !== 'Enter' && keyEvent.key !== ' ') return;
                    keyEvent.preventDefault();
                    selectThisNode();
                  }}
                  role="button"
                  tabIndex={0}
                >
                  <div className="gss-node-card-actions">
                    <button
                      aria-label={`${index + 1}번째 노드를 시작으로 설정`}
                      className="gss-icon-button"
                      disabled={startReason !== null}
                      draggable={false}
                      onClick={(clickEvent) => {
                        clickEvent.stopPropagation();
                        onApply(setStartNode(project, scene.id, node.id));
                      }}
                      title={startReason ?? '이 노드를 시작으로 설정'}
                      type="button"
                    >★</button>
                    <button
                      aria-label={`${index + 1}번째 노드 삭제`}
                      className="gss-icon-button"
                      disabled={removalReason !== null}
                      draggable={false}
                      onClick={(clickEvent) => {
                        clickEvent.stopPropagation();
                        onApply(removeDialogueNode(project, scene.id, node.id));
                      }}
                      title={removalReason ?? '노드 삭제'}
                      type="button"
                    >×</button>
                  </div>
                  <div>
                    <strong>{(isSelected ? previewSpeaker : node.speaker) || '내레이션'}</strong>
                    <small>{flow.outcomes.join(' · ')}</small>
                  </div>
                  {node.id === scene.startNodeId && <em>START</em>}
                </div>
              );
            })}
          </div>
          {(dialogueFlow.unreachableCount > 0 || dialogueFlow.incompleteOutcomeCount > 0) && (
            <p>붉은 점은 시작 대화에서 갈 수 없거나 선택 후 결과가 없는 노드입니다. 눌러서 바로 수정하세요.</p>
          )}
        </section>
      </div>

      <div className="gss-dialogue-scroll">
        <section
          className={`gss-dialogue-live-preview${scene.presentation === 'OVERLAY' ? ' is-overlay' : ''}`}
          style={backgroundVisual === null ? undefined : staticImageBackgroundStyle(backgroundVisual)}
        >
          {/* S15P21A604-512 — 원시 presentation 값 제거(정보 중복, 위 원인 참고). Overlay/
              Fullscreen 차이는 이 미리보기 카드 자체의 레이아웃(is-overlay 클래스)으로
              이미 시각적으로 드러난다. */}
          <span className="gss-preview-badge">LIVE PREVIEW</span>
          {portraitVisual !== null && (
            <div className="gss-dialogue-preview-portrait" style={staticImageBackgroundStyle(portraitVisual)} />
          )}
          <div className="gss-dialogue-preview-box">
            <strong>{previewSpeaker || '내레이션'}</strong>
            <p>{previewText}</p>
            {selectedNode.choices.length > 0 && (
              <div>
                {selectedNode.choices.map((choice, index) => (
                  <span key={choice.id}>{index + 1}. {choiceDrafts[choice.id] ?? choice.text}</span>
                ))}
              </div>
            )}
          </div>
        </section>
        <section className="gss-dialogue-node-card">
          {/* S15P21A604-512 — 내부 node id 노출 제거. 어떤 노드를 편집 중인지는 위 FLOW
              OVERVIEW의 speaker/결과 요약과 START 배지로 이미 구분되고, id 자체는
              사용자에게 의미 없는 기술적 정보였다. */}
          <header><span>NODE</span></header>
          <div className="gss-field-row">
            <label className="gss-field">
              <span>연출 배경</span>
              <select
                onChange={(event) => onApply(setDialogueBackground(project, scene.id, event.target.value || undefined))}
                value={scene.backgroundAssetId ?? ''}
              >
                <option value="">배경 없음 / 게임 화면 유지</option>
                {project.assets.filter((asset) => isAssetForRole(asset, 'BACKGROUND')).map((asset) => <option key={asset.id} value={asset.id}>{assetDisplayLabel(asset)}</option>)}
              </select>
            </label>
            <label className="gss-field">
              <span>인물 이미지 / 표정</span>
              <select
                onChange={(event) => onApply(updateDialogueNode(project, scene.id, selectedNode.id, (node) => ({
                  ...node,
                  portraitAssetId: event.target.value || undefined,
                })))}
                value={selectedNode.portraitAssetId ?? ''}
              >
                <option value="">인물 없음</option>
                {project.assets.filter((asset) => isAssetForRole(asset, 'PORTRAIT')).map((asset) => <option key={asset.id} value={asset.id}>{assetDisplayLabel(asset)}</option>)}
              </select>
            </label>
          </div>
          <CommitInput
            label="말하는 인물"
            onCommit={(speaker) => onApply(updateDialogueNode(project, scene.id, selectedNode.id, (node) => ({
              ...node,
              speaker,
            })))}
            onDraftChange={setSpeakerDraft}
            value={selectedNode.speaker || '내레이션'}
          />
          <CommitInput
            label="대사"
            multiline
            onCommit={(text) => onApply(updateDialogueNode(project, scene.id, selectedNode.id, (node) => ({
              ...node,
              text,
            })))}
            onDraftChange={setTextDraft}
            value={selectedNode.text}
          />
        </section>

        <div className="gss-choice-heading">
          <div><span className="gss-eyebrow">CHOICES</span><strong>사용자 선택지</strong></div>
          <button
            disabled={selectedNode.choices.length >= 6}
            onClick={() => onApply(addDialogueChoice(project, scene.id, selectedNode.id))}
            type="button"
          >+ 선택지</button>
        </div>
        {selectedNode.choices.length === 0 && (
          <div className="gss-help-card"><strong>선택지가 없습니다</strong><p>자동 진행은 Runtime 정책 확정 전이므로 현재는 선택지를 하나 이상 추가하는 것을 권장합니다.</p></div>
        )}
        {selectedNode.choices.map((choice, index) => (
          <article className="gss-choice-card" key={choice.id}>
            <header><span>{index + 1}</span><strong>{choice.id}</strong><button
              aria-label="선택지 삭제"
              className="gss-icon-button"
              onClick={() => onApply(removeDialogueChoice(project, scene.id, selectedNode.id, choice.id))}
              type="button"
            >×</button></header>
            <CommitInput
              label="표시 문구"
              onCommit={(text) => onApply(updateDialogueChoice(
                project,
                scene.id,
                selectedNode.id,
                choice.id,
                (current) => ({ ...current, text }),
              ))}
              onDraftChange={(text) => setChoiceDrafts((prev) => ({ ...prev, [choice.id]: text }))}
              value={choice.text}
            />
            <label className="gss-field">
              <span>선택 후 결과</span>
              <select onChange={(event) => setOutcome(choice, event.target.value)} value={choiceOutcome(choice)}>
                {scene.nodes.filter((node) => node.id !== selectedNode.id).map((node) => (
                  <option key={node.id} value={`next:${node.id}`}>다음 대화 · {node.speaker}: {node.text}</option>
                ))}
                {scene.presentation === 'OVERLAY' ? (
                  <option value="close">대화 닫고 게임으로 복귀</option>
                ) : (
                  <>
                    {project.scenes.filter((candidate) => (
                      candidate.id !== scene.id && (candidate.type !== 'DIALOGUE' || candidate.presentation === 'FULL_SCREEN')
                    )).map((candidate) => (
                      <option key={candidate.id} value={`scene:${candidate.id}`}>Scene 이동 · {candidate.name}</option>
                    ))}
                    <option value="complete">게임 완료</option>
                  </>
                )}
              </select>
            </label>
          </article>
        ))}
      </div>
    </div>
  );
};
