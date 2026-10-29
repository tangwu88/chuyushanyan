export type Row = Record<string, any>;
export interface Page<T = Row> {
  items: T[];
  total: number;
  page: number;
  size: number;
}
export interface User {
  id: string;
  username: string;
  displayName: string;
  role: string;
  ownerId: string;
  mustChangePassword: boolean;
  permissions: string[];
  department?: string;
  dataScope?: string;
}
export class ApiClient {
  private pending = new Map<string, string>();
  constructor(public scope: string) {}
  get token() {
    return sessionStorage.getItem(this.scope + '.token') || '';
  }
  set token(value: string) {
    value
      ? sessionStorage.setItem(this.scope + '.token', value)
      : sessionStorage.removeItem(this.scope + '.token');
  }
  async get<T = any>(path: string): Promise<T> {
    return this.request(path);
  }
  async post<T = any>(path: string, body: Row = {}, key?: string): Promise<T> {
    const fingerprint = path + JSON.stringify(body);
    const id = key || this.pending.get(fingerprint) || crypto.randomUUID();
    this.pending.set(fingerprint, id);
    try {
      const result = await this.request<T>(path, {
        method: 'POST',
        body: JSON.stringify(body),
        headers: { 'Idempotency-Key': id },
      });
      this.pending.delete(fingerprint);
      return result;
    } catch (e) {
      if (e instanceof ApiError) this.pending.delete(fingerprint);
      throw e;
    }
  }
  private async request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const res = await fetch('/api/v1' + path, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...(this.token ? { Authorization: 'Bearer ' + this.token } : {}),
        ...options.headers,
      },
    });
    const data = await res.json();
    if (!res.ok) {
      if (res.status === 401) this.token = '';
      throw new ApiError(data.message || '操作未完成，请重试', res.status, data.code);
    }
    return data as T;
  }
  async login(username: string, password: string) {
    const result = await this.post<{ token: string; user: User }>('/auth/login', {
      username,
      password,
    });
    this.token = result.token;
    return result.user;
  }
  async export(resource: string, query: string) {
    const res = await fetch('/api/v1/exports/' + resource + '?' + query, {
      headers: { Authorization: 'Bearer ' + this.token },
    });
    if (!res.ok) throw new Error((await res.json()).message || '导出失败');
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement('a');
    a.href = url;
    a.download = '初语山言-' + resource + '.xlsx';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
  }
}
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public code?: string,
  ) {
    super(message);
  }
}
export function cents(value: any): bigint {
  const text = typeof value === 'number' ? value.toFixed(2) : String(value || '0');
  const match = /^(-?)(\d+)(?:\.(\d*))?$/.exec(text);
  if (!match) return 0n;
  return (
    (BigInt(match[2]) * 100n + BigInt(((match[3] || '') + '00').slice(0, 2))) *
    (match[1] ? -1n : 1n)
  );
}
export function fromCents(value: bigint): string {
  const n = value < 0n ? -value : value;
  return (value < 0n ? '-' : '') + String(n / 100n) + '.' + String(n % 100n).padStart(2, '0');
}
export function proportionalMoney(amount: unknown, rate: unknown): string {
  const parts = /^(\d+)(?:\.(\d+))?$/.exec(String(rate || '0'));
  if (!parts) return '0.00';
  const precision = 10n ** BigInt((parts[2] || '').length),
    factor = BigInt(parts[1] + (parts[2] || ''));
  return fromCents((cents(amount) * factor + precision / 2n) / precision);
}
export const availableBalance = (wallet: Row | undefined) =>
  fromCents(cents(wallet?.balance) - cents(wallet?.frozen));
export function money(value: any): string {
  const n = cents(value),
    absolute = n < 0n ? -n : n;
  return (
    (n < 0n ? '-' : '') +
    (absolute / 100n).toLocaleString('zh-CN') +
    '.' +
    String(absolute % 100n).padStart(2, '0')
  );
}
export const percent = (n: any) => (Number(n || 0) * 100).toFixed(2) + '%';
export const grade = (n: any) => (Number(n) === 0 ? '普通会员' : Number(n) + '星经理');
export const dateTime = (v: any) =>
  String(v || '—')
    .replace('T', ' ')
    .slice(0, 19);
