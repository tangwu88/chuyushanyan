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
async function load(reset = true) {
  loading.value = true;
  error.value = '';
  try {
    if (props.orderId) {
      detail.value = await api.get('/orders/' + props.orderId);
    } else {
      if (reset) page.value = 1;
      const result = await api.get(
        '/lists/orders?' + query({ pointId: state.pointId, page: page.value, size: 20 }),
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
  }
}
onMounted(() => load());
</script>
<template>
  <div class="mobile-section-heading"><button @click="load()">刷新</button></div>
  <van-notice-bar v-if="error" :text="error" color="#a25247" background="#fff2eb" />
  <template v-if="detail"
    ><section class="mobile-card order-detail">
      <div class="order-card-head">
        <span>{{ label(detail.orderType) }}</span
        ><van-tag plain type="primary">{{ label(orderStatus(detail)) }}</van-tag>
      </div>
      <h2>¥{{ money(detail.total) }}</h2>
      <p>{{ detail.id }}</p>
      <dl>
        <dt>购买点位</dt>
        <dd>{{ detail.pointId }}</dd>
        <dt>代办店铺</dt>
        <dd>{{ detail.shopId }}</dd>
        <dt>商品数量</dt>
        <dd>{{ detail.quantity }} 瓶</dd>
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
        >确认演示支付</van-button
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
      <h3>初语山言 · 会员专供</h3>
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
