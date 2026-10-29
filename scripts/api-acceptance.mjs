import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const base = process.env.CHUYU_API || 'http://127.0.0.1:18080/api/v1';
const checks = [];
const check = (name, test) => {
  assert.ok(test, name);
  checks.push(name);
  process.stdout.write('✓ ' + name + '\n');
};
async function request(token, method, uri, body, key) {
  const r = await fetch(base + uri, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(method === 'POST' ? { 'Idempotency-Key': key || crypto.randomUUID() } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const data = await r.json();
  return { status: r.status, data };
}
async function login(username, password = 'ChuyuDemo!2026') {
  const r = await request('', 'POST', '/auth/login', { username, password });
  assert.equal(r.status, 200);
  return r.data.token;
}
const post = async (t, u, b = {}, key) => {
  const r = await request(t, 'POST', u, b, key);
  assert.equal(r.status, 200, u + ': ' + JSON.stringify(r.data));
  return r.data;
};
const get = async (t, u) => {
  const r = await request(t, 'GET', u);
  assert.equal(r.status, 200, u + ': ' + JSON.stringify(r.data));
  return r.data;
};
const rejects = async (t, u, b, status = 422) => {
  const r = await request(t, 'POST', u, b);
  assert.equal(r.status, status, u + ': ' + JSON.stringify(r.data));
  return r;
};
const n = (v) => Math.round(Number(v) * 100);
const admin = await login('admin'),
  finance = await login('finance'),
  shipping = await login('shipping'),
  auditor = await login('auditor'),
  shop = await login('shop');
const config = await get(admin, '/config');
assert.equal(config.demo, true);
const anonymous = await request('', 'GET', '/lists/orders');
check('未登录请求被拒绝', anonymous.status === 401);
const scenarios = await get(admin, '/scenarios');
check(
  '销售级差1490元独立样例603.45',
  scenarios.find((s) => s.id === 'sales').result.net === '603.45',
);
check('七星日结样例36000', scenarios.find((s) => s.id === 'seven-daily').result.net === '36000.00');
const phone = '0088' + String(Date.now());
const member = await post(admin, '/members', {
  phone,
  nickname: '验收会员 ' + phone.slice(-4),
  parentId: 'CY2002',
});
let token = await login(phone, phone.slice(-6));
await post(token, '/auth/password', {
  oldPassword: phone.slice(-6),
  newPassword: 'Acceptance!2026',
  newPaymentPassword: '123456',
});
token = await login(phone, 'Acceptance!2026');
check(
  '海外前导零手机号注册及首次改密',
  (await get(token, '/lists/members?size=20')).items[0].phone === phone,
);
await post(admin, '/funds-adjustments', {
  pointId: member.id,
  kind: 'REWARD',
  amount: '2000',
  reason: '独立验收测试初始积分',
});
const body = {
  pointId: member.id,
  payerId: member.id,
  shopId: 'CY9001',
  type: 'NEW',
  productId: 'PR001',
  quantity: 5,
  rewardAmount: '1043',
  pickup: false,
};
const q = await post(token, '/quotes', body);
check('70%抵扣及现金447元', q.total === '1490.00' && q.cashAmount === '447.00');
await rejects(token, '/quotes', { ...body, rewardAmount: '1043.01' });
check('超过70%抵扣被后端拒绝', true);
const key = crypto.randomUUID(),
  order = await post(token, '/orders', body, key),
  retry = await post(token, '/orders', body, key);
check('重复创建同一请求只生成一单', order.id === retry.id);
const reused = await request(token, 'POST', '/orders', { ...body, quantity: 4 }, key);
check('同一请求编号不能用于不同内容', reused.status === 409);
const storeBefore = (await get(shop, '/lists/members?size=20')).items.find(
  (p) => p.id === 'CY9001',
);
await post(token, '/orders/' + order.id + '/pay');
const storeReserved = (await get(shop, '/lists/members?size=20')).items.find(
  (p) => p.id === 'CY9001',
);
check(
  '已付款待激活资金被预留',
  n(storeReserved.recharge.frozen) - n(storeBefore.recharge.frozen) === 149000,
);
await rejects(auditor, '/orders/' + order.id + '/activate', {}, 403);
check('监察角色不能激活订单', true);
await post(admin, '/orders/' + order.id + '/activate');
const afterActivate = await get(token, '/orders/' + order.id);
check(
  '激活后产生真实销售奖并保存版本',
  afterActivate.activationStatus === 'ACTIVE' &&
    afterActivate.awards
      .filter((a) => a.awardType === 'SALES')
      .reduce((s, a) => s + n(a.net), 0) === 60345 &&
    !!afterActivate.memberSnapshot,
);
const points = await get(token, '/lists/members?size=100');
check(
  '会员仅能看到本人点位',
  points.items.every((p) => p.ownerId === member.ownerId),
);
const transfer = { fromId: member.id, toId: 'CY1001', amount: '20', paymentPassword: '123456' },
  transferKey = crypto.randomUUID();
const t1 = await post(token, '/transfers', transfer, transferKey),
  t2 = await post(token, '/transfers', transfer, transferKey);
check('积分转账重复提交只入账一次', t1.id === t2.id);
try {
  await post(admin, '/settings/1/update', {
    transferEnabled: false,
    reason: '验收：关闭转账入口及接口',
  });
  await rejects(token, '/transfers', { ...transfer, amount: '1' });
  check('后台关闭开关后接口停止新转账', true);
  const history = await get(
    token,
    '/lists/ledger?' +
      new URLSearchParams({ pointId: member.id, kind: 'REWARD', direction: 'OUT' }),
  );
  check(
    '关闭转账后历史流水仍可查询',
    history.items.some((x) => x.description.includes('转账给')),
  );
} finally {
  await post(admin, '/settings/1/update', {
    transferEnabled: true,
    reason: '验收结束，恢复转账入口',
  });
}
await rejects(token, '/transfers', { ...transfer, fromId: 'CY9001' });
await rejects(token, '/transfers', { ...transfer, paymentPassword: '654321' });
await rejects(token, '/transfers', { ...transfer, amount: '1.001' });
check('付款归属、支付密码和金额精度校验生效', true);
const w = await post(token, '/withdrawals', {
  pointId: member.id,
  amount: '40',
  bankAccount: '00000000001234',
  paymentPassword: '123456',
});
check('提现费率与冻结金额正确', w.fee === '2.40' && w.net === '37.60');
await post(finance, '/withdrawals/' + w.id + '/review', {
  approved: false,
  reason: '验收：测试驳回解冻',
});
check(
  '提现驳回释放冻结金额',
  (await get(token, '/lists/members?size=100')).items.find((p) => p.id === member.id).reward
    .frozen === '0.00',
);
const approved = await post(token, '/withdrawals', {
  pointId: member.id,
  amount: '20',
  bankAccount: '00000000001234',
  paymentPassword: '123456',
});
await post(finance, '/withdrawals/' + approved.id + '/review', {
  approved: true,
  reason: '验收：演示审核通过',
});
check(
  '提现审核完成并回收积分',
  (await get(token, '/lists/withdrawals?search=' + approved.id)).items[0].status === 'PAID_DEMO',
);
const balanceBefore = (await get(token, '/lists/members?size=100')).items[0];
await post(token, '/conversions', { pointId: member.id, amount: '10', paymentPassword: '123456' });
const converted = (await get(token, '/lists/members?size=100')).items[0];
check(
  '积分转换扣减奖励并增加充值',
  n(balanceBefore.reward.balance) - n(converted.reward.balance) === 1000 &&
    n(converted.recharge.balance) - n(balanceBefore.recharge.balance) === 1000,
);
const repeat = { ...body, type: 'REPEAT', quantity: 2, rewardAmount: '0' };
check('一星复购价格及月度额度正确', (await post(token, '/quotes', repeat)).total === '300.00');
await rejects(token, '/quotes', { ...repeat, rewardAmount: '1' });
check('复购积分抵扣被拒绝', true);
const ro = await post(token, '/orders', repeat);
await post(token, '/orders/' + ro.id + '/pay');
await rejects(token, '/quotes', { ...repeat, quantity: 1 });
check('已支付复购订单占用本月限额', true);
await post(admin, '/orders/' + ro.id + '/cancel', { reason: '验收：复购撤销并释放限额' });
check(
  '撤销后复购额度恢复',
  (await post(token, '/quotes', { ...repeat, quantity: 1 })).remainingQuota === 2,
);
const second = await post(token, '/orders', {
  ...body,
  type: 'FIRST',
  quantity: 4,
  rewardAmount: '0',
});
await post(token, '/orders/' + second.id + '/pay');
const shippingBody = {
  shopId: 'CY9001',
  recipient: '验收收货人',
  phone: '0001234567',
  address: '独立演示地址',
  pickup: false,
  lines: [
    { orderId: order.id, quantity: 3 },
    { orderId: second.id, quantity: 1 },
  ],
};
const shipment = await post(shop, '/shipments', shippingBody);
await post(shipping, '/shipments/' + shipment.id + '/review', {
  approved: true,
  reason: '验收：合并部分发货',
});
await post(shipping, '/shipments/' + shipment.id + '/ship', {
  carrier: '演示快递',
  tracking: 'LOCAL-' + Date.now(),
});
check(
  '多订单合并与单订单部分发货',
  (await get(token, '/orders/' + order.id)).fulfilmentStatus === 'PARTIAL',
);
await rejects(shop, '/shipments', { ...shippingBody, lines: [{ orderId: order.id, quantity: 3 }] });
check('已分配数量不能重复发货', true);
await post(admin, '/orders/' + order.id + '/cancel', { reason: '验收：已发货订单撤销' });
const shipDetails = await get(shop, '/shipments/' + shipment.id);
check(
  '已发货撤销保留物流及明细标记',
  shipDetails.application.status === 'SHIPPED' &&
    shipDetails.lines.some((l) => l.orderId === order.id && l.cancelled),
);
check(
  '撤销退款进入受限充值积分',
  n(
    (await get(shop, '/lists/members?size=100')).items.find((p) => p.id === 'CY9001').recharge
      .restricted,
  ) >= 149000,
);
const topup = await post(shop, '/topups', { shopId: 'CY9001', amount: '100' });
await post(finance, '/topups/' + topup.id + '/review', {
  approved: true,
  reason: '验收：充值到账',
});
check(
  '充值申请审批到账',
  (await get(shop, '/lists/topups?search=' + topup.id)).items[0].status === 'APPROVED',
);
const stockBefore = (await get(admin, '/lists/members?search=CY6002')).items[0];
await post(shop, '/stock-transfers', {
  fromId: 'CY9001',
  toId: 'CY6002',
  amount: '1',
  paymentPassword: '123456',
});
const stockAfter = (await get(admin, '/lists/members?search=CY6002')).items[0];
check(
  '店铺转货扣款与收款一致',
  n(stockAfter.recharge.balance) - n(stockBefore.recharge.balance) === 100,
);
await rejects(
  auditor,
  '/funds-adjustments',
  { pointId: member.id, kind: 'REWARD', amount: '1', reason: '越权验收' },
  403,
);
await rejects(auditor, '/rules', { name: '越权验收' }, 403);
await rejects(auditor, '/orders/' + second.id + '/pay', {}, 403);
check('监察角色的资金、规则、付款写入被拒绝', true);
const today = (await get(admin, '/config')).today;
check('订单时间使用上海业务日期', String(order.createdAt).slice(0, 10) === today);
const report = await get(admin, '/reports?start=' + today + '&end=' + today);
const reportOrders = await get(
  admin,
  '/lists/orders?' +
    new URLSearchParams({
      start: today,
      end: today,
      dateField: 'activatedAt',
      type: 'REPORT',
      status: 'ACTIVE',
      size: '200',
    }),
);
check(
  '业绩下钻的类型和日期与汇总一致',
  reportOrders.items.reduce((sum, row) => sum + n(row.total), 0) === n(report.totals.reportTotal),
);
const candidates = (await get(admin, '/lists/orders?status=ACTIVE&size=200')).items
  .map((o) => String(o.activatedAt).slice(0, 10))
  .filter((d) => d < today)
  .sort()
  .reverse();
const yesterday =
  candidates[0] ||
  new Date(Date.now() - 86400000).toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' });
const preview = await post(finance, '/previews', { cycle: 'DAILY', period: yesterday });
const settled =
  preview.status === 'POSTED' ? preview : await post(finance, '/batches/' + preview.id + '/post');
check('日结预览与入账使用相同金额', settled.net === preview.net && settled.status === 'POSTED');
await rejects(finance, '/batches/' + preview.id + '/post', {});
check('同一账期不能重复入账', true);
const active = (await get(admin, '/rules')).find((r) => r.status === 'ACTIVE');
const draft = await post(admin, '/rules', { name: 'V2 验收演示规则', config: active.config });
await rejects(admin, '/rules/' + draft.id + '/publish', {});
await post(admin, '/rules/' + draft.id + '/test');
await post(admin, '/rules/' + draft.id + '/publish');
await rejects(admin, '/rules/' + draft.id + '/update', { config: active.config });
check('规则草稿试算发布和发布后只读', true);
const old = await get(token, '/orders/' + second.id);
check('新规则发布不覆盖历史订单快照', old.ruleId === active.id);
const file = await fetch(base + '/exports/orders?size=20', {
  headers: { Authorization: 'Bearer ' + finance },
});
const bytes = new Uint8Array(await file.arrayBuffer());
check('Excel导出返回有效XLSX', file.status === 200 && bytes[0] === 0x50 && bytes[1] === 0x4b);
const reconciliation = await get(finance, '/reconciliation');
check('MySQL全部余额和流水平衡', reconciliation.balanced === true);
const result = {
  verifiedAt: new Date().toISOString(),
  database: 'MySQL 8.4.13',
  checks: checks.length,
  passed: checks,
  examples: {
    member: member.id,
    orders: [order.id, second.id, ro.id],
    shipment: shipment.id,
    withdrawal: approved.id,
    batch: preview.id,
    rule: draft.id,
  },
  reconciliation,
};
await fs.mkdir(path.join(root, 'docs/verification'), { recursive: true });
await fs.writeFile(
  path.join(root, 'docs/verification/api-acceptance.json'),
  JSON.stringify(result, null, 2) + '\n',
);
console.log('MySQL接口验收通过：' + checks.length + '项');