export const labels: Record<string, string> = {
  '*': '全部权限',
  self: '本人账户',
  'members:read': '查看会员',
  'members:write': '管理会员',
  'orders:read': '查看订单',
  'orders:create': '创建订单',
  'orders:activate': '激活订单',
  'orders:cancel': '撤销订单',
  'funds:read': '查看资金',
  'funds:write': '资金审批与调整',
  'withdraw:review': '审核提现',
  'shipping:read': '查看发货',
  'shipping:review': '审批发货',
  'shipping:ship': '登记发货',
  'rules:read': '查看规则',
  'rules:write': '编辑与发布规则',
  'reports:read': '查看报表',
  'reports:export': '导出数据',
  'audit:read': '查看操作记录',
  'system:write': '管理系统用户',
  RULE_DRAFT: '新建规则',
  RULE_EDIT: '修改规则',
  RULE_TEST: '规则试算',
  RULE_PUBLISH: '发布规则',
  MEMBER_CREATE: '新增会员',
  MEMBER_EDIT: '修改会员',
  PASSWORD_RESET: '重置密码',
  PASSWORD_CHANGE: '修改密码',
  GRADE_AUTO: '会员等级调整',
  DIRECTOR_AUTO: '董事等级调整',
  TRANSFER: '积分转账',
  CONVERSION: '积分转换',
  WITHDRAW_REQUEST: '申请提现',
  WITHDRAW_REVIEW: '审核提现',
  TOPUP_REQUEST: '申请充值',
  TOPUP_REVIEW: '审核充值',
  FUNDS_ADJUST: '资金调整',
  STOCK_TRANSFER: '店铺转货',
  ORDER_CREATE: '创建订单',
  ORDER_PAY_DEMO: '演示支付',
  ORDER_PAYMENT: '订单付款',
  ORDER_RESERVATION: '订单额度预留',
  MANUAL_ADJUST: '人工资金调整',
  TOPUP: '充值到账',
  SETTLEMENT: '奖金结算入账',
  DEMO_OPENING: '期初积分',
  WITHDRAW_HOLD: '提现冻结',
  ORDER_ACTIVATE: '激活订单',
  ORDER_CANCEL: '撤销订单',
  SHIP_APPLY: '申请发货',
  SHIP_REVIEW: '审批发货',
  SHIP_DISPATCH: '登记发货',
  SETTLEMENT_PREVIEW: '结算预览',
  SETTLEMENT_POST: '结算入账',
  TRANSFER_SWITCH: '转账开关变更',
  PRODUCT_EDIT: '商品调整',
  SYSTEM_USER_EDIT: '用户权限调整',
  DEMO_SEED: '初始化数据',
  GRADE_MANUAL: '人工调整会员等级',
  GRADE_SYNC: '同步升级',
  DIRECTOR_MANUAL: '人工调整董事等级',
  REFERRAL_CHANGE: '更改推荐人',
  PHONE_MERGE: '手机号归并',
  MEMBER_RESTORE: '注销找回',
  EMPTY_ACTIVATE: '空点激活',
  CONFIG_EDIT: '修改配置',
  CONFIG_DELETE: '删除配置',
  ACCOUNT_UNLOCK: '账号解锁',
  PRODUCT_DELETE: '删除商品',
  EXPORT_REQUEST: '导出数据',
  REQUEST_FAILED: '操作失败',
  TOPUP_DELETE: '删除充值申请',
  TOPUP_RESTORE: '找回充值申请',
  UPGRADE_REQUEST: '申请同步升级',
  UPGRADE_REVIEW: '审批同步升级',
  DATA_ARCHIVE: '数据归档',
  LOG_CLEAR: '清理日志',
  SYSTEM_USER_IMPORT: '导入后台用户',
  SUCCESS: '成功',
  FAILED: '失败',
  ALL: '全部数据',
  DEPARTMENT: '本部门数据',
  SELF: '本人创建的数据',
  DONE: '已完成',
  RUNNING: '执行中',
  ERROR: '失败',
  NEW: '新人',
  FIRST: '首购',
  REPEAT: '复购',
  REWARD: '奖励积分',
  RECHARGE: '充值积分',
  NONE: '非店铺',
  SMALL: '小店',
  BIG: '大店',
  UNPAID: '待支付',
  PAID: '已支付',
  REFUNDED: '已退回充值',
  PENDING: '待处理',
  ACTIVE: '已激活',
  CANCELLED: '已撤销',
  UNFULFILLED: '待申请发货',
  APPLIED: '已申请发货',
  PARTIAL: '部分发货',
  FULFILLED: '已完成发货',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  SHIPPED: '已发货',
  PICKED_UP: '已自提',
  DELIVERED: '已收货',
  VOID: '已撤销',
  PREVIEW: '待入账',
  POSTED: '已入账',
  PAID_DEMO: '演示已支付',
  DRAFT: '草稿',
  ARCHIVED: '历史版本',
  REFERENCE: '现网参考',
  DAILY: '日结',
  MONTHLY: '月结 · 计划与身份',
  MONTHLY_REPEAT: '月结 · 七星复购',
  MEMBERS: '会员资格复核',
  UPGRADES: '同步升级核验',
  PERFORMANCE: '业绩快照',
  RANKING: '会员排行快照',
  ARCHIVE: '历史数据归档',
  RECONCILIATION: '资金对账',
  REPORT: '报单业绩',
  TOTAL: '总业绩',
  SALES: '销售级差奖',
  PEER: '平级奖',
  REPEAT_FIXED: '复购固定级差',
  SHOP_SUBSIDY: '店铺补贴',
  SEVEN_DAILY: '七星日分红',
  SMALL_SHOP: '小店分红',
  PLAN1: '计划1',
  PLAN2: 'A店 / 计划2',
  SEVEN_REPEAT: '七星复购分红',
  SEVEN_REPEAT_PEER: '复购分红平级奖',
  REPEAT_TOP: '复购额外分红',
  IDENTITY_B: 'B店',
  IDENTITY_C: 'C店',
  IDENTITY_H_FUNDS: '韩总资金',
  IDENTITY_H_REPEAT: '韩总复购分润',
  IDENTITY_BING: '丙店',
  IDENTITY_MALI: '马丽',
  IDENTITY_H_COMPANY2: '韩总公司2',
  ADMIN: '超级管理员',
  MANAGER: '品牌主管理员',
  OPERATOR: '业务管理员',
  FINANCE: '财务',
  SHIPPING: '发货管理',
  AUDITOR: '监察',
  VIEWER: '普通角色',
  MEMBER: '会员',
  SYSTEM_USER_DISABLE: '停用后台账号',
};
for (let i = 1; i <= 6; i++) labels['DIRECTOR_' + i] = i + '星董事分红';
export const label = (v: any) => labels[String(v)] || String(v ?? '—');
export const businessText = (v: any) =>
  String(v ?? '—').replace(/^([A-Z][A-Z0-9_]*)(?=\s|$)/, (code) => labels[code] || code);
