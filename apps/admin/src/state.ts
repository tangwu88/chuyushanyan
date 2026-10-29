import { reactive } from 'vue';
import { ApiClient, type User, type Row } from '@chuyu/shared';
export const api = new ApiClient('chuyu.admin');
export const state = reactive({
  user: null as User | null,
  config: {} as Row,
  points: [] as Row[],
  catalog: { products: [], shops: [] } as Row,
});
export const can = (permission: string) =>
  !!state.user &&
  (state.user.permissions.includes('*') || state.user.permissions.includes(permission));
export async function hydrate() {
  state.user = await api.get('/auth/me');
  state.config = await api.get('/config');
  state.catalog = await api.get('/catalog');
  state.points = can('members:read') ? (await api.get('/lists/members?size=200')).items : [];
}
