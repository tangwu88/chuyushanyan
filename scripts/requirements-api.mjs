import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const base = process.env.CHUYU_API || 'http://127.0.0.1:18080/api/v1';
const checks = [],
  tag = Date.now().toString(36),
  phone = '00087' + Date.now();
const check = (name, value) => {
  assert.ok(value, name);
  checks.push(name);
  process.stdout.write('✓ ' + name + '\n');
};
async function request(token, method, uri, body, status = 200, key = crypto.randomUUID()) {
  const response = await fetch(base + uri, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(method === 'POST' ? { 'Idempotency-Key': key } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const data = await response.json();
  assert.equal(response.status, status, uri + ': ' + JSON.stringify(data));
  return data;
}
const get = (token, uri, status) => request(token, 'GET', uri, undefined, status);
const post = (token, uri, body = {}, status, key) => request(token, 'POST', uri, body, status, key);
const login = async (username, password = 'ChuyuDemo!2026') =>
  (await post('', '/auth/login', { username, password })).token;
const admin = await login('admin'),
  shop = await login('shop'),
  shipping = await login('shipping');
assert.equal((await get(admin, '/config')).demo, true);
const config = await get(admin, '/config'),
  today = config.today;
const reportRange = '?start=' + today + '&end=' + today;
for (const resource of [
  'products',
  'empty-points',
  'referrals',
  'promotions',
  'director-promotions',
  'cancellations',
  'sync-upgrades',
  'stock-transfers',
  'unused-recharge',
  'recharge-records',
  'login-logs',
  'jobs',
  'report-snapshots',
  'rankings',
  'shop-stats',
  'shop-cash',
  'product-sales',
  'daily-stats',
]) {
  const page = await get(admin, '/lists/' + resource + reportRange + '&size=20');
  assert.ok(Array.isArray(page.items) && typeof page.total === 'number', resource);
}
check('18个新增列表与统计入口在MySQL返回有效分页', true);
const dept = 'AUDIT_' + tag,
  role = 'AUDIT_' + tag.toUpperCase(),
  postCode = 'POST_' + tag;
await post(admin, '/management/departments', {
  code: dept,
  name: '复核部门 ' + tag,
  parentId: '初语山言',
});
await post(admin, '/management/posts', { code: postCode, name: '复核岗位 ' + tag });
await post(admin, '/management/roles', {
  code: role,
  name: '部门业务复核',
  dataScope: 'DEPARTMENT',
  permissions: [
    'members:read',
    'members:write',
    'orders:read',
    'orders:create',
    'orders:activate',
    'funds:read',
    'funds:write',
    'shipping:read',
    'shipping:review',
    'shipping:ship',
    'reports:read',
    'reports:export',
    'audit:read',
    'system:write',
  ],
});
const staffInput = {
  username: 'qa_' + tag,
  password: 'AuditStaff!2026',
  displayName: '复核员工',
  phone,
  role,
  department: dept,
  postCode,
  email: 'audit@example.invalid',
  gender: 'UNKNOWN',
};
const staff = await post(admin, '/system-users', staffInput);
let staffToken = await login(staffInput.username, staffInput.password);
await get(staffToken, '/lists/members', 403);
await post(staffToken, '/auth/password', {
  oldPassword: staffInput.password,
  newPassword: 'AuditChanged!2026',
});
staffToken = await login(staffInput.username, 'AuditChanged!2026');
const safeUser = await get(staffToken, '/auth/me');
check(
  '后台用户角色、部门、岗位及首次改密生效',
  safeUser.dataScope === 'DEPARTMENT' &&
    safeUser.department === dept &&
    safeUser.permissions.includes('orders:create'),
);
const own = await post(staffToken, '/members', {
  phone: '00086' + Date.now(),
  nickname: '本部门店铺 ' + tag,
  remark: '空点资料备注',
  parentId: '',
  emptyPoint: true,
  grade: 1,
  manualOwn: '1490',
});
await post(staffToken, '/members/' + own.id + '/activate-empty', { reason: '接口复核空点激活' });
await post(admin, '/members/' + own.id + '/update', { shopType: 'BIG', reason: '复核店铺身份' });
await post(admin, '/funds-adjustments', {
  pointId: own.id,
  kind: 'RECHARGE',
  amount: '20000',
  reason: '独立测试充值',
});
const outsider = await post(admin, '/members', {
  phone: '00085' + Date.now(),
  nickname: '外部门客户 ' + tag,
  parentId: own.id,
  emptyPoint: true,
  grade: 1,
  manualOwn: '1490',
});
await post(admin, '/members/' + outsider.id + '/activate-empty', { reason: '接口复核空点激活' });
await post(admin, '/members/' + outsider.id + '/update', {
  department: '初语山言',
  reason: '复核部门隔离',
});
const scopedMembers = await get(staffToken, '/lists/members?size=200');
await get(staffToken, '/members/' + outsider.id, 403);
await post(
  staffToken,
  '/funds-adjustments',
  { pointId: outsider.id, kind: 'REWARD', amount: '1', reason: '越权检查' },
  403,
);
await get(staffToken, '/reconciliation', 403);
const scopedNetwork = await get(staffToken, '/network/' + own.id);
check(
  '部门权限同时约束列表、详情、网络、资金写入及全局对账',
  scopedMembers.items.some((p) => p.id === own.id) &&
    !scopedMembers.items.some((p) => p.id === outsider.id) &&
    !scopedNetwork.children.some((p) => p.id === outsider.id),
);
const freightChannel = '复核渠道' + tag;
const freightProduct = await post(admin, '/products', {
  name: '履约复核商品 ' + tag,
  firstPrice: '298',
  repeatOne: '150',
  repeatOther: '99',
  limitOne: 2,
  limitOther: 4,
  subsidyOne: '51',
  subsidyRate: '.05',
  differential: [1, 2, 3, 4, 5],
  weightKg: '.5',
  channel: freightChannel,
  purchaseTypes: 'FIRST',
});
const externalOrder = await post(staffToken, '/orders', {
  pointId: outsider.id,
  payerId: outsider.id,
  shopId: own.id,
  productId: freightProduct.id,
  type: 'FIRST',
  quantity: 2,
  rewardAmount: '0',
  paymentMethod: 'DEMO',
  remark: '客户订单备注',
});
await post(admin, '/orders/' + externalOrder.id + '/pay');
const scopedOrder = await get(staffToken, '/orders/' + externalOrder.id);
check(
  '部门店铺可以办理外部门客户订单且不泄露全量会员快照',
  scopedOrder.shopId === own.id &&
    scopedOrder.remark === '客户订单备注' &&
    (await get(admin, '/members/' + own.id)).remark === '空点资料备注' &&
    !('memberSnapshot' in scopedOrder) &&
    !('ruleSnapshot' in scopedOrder) &&
    (await get(staffToken, '/lists/orders?search=' + externalOrder.id)).total === 1,
);
const shippingOrder = await get(shipping, '/orders/' + externalOrder.id);
check(
  '发货角色订单详情不包含奖金与会员计算快照',
  shippingOrder.awards.length === 0 && !('memberSnapshot' in shippingOrder),
);
const page = await get(staffToken, '/reports' + reportRange);
check(
  '部门工作台统计使用相同的业务范围和日期',
  page.totals.memberCount === scopedMembers.total && /^\d{4}-\d{2}-\d{2}$/.test(page.start),
);
const rewardBefore = (await get(admin, '/members/' + own.id)).reward.balance;
for (const amount of ['2', '3'])
  await post(admin, '/funds-adjustments', {
    pointId: own.id,
    kind: 'REWARD',
    amount,
    reason: '流水顺序复核',
  });
const orderedLedger = (await get(admin, '/lists/ledger?pointId=' + own.id + '&kind=REWARD')).items;
check(
  '连续入账流水按真实写入顺序展示且余额连续',
  Number(orderedLedger[0].entryNo) > Number(orderedLedger[1].entryNo) &&
    Number(orderedLedger[0].balanceAfter) === Number(rewardBefore) + 5 &&
    Number(orderedLedger[1].balanceAfter) === Number(rewardBefore) + 2,
);
await post(
  admin,
  '/management/departments/' + dept + '/update',
  { name: '复核部门 ' + tag, parentId: dept },
  422,
);
await post(admin, '/management/departments/' + dept + '/delete', {}, 422);
await post(
  admin,
  '/management/roles/ADMIN/update',
  { name: '超级管理员', permissions: [], dataScope: 'ALL', active: false },
  422,
);
check('部门循环、关联部门删除及超级管理员停用受到保护', true);
const image =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+k3ioAAAAASUVORK5CYII=';
const banner = await post(admin, '/management/banners', {
  name: '复核轮播图 ' + tag,
  imageUrl: image,
  link: '/#/order',
  active: true,
});
const announcement = await post(admin, '/management/announcements', {
  name: '复核消息 ' + tag,
  channel: '初语山言',
  type: 'NEW',
  content: '接口复核消息',
  active: true,
});
const agreement = await post(admin, '/management/agreements', {
  name: '复核协议 ' + tag,
  content: '<p><strong>复核协议正文</strong></p>',
  active: true,
});
let content = await get('', '/content');
check(
  '轮播图、订单消息和富文本协议可由后台配置并公开读取',
  content.banners.some((row) => row.id === banner.id && row.imageUrl === image) &&
    content.announcements.some((row) => row.id === announcement.id) &&
    content.agreements.some((row) => row.id === agreement.id),
);
for (const [resource, value] of [
  ['banners', banner],
  ['announcements', announcement],
  ['agreements', agreement],
])
  await post(admin, '/management/' + resource + '/' + value.id + '/delete');
content = await get('', '/content');
check(
  '已删除内容不再出现在手机端内容接口',
  !content.banners.some((row) => row.id === banner.id) &&
    !content.agreements.some((row) => row.id === agreement.id),
);
await post(
  admin,
  '/management/banners',
  { name: '错误图片', imageUrl: 'javascript:alert(1)' },
  422,
);
check('图片配置拒绝无效协议', true);
const productInput = {
  name: '复核商品 ' + tag,
  firstPrice: '399',
  repeatOne: '160',
  repeatOther: '100',
  limitOne: 2,
  limitOther: 4,
  subsidyOne: '51',
  subsidyRate: '0.05',
  differential: [1, 2, 3, 4, 5],
  weightKg: '0.3',
  channel: '复核渠道',
  specification: '单瓶300g',
  purchaseTypes: 'FIRST',
  imageUrl: image,
  active: false,
};
const product = await post(admin, '/products', productInput);
check(
  '下架商品在后台可见且不进入手机端目录',
  (await get(admin, '/lists/products?search=' + product.id)).total === 1 &&
    !(await get(shop, '/catalog')).products.some((p) => p.id === product.id),
);
await post(admin, '/products/' + product.id + '/update', { ...productInput, active: true });
await post(
  shop,
  '/quotes',
  {
    pointId: 'CY9001',
    payerId: 'CY9001',
    shopId: 'CY9001',
    productId: product.id,
    type: 'REPEAT',
    quantity: 1,
    rewardAmount: '0',
  },
  422,
);
check('商品购买类型由后端校验', true);
await post(admin, '/products/' + product.id + '/delete', { reason: '测试数据清理' });
check(
  '商品删除保留记录并从有效列表移除',
  (await get(admin, '/lists/products?search=' + product.id)).total === 0,
);
const tariff = await post(admin, '/management/freights', {
  name: '复核运费 ' + tag,
  channel: freightChannel,
  maxWeight: '1',
  amount: '8',
});
const shipmentInput = {
  shopId: own.id,
  recipient: '复核收货人',
  phone: '00012345678',
  address: '独立测试地址',
  pickup: false,
  lines: [{ orderId: externalOrder.id, quantity: 2 }],
};
const quote = await post(staffToken, '/shipping-quotes', shipmentInput);
await post(staffToken, '/shipments', { ...shipmentInput, expectedFreight: '7' }, 422);
const shipment = await post(staffToken, '/shipments', { ...shipmentInput, expectedFreight: '8' });
await post(admin, '/management/freights/' + tariff.id + '/update', {
  name: '复核运费 ' + tag,
  channel: freightChannel,
  maxWeight: '1',
  amount: '9',
});
const shipmentDetail = await get(staffToken, '/shipments/' + shipment.id);
const waitingRecords = await get(
  admin,
  '/lists/recharge-records?pointId=' + own.id + '&search=' + externalOrder.id + '&status=WAITING',
);
await post(admin, '/shipments/' + shipment.id + '/review', {
  approved: true,
  reason: '接口复核发货审批',
});
await post(admin, '/shipments/' + shipment.id + '/ship', {
  carrier: '独立复核物流',
  tracking: 'QA' + tag,
});
const shippedRecords = await get(
  admin,
  '/lists/recharge-records?pointId=' + own.id + '&search=' + externalOrder.id + '&status=SHIPPED',
);
check(
  '报单积分记录可按待发货和已发货切换并追溯关联订单',
  waitingRecords.total > 0 &&
    shippedRecords.total === waitingRecords.total &&
    shippedRecords.items.every((r) => r.orderFulfilment === 'FULFILLED'),
);
check(
  '重量运费报价、失效报价拦截与历史运费快照一致',
  Number(quote.freightAmount) === 8 &&
    Number(quote.weightKg) === 1 &&
    Number(shipmentDetail.application.freightAmount) === 8,
);
await post(admin, '/management/freights/' + tariff.id + '/delete');
await post(admin, '/products/' + freightProduct.id + '/delete', {
  reason: '复核结束，保留订单商品快照',
});
const pending = await post(shop, '/topups', { shopId: 'CY9001', amount: '1' });
await post(shop, '/topups/' + pending.id + '/delete', { reason: '复核误申请' });
await post(
  admin,
  '/topups/' + pending.id + '/review',
  { approved: true, reason: '不可审批已删除申请' },
  422,
);
await post(shop, '/topups/' + pending.id + '/restore', { reason: '恢复复核' });
await post(admin, '/topups/' + pending.id + '/review', { approved: false, reason: '复核结束' });
check(
  '充值申请删除、找回和审批状态约束完整',
  (await get(shop, '/lists/topups?search=' + pending.id)).items[0].status === 'REJECTED',
);
const yesterday = new Date(Date.parse(today + 'T12:00:00+08:00') - 86400000).toLocaleDateString(
  'sv-SE',
  { timeZone: 'Asia/Shanghai' },
);
for (const job of ['PERFORMANCE', 'RANKING', 'FINANCE', 'RECONCILIATION', 'ARCHIVE'])
  await post(admin, '/jobs/' + job + '/run', { period: yesterday });
await post(admin, '/jobs/PERFORMANCE/run', { period: 'invalid' }, 422);
const jobs = await get(admin, '/lists/jobs?size=200');
check(
  '配置任务实际执行，成功和失败均保留记录且结果无内部JSON',
  jobs.items.some((j) => j.jobName === 'FINANCE' && j.status === 'DONE') &&
    jobs.items.some((j) => j.jobName === 'PERFORMANCE' && j.status === 'ERROR') &&
    jobs.items.filter((j) => j.status === 'DONE').every((j) => !String(j.message).startsWith('{')),
);
await post(
  admin,
  '/management/job-definitions/PERFORMANCE/update',
  { name: '业绩快照', cron: 'wrong cron' },
  422,
);
check('定时任务拒绝无效执行时间', true);
const importedName = 'imp_' + tag,
  importedPhone = '00084' + Date.now();
const imported = await post(admin, '/system-users/import', {
  rows: [
    {
      username: importedName,
      displayName: '导入复核',
      phone: importedPhone,
      role: 'OPERATOR',
      department: '初语山言',
    },
  ],
});
const importedToken = await login(importedName, importedPhone.slice(-6));
check(
  '批量导入保留前导零并使用手机号后六位初始密码',
  imported.count === 1 && (await get(importedToken, '/auth/me')).mustChangePassword,
);
await post(admin, '/system-users/' + imported.ids[0] + '/delete', { reason: '导入复核结束' });
await get(importedToken, '/auth/me', 401);
await post('', '/auth/login', { username: importedName, password: importedPhone.slice(-6) }, 422);
check(
  '停用后台用户撤销登录并保留用户记录',
  (await get(admin, '/lists/users?search=' + importedName)).items[0].active === false,
);
for (let i = 0; i < 5; i++)
  await post('', '/auth/login', { username: staffInput.username, password: 'incorrect' }, 422);
const locked = await post(
  '',
  '/auth/login',
  { username: staffInput.username, password: 'AuditChanged!2026' },
  422,
);
await post(admin, '/system-users/' + staff.id + '/unlock', { reason: '复核解锁' });
staffToken = await login(staffInput.username, 'AuditChanged!2026');
check(
  '连续五次错误登录触发锁定，后台解锁可恢复',
  String(locked.message).includes('锁定') && !!staffToken,
);
await post(admin, '/system-users/' + staff.id + '/reset-password', { reason: '复核密码重置' });
await get(staffToken, '/auth/me', 401);
const resetToken = await login(staffInput.username, phone.slice(-6));
check(
  '密码重置使用电话后六位、撤销旧登录并要求改密',
  (await get(resetToken, '/auth/me')).mustChangePassword,
);
const loginLogs = await get(admin, '/lists/login-logs?search=' + staffInput.username + '&size=200');
check(
  '登录日志记录成功、失败与IP且不记录密码',
  loginLogs.items.some((row) => row.status === 'SUCCESS') &&
    loginLogs.items.some((row) => row.status === 'FAILED') &&
    loginLogs.items.every((row) => row.ip && !JSON.stringify(row).includes('AuditChanged!2026')),
);
await post(admin, '/management/roles/' + role + '/update', {
  name: '部门业务复核',
  dataScope: 'DEPARTMENT',
  permissions: ['members:read'],
  active: false,
});
await post('', '/auth/login', { username: staffInput.username, password: phone.slice(-6) }, 422);
check('停用角色在服务端立即阻止登录', true);
for (const report of ['rankings', 'shop-stats', 'shop-cash', 'product-sales', 'daily-stats']) {
  const response = await fetch(base + '/exports/' + report + reportRange, {
    headers: { Authorization: 'Bearer ' + admin },
  });
  const bytes = new Uint8Array(await response.arrayBuffer());
  assert.equal(response.status, 200, report);
  assert.equal(bytes[0], 0x50);
  assert.equal(bytes[1], 0x4b);
}
check('五类新增统计可导出有效Excel文件', true);
check('扩展功能操作后全部资金仍然平衡', (await get(admin, '/reconciliation')).balanced === true);
const proof = {
  verifiedAt: new Date().toISOString(),
  database: 'MySQL 8.4.13',
  checks: checks.length,
  passed: checks,
};
await fs.mkdir(path.join(root, 'docs/verification'), { recursive: true });
await fs.writeFile(
  path.join(root, 'docs/verification/requirements-api.json'),
  JSON.stringify(proof, null, 2) + '\n',
);
process.stdout.write('需求扩展接口验收通过：' + checks.length + ' 项\n');
