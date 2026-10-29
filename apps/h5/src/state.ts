import { reactive } from 'vue';
import { showFailToast } from 'vant';
import { ApiClient, type Row, type User } from '@chuyu/shared';
export const api = new ApiClient('chuyu.h5');
export const state = reactive({
  user: null as User | null,
  config: {} as Row,
  points: [] as Row[],
  assisted: [] as Row[],
  pointId: sessionStorage.getItem('chuyu.point') || '',
  catalog: { products: [], shops: [] } as Row,
});
export function selectedPoint(): Row {
  return state.points.find((p) => p.id === state.pointId) || state.points[0] || {};
}
export async function hydrate() {
  state.user = await api.get('/auth/me');
  state.config = await api.get('/config');
  state.catalog = await api.get('/catalog');
  state.points = (await api.get('/lists/members?size=100')).items;
  if (!state.points.some((p) => p.id === state.pointId)) state.pointId = state.points[0]?.id || '';
  sessionStorage.setItem('chuyu.point', state.pointId);
}
export function fail(e: unknown) {
  if (!api.token) state.user = null;
  showFailToast((e as Error).message || '操作未完成，请重试');
}
