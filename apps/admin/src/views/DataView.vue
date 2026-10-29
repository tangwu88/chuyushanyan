<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, state, can, globalCan, hydrate } from '../state';
import {
  money,
  businessText,
  label,
  grade,
  query,
  dateTime,
  orderStatus,
  percent,
  type Row,
  type Page,
} from '@chuyu/shared';
import ActionDialogs from '../components/ActionDialogs.vue';
import ManagementView from '../components/ManagementView.vue';
import AuditDetails from '../components/AuditDetails.vue';
const props = defineProps<{ section: string }>();
const route = useRoute();
const tab = ref(
  props.section === 'finance'
    ? 'ledger'
    : props.section === 'system'
      ? can('system:write')
        ? 'users'
        : 'audits'
      : props.section === 'orders'
        ? 'orders'
        : 'members',
);
if (typeof route.query.tab === 'string') tab.value = route.query.tab;
const resource = computed(() => tab.value);
const rawTabs = computed(() =>
  props.section === 'orders'
    ? [
        ['orders', '订单管理'],
        ['shipments', '发货管理'],
        ['products', '商品配置'],
        ['stock-transfers', '转货记录'],
        ['freights', '运费管理'],
      ]
    : props.section === 'finance'
      ? [
          ['ledger', '资金流水'],
          ['awards', '奖金记录'],
          ['withdrawals', '提现审核'],
          ['topups', '充值审批'],
          ['batches', '结算批次'],
          ['recharge-records', '报单积分记录'],
          ['unused-recharge', '未消费积分'],
          ...(can('reports:read')
            ? [
                ['daily-stats', '财务统计'],
                ['rankings', '会员排名'],
                ['shop-cash', '店铺应收现金'],
                ['shop-stats', '店铺统计'],
                ['product-sales', '商品销量'],
              ]
            : []),
        ]
      : props.section === 'system'
        ? [
            ...(can('system:write')
              ? [
                  ['users', '后台用户'],
                  ['roles', '角色权限'],
                  ['departments', '部门管理'],
                  ['posts', '岗位管理'],
                  ['menus', '菜单与按钮'],
                  ['parameters', '保留策略'],
                ]
              : []),
            ['audits', '操作记录'],
            ['jobs', '定时任务'],
            ['job-definitions', '任务配置与补跑'],
            ['login-logs', '登录日志'],
            ...(can('rules:read')
              ? [
                  ['announcements', '订单消息'],
                  ['banners', '轮播图'],
                  ['agreements', '协议管理'],
                  ['appearance', '页面颜色'],
                ]
              : []),
          ]
        : [
            ['members', '会员管理'],
            ['empty-points', '空点管理'],
            ['sync-upgrades', '同步升级'],
            ['referrals', '推荐变动'],
            ['promotions', '会员晋升'],
            ['director-promotions', '董事升级'],
            ['cancellations', '注销找回'],
          ],
);
const tabPermissions: Record<string, string> = {
  'stock-transfers': 'funds:read',
  freights: 'rules:read',
  shipments: 'shipping:read',
  users: 'system:write',
  roles: 'system:write',
  departments: 'system:write',
  posts: 'system:write',
  menus: 'system:write',
  parameters: 'system:write',
  audits: 'audit:read',
  jobs: 'audit:read',
  'job-definitions': 'audit:read',
  'login-logs': 'audit:read',
};
const tabs = computed(() =>
  rawTabs.value.filter(
    (t) =>
      (!state.user?.dataScope ||
        state.user.dataScope === 'ALL' ||
        !['users', 'audits', 'jobs', 'login-logs', 'batches'].includes(t[0])) &&
      (!tabPermissions[t[0]] || can(tabPermissions[t[0]])),
  ),
);
if (!tabs.value.some((t) => t[0] === tab.value)) tab.value = tabs.value[0]?.[0] || 'members';
const managedResources = [
  'roles',
  'departments',
  'posts',
  'menus',
  'freights',
  'announcements',
  'banners',
  'agreements',
  'appearance',
  'parameters',
  'job-definitions',
];
const managed = computed(() => managedResources.includes(resource.value));
const statistic = computed(() =>
  ['rankings', 'shop-stats', 'shop-cash', 'product-sales', 'daily-stats'].includes(resource.value),
);
watch(
  () => route.query.tab,
  (v) => {
    if (typeof v === 'string' && tabs.value.some((t) => t[0] === v)) tab.value = v;
  },
);
const descriptions: Record<string, string> = {
  members: '账户、等级与推荐关系各自清楚，变动均有记录。',
  orders: '从报价到发货，跟踪每一笔订单的完整进度。',
  finance: '分清积分、扣费与实得，逐笔追溯资金流向。',
  system: '管理协作权限，查看关键操作与任务执行情况。',
};
const title: Record<string, string> = {
  members: '会员与关系',
  orders: '交易与履约',
  finance: '资金与结算',
  system: '系统设置',
};
const columns: Record<string, Array<[string, string, string?]>> = {
  members: [
    ['id', '点位编号'],
    ['nickname', '昵称'],
    ['phone', '手机号'],
    ['grade', '会员等级', 'grade'],
    ['directorGrade', '董事等级'],
    ['shopType', '店铺身份', 'enum'],
    ['reward.balance', '奖励积分', 'money'],
    ['recharge.balance', '充值积分', 'money'],
    ['team', '团队业绩', 'money'],
    ['small', '小市场业绩', 'money'],
  ],
  orders: [
    ['id', '订单编号'],
    ['pointId', '购买点位'],
    ['orderType', '类型', 'enum'],
    ['total', '订单金额', 'money'],
    ['quantity', '瓶数'],
    ['shopId', '代办店铺'],
    ['displayStatus', '业务进度', 'enum'],
    ['createdAt', '创建时间', 'date'],
  ],
  shipments: [
    ['id', '发货申请'],
    ['shopId', '店铺'],
    ['recipient', '收货人'],
    ['phone', '电话'],
    ['status', '状态', 'enum'],
    ['carrier', '快递公司'],
    ['tracking', '运单号'],
    ['createdAt', '申请时间', 'date'],
  ],
  ledger: [
    ['pointId', '账户点位'],
    ['walletKind', '积分类型', 'enum'],
    ['delta', '变动金额', 'money'],
    ['balanceAfter', '变动后余额', 'money'],
    ['frozenAfter', '冻结金额', 'money'],
    ['description', '业务说明'],
    ['createdAt', '时间', 'date'],
  ],
  awards: [
    ['recipientId', '受益账户'],
    ['awardType', '奖项', 'enum'],
    ['gross', '毛奖', 'money'],
    ['fee', '管理费', 'money'],
    ['net', '实得', 'money'],
    ['ruleId', '规则版本'],
    ['explanation', '计算依据'],
    ['createdAt', '入账时间', 'date'],
  ],
  withdrawals: [
    ['pointId', '会员点位'],
    ['nickname', '姓名'],
    ['phone', '电话'],
    ['gross', '申请金额', 'money'],
    ['fee', '手续费', 'money'],
    ['tax', '演示税额', 'money'],
    ['net', '到账金额', 'money'],
    ['bankMask', '银行卡'],
    ['bankName', '开户行'],
    ['accountName', '开户姓名'],
    ['identityMask', '身份证'],
    ['reviewerName', '审核人'],
    ['reviewedAt', '审核时间', 'date'],
    ['status', '状态', 'enum'],
    ['createdAt', '申请时间', 'date'],
  ],
  topups: [
    ['shopId', '店铺'],
    ['amount', '申请金额', 'money'],
    ['status', '状态', 'enum'],
    ['requestBy', '申请人'],
    ['createdAt', '申请时间', 'date'],
  ],
  batches: [
    ['period', '账期'],
    ['periodType', '结算类型', 'enum'],
    ['ruleId', '规则版本'],
    ['gross', '毛奖', 'money'],
    ['fee', '管理费', 'money'],
    ['net', '实发', 'money'],
    ['status', '状态', 'enum'],
    ['createdAt', '时间', 'date'],
  ],
  products: [
    ['id', '商品编号'],
    ['name', '商品名称'],
    ['firstPrice', '首购价格', 'money'],
    ['repeatOne', '一星复购', 'money'],
    ['repeatOther', '二星及以上复购', 'money'],
    ['weightKg', '重量 / kg'],
    ['active', '上架状态', 'bool'],
    ['channel', '渠道'],
    ['purchaseTypes', '购买类型'],
  ],
  users: [
    ['username', '登录账号'],
    ['displayName', '姓名'],
    ['role', '角色', 'enum'],
    ['department', '部门'],
    ['active', '可用', 'bool'],
    ['createdAt', '创建时间', 'date'],
  ],
  audits: [
    ['action', '操作类型', 'enum'],
    ['actorName', '操作人'],
    ['targetId', '目标记录'],
    ['reason', '操作原因'],
    ['createdAt', '操作时间', 'date'],
  ],
  jobs: [
    ['jobName', '任务名称', 'enum'],
    ['period', '账期'],
    ['status', '状态', 'enum'],
    ['message', '执行结果'],
    ['startedAt', '开始时间', 'date'],
  ],
};
Object.assign(columns, {
  'empty-points': columns.members,
  'stock-transfers': [
    ['id', '记录编号'],
    ['fromId', '转出店铺'],
    ['toId', '接收店铺'],
    ['amount', '金额', 'money'],
    ['createdAt', '转货时间', 'date'],
  ],
  referrals: [
    ['nickname', '会员'],
    ['phone', '电话'],
    ['before.parentId', '原推荐人'],
    ['after.parentId', '新推荐人'],
    ['actorId', '操作人'],
    ['reason', '原因'],
    ['createdAt', '变更时间', 'date'],
  ],
  promotions: [
    ['nickname', '会员'],
    ['phone', '电话'],
    ['before', '原等级'],
    ['after', '新等级'],
    ['reason', '原因'],
    ['createdAt', '变更时间', 'date'],
  ],
  'director-promotions': [
    ['nickname', '会员'],
    ['phone', '电话'],
    ['before', '原董事等级'],
    ['after', '新董事等级'],
    ['reason', '原因'],
    ['createdAt', '变更时间', 'date'],
  ],
  cancellations: [
    ['nickname', '会员'],
    ['phone', '电话'],
    ['snapshot.grade', '注销时等级', 'grade'],
    ['snapshot.shopType', '店铺身份', 'enum'],
    ['reason', '注销原因'],
    ['restored', '已找回', 'yesno'],
    ['createdAt', '注销时间', 'date'],
  ],
  'sync-upgrades': [
    ['fromPoint', '发起点位'],
    ['targetPoint', '升级点位'],
    ['expectedGrade', '申请等级', 'grade'],
    ['status', '状态', 'enum'],
    ['reviewBy', '审批人'],
    ['reason', '说明'],
    ['createdAt', '申请时间', 'date'],
  ],
  'unused-recharge': [
    ['id', '店铺编号'],
    ['nickname', '店铺'],
    ['phone', '电话'],
    ['balance', '充值积分', 'money'],
    ['frozen', '冻结积分', 'money'],
    ['restricted', '受限积分', 'money'],
  ],
  'recharge-records': [...columns.ledger, ['orderFulfilment', '关联订单发货', 'enum']],
  rankings: [
    ['rank', '排名'],
    ['nickname', '会员'],
    ['phone', '电话'],
    ['parentId', '推荐人'],
    ['grade', '会员等级', 'grade'],
    ['directorGrade', '董事等级'],
    ['rewardBalance', '奖励积分', 'money'],
    ['dayReport', '日报单业绩', 'money'],
    ['own', '个人报单业绩', 'money'],
    ['team', '团队报单业绩', 'money'],
    ['dayRepeat', '日复购业绩', 'money'],
    ['monthRepeat', '本月复购', 'money'],
    ['lastMonthRepeat', '上月复购', 'money'],
    ['smallWeek', '小市场周新增', 'money'],
    ['smallMonth', '小市场月新增', 'money'],
    ['smallDay', '小市场日新增', 'money'],
    ['average', '周日均小市场', 'money'],
    ['snapshotDate', '归属日期'],
  ],
  'shop-stats': [
    ['rank', '排名'],
    ['id', '店铺编号'],
    ['nickname', '店铺'],
    ['phone', '电话'],
    ['reportTotal', '报单业绩', 'money'],
    ['repeatTotal', '复购业绩', 'money'],
    ['total', '总业绩', 'money'],
    ['rechargeBalance', '充值积分', 'money'],
    ['rewardBalance', '奖励积分', 'money'],
  ],
  'shop-cash': [
    ['id', '店铺编号'],
    ['nickname', '店铺'],
    ['phone', '电话'],
    ['rechargeTotal', '本期现金充值', 'money'],
    ['companyReward', '本期转公司积分', 'money'],
    ['cashReceivable', '应收现金', 'money'],
  ],
  'product-sales': [
    ['productName', '商品'],
    ['orderType', '购买类型', 'enum'],
    ['quantity', '售出数量'],
    ['total', '售出金额', 'money'],
    ['orderCount', '订单数'],
  ],
  'daily-stats': [
    ['day', '业务日期'],
    ['reportTotal', '报单业绩', 'money'],
    ['reportAward', '报单奖金', 'money'],
    ['reportRatio', '报单拨比', 'percent'],
    ['repeatTotal', '复购业绩', 'money'],
    ['repeatAward', '复购奖金', 'money'],
    ['repeatRatio', '复购拨比', 'percent'],
    ['totalAward', '综合业绩奖金', 'money'],
    ['net', '总实发奖金', 'money'],
    ['payoutRatio', '总拨比', 'percent'],
  ],
  'login-logs': [
    ['username', '登录账号'],
    ['ip', 'IP地址'],
    ['userAgent', '浏览器与系统'],
    ['status', '状态', 'enum'],
    ['message', '结果'],
    ['createdAt', '登录时间', 'date'],
  ],
});
columns.shipments.splice(
  3,
  0,
  ['goodsAmount', '申请商品金额', 'money'],
  ['freightAmount', '运费', 'money'],
);
columns.members.splice(2, 0, ['isMain', '主号', 'yesno'], ['gradeVersion', '等级版本']);
columns.topups.splice(1, 0, ['balance', '充值积分余额', 'money']);
const filters = ref<Row>({
    search: '',
    status: route.query.status || '',
    start: route.query.start || '',
    end: route.query.end || '',
    type: route.query.type || '',
    dateField: route.query.dateField || '',
    page: 1,
    size: 20,
  }),
  data = ref<Page>({ items: [], total: 0, page: 1, size: 20 }),
  loading = ref(false),
  error = ref(''),
  dialogs = ref<InstanceType<typeof ActionDialogs>>(),
  reconcile = ref<Row | null>(null),
  auditDetail = ref<Row | null>(null);
