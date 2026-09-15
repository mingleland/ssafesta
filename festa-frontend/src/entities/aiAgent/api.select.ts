// mock/real 선택 지점 — leaseApi.select와 같은 상용구 (VITE_USE_MOCK). 소비자는 이 파일에서
// import하면 된다 — named export 모양은 ./api와 동일해 호출부를 바꿀 필요가 없다.
import * as realApi from './api';
import * as mockApi from './api.mock';

const impl = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;

export const getAiAgent = impl.getAiAgent;
export const createAiAgent = impl.createAiAgent;
export const updateAiAgent = impl.updateAiAgent;
export const listAiDocuments = impl.listAiDocuments;
export const uploadAiDocument = impl.uploadAiDocument;
export const replaceAiDocument = impl.replaceAiDocument;
export const deleteAiDocument = impl.deleteAiDocument;

export type {
  AiAgent,
  AiAgentCommand,
  AiAgentResponseLength,
  AiAgentRole,
  AiAgentTone,
  AiDocumentListView,
  AiDocumentStatus,
  AiDocumentView,
  DocumentUploadResult,
} from './api';