export function configureLabels(config: Row) {
  Object.entries(config.roleNames || {}).forEach(([code, name]) => (labels[code] = String(name)));
  Object.entries(config.rules?.identities || {}).forEach(([code, value]) => {
    const row = value as Row;
    labels['IDENTITY_' + code] = row.displayMark || row.name;
  });
}
export const orderStatus = (o: Row) =>
  o.cancelled
    ? 'CANCELLED'
    : o.paymentStatus === 'UNPAID'
      ? 'UNPAID'
      : o.activationStatus === 'PENDING'
        ? 'PENDING'
        : o.fulfilmentStatus;
export function query(values: Row) {
  const q = new URLSearchParams();
  Object.entries(values).forEach(([k, v]) => {
    if (v !== '' && v !== undefined && v !== null) q.set(k, String(v));
  });
  return q.toString();
}

export function safeHtml(value: unknown): string {
  const doc = new DOMParser().parseFromString(String(value || ''), 'text/html');
  const allowed = new Set([
    'P',
    'BR',
    'B',
    'STRONG',
    'I',
    'EM',
    'U',
    'H2',
    'H3',
    'OL',
    'UL',
    'LI',
    'A',
    'BLOCKQUOTE',
  ]);
  function clean(parent: Element) {
    for (const child of Array.from(parent.children)) {
      if (['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT'].includes(child.tagName)) {
        child.remove();
        continue;
      }
      clean(child);
      if (!allowed.has(child.tagName)) {
        child.replaceWith(...Array.from(child.childNodes));
        continue;
      }
      const href = child.tagName === 'A' ? child.getAttribute('href') || '' : '';
      for (const attr of Array.from(child.attributes)) child.removeAttribute(attr.name);
      if (/^https?:\/\//i.test(href)) {
        child.setAttribute('href', href);
        child.setAttribute('rel', 'noopener noreferrer');
      }
    }
  }
  clean(doc.body);
  return doc.body.innerHTML;
}
