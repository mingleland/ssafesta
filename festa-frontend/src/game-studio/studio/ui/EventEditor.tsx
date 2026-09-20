import { useState } from 'react';
import type {
  Action,
  Condition,
  GameEvent,
  GameObject,
  GameProject,
  Scalar,
  WorldScene,
  VariableDefinition,
} from '../../contracts/gameProject.ts';
import {
  addObjectEvent,
  applyBehaviorRecipe,
  appendEventAction,
  appendEventCondition,
  isTerminalActionType,
  removeEvent,
  removeEventAction,
  removeEventCondition,
  reorderEventAction,
  replaceEventAction,
  replaceEventCondition,
  replaceEventTrigger,
} from '../model/authoringCommands.ts';
import type { BehaviorRecipe } from '../model/authoringCommands.ts';
import {
  ACTION_LABELS,
  CONDITION_LABELS,
  TOP_DOWN_ACTION_TYPES,
} from '../model/authoringRegistry.ts';

interface EventEditorProps {
  readonly project: GameProject;
  readonly scene: WorldScene;
  readonly selectedObject: GameObject | null;
  readonly onApply: (project: GameProject) => void;
}

const scalarForInput = (variable: VariableDefinition, raw: string | boolean): Scalar => {
  if (variable.type === 'BOOLEAN') return Boolean(raw);
  if (variable.type === 'INTEGER') return Number.parseInt(String(raw), 10) || 0;
  return String(raw);
};

interface ScalarEditorProps {
  readonly variable: VariableDefinition;
  readonly value: Scalar;
  readonly onChange: (value: Scalar) => void;
}

const ScalarEditor = ({ variable, value, onChange }: ScalarEditorProps) => {
  if (variable.type === 'BOOLEAN') {
    return (
      <label className="gss-check-row gss-check-row--compact">
        <input checked={value === true} onChange={(event) => onChange(event.target.checked)} type="checkbox" />
        true
      </label>
    );
  }
  return (
    <input
      aria-label={`${variable.id} 비교 값`}
      onChange={(event) => onChange(scalarForInput(variable, event.target.value))}
      type={variable.type === 'INTEGER' ? 'number' : 'text'}
      value={String(value)}
    />
  );
};

const ConditionRow = ({
  condition,
  index,
  project,
  onReplace,
  onRemove,
}: {
  readonly condition: Condition;
  readonly index: number;
  readonly project: GameProject;
  readonly onReplace: (condition: Condition) => void;
  readonly onRemove: () => void;
}) => (
  <div className="gss-rule-row gss-rule-row--condition">
    <span className="gss-rule-index">IF {index + 1}</span>
    <div className="gss-rule-content">
      <strong>{CONDITION_LABELS[condition.type]}</strong>
      {condition.type === 'HAS_ITEM' ? (
        <select onChange={(event) => onReplace({ type: 'HAS_ITEM', itemId: event.target.value })} value={condition.itemId}>
          {project.items.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select>
      ) : (() => {
        const variable = project.variables.find((candidate) => candidate.id === condition.variableId);
        if (variable === undefined) return null;
        return (
          <div className="gss-rule-fields">
            <select
              onChange={(event) => {
                const next = project.variables.find((candidate) => candidate.id === event.target.value);
                if (next !== undefined) onReplace({
                  type: 'VARIABLE_EQUALS',
                  variableId: next.id,
                  value: next.initialValue,
                });
              }}
              value={condition.variableId}
            >
              {project.variables.map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.id}</option>)}
            </select>
            <ScalarEditor
              onChange={(value) => onReplace({ ...condition, value })}
              value={condition.value}
              variable={variable}
            />
          </div>
        );
      })()}
    </div>
    <button aria-label="조건 제거" className="gss-icon-button" onClick={onRemove} type="button">×</button>
  </div>
);

const ActionFields = ({
  action,
  project,
  onReplace,
}: {
  readonly action: Action;
  readonly project: GameProject;
  readonly onReplace: (action: Action) => void;
}) => {
  if (action.type === 'SHOW_DIALOGUE' || action.type === 'GO_TO_SCENE') {
    const scenes = project.scenes.filter((scene) => action.type === 'SHOW_DIALOGUE'
      ? scene.type === 'DIALOGUE' && scene.presentation === 'OVERLAY'
      : scene.type !== 'DIALOGUE' || scene.presentation === 'FULL_SCREEN');
    return (
      <select onChange={(event) => onReplace({ type: action.type, sceneId: event.target.value })} value={action.sceneId}>
        {scenes.map((scene) => <option key={scene.id} value={scene.id}>{scene.name}</option>)}
      </select>
    );
  }
  if (action.type === 'GIVE_ITEM' || action.type === 'REMOVE_ITEM') {
    return (
      <select onChange={(event) => onReplace({ type: action.type, itemId: event.target.value })} value={action.itemId}>
        {project.items.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
      </select>
    );
  }
  if (action.type === 'SHOW_OBJECT' || action.type === 'HIDE_OBJECT') {
    const objects = project.scenes.flatMap((scene) => scene.type !== 'DIALOGUE' ? scene.objects : []);
    return (
      <select onChange={(event) => onReplace({ type: action.type, objectId: event.target.value })} value={action.objectId}>
        {objects.map((object) => <option key={object.id} value={object.id}>{object.id}</option>)}
      </select>
    );
  }
  if (action.type === 'SET_VARIABLE') {
    const variable = project.variables.find((candidate) => candidate.id === action.variableId);
    if (variable === undefined) return null;
    return (
      <div className="gss-rule-fields">
        <select
          onChange={(event) => {
            const next = project.variables.find((candidate) => candidate.id === event.target.value);
            if (next !== undefined) onReplace({ type: 'SET_VARIABLE', variableId: next.id, value: next.initialValue });
          }}
          value={action.variableId}
        >
          {project.variables.map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.id}</option>)}
        </select>
        <ScalarEditor
          onChange={(value) => onReplace({ ...action, value })}
          value={action.value}
          variable={variable}
        />
      </div>
    );
  }
  return <small>추가 설정 없음</small>;
};