function value(row: Row, key: string) {
  return key.split('.').reduce((v: any, k) => v?.[k], row);
}
async function load() {
  loading.value = true;
  error.value = '';
  try {
    if (!managed.value) {
      if (statistic.value && !filters.value.start && !filters.value.end) {
        const today = state.config.today || new Date().toLocaleDateString('sv-SE');
        filters.value.start = today.slice(0, 7) + '-01';
        filters.value.end = today;
      }
      data.value = await api.get('/lists/' + resource.value + '?' + query(filters.value));
      if (resource.value === 'orders')
        data.value.items.forEach((r) => (r.displayStatus = orderStatus(r)));
    }
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
function dateRange(kind: string) {
  const date = new Date(
    (state.config.today || new Date().toLocaleDateString('sv-SE')) + 'T12:00:00',
  );
  let from = new Date(date),
    to = new Date(date);
  if (kind === 'yesterday') {
    from.setDate(from.getDate() - 1);
    to = new Date(from);
  }
  if (kind === 'month') from.setDate(1);
  if (kind === 'lastMonth') {
    from = new Date(date.getFullYear(), date.getMonth() - 1, 1);
    to = new Date(date.getFullYear(), date.getMonth(), 0);
  }
  filters.value.start = from.toLocaleDateString('sv-SE');
  filters.value.end = to.toLocaleDateString('sv-SE');
  filters.value.page = 1;
  load();
}
watch(
  resource,
  (_, old) => {
    if (old) filters.value = { ...filters.value, page: 1, status: '', search: '', type: '' };
    load();
  },
  { immediate: true },
);
async function action(path: string, body: Row = {}, prompt = '确认执行此操作？') {
  try {
    const { value: reason } = await ElMessageBox.prompt(prompt, '操作确认', {
      inputPlaceholder: '请填写操作说明',
      inputValidator: (v) => !!v?.trim() || '请输入操作说明',
      confirmButtonText: '确认执行',
      cancelButtonText: '取消',
    });
    await api.post(path, { ...body, reason });
    ElMessage.success('操作已完成');
    await hydrate();
    await load();
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message);
  }
}
async function pay(row: Row) {
  try {
    await ElMessageBox.confirm(
      '此操作使用演示支付，不产生实际收款。确认后会扣除所选积分并按规则激活订单。',
      '确认演示支付',
      { confirmButtonText: '确认演示支付', cancelButtonText: '取消' },
    );
    await api.post('/orders/' + row.id + '/pay');
    ElMessage.success('演示支付完成');
    await hydrate();
    load();
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message);
  }
}
async function exportData() {
  try {
    await api.export(resource.value, query(filters.value));
    ElMessage.success('导出完成');
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
async function check() {
  try {
    reconcile.value = await api.get('/reconciliation');
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
async function importUsers() {
  try {
    const { value } = await ElMessageBox.prompt(
      '每行填写：账号,姓名,电话,角色编号,部门。初始密码为电话后六位。',
      '导入后台用户',
      {
        inputType: 'textarea',
        inputPlaceholder: 'staff01,员工,001234567890,OPERATOR,初语山言',
        confirmButtonText: '导入',
        cancelButtonText: '取消',
      },
    );
    const rows = value
      .trim()
      .split(/\r?\n/)
      .filter(Boolean)
      .map((line) => {
        const [username, displayName, phone, role = 'OPERATOR', department = '初语山言'] = line
          .split(',')
          .map((v) => v.trim());
        return { username, displayName, phone, role, department };
      });
    const result = await api.post('/system-users/import', { rows });
    ElMessage.success('已导入' + result.count + '名用户');
    await load();
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message);
  }
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>{{ title[section] }}</h1>
    </div>
    <div class="heading-actions">
      <el-button
        v-if="['members', 'empty-points'].includes(resource) && can('members:write')"
        type="primary"
        @click="dialogs?.open('member', { emptyPoint: resource === 'empty-points' })"
        >＋ 新增会员</el-button
      ><el-button
        v-if="resource === 'orders' && can('orders:create')"
        type="primary"
        @click="dialogs?.open('order')"
        >＋ 创建订单</el-button
      ><el-button
        v-if="resource === 'shipments' && can('shipping:review')"
        type="primary"
        @click="dialogs?.open('shipment')"
        >＋ 发货申请</el-button
      ><el-button
        v-if="resource === 'products' && globalCan('rules:write')"
        type="primary"
        @click="dialogs?.open('product')"
        >＋ 新增商品</el-button
      ><el-button
        v-if="section === 'finance' && can('funds:write')"
        @click="dialogs?.open('adjust')"
        >资金调整</el-button
      ><el-button
        v-if="section === 'finance' && globalCan('funds:write')"
        type="primary"
        @click="dialogs?.open('preview')"
        >生成结算预览</el-button
      ><el-button
        v-if="resource === 'users' && globalCan('system:write')"
        type="primary"
        @click="dialogs?.open('user')"
        >＋ 新增用户</el-button
      >
      <el-button
        v-if="resource === 'sync-upgrades' && can('members:write')"
        type="primary"
        @click="dialogs?.open('sync')"
        >发起同步升级</el-button
      >
      <el-button
        v-if="resource === 'stock-transfers' && can('funds:write')"
        type="primary"
        @click="dialogs?.open('stock')"
        >店铺转货</el-button
      >
      <el-button v-if="resource === 'users' && globalCan('system:write')" @click="importUsers"
        >导入用户</el-button
      >
      <el-button
        v-if="['audits', 'login-logs'].includes(resource) && globalCan('system:write')"
        @click="
          action('/logs/' + resource + '/all/delete', {}, '清空当前日志列表？原始记录保留归档。')
        "
        >清空日志</el-button
      >
    </div>
  </div>
  <div v-if="tabs.length" class="module-tabs">
    <button v-for="t in tabs" :key="t[0]" :class="{ active: tab === t[0] }" @click="tab = t[0]">
      {{ t[1] }}
    </button>
  </div>
  <ManagementView v-if="managed" :resource="resource" />
  <section v-else class="surface data-surface">
    <div class="list-toolbar">
      <div>
        <el-input
          v-model="filters.search"
          :placeholder="'精确搜索：编号 / 电话 / 名称'"
          clearable
          class="search-input"
          @keyup.enter="
            filters.page = 1;
            load();
          "
        /><el-select
          v-if="
            [
              'members',
              'empty-points',
              'products',
              'sync-upgrades',
              'orders',
              'shipments',
              'withdrawals',
              'topups',
              'batches',
            ].includes(resource)
          "
          v-model="filters.status"
          placeholder="全部状态"
          clearable
          style="width: 150px"
          @change="
            filters.page = 1;
            load();
          "
          ><el-option
            v-for="s in ['members', 'empty-points', 'products'].includes(resource)
              ? ['ACTIVE', 'FROZEN']
              : resource === 'orders'
                ? [
                    'UNPAID',
                    'PENDING',
                    'ACTIVE',
                    'UNFULFILLED',
                    'APPLIED',
                    'PARTIAL',
                    'FULFILLED',
                    'CANCELLED',
                  ]
                : resource === 'batches'
                  ? ['PREVIEW', 'POSTED']
                  : ['PENDING', 'APPROVED', 'REJECTED', 'SHIPPED', 'PAID_DEMO']"
            :key="s"
            :value="s"
            :label="
              ['members', 'empty-points', 'products'].includes(resource)
                ? s === 'ACTIVE'
                  ? resource === 'products'
                    ? '上架'
                    : '正常'
                  : resource === 'products'
                    ? '下架'
                    : '冻结'
                : label(s)
            " /></el-select
        ><el-button
          type="primary"
          plain
          @click="
            filters.page = 1;
            load();
          "
          >查询</el-button
        ><el-button
          @click="
            filters.search = '';
            filters.status = '';
            filters.start = '';
            filters.end = '';
            filters.type = '';
            filters.dateField = '';
            filters.includeArchived = false;
            filters.deleted = false;
            filters.page = 1;
            load();
          "
          >重置</el-button
        >
      </div>
      <div>
        <el-button
          v-if="
            section === 'finance' &&
            can('funds:read') &&
            state.user?.dataScope !== 'DEPARTMENT' &&
            state.user?.dataScope !== 'SELF'
          "
          @click="check"
          >账本核对</el-button
        ><el-button v-if="can('reports:export')" @click="exportData">导出 Excel</el-button
        ><el-button :loading="loading" @click="load()">刷新</el-button>
      </div>
    </div>
    <div class="list-subtoolbar">
      <el-button-group v-if="resource === 'recharge-records'"
        ><el-button
          :type="!filters.status ? 'primary' : 'default'"
          @click="
            filters.status = '';
            filters.page = 1;
            load();
          "
          >合计</el-button
        ><el-button
          :type="filters.status === 'WAITING' ? 'primary' : 'default'"
          @click="
            filters.status = 'WAITING';
            filters.page = 1;
            load();
          "
          >待发货</el-button
        ><el-button
          :type="filters.status === 'SHIPPED' ? 'primary' : 'default'"
          @click="
            filters.status = 'SHIPPED';
            filters.page = 1;
            load();
          "
          >已发货</el-button
        ></el-button-group
      >
      <span
        >共 <b>{{ data.total }}</b> 条记录</span
      >
      <div>
        <template v-if="statistic"
          ><el-button size="small" @click="dateRange('today')">今日</el-button
          ><el-button size="small" @click="dateRange('yesterday')">昨日</el-button
          ><el-button size="small" @click="dateRange('month')">本月</el-button
          ><el-button size="small" @click="dateRange('lastMonth')">上月</el-button></template
        >
        <el-checkbox
          v-if="['orders', 'ledger', 'topups', 'recharge-records'].includes(resource)"
          v-model="filters.includeArchived"
          @change="
            filters.page = 1;
            load();
          "
          >含归档记录</el-checkbox
        >
        <el-checkbox
          v-if="resource === 'topups'"
          v-model="filters.deleted"
          @change="
            filters.page = 1;
            load();
          "
          >已删除申请</el-checkbox
        >
        <el-select
          v-if="resource === 'orders'"
          v-model="filters.type"
          placeholder="全部订单类型"
          clearable
          style="width: 130px"
          @change="
            filters.page = 1;
            load();
          "
          ><el-option v-for="t in ['NEW', 'FIRST', 'REPEAT']" :key="t" :value="t" :label="label(t)"
        /></el-select>
        <el-date-picker
          v-model="filters.start"
          value-format="YYYY-MM-DD"
          placeholder="开始日期"
          style="width: 140px"
        /><span>至</span
        ><el-date-picker
          v-model="filters.end"
          value-format="YYYY-MM-DD"
          placeholder="结束日期"
          style="width: 140px"
        />
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon /><el-alert
      v-if="reconcile"
      :title="
        reconcile.balanced
          ? '核对通过：全部余额与流水一致，每笔资金变动借贷平衡'
          : '核对发现差异，请查看记录'
      "
      :type="reconcile.balanced ? 'success' : 'error'"
      show-icon
      @close="reconcile = null"
    />
    <el-table
      v-loading="loading"
      :data="data.items"
      row-key="id"
      empty-text="当前筛选条件下暂无记录"
      stripe
      ><el-table-column
        v-for="c in columns[resource] || []"
        :key="c[0]"
        :prop="c[0]"
        :label="c[1]"
        :min-width="
          c[0].includes('explanation') || c[0] === 'description'
            ? 240
            : c[2] === 'date'
              ? 170
              : c[0] === 'id'
                ? 190
                : 120
        "
        :align="c[2] === 'money' ? 'right' : 'left'"
        show-overflow-tooltip
        ><template #default="{ row }"
          ><span
            v-if="c[2] === 'money'"
            :class="{ negative: Number(value(row, c[0])) < 0 }"
            class="amount"
            >{{ money(value(row, c[0])) }}</span
          ><el-tag
            v-else-if="c[2] === 'enum'"
            :type="['CANCELLED', 'REJECTED'].includes(String(value(row, c[0]))) ? 'danger' : 'info'"
            size="small"
            >{{ label(value(row, c[0])) }}</el-tag
          ><span v-else-if="c[2] === 'grade'">{{ grade(value(row, c[0])) }}</span
          ><span v-else-if="c[2] === 'date'">{{ dateTime(value(row, c[0])) }}</span
          ><span v-else-if="c[2] === 'bool'">{{
            value(row, c[0])
              ? resource === 'products'
                ? '上架'
                : '正常'
              : resource === 'products'
                ? '下架'
                : '冻结'
          }}</span
          ><span v-else-if="c[2] === 'yesno'">{{ value(row, c[0]) ? '是' : '否' }}</span
          ><span v-else-if="c[2] === 'percent'">{{ percent(value(row, c[0])) }}</span
          ><span v-else>{{ businessText(value(row, c[0])) }}</span></template
        ></el-table-column
      >
      <el-table-column
        v-if="!statistic"
        label="操作"
        fixed="right"
        :width="['members', 'empty-points', 'users'].includes(resource) ? 290 : 220"
        ><template #default="{ row }">
          <template v-if="['members', 'empty-points'].includes(resource)"
            ><el-button link type="primary" @click="dialogs?.open('memberDetail', row)"
              >详情</el-button
            ><el-button link type="primary" @click="dialogs?.open('network', row)">网络</el-button
            ><el-button
              v-if="can('members:write')"
              link
              type="primary"
              @click="dialogs?.open('editMember', row)"
              >调整</el-button
            ><el-button
              v-if="can('members:write')"
              link
              @click="
                action(
                  '/members/' + row.id + '/reset-password',
                  {},
                  '重置该会员登录密码？首次登录将要求修改。',
                )
              "
              >重置密码</el-button
            ></template
          >
          <el-button
            v-if="resource === 'empty-points' && row.emptyPoint && can('members:write')"
            link
            type="primary"
            @click="
              action(
                '/members/' + row.id + '/activate-empty',
                {},
                '将空点转为正常点位？历史奖金不补发。',
              )
            "
            >激活空点</el-button
          >
          <el-button
            v-if="resource === 'cancellations'"
            link
            type="primary"
            @click="auditDetail = row.snapshot"
            >注销快照</el-button
          >
          <el-button
            v-if="resource === 'cancellations' && !row.restored && can('members:write')"
            link
            type="primary"
            @click="action('/cancellations/' + row.id + '/restore', {}, '找回此会员点位？')"
            >注销找回</el-button
          >
          <template
            v-if="resource === 'sync-upgrades' && row.status === 'PENDING' && can('members:write')"
            ><el-button
              link
              type="primary"
              @click="
                action(
                  '/sync-upgrades/' + row.id + '/review',
                  { approved: true },
                  '核验业绩条件并通过升级？',
                )
              "
              >通过</el-button
            ><el-button
              link
              type="danger"
              @click="
                action(
                  '/sync-upgrades/' + row.id + '/review',
                  { approved: false },
                  '驳回此升级申请？',
                )
              "
              >驳回</el-button
            ></template
          >
          <template v-if="resource === 'orders'"
            ><el-button link type="primary" @click="dialogs?.open('detail', row)">查看</el-button
            ><el-button
              v-if="!row.cancelled && row.paymentStatus === 'UNPAID' && can('orders:create')"
              link
              type="primary"
              @click="pay(row)"
              >演示支付</el-button
            ><el-button
              v-if="
                !row.cancelled &&
                row.paymentStatus === 'PAID' &&
                row.activationStatus === 'PENDING' &&
                can('orders:activate')
              "
              link
              type="primary"
              @click="action('/orders/' + row.id + '/activate', {}, '激活订单并发放实时奖金？')"
              >激活</el-button
            ><el-button
              v-if="!row.cancelled && can('orders:cancel')"
              link
              type="danger"
              @click="
                action(
                  '/orders/' + row.id + '/cancel',
                  {},
                  '撤销订单将冲回对应奖金，并按规则退回店铺受限充值积分。',
                )
              "
              >撤销</el-button
            ></template
          >
          <template v-if="resource === 'shipments'"
            ><el-button link type="primary" @click="dialogs?.open('shipmentDetail', row)"
              >详情</el-button
            ><template v-if="row.status === 'PENDING' && can('shipping:review')"
              ><el-button
                link
                type="primary"
                @click="
                  action('/shipments/' + row.id + '/review', { approved: true }, '通过此发货申请？')
                "
                >通过</el-button
              ><el-button
                link
                type="danger"
                @click="
                  action(
                    '/shipments/' + row.id + '/review',
                    { approved: false },
                    '驳回此发货申请？',
                  )
                "
                >驳回</el-button
              ></template
            ><el-button
              v-if="row.status === 'APPROVED' && can('shipping:ship')"
              link
              type="primary"
              @click="dialogs?.open('ship', row)"
              >{{ row.pickup ? '确认自提' : '登记发货' }}</el-button
            ><span v-if="['SHIPPED', 'PICKED_UP'].includes(row.status)" class="muted"
              >物流已留存</span
            ></template
          >
          <template
            v-if="resource === 'withdrawals' && row.status === 'PENDING' && can('withdraw:review')"
            ><el-button
              link
              type="primary"
              @click="
                action(
                  '/withdrawals/' + row.id + '/review',
                  { approved: true },
                  '确认演示提现审核通过并回收积分？不产生实际银行转账。',
                )
              "
              >通过</el-button
            ><el-button
              link
              type="danger"
              @click="
                action(
                  '/withdrawals/' + row.id + '/review',
                  { approved: false },
                  '驳回并解冻提现积分？',
                )
              "
              >驳回</el-button
            ></template
          >
          <template
            v-if="
              resource === 'topups' &&
              row.status === 'PENDING' &&
              !row.deleted &&
              can('funds:write')
            "
            ><el-button
              link
              type="primary"
              @click="
                action('/topups/' + row.id + '/review', { approved: true }, '确认演示充值到账？')
              "
              >通过</el-button
            ><el-button
              link
              type="danger"
              @click="
                action('/topups/' + row.id + '/review', { approved: false }, '驳回该充值申请？')
              "
              >驳回</el-button
            ></template
          >
          <el-button
            v-if="resource === 'topups' && row.status === 'PENDING' && can('funds:write')"
            link
            @click="
              action(
                '/topups/' + row.id + (row.deleted ? '/restore' : '/delete'),
                {},
                row.deleted ? '找回充值申请？' : '删除此待审批申请？',
              )
            "
            >{{ row.deleted ? '找回' : '删除' }}</el-button
          >
          <template v-if="resource === 'batches'"
            ><el-button link type="primary" @click="dialogs?.open('batch', row)">明细</el-button
            ><el-button
              v-if="row.status === 'PREVIEW' && can('funds:write')"
              link
              type="primary"
              @click="
                action(
                  '/batches/' + row.id + '/post',
                  {},
                  '按已保存的业务快照将此账期奖金入账？同一账期只允许入账一次。',
                )
              "
              >入账</el-button
            ></template
          >
          <el-button
            v-if="resource === 'products' && globalCan('rules:write')"
            link
            type="primary"
            @click="dialogs?.open('product', row)"
            >编辑</el-button
          ><el-button v-if="resource === 'audits'" link type="primary" @click="auditDetail = row"
            >查看记录</el-button
          >
          <el-button
            v-if="resource === 'products' && globalCan('rules:write')"
            link
            type="danger"
            @click="action('/products/' + row.id + '/delete', {}, '删除此商品？历史订单保留。')"
            >删除</el-button
          >
          <template v-if="resource === 'users' && globalCan('system:write')"
            ><el-button link type="primary" @click="dialogs?.open('user', row)">编辑</el-button
            ><el-button
              link
              @click="
                action(
                  '/system-users/' + row.id + '/reset-password',
                  {},
                  '重置为手机号后六位并要求首次改密？',
                )
              "
              >重置密码</el-button
            ><el-button
              v-if="row.lockedUntil"
              link
              @click="action('/system-users/' + row.id + '/unlock', {}, '解除账号登录锁定？')"
              >解锁</el-button
            ><el-button
              v-if="row.active && row.id !== state.user?.id"
              link
              type="danger"
              @click="
                action('/system-users/' + row.id + '/delete', {}, '停用此账号并撤销当前登录？')
              "
              >停用</el-button
            ></template
          >
          <el-button
            v-if="resource === 'login-logs' && row.userId && globalCan('system:write')"
            link
            @click="action('/system-users/' + row.userId + '/unlock', {}, '解除该账号登录锁定？')"
            >解锁账号</el-button
          >
          <el-button
            v-if="['audits', 'login-logs'].includes(resource) && globalCan('system:write')"
            link
            type="danger"
            @click="action('/logs/' + resource + '/' + row.id + '/delete', {}, '删除该日志？')"
            >删除</el-button
          >
        </template></el-table-column
      >
    </el-table>
    <div class="pagination">
      <el-pagination
        v-if="!managed"
        v-model:current-page="filters.page"
        v-model:page-size="filters.size"
        :total="data.total"
        :page-sizes="[20, 50, 100, 200]"
        layout="sizes,prev,pager,next,total"
        @change="load"
      />
    </div>
  </section>
  <ActionDialogs ref="dialogs" @updated="load" />
  <el-dialog :model-value="!!auditDetail" title="操作记录" width="700px" @close="auditDetail = null"
    ><AuditDetails v-if="auditDetail" :record="auditDetail"
  /></el-dialog>
</template>
