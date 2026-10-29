<script setup lang="ts">
import { computed } from 'vue';
import { label, dateTime, type Row } from '@chuyu/shared';
const props = defineProps<{ record: Row }>();
const names: Record<string, string> = {
  id: '编号',
  ownerId: '所属账号',
  phone: '电话',
  nickname: '昵称',
  parentId: '推荐点位',
  isMain: '主号',
  sequenceNo: '注册顺序',
  grade: '会员等级',
  directorGrade: '董事等级',
  gradeVersion: '等级版本',
  shopType: '店铺身份',
  active: '正常状态',
  activated: '已激活',
  emptyPoint: '空点',
  manualOwn: '人工个人业绩',
  manualTeam: '人工团队业绩',
  manualSmall: '人工小市场业绩',
  planOverride: '计划资格设置',
  fixedPlan1: '计划1固定毛奖',
  fixedPlan2: '计划2固定毛奖',
  salesRateOverride: '个人销售比例',
  identities: '特殊身份',
  own: '个人业绩',
  team: '团队业绩',
  small: '小市场业绩',
  repeat: '复购业绩',
  performance: '业绩',
  wallets: '积分账户',
  balance: '余额',
  frozen: '冻结',
  restricted: '受限',
  kind: '积分类型',
  pointId: '会员点位',
  shopId: '店铺',
  payerId: '付款点位',
  productId: '商品',
  type: '类型',
  quantity: '数量',
  unitPrice: '单价',
  total: '总金额',
  rewardPaid: '奖励积分抵扣',
  cashPaid: '现金',
  paymentStatus: '支付状态',
  activationStatus: '激活状态',
  fulfilmentStatus: '履约状态',
  cancelled: '已撤销',
  pickup: '自提',
  amount: '金额',
  gross: '毛奖',
  fee: '管理费',
  net: '实得',
  recipient: '受益点位',
  rate: '比例',
  base: '计算基数',
  awards: '奖金明细',
  ruleId: '规则版本',
  period: '账期',
  cycle: '结算周期',
  fromId: '转出点位',
  toId: '接收点位',
  approved: '通过',
  role: '角色',
  username: '登录账号',
  displayName: '姓名',
  department: '部门',
  email: '邮箱',
  postCode: '岗位',
  name: '名称',
  permissions: '操作权限',
  dataScope: '数据权限',
  status: '状态',
  reason: '原因',
  remark: '备注',
  channel: '渠道',
  weightKg: '重量',
  freightAmount: '运费',
  createdAt: '创建时间',
  updatedAt: '更新时间',
};
function parse(value: any) {
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}
function flatten(value: any, prefix = '', out: Row[] = []): Row[] {
  if (value === null || value === undefined) return out;
  if (Array.isArray(value)) {
    out.push({
      field: prefix || '明细',
      value:
        value
          .map((v) =>
            typeof v === 'object'
              ? v.nickname || v.name || v.pointId || v.id || '业务明细'
              : label(v),
          )
          .join('、') || '无',
    });
    return out;
  }
  if (typeof value === 'object') {
    Object.entries(value).forEach(([key, v]) => {
      if (key === 'dataJson' || key === 'json') flatten(parse(v), prefix, out);
      else if (names[key]) flatten(v, prefix ? prefix + ' / ' + names[key] : names[key], out);
    });
    return out;
  }
  out.push({
    field: prefix || '数值',
    value: typeof value === 'boolean' ? (value ? '是' : '否') : label(value),
  });
  return out;
}
Object.assign(names, {
  firstPrice: '首购价格',
  repeatOne: '一星复购价格',
  repeatOther: '二星以上复购价格',
  first: '首购价格',
  one: '一星复购价格',
  other: '二星以上复购价格',
  limit1: '一星月限额',
  limit2: '二星以上月限额',
  limitOne: '一星月限额',
  limitOther: '二星以上月限额',
  subsidy1: '一星店补',
  subsidyOne: '一星店补',
  subsidyRate: '店补比例',
  diff: '固定级差',
  differentialJson: '固定级差',
  weight: '重量',
  specification: '规格',
  types: '购买类型',
  purchaseTypes: '购买类型',
  managementFee: '管理费',
  withdrawalFee: '提现手续费',
  tax: '税率',
  maxPoints: '点位数量上限',
  directReferrals: '直推人数',
  peerGenerations: '平级代数',
  peerRate: '平级比例',
  shippingThreshold: '发货门槛',
  smallShopThreshold: '小店门槛',
  smallShopRate: '小店分红比例',
  sevenDailyRate: '七星日分红比例',
  plan1Rate: '计划1比例',
  plan2Rate: '计划2比例',
  recoveryPoint: '回收账户',
  config: '规则配置',
  gradeVersions: '等级版本',
  directorRates: '董事分红比例',
  salesRates: '销售比例',
  repeatTiers: '复购档位',
  totalCoefficient: '最高系数',
  realCoefficient: '实际系数',
  diffRatio: '动态级差',
  enabled: '启用',
  mode: '计算方式',
  basis: '业绩类型',
  minimumGrade: '最低等级',
  newMemberGradeVersion: '新会员等级版本',
  cron: '执行时间',
  sortNo: '排序',
  archiveDays: '业务数据归档天数',
  auditDays: '操作日志保留天数',
  loginDays: '登录日志保留天数',
  content: '内容',
  route: '页面地址',
  maxWeight: '重量上限',
});
const before = computed(() => flatten(parse(props.record.beforeJson))),
  after = computed(() => flatten(parse(props.record.afterJson ?? props.record)));
</script>
<template>
  <el-descriptions v-if="record.action" :column="2" border
    ><el-descriptions-item label="操作">{{ label(record.action) }}</el-descriptions-item
    ><el-descriptions-item label="操作人">{{
      record.actorName || record.actorId
    }}</el-descriptions-item
    ><el-descriptions-item label="时间">{{ dateTime(record.createdAt) }}</el-descriptions-item
    ><el-descriptions-item label="结果">{{
      label(record.resultStatus || 'SUCCESS')
    }}</el-descriptions-item
    ><el-descriptions-item label="原因" :span="2">{{ record.reason }}</el-descriptions-item
    ><el-descriptions-item label="IP">{{ record.ip || '—' }}</el-descriptions-item
    ><el-descriptions-item label="耗时"
      >{{ record.elapsedMs || 0 }}毫秒</el-descriptions-item
    ></el-descriptions
  >
  <div class="change-tables">
    <section v-if="before.length">
      <h3>变更前</h3>
      <el-table :data="before" max-height="450"
        ><el-table-column prop="field" label="项目" min-width="160" /><el-table-column
          prop="value"
          label="资料"
          min-width="180"
          show-overflow-tooltip
      /></el-table>
    </section>
    <section v-if="after.length">
      <h3>{{ record.action ? '变更后' : '注销快照' }}</h3>
      <el-table :data="after" max-height="450"
        ><el-table-column prop="field" label="项目" min-width="160" /><el-table-column
          prop="value"
          label="资料"
          min-width="180"
          show-overflow-tooltip
      /></el-table>
    </section>
  </div>
  <el-empty v-if="!before.length && !after.length" description="此操作未改变业务资料" />
</template>
<style scoped>
.change-tables {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(270px, 1fr));
  gap: 20px;
  margin-top: 22px;
}
</style>
