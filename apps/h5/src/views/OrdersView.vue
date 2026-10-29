<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { showConfirmDialog, showSuccessToast } from 'vant';
import { api, state, hydrate, fail } from '../state';
import { money, label, dateTime, orderStatus, query, type Row } from '@chuyu/shared';
const props = defineProps<{ orderId: string }>(),
  router = useRouter(),
  rows = ref<Row[]>([]),
  detail = ref<Row | null>(null),
  loading = ref(false),
  error = ref(''),
  page = ref(1),
  total = ref(0);
const busy = ref(false),
  scope = ref('current'),
  status = ref(''),
  type = ref(''),
  start = ref(''),
  end = ref('');
async function load(reset = true) {
  loading.value = true;
  error.value = '';
  try {
    if (props.orderId) {
      detail.value = await api.get('/orders/' + props.orderId);
    } else {
      if (reset) page.value = 1;
      const result = await api.get(
        '/lists/orders?' +
          query({
            pointId: scope.value === 'current' ? state.pointId : '',
            payerId: scope.value === 'assisted' ? state.pointId : '',
            page: page.value,
            size: 20,
            status: status.value,
            type: type.value,
            start: start.value,
            end: end.value,
            includeArchived: true,
          }),
      );
      rows.value = reset ? result.items : [...rows.value, ...result.items];
      total.value = result.total;
    }
  } catch (e) {
    error.value = (e as Error).message;
    fail(e);
  } finally {
    loading.value = false;
  }
}
async function pay() {
  if (!detail.value) return;
  if (busy.value) return;
  busy.value = true;
  try {
    await showConfirmDialog({
      title: '确认演示支付',
      message: '将按订单扣除所选积分，现金部分仅做演示记录。',
    });
    await api.post('/orders/' + detail.value.id + '/pay');
    await hydrate();
    await load();
    showSuccessToast('演示支付完成');
  } catch (e) {
    if (e instanceof Error) fail(e);
  } finally {
    busy.value = false;
  }
}
async function cancelUnpaid() {
  if (!detail.value) return;
  busy.value = true;
  try {
    await showConfirmDialog({ title: '取消未付款订单', confirmButtonText: '确认取消' });
    await api.post('/orders/' + detail.value.id + '/cancel', { reason: '会员取消未付款订单' });
    await load();
    showSuccessToast('订单已取消');
  } catch (e) {
    if (e instanceof Error) fail(e);
  } finally {
    busy.value = false;
  }
}
onMounted(() => load());
</script>
<template>
  <div class="mobile-section-heading"><button @click="load()">刷新</button></div>
  <template v-if="!orderId"
    ><van-tabs v-model:active="scope" @change="load()"
      ><van-tab title="当前点位" name="current" /><van-tab title="代付订单" name="assisted"
    /></van-tabs>
    <details class="mobile-card order-filters">
      <summary>筛选订单</summary>
      <label
        >购买类型<select v-model="type">
          <option value="">全部</option>
          <option v-for="t in ['NEW', 'FIRST', 'REPEAT']" :key="t" :value="t">
            {{ label(t) }}
          </option>
        </select></label
      ><label
        >订单状态<select v-model="status">
          <option value="">全部</option>
          <option
            v-for="s in [
              'UNPAID',
              'PENDING',
              'UNFULFILLED',
              'APPLIED',
              'PARTIAL',
              'FULFILLED',
              'CANCELLED',
            ]"
            :key="s"
            :value="s"
          >
            {{ label(s) }}
          </option>
        </select></label
      ><label>开始日期<input v-model="start" type="date" /></label
      ><label>结束日期<input v-model="end" type="date" /></label
      ><van-button block size="small" plain @click="load()">查询</van-button>
    </details></template
  >
  <van-notice-bar v-if="error" :text="error" color="#a25247" background="#fff2eb" />
  <template v-if="detail"
    ><section class="mobile-card order-detail">
      <div class="order-card-head">
        <span>{{ label(detail.orderType) }}</span
        ><van-tag plain type="primary">{{ label(orderStatus(detail)) }}</van-tag>
      </div>
      <h2>¥{{ money(detail.total) }}</h2>
      <p>{{ detail.id }}</p>
      <p v-if="detail.remark">备注：{{ detail.remark }}</p>
      <dl>
        <dt>购买点位</dt>
        <dd>{{ detail.pointId }}</dd>
        <dt>代办店铺</dt>
        <dd>{{ detail.shopId }}</dd>
        <dt>商品数量</dt>
        <dd>{{ detail.quantity }} 瓶</dd>
        <dt>商品</dt>
        <dd>{{ detail.product?.name }}</dd>
        <dt>奖励积分</dt>
        <dd>¥{{ money(detail.rewardPaid) }}</dd>
        <dt>现金部分</dt>
        <dd>¥{{ money(detail.cashPaid) }}</dd>
        <dt>支付状态</dt>
        <dd>{{ label(detail.paymentStatus) }}</dd>
        <dt>激活状态</dt>
        <dd>{{ label(detail.activationStatus) }}</dd>
        <dt>履约状态</dt>
        <dd>{{ label(detail.fulfilmentStatus) }}</dd>
        <dt>取货方式</dt>
        <dd>{{ detail.pickup ? '自提' : '快递配送' }}</dd>
        <dt>规则版本</dt>
        <dd>{{ detail.ruleId }}</dd>
      </dl>
      <van-button
        v-if="!detail.cancelled && detail.paymentStatus === 'UNPAID'"
        block
        round
        type="primary"
        @click="pay"
        :loading="busy"
        >确认演示支付</van-button
      >
      <van-button
        v-if="!detail.cancelled && detail.paymentStatus === 'UNPAID'"
        block
        round
        plain
        :loading="busy"
        @click="cancelUnpaid"
        >取消订单</van-button
      >
      <p
        v-if="detail.paymentStatus === 'PAID' && detail.activationStatus === 'PENDING'"
        class="inline-note"
      >
        待店铺激活。
      </p>
    </section>
    <section class="mobile-card">
      <h3>发货进度</h3>
      <van-empty v-if="!detail.shipments?.length" description="尚未申请发货" :image-size="60" />
      <div v-for="s in detail.shipments" :key="s.id" class="shipping-progress">
        <b>{{ label(s.status) }} · {{ s.quantity }}瓶</b>
        <p v-if="s.cancelled" class="negative">本订单已撤销，历史发货记录保留</p>
        <p>{{ s.pickup ? '到店自提' : (s.carrier || '待登记快递') + ' ' + (s.tracking || '') }}</p>
        <small>{{ s.id }}</small>
      </div>
    </section>
    <section class="mobile-card">
      <h3>业务轨迹</h3>
      <van-steps direction="vertical" :active="detail.history?.length - 1"
        ><van-step v-for="(h, i) in detail.history" :key="i"
          ><h4>{{ h.reason || h.action }}</h4>
          <p>{{ dateTime(h.createdAt) }}</p></van-step
        ></van-steps
      >
    </section></template
  >
  <template v-else
    ><article
      v-for="o in rows"
      :key="o.id"
      class="mobile-card order-card"
      @click="router.push('/orders/' + o.id)"
    >
      <div class="order-card-head">
        <span>{{ label(o.orderType) }} · {{ o.quantity }}瓶</span
        ><van-tag plain>{{ label(orderStatus(o)) }}</van-tag>
      </div>
      <h3>{{ o.productName }}</h3>
      <p>{{ o.id }}</p>
      <div class="order-card-bottom">
        <span>{{ dateTime(o.createdAt) }}</span
        ><b>¥{{ money(o.total) }} <small>›</small></b>
      </div>
    </article>
    <van-empty
      v-if="!loading && !rows.length && !error"
      description="当前点位暂无订单"
    /><van-button
      v-if="rows.length < total"
      block
      plain
      :loading="loading"
      @click="
        page++;
        load(false);
      "
      >加载更多</van-button
    ></template
  ><van-loading v-if="loading" class="center-loading" size="22">加载中</van-loading>
</template>
<style scoped>
.order-filters {
  padding: 12px 16px;
}
.order-filters summary {
  font-size: 13px;
}
.order-filters label {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin: 12px 0;
  font-size: 12px;
  align-items: center;
}
.order-filters select,
.order-filters input {
  max-width: 185px;
  min-height: 34px;
  border: 1px solid #dce4d4;
  border-radius: 5px;
  padding: 6px;
  background: white;
  color: #365440;
}
</style>
