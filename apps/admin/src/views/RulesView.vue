<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, state, can, hydrate } from '../state';
import { money, percent, label, type Row } from '@chuyu/shared';
const versions = ref<Row[]>([]),
  scenarios = ref<Row[]>([]),
  busy = ref(false),
  error = ref(''),
  openId = ref(''),
  edit = ref<Row>({}),
  selected = ref<Row | null>(null),
  scenarioId = ref('');
const active = computed(() => versions.value.find((v) => v.status === 'ACTIVE'));
const referenceLabels: Record<string, string> = {
  label: '配置类型',
  repeatBandCount: '复购档数',
  repeatRates: '复购分红比例',
  firstBands: '前3档业绩区间',
  maxSubPoints: '小号数量上限',
  directorCounts: '各级董事人数',
  originalThresholdField: '董事原阈值字段',
  repeatTopThreshold: '最高档奖励门槛',
  sourceDate: '核对日期',
  lastBand: '最后一档',
  smallShopThreshold: '小店升级门槛',
};
const rateFields = [
  ['managementFee', '奖金管理费'],
  ['withdrawalFee', '提现手续费'],
  ['tax', '演示税率'],
  ['peerRate', '平级奖比例'],
  ['sevenDailyRate', '七星日分红比例'],
  ['averageShare', '日分红平均分配占比'],
  ['smallShopRate', '小店分红比例'],
  ['smallShopSubsidy', '小店报单店补'],
  ['bigShopSubsidy', '大店报单店补'],
  ['plan1Rate', '计划1报单分红比例'],
  ['plan2Rate', '计划2复购分红比例'],
  ['repeatTopRate', '复购额外奖池比例'],
];
const numberFields = [
  ['maxPoints', '主号与小号数量上限'],
  ['directReferrals', '普通升级直推人数'],
  ['peerGenerations', '平级奖代数'],
  ['shippingThreshold', '发货充值积分门槛'],
  ['smallShopThreshold', '小店累计消费门槛'],
  ['smallShopMinimumGrade', '小店分红最低会员等级'],
  ['repeatTopThreshold', '复购最高档额外奖门槛'],
];
async function load() {
  error.value = '';
  try {
    versions.value = await api.get('/rules');
    scenarios.value = await api.get('/scenarios');
  } catch (e) {
    error.value = (e as Error).message;
  }
}
function select(v: Row) {
  selected.value = v;
  openId.value = v.id;
  edit.value = JSON.parse(JSON.stringify(v.config));
}
function setRate(object: Row, key: string, value: number | undefined) {
  object[key] = String(Number(value || 0) / 100);
  if (key === 'plan2Rate' && object.identities?.A) object.identities.A.rate = object[key];
}
async function create() {
  try {
    const { value } = await ElMessageBox.prompt(
      '新版本会复制当前生效规则，修改后需要重新试算才能发布。',
      '新建规则版本',
      {
        inputValue: 'V2 演示规则 · 调整版',
        inputValidator: (v) => !!v?.trim() || '请输入版本名称',
      },
    );
    const result = await api.post('/rules', { name: value });
    await load();
    select(versions.value.find((v) => v.id === result.id)!);
    ElMessage.success('草稿已建立');
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message);
  }
}
async function save() {
  await api.post('/rules/' + openId.value + '/update', {
    config: edit.value,
    reason: '规则草稿编辑',
  });
  await load();
  select(versions.value.find((v) => v.id === openId.value)!);
}
async function trial() {
  busy.value = true;
  try {
    await save();
    const result = await api.post('/rules/' + openId.value + '/test');
    scenarios.value = result.scenarios;
    await loadVersions();
    ElMessage.success('草稿试算通过，可以发布');
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = false;
  }
}
async function loadVersions() {
  versions.value = await api.get('/rules');
  selected.value = versions.value.find((v) => v.id === openId.value) || null;
}
async function saveOnly() {
  busy.value = true;
  try {
    await save();
    ElMessage.success('草稿已保存，需重新试算');
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = false;
  }
}
async function publish() {
  try {
    await ElMessageBox.confirm(
      '发布后新业务采用此规则；历史订单、历史结算保留原版本。',
      '发布规则版本',
      { confirmButtonText: '发布生效', cancelButtonText: '取消' },
    );
    await api.post('/rules/' + openId.value + '/publish');
    await hydrate();
    await load();
    select(active.value!);
    ElMessage.success('新版本已生效');
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message);
  }
}
async function toggle(enabled: boolean) {
  try {
    const { value: reason } = await ElMessageBox.prompt(
      enabled
        ? '开启后会员可以提交新的奖励积分转账。'
        : '关闭后界面和接口同时停止新的转账，历史流水保留。',
      '变更转账开关',
      { inputPlaceholder: '请填写变更原因', inputValidator: (v) => !!v?.trim() || '请输入原因' },
    );
    await api.post('/settings/1/update', { transferEnabled: enabled, reason });
    await hydrate();
    ElMessage.success('转账开关已更新');
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message);
  }
}
onMounted(async () => {
  await load();
  if (active.value) select(active.value);
});
</script>
<template>
  <div class="page-heading">
    <div><h1>规则中心</h1></div>
    <el-button v-if="can('rules:write')" type="primary" @click="create">＋ 新建规则版本</el-button>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <div class="rule-overview">
    <article class="surface rule-current">
      <span class="eyebrow">当前生效版本</span>
      <h2>{{ active?.name }}</h2>
      <p>{{ active?.source }}</p>
      <div>
        <el-tag type="success">{{ active?.id }}</el-tag
        ><span
          >管理费 {{ active ? percent(active.config.managementFee) : '—' }} · 提现
          {{ active ? percent(active.config.withdrawalFee) : '—' }}</span
        >
      </div>
    </article>
    <article class="surface transfer-switch">
      <div><h3>会员奖励积分转账</h3></div>
      <el-switch
        :model-value="!!state.config.transferEnabled"
        :disabled="!can('rules:write')"
        active-text="已开启"
        inactive-text="已关闭"
        @change="(v: boolean | string | number) => toggle(!!v)"
      />
    </article>
  </div>
  <div class="rules-workspace">
    <aside class="surface versions-panel">
      <div class="section-heading">
        <h3>规则版本</h3>
        <span>{{ versions.length }}</span>
      </div>
      <button
        v-for="v in versions"
        :key="v.id"
        :class="{ chosen: v.id === openId }"
        @click="select(v)"
      >
        <span>{{ v.name }}</span
        ><el-tag size="small" :type="v.status === 'ACTIVE' ? 'success' : 'info'">{{
          label(v.status)
        }}</el-tag
        ><small>{{ v.source }}</small>
      </button>
    </aside>
    <section v-if="selected" class="surface rule-editor">
      <div class="section-heading">
        <div>
          <h3>{{ selected.name }}</h3>
          <p class="muted">{{ selected.id }} · {{ label(selected.status) }}</p>
        </div>
        <div v-if="selected.status === 'DRAFT' && can('rules:write')">
          <el-button :loading="busy" @click="saveOnly">保存草稿</el-button
          ><el-button :loading="busy" @click="trial">保存并试算</el-button
          ><el-button type="primary" :disabled="!selected.testedHash" @click="publish"
            >发布生效</el-button
          >
        </div>
      </div>
      <template v-if="selected.status === 'REFERENCE'"
        ><el-alert title="现网参数仅供对照。" :closable="false" show-icon /><el-descriptions
          :column="1"
          border
          ><el-descriptions-item
            v-for="(v, k) in edit"
            :key="k"
            :label="referenceLabels[String(k)] || String(k)"
            >{{ Array.isArray(v) ? v.join(' / ') : v }}</el-descriptions-item
          ></el-descriptions
        ></template
      >
      <template v-else
        ><el-alert
          v-if="selected.status !== 'DRAFT'"
          title="已发布版本只读。"
          type="info"
          :closable="false"
        /><el-form
          :key="selected.id"
          label-position="top"
          :disabled="selected.status !== 'DRAFT' || !can('rules:write')"
        >
          <el-tabs
            ><el-tab-pane label="费用与门槛"
              ><div class="rule-field-grid">
                <el-form-item v-for="f in rateFields" :key="f[0]" :label="f[1] + '（%）'"
                  ><el-input-number
                    :model-value="Number(edit[f[0]]) * 100"
                    :min="0"
                    :max="100"
                    :precision="2"
                    @update:model-value="
                      (v: number | undefined) => setRate(edit, f[0], v)
                    " /></el-form-item
                ><el-form-item v-for="f in numberFields" :key="f[0]" :label="f[1]"
                  ><el-input-number
                    :model-value="Number(edit[f[0]])"
                    @update:model-value="(v: number | undefined) => (edit[f[0]] = v)"
                    :min="0"
                    :max="1000000000000" /></el-form-item
                ><el-form-item label="小店分红周期"
                  ><el-select v-model="edit.smallShopCycle"
                    ><el-option value="DAILY" label="日结" /><el-option
                      value="MONTHLY"
                      label="月结" /></el-select></el-form-item
                ><el-form-item label="小店分红等级限制"
                  ><el-switch v-model="edit.smallShopGradeLimit" /></el-form-item
                ><el-form-item label="最高档额外池包含一条线最高档成员"
                  ><el-switch v-model="edit.repeatTopLineBonus"
                /></el-form-item>
              </div>
              <h4>销售奖比例</h4>
              <div class="rule-field-grid">
                <el-form-item
                  v-for="(_, i) in edit.salesRates"
                  :key="i"
                  :label="Number(i) === 0 ? '普通会员（%）' : i + '星经理（%）'"
                  ><el-input-number
                    :model-value="Number(edit.salesRates[i]) * 100"
                    :min="0"
                    :max="100"
                    @update:model-value="
                      (v: number | undefined) => (edit.salesRates[i] = String(Number(v || 0) / 100))
                    "
                /></el-form-item></div
            ></el-tab-pane>
            <el-tab-pane label="复购13档"
              ><p class="muted">包含下限，不含上限；最后一档无上限。</p>
              <el-table :data="edit.repeatTiers"
                ><el-table-column label="档位" width="70"
                  ><template #default="{ $index }">{{ $index + 1 }}</template></el-table-column
                ><el-table-column label="月新增业绩下限"
                  ><template #default="{ row }"
                    ><el-input v-model="row.min" /></template></el-table-column
                ><el-table-column label="上限"
                  ><template #default="{ row }"
                    ><el-input v-if="row.max !== null" v-model="row.max" /><span v-else
                      >无上限</span
                    ></template
                  ></el-table-column
                ><el-table-column label="分红比例 %"
                  ><template #default="{ row }"
                    ><el-input-number
                      :model-value="Number(row.rate) * 100"
                      :min="0"
                      :max="100"
                      @update:model-value="
                        (v: number | undefined) => setRate(row, 'rate', v)
                      " /></template></el-table-column></el-table
            ></el-tab-pane>
            <el-tab-pane label="董事与等级"
              ><h4>董事分红与除权</h4>
              <div v-for="(_, i) in edit.directorRates" :key="i" class="director-row">
                <b>{{ Number(i) + 1 }}星董事</b
                ><label
                  >分红 %
                  <el-input-number
                    :model-value="Number(edit.directorRates[i]) * 100"
                    :min="0"
                    :max="100"
                    @update:model-value="
                      (v: number | undefined) =>
                        (edit.directorRates[i] = String(Number(v || 0) / 100))
                    " /></label
                ><template v-if="Number(i) < 5"
                  ><label
                    >除权阈值 %
                    <el-input-number
                      :model-value="Number(edit.directorThresholds[i]) * 100"
                      :min="0"
                      :max="100"
                      @update:model-value="
                        (v: number | undefined) =>
                          (edit.directorThresholds[i] = String(Number(v || 0) / 100))
                      " /></label
                  ><label
                    >最少同级人数
                    <el-input-number v-model="edit.directorCounts[i]" :min="1" /></label></template
                ><span v-else>纯平均 · 不除权</span>
              </div>
              <div v-for="(grades, version) in edit.gradeVersions" :key="version">
                <h4>{{ version }} 会员等级门槛</h4>
                <el-table :data="grades"
                  ><el-table-column prop="grade" label="星级" /><el-table-column
                    label="团队累计门槛"
                    ><template #default="{ row }"
                      ><el-input v-model="row.team" /></template></el-table-column
                  ><el-table-column label="小市场累计门槛"
                    ><template #default="{ row }"
                      ><el-input v-model="row.small" /></template></el-table-column
                ></el-table></div
            ></el-tab-pane>
            <el-tab-pane label="八类特殊身份"
              ><el-alert title="A店与计划2只发放一次。" type="warning" :closable="false" />
              <div v-for="(identity, code) in edit.identities" :key="code" class="identity-rule">
                <h4>
                  {{ identity.name }} <el-tag size="small">{{ identity.source }}</el-tag>
                </h4>
                <div class="rule-field-grid">
                  <el-form-item label="业绩类型"
                    ><el-select v-model="identity.basis" :disabled="code === 'A'"
                      ><el-option
                        v-for="basis in ['REPORT', 'REPEAT', 'TOTAL']"
                        :key="basis"
                        :value="basis"
                        :label="label(basis)" /></el-select></el-form-item
                  ><el-form-item label="结算周期"
                    ><el-select v-model="identity.cycle" :disabled="code === 'A'"
                      ><el-option value="DAILY" label="日结" /><el-option
                        value="MONTHLY"
                        label="月结" /></el-select></el-form-item
                  ><el-form-item label="最低会员星级"
                    ><el-input-number
                      :disabled="code === 'A'"
                      v-model="identity.minimumGrade"
                      :min="0"
                      :max="7" /></el-form-item
                  ><el-form-item label="分红比例 %"
                    ><el-input-number
                      :disabled="code === 'A'"
                      :model-value="Number(identity.rate) * 100"
                      :min="0"
                      :max="100"
                      :precision="2"
                      @update:model-value="(v: number | undefined) => setRate(identity, 'rate', v)"
                  /></el-form-item>
                </div></div
            ></el-tab-pane> </el-tabs></el-form
        ><el-collapse class="assumptions"
          ><el-collapse-item title="待确认规则" name="assumptions"
            ><p v-for="(a, i) in edit.assumptions" :key="i">
              {{ Number(i) + 1 }}. {{ a }}
            </p></el-collapse-item
          ></el-collapse
        ></template
      >
    </section>
  </div>
  <section class="surface scenario-section">
    <div class="section-heading">
      <div><h3>奖金计算案例</h3></div>
      <el-button @click="load">重新计算</el-button>
    </div>
    <el-collapse v-model="scenarioId" accordion
      ><el-collapse-item v-for="s in scenarios" :key="s.id" :name="s.id"
        ><template #title
          ><span class="scenario-title"
            >{{ s.title }}<small>实发 ¥{{ money(s.result.net) }}</small></span
          ></template
        ><el-table :data="s.result.awards"
          ><el-table-column label="奖项"
            ><template #default="{ row }">{{ label(row.type) }}</template></el-table-column
          ><el-table-column prop="recipient" label="受益账户" /><el-table-column label="毛奖"
            ><template #default="{ row }">{{ money(row.gross) }}</template></el-table-column
          ><el-table-column label="管理费"
            ><template #default="{ row }">{{ money(row.fee) }}</template></el-table-column
          ><el-table-column label="实得"
            ><template #default="{ row }">{{ money(row.net) }}</template></el-table-column
          ><el-table-column
            prop="explanation"
            label="计算依据"
            min-width="260" /></el-table></el-collapse-item
    ></el-collapse>
  </section>
</template>