const EventCard = ({
  event,
  project,
  scene,
  selectedObject,
  onApply,
}: {
  readonly event: GameEvent;
  readonly project: GameProject;
  readonly scene: WorldScene;
  readonly selectedObject: GameObject;
  readonly onApply: (project: GameProject) => void;
}) => {
  const [actionToAdd, setActionToAdd] = useState<Action['type']>('SET_VARIABLE');
  const [draggedActionIndex, setDraggedActionIndex] = useState<number | null>(null);
  const [dragOverActionIndex, setDragOverActionIndex] = useState<number | null>(null);

  // terminal Action(있다면 반드시 배열 마지막)은 자리를 바꿀 수 없다 — 그 앞쪽까지만 놓을 수
  // 있게 드롭 가능 최대 인덱스를 미리 계산해 둔다. 이렇게 하면 애초에 규칙을 어기는 드롭 자체가
  // 발생하지 않아 reorderEventAction 안쪽의 validated()가 예외를 던질 일이 없다
  // (S15P21A604-360 초기 버전에서 이 clamp를 놓쳐 Uncaught 예외가 났던 것과 같은 종류의 버그를
  // 여기서는 "애초에 불가능한 드롭을 만들지 않는" 방식으로 막는다).
  const terminalActionIndex = event.actions.findIndex((action) => isTerminalActionType(action.type));
  const maxDropIndex = terminalActionIndex === -1 ? event.actions.length - 1 : terminalActionIndex - 1;

  const handleActionDrop = (targetIndex: number) => {
    if (draggedActionIndex !== null) {
      const clampedIndex = Math.min(targetIndex, maxDropIndex);
      if (clampedIndex !== draggedActionIndex) {
        onApply(reorderEventAction(project, scene.id, event.id, draggedActionIndex, clampedIndex));
      }
    }
    setDraggedActionIndex(null);
    setDragOverActionIndex(null);
  };

  return (
    <article className="gss-event-card">
      <header>
        <div><span className="gss-event-dot" /><strong>{event.id}</strong></div>
        <button
          aria-label={`${event.id} 이벤트 삭제`}
          className="gss-icon-button"
          onClick={() => onApply(removeEvent(project, scene.id, event.id))}
          type="button"
        >×</button>
      </header>
      <label className="gss-field">
        <span>TRIGGER</span>
        <select
          onChange={(changeEvent) => onApply(replaceEventTrigger(
            project,
            scene.id,
            event.id,
            { type: changeEvent.target.value as 'ON_INTERACT' | 'ON_ENTER', targetId: selectedObject.id },
          ))}
          value={event.trigger.type}
        >
          <option value="ON_INTERACT">플레이어가 상호작용할 때</option>
          <option value="ON_ENTER">플레이어가 닿을 때</option>
        </select>
      </label>

      <div className="gss-section-title"><span>CONDITIONS</span><small>모두 만족</small></div>
      {event.conditions.length === 0 && <div className="gss-empty-inline">조건 없이 항상 실행됩니다.</div>}
      {event.conditions.map((condition, index) => (
        <ConditionRow
          condition={condition}
          index={index}
          key={`${event.id}-condition-${index}`}
          onRemove={() => onApply(removeEventCondition(project, scene.id, event.id, index))}
          onReplace={(next) => onApply(replaceEventCondition(project, scene.id, event.id, index, next))}
          project={project}
        />
      ))}
      <div className="gss-inline-actions">
        <button
          disabled={project.items.length === 0 || event.conditions.length >= 10}
          onClick={() => onApply(appendEventCondition(project, scene.id, event.id, 'HAS_ITEM'))}
          type="button"
        >+ 아이템 조건</button>
        <button
          disabled={project.variables.length === 0 || event.conditions.length >= 10}
          onClick={() => onApply(appendEventCondition(project, scene.id, event.id, 'VARIABLE_EQUALS'))}
          type="button"
        >+ 변수 조건</button>
      </div>

      <div className="gss-section-title"><span>ACTIONS</span><small>드래그해서 순서 변경</small></div>
      {event.actions.map((action, index) => {
        const terminal = isTerminalActionType(action.type);
        const rowClassName = ['gss-rule-row', 'gss-rule-row--action',
          draggedActionIndex === index ? 'is-dragging' : '',
          dragOverActionIndex === index && draggedActionIndex !== index ? 'is-drag-over' : '']
          .filter(Boolean).join(' ');
        return (
          <div
            className={rowClassName}
            key={`${event.id}-action-${index}`}
            onDragOver={(dragEvent) => {
              if (draggedActionIndex === null) return;
              dragEvent.preventDefault();
              const clampedIndex = Math.min(index, maxDropIndex);
              if (dragOverActionIndex !== clampedIndex) setDragOverActionIndex(clampedIndex);
            }}
            onDrop={(dragEvent) => {
              dragEvent.preventDefault();
              handleActionDrop(index);
            }}
          >
            <div className="gss-rule-drag-cell">
              <button
                aria-label={`행동 순서 변경 핸들 (${index + 1}번째${terminal ? ' · 흐름 종료 동작이라 이동할 수 없습니다' : ''})`}
                className="gss-icon-button gss-drag-handle"
                disabled={terminal}
                draggable={!terminal}
                onDragEnd={() => { setDraggedActionIndex(null); setDragOverActionIndex(null); }}
                onDragStart={(dragEvent) => {
                  dragEvent.dataTransfer?.setData('text/plain', String(index));
                  setDraggedActionIndex(index);
                }}
                type="button"
              >☰</button>
              <span className="gss-rule-index">{index + 1}</span>
            </div>
            <div className="gss-rule-content">
              <strong>{ACTION_LABELS[action.type]}</strong>
              <ActionFields
                action={action}
                onReplace={(next) => onApply(replaceEventAction(project, scene.id, event.id, index, next))}
                project={project}
              />
            </div>
            <button
              aria-label="행동 제거"
              className="gss-icon-button"
              disabled={event.actions.length === 1}
              onClick={() => onApply(removeEventAction(project, scene.id, event.id, index))}
              type="button"
            >×</button>
          </div>
        );
      })}
      <div className="gss-inline-actions">
        <select onChange={(changeEvent) => setActionToAdd(changeEvent.target.value as Action['type'])} value={actionToAdd}>
          {TOP_DOWN_ACTION_TYPES.map((type) => <option key={type} value={type}>{ACTION_LABELS[type]}</option>)}
        </select>
        <button
          disabled={event.actions.length >= 20}
          onClick={() => onApply(appendEventAction(project, scene.id, event.id, actionToAdd))}
          type="button"
        >행동 추가</button>
      </div>
    </article>
  );
};

