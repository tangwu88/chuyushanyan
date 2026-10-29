<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, state, can, hydrate } from '../state';
import {
  money,
  label,
  grade,
  query,
  dateTime,
  orderStatus,
  type Row,
  type Page,
} from '@chuyu/shared';
import ActionDialogs from '../components/ActionDialogs.vue';
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
const tabs = computed(() =>
  props.section === 'orders'
    ? [
        ['orders', '订单管理'],
        ['shipments', '发货管理'],
        ['products', '商品配置'],
      ]
    : props.section === 'finance'
      ? [
          ['ledger', '资金流水'],
          ['awards', '奖金记录'],
          ['withdrawals', '提现审核'],
          ['topups', '充值审批'],
          ['batches', '结算批次'],
        ]
      : props.section === 'system'
        ? [
            ...(can('system:write')
              ? [
                  ['users', '后台用户'],
                  ['roles', '角色权限'],
                ]
              : []),
            ['audits', '操作记录'],
            ['jobs', '定时任务'],
          ]
        : [],
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
    ['gross', '申请金额', 'money'],
    ['fee', '手续费', 'money'],
    ['tax', '演示税额', 'money'],
    ['net', '到账金额', 'money'],
    ['bankMask', '银行卡'],
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
    ['actorId', '操作人'],
    ['targetId', '目标记录'],
    ['reason', '操作原因'],
    ['createdAt', '操作时间', 'date'],
  ],
  jobs: [
    ['jobName', '任务名称'],
    ['period', '账期'],
    ['status', '状态', 'enum'],
    ['message', '执行结果'],
    ['startedAt', '开始时间', 'date'],
  ],
};
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
    if (resource.value === 'products') {
      data.value = {
        items: state.catalog.products,
        total: state.catalog.products.length,
        page: 1,
        size: 20,
      };
    } else if (resource.value !== 'roles') {
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
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>{{ title[section] }}</h1>
    </div>
    <div class="heading-actions">
      <el-button
        v-if="section === 'members' && can('members:write')"
        type="primary"
        @click="dialogs?.open('member')"
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
        v-if="resource === 'products' && can('rules:write')"
        type="primary"
        @click="dialogs?.open('product')"
        >＋ 新增商品</el-button
      ><el-button
        v-if="section === 'finance' && can('funds:write')"
        @click="dialogs?.open('adjust')"
        >资金调整</el-button
      ><el-button
        v-if="section === 'finance' && can('funds:write')"
        type="primary"
        @click="dialogs?.open('preview')"
        >生成结算预览</el-button
      ><el-button
        v-if="resource === 'users' && can('system:write')"
        type="primary"
        @click="dialogs?.open('user')"
        >＋ 新增用户</el-button
      >
    </div>
  </div>
  <div v-if="tabs.length" class="module-tabs">
    <button v-for="t in tabs" :key="t[0]" :class="{ active: tab === t[0] }" @click="tab = t[0]">
      {{ t[1] }}
    </button>
  </div>
  <section v-if="resource === 'roles'" class="role-grid">
    <article v-for="(permissions, role) in state.config.roles" :key="role" class="surface">
      <h3>{{ label(role) }}</h3>
      <el-tag v-for="p in permissions" :key="p" class="permission-tag">{{ label(p) }}</el-tag>
    </article>
  </section>
  <section v-else class="surface data-surface">
    <div class="list-toolbar">
      <div>
        <el-input
          v-model="filters.search"
          :placeholder="
            resource === 'members' ? '精确搜索：电话 / 昵称 / 点位号' : '精确搜索记录编号'
          "
          clearable
          class="search-input"
          @keyup.enter="
            filters.page = 1;
            load();
          "
        /><el-select
          v-if="['orders', 'shipments', 'withdrawals', 'topups', 'batches'].includes(resource)"
          v-model="filters.status"
          placeholder="全部状态"
          clearable
          style="width: 150px"
          @change="
            filters.page = 1;
            load();
          "
          ><el-option
            v-for="s in resource === 'orders'
              ? ['UNPAID', 'PENDING', 'ACTIVE', 'CANCELLED']
              : resource === 'batches'
                ? ['PREVIEW', 'POSTED']
                : ['PENDING', 'APPROVED', 'REJECTED', 'SHIPPED', 'PAID_DEMO']"
            :key="s"
            :value="s"
            :label="label(s)" /></el-select
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
            filters.page = 1;
            load();
          "
          >重置</el-button
        >
      </div>
      <div>
        <el-button v-if="section === 'finance'" @click="check">账本核对</el-button
        ><el-button v-if="can('reports:export') && resource !== 'products'" @click="exportData"
          >导出 Excel</el-button
        ><el-button :loading="loading" @click="load()">刷新</el-button>
      </div>
    </div>
    <div class="list-subtoolbar">
      <span
        >共 <b>{{ data.total }}</b> 条记录</span
      >
      <div>
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
          ><span v-else-if="c[2] === 'bool'">{{ value(row, c[0]) ? '正常' : '冻结' }}</span
          ><span v-else>{{ value(row, c[0]) ?? '—' }}</span></template
        ></el-table-column
      >
      <el-table-column label="操作" fixed="right" :width="resource === 'members' ? 210 : 200"
        ><template #default="{ row }">
          <template v-if="resource === 'members'"
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
          <template v-if="resource === 'topups' && row.status === 'PENDING' && can('funds:write')"
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
            v-if="resource === 'products' && can('rules:write')"
            link
            type="primary"
            @click="dialogs?.open('product', row)"
            >编辑</el-button
          ><el-button v-if="resource === 'audits'" link type="primary" @click="auditDetail = row"
            >查看记录</el-button
          >
        </template></el-table-column
      >
    </el-table>
    <div class="pagination">
      <span class="muted">金额单位：元</span
      ><el-pagination
        v-if="resource !== 'products'"
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
    ><el-descriptions v-if="auditDetail" :column="1" border
      ><el-descriptions-item v-for="(v, k) in auditDetail" :key="k" :label="String(k)">
        <pre class="audit-value">{{ v }}</pre>
      </el-descriptions-item></el-descriptions
    ></el-dialog
  >
</template>
