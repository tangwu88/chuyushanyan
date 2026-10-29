<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '../state';
import { money, percent, label, query, type Row, dateTime } from '@chuyu/shared';
const data = ref<Row>({ totals: {}, daily: [], byType: [] }),
  loading = ref(false),
  error = ref(''),
  dates = ref<string[]>([]);
function localDay(d: Date) {
  return (
    d.getFullYear() +
    '-' +
    String(d.getMonth() + 1).padStart(2, '0') +
    '-' +
    String(d.getDate()).padStart(2, '0')
  );
}
function quick(kind: string) {
  const now = new Date(),
    s = new Date(now),
    e = new Date(now);
  if (kind === 'today') {
  } else if (kind === 'yesterday') {
    s.setDate(s.getDate() - 1);
    e.setDate(e.getDate() - 1);
  } else if (kind === 'last') {
    s.setMonth(s.getMonth() - 1, 1);
    e.setDate(0);
  } else {
    s.setDate(1);
  }
  dates.value = [localDay(s), localDay(e)];
  load();
}
async function load() {
  loading.value = true;
  error.value = '';
  try {
    data.value = await api.get(
      '/reports?' + query({ start: dates.value?.[0], end: dates.value?.[1] }),
    );
  } catch (e) {
    error.value = (e as Error).message;
    ElMessage.error(error.value);
  } finally {
    loading.value = false;
  }
}
function drilldown(type: string) {
  return (
    '/' +
    (type === 'AWARD' ? 'finance' : 'orders') +
    '?' +
    query({
      tab: type === 'AWARD' ? 'awards' : 'orders',
      type: type === 'AWARD' ? '' : type,
      status: type === 'AWARD' ? '' : 'ACTIVE',
      dateField: type === 'AWARD' ? 'createdAt' : 'activatedAt',
      start: dates.value?.[0],
      end: dates.value?.[1],
    })
  );
}
onMounted(() => quick('month'));
</script>
<template>
  <div class="page-heading">
    <div><h1>工作台</h1></div>
    <div class="heading-actions">
      <el-date-picker
        v-model="dates"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        @change="load"
      /><el-button @click="load" :loading="loading">刷新数据</el-button>
    </div>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
  <div class="quick-filters">
    <button
      v-for="item in [
        { id: 'today', name: '今日' },
        { id: 'yesterday', name: '昨日' },
        { id: 'month', name: '本月' },
        { id: 'last', name: '上月' },
      ]"
      :key="item.id"
      @click="quick(item.id)"
    >
      {{ item.name }}
    </button>
  </div>

  <section class="kpi-grid" v-loading="loading">
    <article class="kpi-card primary">
      <span>报单业绩</span><strong><small>¥</small>{{ money(data.totals.reportTotal) }}</strong>
      <p>新人及首购 · 已激活订单</p>
      <router-link :to="drilldown('REPORT')">查看订单 ↗</router-link>
    </article>
    <article class="kpi-card">
      <span>复购业绩</span><strong><small>¥</small>{{ money(data.totals.repeatTotal) }}</strong>
      <p>当前区间复购订单</p>
      <router-link :to="drilldown('REPEAT')">查看明细 ↗</router-link>
    </article>
    <article class="kpi-card">
      <span>实发奖金</span><strong><small>¥</small>{{ money(data.totals.net) }}</strong>
      <p>管理费 ¥{{ money(data.totals.fee) }}</p>
      <router-link :to="drilldown('AWARD')">查看奖金 ↗</router-link>
    </article>
    <article class="kpi-card">
      <span>奖金拨比</span><strong>{{ percent(data.totals.payoutRatio) }}</strong>
      <p>实发奖金 / 同期业绩</p>
    </article>
  </section>
  <section class="dashboard-columns">
    <article class="surface">
      <div class="section-heading"><h3>待办事项</h3></div>
      <div class="todo-grid">
        <router-link
          v-for="t in [
            { name: '订单待激活', n: data.totals.pendingActivations, to: '/orders' },
            { name: '充值待审批', n: data.totals.pendingTopups, to: '/finance' },
            { name: '提现待审核', n: data.totals.pendingWithdrawals, to: '/finance' },
            { name: '发货待审批', n: data.totals.pendingShipments, to: '/orders' },
          ]"
          :key="t.name"
          :to="t.to"
          ><span>{{ t.name }}</span
          ><strong>{{ t.n || 0 }} <small>→</small></strong></router-link
        >
      </div>
    </article>
    <article class="surface membership-card">
      <h3>会员概况</h3>
      <div>
        <strong>{{ data.totals.memberCount || 0 }}</strong
        ><span>会员点位</span><strong>{{ data.totals.newMembers || 0 }}</strong
        ><span>区间新增</span>
      </div>
      <p>
        会员奖励积分余额 <b>¥{{ money(data.totals.rewardBalance) }}</b>
      </p>
      <router-link to="/members">查看会员与关系 →</router-link>
    </article>
  </section>
  <section class="dashboard-columns">
    <article class="surface">
      <div class="section-heading">
        <h3>每日业绩</h3>
        <span class="legend"><i />报单 <i class="sand" />复购</span>
      </div>
      <el-empty
        v-if="!data.daily.length && !loading"
        description="当前区间暂无已激活订单"
        :image-size="70"
      />
      <div v-for="d in data.daily" :key="d.day" class="bar-row">
        <span>{{ String(d.day).slice(5) }}</span>
        <div class="bar-track">
          <i
            :style="{
              width:
                Math.max(
                  2,
                  (Number(d.reportTotal) /
                    Math.max(
                      ...data.daily.map((x: Row) => Number(x.reportTotal) + Number(x.repeatTotal)),
                      1,
                    )) *
                    100,
                ) + '%',
            }"
          /><i
            class="sand"
            :style="{
              width:
                Math.max(
                  Number(d.repeatTotal) > 0 ? 2 : 0,
                  (Number(d.repeatTotal) /
                    Math.max(
                      ...data.daily.map((x: Row) => Number(x.reportTotal) + Number(x.repeatTotal)),
                      1,
                    )) *
                    100,
                ) + '%',
            }"
          />
        </div>
        <b>¥{{ money(Number(d.reportTotal) + Number(d.repeatTotal)) }}</b>
      </div>
    </article>
    <article class="surface">
      <div class="section-heading">
        <h3>奖金构成</h3>
        <router-link to="/finance">查看明细 ↗</router-link>
      </div>
      <el-table :data="data.byType" size="small" empty-text="当前区间暂无奖金入账"
        ><el-table-column label="奖项"
          ><template #default="{ row }">{{ label(row.awardType) }}</template></el-table-column
        ><el-table-column label="毛奖" align="right"
          ><template #default="{ row }">{{ money(row.gross) }}</template></el-table-column
        ><el-table-column label="实发" align="right"
          ><template #default="{ row }"
            ><b>{{ money(row.net) }}</b></template
          ></el-table-column
        ></el-table
      >
    </article>
  </section>
  <p class="data-caption">
    <el-tooltip :content="data.basis"><span>统计口径 ⓘ</span></el-tooltip> · 更新于
    {{ dateTime(data.asOf) }}
  </p>
</template>
