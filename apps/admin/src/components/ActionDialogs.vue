<script setup lang="ts">
import { ref, computed } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, state, globalCan, hydrate } from '../state';
import { money, grade, label, type Row, orderStatus } from '@chuyu/shared';
import MemberPicker from './MemberPicker.vue';
import NetworkView from './NetworkView.vue';
const emit = defineEmits<{ updated: [] }>();
const visible = ref(false),
  kind = ref(''),
  busy = ref(false),
  form = ref<Row>({}),
  detail = ref<Row>({}),
  quote = ref<Row | null>(null),
  eligible = ref<Row[]>([]),
  selected = ref<string[]>([]);
const eligiblePage = ref(1),
  eligibleTotal = ref(0),
  shippingQuote = ref<Row | null>(null),
  detailError = ref(''),
  departments = ref<Row[]>([]),
  roles = ref<Row[]>([]),
  posts = ref<Row[]>([]),
  initialPhone = ref(''),
  initialActive = ref(true);
const names: Record<string, string> = {
  member: '新增会员点位',
  editMember: '会员资料与独立调整',
  order: '创建订单',
  detail: '订单详情',
  shipment: '申请合并 / 分批发货',
  ship: '登记发货 / 自提',
  preview: '结算预览',
  batch: '结算明细',
  adjust: '人工资金调整',
  product: '商品配置',
  user: '新增后台用户',
  network: '推荐关系网络',
  memberDetail: '会员详情',
  shipmentDetail: '发货明细',
  stock: '店铺转货',
  sync: '发起同步升级',
};
const title = computed(() => names[kind.value] || '业务操作');
async function open(type: string, row: Row = {}) {
  kind.value = type;
  form.value = { ...row };
  initialPhone.value = row.phone || '';
  initialActive.value = row.active !== false;
  detail.value = {};
  quote.value = null;
  selected.value = [];
  detailError.value = '';
  shippingQuote.value = null;
  eligiblePage.value = 1;
  if (type === 'member')
    form.value = {
      nickname: '',
      phone: '',
      parentId: 'CY1001',
      subPoint: false,
      emptyPoint: false,
      grade: 0,
      directorGrade: 0,
      manualOwn: '0',
      manualTeam: '0',
      manualSmall: '0',
      ...row,
    };
  if (type === 'editMember')
    form.value = {
      ...row,
      reason: '',
      fixedPlan1: row.fixedPlan1 ?? '',
      fixedPlan2: row.fixedPlan2 ?? '',
      salesRateOverride: row.salesRateOverride ?? '',
    };
  if (type === 'order')
    form.value = {
      pointId: 'CY1001',
      shopId: 'CY9001',
      payerId: 'CY1001',
      productId: 'PR001',
      type: 'FIRST',
      quantity: 1,
      rewardAmount: '0',
      paymentMethod: 'DEMO',
      pickup: false,
    };
  if (type === 'shipment') {
    form.value = {
      shopId: 'CY9001',
      recipient: '演示收货人',
      phone: '001900001',
      address: '演示地址 · 本地验证使用',
      pickup: false,
    };
    await loadEligible();
  }
  if (type === 'ship') form.value = { ...row, carrier: '演示快递', tracking: 'DEMO' + Date.now() };
  if (type === 'preview') {
    const day = new Date();
    day.setDate(day.getDate() - 1);
    form.value = {
      cycle: 'DAILY',
      period:
        day.getFullYear() +
        '-' +
        String(day.getMonth() + 1).padStart(2, '0') +
        '-' +
        String(day.getDate()).padStart(2, '0'),
      ruleId: state.config.ruleId,
    };
  }
  if (type === 'adjust') form.value = { pointId: 'CY1001', kind: 'REWARD', amount: '', reason: '' };
  if (type === 'product')
    form.value = {
      name: '',
      firstPrice: '298',
      repeatOne: '150',
      repeatOther: '99',
      limitOne: 2,
      limitOther: 4,
      subsidyOne: '51',
      subsidyRate: '0.05',
      weightKg: '0.5',
      active: true,
      channel: '初语山言',
      purchaseTypes: 'NEW,FIRST,REPEAT',
      imageUrl: '',
      specification: '瓶',
      ...row,
      differential: row.differentialJson ? JSON.parse(row.differentialJson) : [1, 2, 3, 4, 5],
    };
  if (type === 'user')
    form.value = {
      username: '',
      displayName: '',
      phone: '',
      role: 'OPERATOR',
      password: '',
      reason: '',
      department: '初语山言',
      postCode: '',
      email: '',
      gender: 'UNKNOWN',
      active: true,
      ...row,
    };
  if (type === 'stock')
    form.value = {
      fromId: state.catalog.shops[0]?.id || '',
      toId: state.catalog.shops[1]?.id || '',
      amount: '',
    };
  if (type === 'sync') {
    const source = state.points.find((p) => p.parentId) || state.points[0] || {};
    form.value = {
      fromPoint: source.id || '',
      targetPoint: source.parentId || '',
      expectedGrade: 1,
      reason: '',
    };
  }
  visible.value = true;
  try {
    if (type === 'detail') detail.value = await api.get('/orders/' + row.id);
    if (type === 'batch') detail.value = await api.get('/batches/' + row.id);
    if (type === 'memberDetail') detail.value = await api.get('/members/' + row.id);
    if (type === 'shipmentDetail') detail.value = await api.get('/shipments/' + row.id);
    if (type === 'user') {
      departments.value = await api.get('/management/departments');
      roles.value = await api.get('/management/roles');
      posts.value = await api.get('/management/posts');
    }
    if (type === 'editMember' && globalCan('system:write'))
      departments.value = await api.get('/management/departments');
  } catch (e) {
    detailError.value = (e as Error).message;
  }
}
async function loadEligible(reset = true) {
  try {
    if (reset) {
      eligiblePage.value = 1;
      selected.value = [];
      eligible.value = [];
    }
    const result = await api.get(
      '/lists/orders?available=true&includeCancelled=true&size=100&page=' +
        eligiblePage.value +
        '&shopId=' +
        form.value.shopId,
    );
    result.items.forEach((row: Row) => (row.shipQuantity = row.availableQuantity));
    eligible.value = reset ? result.items : [...eligible.value, ...result.items];
    eligibleTotal.value = result.total;
    shippingQuote.value = null;
    return true;
  } catch (e) {
    ElMessage.error((e as Error).message);
    return false;
  }
}
async function selectAll() {
  busy.value = true;
  try {
    while (eligible.value.length < eligibleTotal.value) {
      eligiblePage.value++;
      if (!(await loadEligible(false))) return;
    }
    selected.value = eligible.value
      .filter((o) => !o.cancelled && o.availableQuantity > 0 && !!o.pickup === !!form.value.pickup)
      .map((o) => o.id);
  } finally {
    busy.value = false;
  }
}
async function priceShipping() {
  busy.value = true;
  try {
    shippingQuote.value = await api.post('/shipping-quotes', {
      ...form.value,
      lines: eligible.value
        .filter((r) => selected.value.includes(r.id))
        .map((r) => ({ orderId: r.id, quantity: r.shipQuantity })),
    });
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = false;
  }
}
async function submit() {
  busy.value = true;
  try {
    let path = '',
      body: Row = { ...form.value };
    switch (kind.value) {
      case 'member':
        path = '/members';
        break;
      case 'editMember':
        path = '/members/' + body.id + '/update';
        body.fixedPlan1 = body.fixedPlan1 === '' ? null : body.fixedPlan1;
        body.fixedPlan2 = body.fixedPlan2 === '' ? null : body.fixedPlan2;
        body.salesRateOverride = body.salesRateOverride === '' ? null : body.salesRateOverride;
        if (
          form.value.active === false &&
          initialActive.value &&
          Number(form.value.childCount) > 0
        ) {
          await ElMessageBox.confirm('已有下级用户，确认冻结此点位？', '冻结会员', {
            confirmButtonText: '确认冻结',
            cancelButtonText: '取消',
          });
          body.confirmDescendants = true;
        }
        if (body.phone !== initialPhone.value) {
          await ElMessageBox.confirm(
            '将本组主小号的手机号改为 ' +
              body.phone +
              '。如该手机号已有主号，将归并点位并停用原登录账号。',
            '修改手机号',
            { confirmButtonText: '确认修改', cancelButtonText: '取消' },
          );
          body.mergeConfirmed = true;
        }
        break;
      case 'order':
        path = '/orders';
        break;
      case 'shipment':
        path = '/shipments';
        body.lines = eligible.value
          .filter((r) => selected.value.includes(r.id))
          .map((r) => ({ orderId: r.id, quantity: r.shipQuantity }));
        break;
      case 'ship':
        path = '/shipments/' + body.id + '/ship';
        break;
      case 'preview':
        path = '/previews';
        break;
      case 'adjust':
        path = '/funds-adjustments';
        break;
      case 'product':
        path = body.id ? '/products/' + body.id + '/update' : '/products';
        break;
      case 'user':
        path = body.id ? '/system-users/' + body.id + '/update' : '/system-users';
        break;
      case 'stock':
        path = '/stock-transfers';
        break;
      case 'sync':
        path = '/sync-upgrades';
        break;
    }
    const result = await api.post(path, body);
    if (kind.value === 'preview') {
      kind.value = 'batch';
      detail.value = result;
      emit('updated');
      return;
    }
    visible.value = false;
    await hydrate();
    emit('updated');
    ElMessage.success(kind.value === 'order' ? '订单已创建，请完成演示支付' : '操作已完成');
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = false;
  }
}
async function trial() {
  busy.value = true;
  try {
    quote.value = await api.post('/quotes', form.value);
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = false;
  }
}
defineExpose({ open });
</script>
<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="800px"
    :close-on-click-modal="false"
    destroy-on-close
  >
    <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
    <template v-if="kind === 'memberDetail'"
      ><el-descriptions :column="2" border
        ><el-descriptions-item label="点位">{{ detail.id }}</el-descriptions-item
        ><el-descriptions-item label="昵称">{{ detail.nickname }}</el-descriptions-item
        ><el-descriptions-item label="电话">{{ detail.phone }}</el-descriptions-item
        ><el-descriptions-item label="主号 / 小号">{{
          detail.isMain ? '主号' : '小号' + (Number(detail.sequenceNo) - 1)
        }}</el-descriptions-item
        ><el-descriptions-item label="会员等级">{{ grade(detail.grade) }}</el-descriptions-item
        ><el-descriptions-item label="董事等级">{{ detail.directorGrade }}星</el-descriptions-item
        ><el-descriptions-item label="店铺">{{ label(detail.shopType) }}</el-descriptions-item
        ><el-descriptions-item label="等级版本">{{ detail.gradeVersion }}</el-descriptions-item
        ><el-descriptions-item label="推荐点位">{{ detail.parentId || '无' }}</el-descriptions-item
        ><el-descriptions-item label="1+2资格">{{
          detail.planQualified ? '已具备' : '未具备'
        }}</el-descriptions-item
        ><el-descriptions-item
          v-for="f in [
            ['own', '个人业绩'],
            ['team', '团队业绩'],
            ['small', '小市场业绩'],
          ]"
          :key="f[0]"
          :label="f[1]"
          >¥{{ money(detail[f[0]]) }}</el-descriptions-item
        ><el-descriptions-item label="奖励积分"
          >¥{{ money(detail.reward?.balance) }}</el-descriptions-item
        ><el-descriptions-item label="充值积分"
          >¥{{ money(detail.recharge?.balance) }}</el-descriptions-item
        ><el-descriptions-item label="账户状态">{{
          detail.active ? '正常' : '冻结'
        }}</el-descriptions-item
        ><el-descriptions-item v-if="detail.remark" label="备注" :span="2">{{
          detail.remark
        }}</el-descriptions-item></el-descriptions
      ></template
    >
    <template v-else-if="kind === 'shipmentDetail'"
      ><el-descriptions :column="2" border
        ><el-descriptions-item label="申请编号">{{ detail.application?.id }}</el-descriptions-item
        ><el-descriptions-item label="状态">{{
          label(detail.application?.status)
        }}</el-descriptions-item
        ><el-descriptions-item label="收货人">{{
          detail.application?.recipient
        }}</el-descriptions-item
        ><el-descriptions-item label="电话">{{ detail.application?.phone }}</el-descriptions-item
        ><el-descriptions-item label="地址">{{ detail.application?.address }}</el-descriptions-item
        ><el-descriptions-item label="取货方式">{{
          detail.application?.pickup ? '自提' : '配送'
        }}</el-descriptions-item
        ><el-descriptions-item label="商品金额"
          >¥{{ money(detail.application?.goodsAmount) }}</el-descriptions-item
        ><el-descriptions-item label="重量 / 运费"
          >{{ detail.application?.weightKg }}kg / ¥{{
            money(detail.application?.freightAmount)
          }}</el-descriptions-item
        ><el-descriptions-item label="物流"
          >{{ detail.application?.carrier }}
          {{ detail.application?.tracking }}</el-descriptions-item
        ><el-descriptions-item label="发货人">{{
          detail.application?.shippedBy || '尚未发货'
        }}</el-descriptions-item></el-descriptions
      ><el-table :data="detail.lines || []"
        ><el-table-column prop="orderId" label="订单编号" min-width="200" /><el-table-column
          label="类型"
          ><template #default="{ row }">{{ label(row.orderType) }}</template></el-table-column
        ><el-table-column prop="quantity" label="本次发货数量" /><el-table-column label="撤销标记"
          ><template #default="{ row }"
            ><el-tag v-if="row.cancelled || row.orderCancelled" type="danger">订单已撤销</el-tag
            ><span v-else>正常</span></template
          ></el-table-column
        ></el-table
      ></template
    >
    <template v-else-if="kind === 'detail'">
      <p v-if="detail.remark">备注：{{ detail.remark }}</p>
      <div class="detail-banner">
        <span>{{ detail.id }}</span
        ><el-tag>{{ label(orderStatus(detail)) }}</el-tag>
      </div>
      <el-descriptions :column="2" border
        ><el-descriptions-item label="购买账户">{{ detail.pointId }}</el-descriptions-item
        ><el-descriptions-item label="代办店铺">{{ detail.shopId }}</el-descriptions-item
        ><el-descriptions-item label="订单类型">{{ label(detail.orderType) }}</el-descriptions-item
        ><el-descriptions-item label="商品数量">{{ detail.quantity }} 瓶</el-descriptions-item
        ><el-descriptions-item label="订单金额">¥{{ money(detail.total) }}</el-descriptions-item
        ><el-descriptions-item label="积分 / 现金"
          >¥{{ money(detail.rewardPaid) }} / ¥{{ money(detail.cashPaid) }}</el-descriptions-item
        ><el-descriptions-item label="支付状态">{{
          label(detail.paymentStatus)
        }}</el-descriptions-item
        ><el-descriptions-item label="激活状态">{{
          label(detail.activationStatus)
        }}</el-descriptions-item
        ><el-descriptions-item label="履约状态">{{
          label(detail.fulfilmentStatus)
        }}</el-descriptions-item
        ><el-descriptions-item label="规则版本">{{
          detail.ruleId
        }}</el-descriptions-item></el-descriptions
      >
      <h3>实时奖金明细</h3>
      <el-table :data="detail.awards" empty-text="此订单暂无奖金"
        ><el-table-column prop="recipientId" label="受益点位" /><el-table-column label="奖项"
          ><template #default="{ row }">{{ label(row.awardType) }}</template></el-table-column
        ><el-table-column label="实得"
          ><template #default="{ row }"
            >¥{{ money(row.net) }}
            <el-tag v-if="row.reversed" type="danger">已冲回</el-tag></template
          ></el-table-column
        ><el-table-column prop="explanation" label="计算依据" min-width="200"
      /></el-table>
      <h3>发货记录</h3>
      <el-table :data="detail.shipments" empty-text="尚未申请发货"
        ><el-table-column prop="id" label="申请编号" /><el-table-column
          prop="quantity"
          label="数量" /><el-table-column label="状态"
          ><template #default="{ row }"
            >{{ label(row.status) }} {{ row.cancelled ? '· 订单已撤销' : '' }}</template
          ></el-table-column
        ><el-table-column prop="carrier" label="快递" /><el-table-column
          prop="tracking"
          label="运单号"
      /></el-table>
      <h3>业务轨迹</h3>
      <el-timeline
        ><el-timeline-item v-for="(h, i) in detail.history" :key="i" :timestamp="h.createdAt"
          >{{ label(h.action) }} · {{ h.reason }}</el-timeline-item
        ></el-timeline
      >
    </template>
    <template v-else-if="kind === 'batch'"
      ><el-alert
        :title="label(detail.status) + ' · ' + detail.period + ' · ' + detail.ruleId"
        type="info"
        :closable="false" />
      <div class="batch-totals">
        <span
          >毛奖 <b>¥{{ money(detail.gross) }}</b></span
        ><span
          >管理费 <b>¥{{ money(detail.fee) }}</b></span
        ><span
          >实发 <b>¥{{ money(detail.net) }}</b></span
        >
      </div>
      <el-table :data="detail.result?.awards || []" max-height="480"
        ><el-table-column label="奖项"
          ><template #default="{ row }">{{ label(row.type) }}</template></el-table-column
        ><el-table-column prop="recipient" label="受益账户" /><el-table-column label="实得"
          ><template #default="{ row }">{{ money(row.net) }}</template></el-table-column
        ><el-table-column prop="explanation" label="计算依据" min-width="240" /></el-table
    ></template>
    <NetworkView
      v-else-if="kind === 'network'"
      :root-id="form.id"
      @detail="(id) => open('memberDetail', { id })"
    />
    <el-form v-else :model="form" label-position="top" class="dialog-form" @submit.prevent="submit">
      <el-form-item
        v-if="['member', 'editMember', 'order'].includes(kind)"
        label="备注"
        class="wide"
        ><el-input v-model="form.remark" type="textarea" :maxlength="1000" :rows="2"
      /></el-form-item>
      <template v-if="kind === 'member'"
        ><el-form-item label="昵称"><el-input v-model="form.nickname" /></el-form-item
        ><el-form-item label="手机号（保留前导零）"
          ><el-input v-model="form.phone" inputmode="numeric" /></el-form-item
        ><el-form-item label="推荐点位"
          ><MemberPicker v-model="form.parentId" clearable /></el-form-item
        ><el-form-item label="点位类型"
          ><el-checkbox v-model="form.subPoint">同手机号小号</el-checkbox
          ><el-checkbox v-model="form.emptyPoint">空点</el-checkbox></el-form-item
        ><template v-if="form.emptyPoint"
          ><el-form-item label="会员等级"
            ><el-input-number v-model="form.grade" :min="0" :max="7" /></el-form-item
          ><el-form-item label="董事等级"
            ><el-input-number v-model="form.directorGrade" :min="0" :max="6" /></el-form-item
          ><el-form-item
            v-for="f in [
              ['manualOwn', '个人业绩'],
              ['manualTeam', '团队业绩'],
              ['manualSmall', '小市场业绩'],
            ]"
            :key="f[0]"
            :label="f[1]"
            ><el-input v-model="form[f[0]]" /></el-form-item></template
      ></template>
      <template v-if="kind === 'editMember'"
        ><el-form-item label="昵称"><el-input v-model="form.nickname" /></el-form-item
        ><el-form-item label="手机号"
          ><el-input v-model="form.phone" inputmode="numeric" /></el-form-item
        ><el-form-item v-if="globalCan('system:write')" label="所属部门"
          ><el-select v-model="form.department"
            ><el-option
              v-for="d in departments.filter((x) => x.active)"
              :key="d.id"
              :value="d.id"
              :label="d.name" /></el-select></el-form-item
        ><el-form-item label="会员等级"
          ><el-select v-model="form.grade"
            ><el-option
              v-for="g in 8"
              :key="g"
              :value="g - 1"
              :label="grade(g - 1)" /></el-select></el-form-item
        ><el-form-item label="董事等级"
          ><el-input-number v-model="form.directorGrade" :min="0" :max="6" /></el-form-item
        ><el-form-item label="等级版本"
          ><el-select v-model="form.gradeVersion"
            ><el-option
              v-for="version in Object.keys(state.config.rules?.gradeVersions || {})"
              :key="version"
              :label="version"
              :value="version" /></el-select></el-form-item
        ><el-form-item label="店铺身份"
          ><el-select v-model="form.shopType"
            ><el-option
              v-for="s in ['NONE', 'SMALL', 'BIG']"
              :key="s"
              :value="s"
              :label="label(s)" /></el-select></el-form-item
        ><el-form-item label="人工个人业绩调整量（不向上累计）"
          ><el-input v-model="form.manualOwn" /></el-form-item
        ><el-form-item label="人工团队业绩调整量"
          ><el-input v-model="form.manualTeam" /></el-form-item
        ><el-form-item label="人工小市场业绩调整量"
          ><el-input v-model="form.manualSmall" /></el-form-item
        ><el-form-item label="推荐点位"><el-input v-model="form.parentId" /></el-form-item
        ><el-form-item label="1+2分红资格"
          ><el-select v-model="form.planOverride"
            ><el-option label="自动判断" value="AUTO" /><el-option
              label="终身无条件加入"
              value="YES" /><el-option label="手动取消资格" value="NO" /></el-select></el-form-item
        ><el-form-item label="特殊身份"
          ><el-select
            :model-value="
              String(form.identities || '')
                .split(',')
                .filter(Boolean)
            "
            multiple
            @change="(v: string[]) => (form.identities = v.join(','))"
            ><el-option
              v-for="(identity, code) in state.config.rules?.identities"
              :key="code"
              :value="code"
              :label="identity.name" /></el-select></el-form-item
        ><el-form-item label="计划1固定毛分红（空白为自动）"
          ><el-input v-model="form.fixedPlan1" /></el-form-item
        ><el-form-item label="计划2固定毛分红（空白为自动）"
          ><el-input v-model="form.fixedPlan2" /></el-form-item
        ><el-form-item label="账户状态"
          ><el-switch v-model="form.active" active-text="正常" inactive-text="冻结" /></el-form-item
        ><el-form-item label="变更原因"><el-input v-model="form.reason" /></el-form-item
        ><el-form-item label="个人销售比例（留空按等级）"
          ><el-input
            :model-value="form.salesRateOverride === '' ? '' : Number(form.salesRateOverride) * 100"
            @update:model-value="
              (v: string | number) =>
                (form.salesRateOverride = v === '' ? '' : String(Number(v) / 100))
            "
            placeholder="百分比" /></el-form-item
      ></template>
      <template v-if="kind === 'order'"
        ><el-form-item label="购买会员点位"><MemberPicker v-model="form.pointId" /></el-form-item
        ><el-form-item label="购买类型"
          ><el-select v-model="form.type" @change="quote = null"
            ><el-option
              v-for="t in ['NEW', 'FIRST', 'REPEAT']"
              :key="t"
              :value="t"
              :label="label(t)" /></el-select></el-form-item
        ><el-form-item label="代办店铺"
          ><el-select v-model="form.shopId"
            ><el-option
              v-for="s in state.catalog.shops"
              :key="s.id"
              :value="s.id"
              :label="s.nickname + ' / ' + s.id" /></el-select></el-form-item
        ><el-form-item label="商品"
          ><el-select v-model="form.productId"
            ><el-option
              v-for="p in state.catalog.products"
              :key="p.id"
              :label="p.name"
              :value="p.id" /></el-select></el-form-item
        ><el-form-item label="数量（瓶）"
          ><el-input-number v-model="form.quantity" :min="1" :max="10000" /></el-form-item
        ><el-form-item label="奖励积分付款点位"><el-input v-model="form.payerId" /></el-form-item
        ><el-form-item label="使用奖励积分"
          ><el-input v-model="form.rewardAmount" :disabled="form.type === 'REPEAT'" /></el-form-item
        ><el-form-item label="付款方式"
          ><el-select v-model="form.paymentMethod"
            ><el-option label="演示现金支付" value="DEMO" /><el-option
              label="店铺充值积分"
              value="STORE" /></el-select></el-form-item
        ><el-form-item label="取货方式"
          ><el-checkbox v-model="form.pickup">自提</el-checkbox></el-form-item
        ><el-form-item class="wide"
          ><el-button :loading="busy" @click="trial">计算报价</el-button
          ><span v-if="quote" class="quote-inline"
            >总额 ¥{{ money(quote.total) }} · 积分 ¥{{ money(quote.rewardAmount) }} · 现金 ¥{{
              money(quote.cashAmount)
            }}
            · 规则 {{ quote.ruleId }}</span
          ></el-form-item
        ></template
      >
      <template v-if="kind === 'shipment'"
        ><el-form-item label="店铺"
          ><el-select v-model="form.shopId" @change="loadEligible"
            ><el-option
              v-for="s in state.catalog.shops"
              :key="s.id"
              :label="s.nickname"
              :value="s.id" /></el-select></el-form-item
        ><el-form-item label="取货方式"
          ><el-switch v-model="form.pickup" active-text="自提" inactive-text="配送" /></el-form-item
        ><el-form-item label="收货人"><el-input v-model="form.recipient" /></el-form-item
        ><el-form-item label="联系电话"><el-input v-model="form.phone" /></el-form-item
        ><el-form-item label="收货地址" class="wide"
          ><el-input v-model="form.address" /></el-form-item
        ><el-form-item class="wide" label="选择订单及本次发货数量"
          ><div class="shipment-options">
            <div>
              <el-button :loading="busy" @click="selectAll">全选可发货订单</el-button
              ><el-button @click="selected = []">清空选择</el-button>
            </div>
            <div
              v-for="o in eligible.filter(
                (x) => (x.cancelled || x.availableQuantity > 0) && !!x.pickup === !!form.pickup,
              )"
              :key="o.id"
            >
              <el-checkbox v-model="selected" :value="o.id" :disabled="o.cancelled"
                >{{ o.id }} · {{ label(o.orderType) }} · 剩余{{ o.availableQuantity }}瓶
                {{ o.cancelled ? '· 已撤销' : '' }}</el-checkbox
              ><el-input-number
                v-model="o.shipQuantity"
                :min="1"
                :max="o.availableQuantity"
                size="small"
                :disabled="o.cancelled"
              />
            </div>
            <el-button
              v-if="eligible.length < eligibleTotal"
              @click="
                eligiblePage++;
                loadEligible(false);
              "
              >加载更多订单</el-button
            >
          </div></el-form-item
        ><el-form-item class="wide"
          ><el-button :loading="busy" @click="priceShipping">计算运费</el-button
          ><span v-if="shippingQuote"
            >重量 {{ shippingQuote.weightKg }}kg · 运费 ¥{{
              money(shippingQuote.freightAmount)
            }}</span
          ></el-form-item
        ></template
      >
      <template v-if="kind === 'ship'"
        ><el-alert
          v-if="form.pickup"
          title="本申请为自提，确认后标记为已自提"
          :closable="false"
          class="wide" /><template v-else
          ><el-form-item label="快递公司"><el-input v-model="form.carrier" /></el-form-item
          ><el-form-item label="快递单号"
            ><el-input v-model="form.tracking" /></el-form-item></template
      ></template>
      <template v-if="kind === 'preview'"
        ><el-form-item label="结算类型"
          ><el-select
            v-model="form.cycle"
            @change="
              form.period =
                form.cycle === 'DAILY'
                  ? state.config.today.slice(0, 10)
                  : state.config.today.slice(0, 7)
            "
            ><el-option
              v-for="s in ['DAILY', 'MONTHLY', 'MONTHLY_REPEAT']"
              :key="s"
              :label="label(s)"
              :value="s" /></el-select></el-form-item
        ><el-form-item label="账期"
          ><el-date-picker
            v-model="form.period"
            :type="form.cycle === 'DAILY' ? 'date' : 'month'"
            :value-format="form.cycle === 'DAILY' ? 'YYYY-MM-DD' : 'YYYY-MM'" /></el-form-item
        ><el-alert class="wide" title="账期结束后可入账。" :closable="false"
      /></template>
      <template v-if="kind === 'adjust'"
        ><el-form-item label="会员点位"
          ><el-select v-model="form.pointId" filterable
            ><el-option
              v-for="p in state.points"
              :key="p.id"
              :value="p.id"
              :label="p.nickname + ' / ' + p.id" /></el-select></el-form-item
        ><el-form-item label="积分类型"
          ><el-select v-model="form.kind"
            ><el-option value="REWARD" label="奖励积分" /><el-option
              value="RECHARGE"
              label="充值积分" /></el-select></el-form-item
        ><el-form-item label="调整金额（扣减填负数）"
          ><el-input v-model="form.amount" /></el-form-item
        ><el-form-item label="调整原因"><el-input v-model="form.reason" /></el-form-item
      ></template>
      <template v-if="kind === 'product'"
        ><el-form-item
          v-for="f in [
            { key: 'name', name: '商品名称' },
            { key: 'channel', name: '渠道' },
            { key: 'specification', name: '商品规格' },
            { key: 'imageUrl', name: '列表图片地址' },
            { key: 'firstPrice', name: '首购单价' },
            { key: 'repeatOne', name: '一星复购单价' },
            { key: 'repeatOther', name: '二星及以上复购单价' },
            { key: 'limitOne', name: '一星月限购瓶数' },
            { key: 'limitOther', name: '二星及以上月限购' },
            { key: 'subsidyOne', name: '一星复购每瓶店补' },
            { key: 'weightKg', name: '每瓶重量（kg）' },
          ]"
          :key="f.key"
          :label="f.name"
          ><el-input v-model="form[f.key]" /></el-form-item
        ><el-form-item label="二星及以上复购店补（%）"
          ><el-input-number
            :model-value="Number(form.subsidyRate) * 100"
            :min="0"
            :max="100"
            :precision="2"
            @update:model-value="
              (v: number | undefined) => (form.subsidyRate = String(Number(v || 0) / 100))
            " /></el-form-item
        ><el-form-item label="上架状态"><el-switch v-model="form.active" /></el-form-item
        ><el-form-item label="购买类型" class="wide"
          ><el-checkbox-group
            :model-value="String(form.purchaseTypes).split(',')"
            @change="(v: string[]) => (form.purchaseTypes = v.join(','))"
            ><el-checkbox v-for="type in ['NEW', 'FIRST', 'REPEAT']" :key="type" :value="type">{{
              label(type)
            }}</el-checkbox></el-checkbox-group
          ></el-form-item
        ><el-form-item
          v-for="(_, i) in form.differential"
          :key="i"
          :label="Number(i) + 2 + '星每瓶级差金额'"
          ><el-input v-model="form.differential[i]" /></el-form-item
      ></template>
      <template v-if="kind === 'user'"
        ><el-form-item
          v-for="f in [
            { key: 'username', name: '登录账号' },
            { key: 'displayName', name: '姓名' },
            { key: 'phone', name: '电话' },
            { key: 'password', name: '初始密码（至少10位）' },
          ]"
          v-show="f.key !== 'password' || !form.id"
          :key="f.key"
          :label="f.name"
          ><el-input
            v-model="form[f.key]"
            :type="f.key === 'password' ? 'password' : 'text'" /></el-form-item
        ><el-form-item label="角色"
          ><el-select v-model="form.role"
            ><el-option
              v-for="r in roles.filter((x) => x.active)"
              :key="r.id"
              :value="r.id"
              :label="r.name" /></el-select></el-form-item
        ><el-form-item label="部门"
          ><el-select v-model="form.department"
            ><el-option
              v-for="d in departments.filter((x) => x.active)"
              :key="d.id"
              :value="d.id"
              :label="d.name" /></el-select></el-form-item
        ><el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item
        ><el-form-item label="岗位"
          ><el-select v-model="form.postCode" clearable
            ><el-option
              v-for="p in posts.filter((x) => x.active)"
              :key="p.id"
              :value="p.id"
              :label="p.name" /></el-select></el-form-item
        ><el-form-item label="账户状态"
          ><el-switch v-model="form.active" active-text="正常" inactive-text="停用"
        /></el-form-item>
        ></template
      >
      <template v-if="kind === 'stock'"
        ><el-form-item label="转出店铺"
          ><el-select v-model="form.fromId"
            ><el-option
              v-for="s in state.catalog.shops"
              :key="s.id"
              :value="s.id"
              :label="s.nickname + ' / ' + s.id" /></el-select></el-form-item
        ><el-form-item label="接收店铺"
          ><el-select v-model="form.toId"
            ><el-option
              v-for="s in state.catalog.shops.filter((x: Row) => x.id !== form.fromId)"
              :key="s.id"
              :value="s.id"
              :label="s.nickname + ' / ' + s.id" /></el-select></el-form-item
        ><el-form-item label="充值积分金额"><el-input v-model="form.amount" /></el-form-item
      ></template>
      <template v-if="kind === 'sync'"
        ><el-form-item label="发起点位"><MemberPicker v-model="form.fromPoint" /></el-form-item
        ><el-form-item label="升级点位（本人或上级）"
          ><MemberPicker v-model="form.targetPoint" /></el-form-item
        ><el-form-item label="申请等级"
          ><el-select v-model="form.expectedGrade"
            ><el-option
              v-for="g in 7"
              :key="g"
              :value="g"
              :label="grade(g)" /></el-select></el-form-item
        ><el-form-item label="申请原因"><el-input v-model="form.reason" /></el-form-item
      ></template>
    </el-form>
    <template #footer
      ><el-button @click="visible = false">关闭</el-button
      ><el-button
        v-if="!['detail', 'batch', 'network', 'memberDetail', 'shipmentDetail'].includes(kind)"
        type="primary"
        :loading="busy"
        @click="submit"
        >{{ kind === 'preview' ? '生成结算预览' : '确认保存' }}</el-button
      ></template
    >
  </el-dialog>
</template>