export const EventEditor = ({ project, scene, selectedObject, onApply }: EventEditorProps) => {
  if (selectedObject === null) {
    return (
      <div className="gss-help-card">
        <strong>Event를 붙일 오브젝트를 선택하세요</strong>
        <p>같은 오브젝트에도 여러 Event를 붙여 조건에 따라 다른 결과를 만들 수 있습니다.</p>
      </div>
    );
  }
  const events = scene.events.filter((event) => (
    event.trigger.type !== 'ON_SCENE_START' && event.trigger.targetId === selectedObject.id
  ));
  return (
    <div className="gss-panel-stack">
      <div className="gss-panel-heading">
        <div><span className="gss-eyebrow">EVENT EDITOR</span><h2>{selectedObject.id}</h2></div>
        <span className="gss-count-badge">{events.length}</span>
      </div>
      <section className="gss-recipe-box">
        <header><div><strong>빠른 행동</strong><small>한 번 눌러 기본 연결 완성</small></div></header>
        <div>
          {([
            ['TALK', '💬', '말 걸기'],
            ['PICKUP', '◇', '아이템 줍기'],
            ['LOCKED_DOOR', '🔐', '잠긴 문'],
            ['GOAL', '★', '도착하면 완료'],
            ['ENTER_DIALOGUE', '✨', '들어가면 연출'],
          ] as const).map(([recipe, icon, label]) => (
            <button
              key={recipe}
              onClick={() => onApply(applyBehaviorRecipe(project, scene.id, selectedObject.id, recipe as BehaviorRecipe))}
              type="button"
            ><span>{icon}</span><strong>{label}</strong></button>
          ))}
        </div>
      </section>
      {events.length === 0 && (
        <div className="gss-help-card"><strong>아직 Event가 없습니다</strong><p>Trigger → Condition → Action 순서로 동작을 만드세요.</p></div>
      )}
      {events.map((event) => (
        <EventCard
          event={event}
          key={event.id}
          onApply={onApply}
          project={project}
          scene={scene}
          selectedObject={selectedObject}
        />
      ))}
      <button
        className="gss-add-block"
        disabled={scene.events.length >= 300}
        onClick={() => onApply(addObjectEvent(project, scene.id, selectedObject.id).project)}
        type="button"
      >+ 새 Event</button>
    </div>
  );
};
