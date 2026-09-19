import * as realApi from './api';
import * as mockApi from './api.mock';

export const raffleApi = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;
