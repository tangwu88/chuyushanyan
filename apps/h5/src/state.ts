import { reactive } from 'vue';
import { showFailToast } from 'vant';
import { ApiClient, configureLabels, type Row, type User } from '@chuyu/shared';
export const api = new ApiClient('chuyu.h5');
export const state = reactive({
  user: null as User | null,
  config: {} as Row,
  points: [] as Row[],
  assisted: [] as Row[],
  pointId: sessionStorage.getItem('chuyu.point') || '',
  catalog: { products: [], shops: [] } as Row,
  content: { banners: [], announcements: [], agreements: [], appearance: [] } as Row,
});
export function selectedPoint(): Row {
  return state.points.find((p) => p.id === state.pointId) || state.points[0] || {};
}
export async function hydrate() {
  const user = await api.get('/auth/me');
  if (user.role !== 'MEMBER') throw new Error('请使用会员或店铺账号登录');
  if (user.mustChangePassword) {
    state.user = user;
    return;
  }
  const [config, catalog, points, invited, content] = await Promise.all([
    api.get('/config'),
    api.get('/catalog'),
    api.get('/lists/members?size=100'),
    api.get('/lists/invited-members?size=200'),
    api.get('/content'),
  ]);
  state.config = config;
  configureLabels(state.config);
  state.catalog = catalog;
  state.points = points.items;
  state.assisted = invited.items.filter(
    (p: Row) => !state.points.some((n) => n.id === p.id) && !p.activated,
  );
  state.content = content;
  if (!state.points.some((p) => p.id === state.pointId)) state.pointId = state.points[0]?.id || '';
  sessionStorage.setItem('chuyu.point', state.pointId);
  state.user = user;
}
export function fail(e: unknown) {
  if (!api.token) state.user = null;
  showFailToast((e as Error).message || '操作未完成，请重试');
}
