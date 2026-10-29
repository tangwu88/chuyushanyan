<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useRouter } from 'vue-router';
import { showConfirmDialog, showSuccessToast } from 'vant';
import { api, state, hydrate, fail } from '../state';
import { money, label, dateTime, query, type Row } from '@chuyu/shared';
const router = useRouter(),
  shop = computed(() => state.points.find((p) => p.shopType !== 'NONE' && p.active) || {}),
  tab = ref('pending'),
  rows = ref<Row[]>([]),
  loading = ref(false),
  total = ref(0),
  page = ref(1),
  error = ref(''),
  showTopup = ref(false),
  showShipping = ref(false),
  topup = ref(''),
  busy = ref(false),
  selected = ref<string[]>([]),
  eligible = ref<Row[]>([]);
const showStock = ref(false),
  stockForm = ref<Row>({ toId: '', amount: '', paymentPassword: '' });
async function stockTransfer() {
  busy.value = true;
  try {
    const to = state.catalog.shops.find((s: Row) => s.id === stockForm.value.toId);
    if (!to) throw new Error('请选择接收店铺');
    await showConfirmDialog({
      title: '确认转货',
      message: to.nickname + '\n充值积分 ¥' + money(stockForm.value.amount),
    });
    await api.post('/stock-transfers', { fromId: shop.value.id, ...stockForm.value });
    showStock.value = false;
    await hydrate();
    showSuccessToast('转货完成');
  } catch (e) {
    if (e instanceof Error) fail(e);
  } finally {
    busy.value = false;
  }
}
const shippingForm = ref<Row>({ recipient: '', phone: '', address: '', pickup: false });
const canShip = computed(
  () =>
    Number(shop.value.recharge?.balance) >= Number(state.config.rules?.shippingThreshold || 10000),
);
async function load(reset = true) {
  loading.value = true;
  error.value = '';
  if (reset) page.value = 1;
  try {
    const resource =
      tab.value === 'shipments' ? 'shipments' : tab.value === 'topups' ? 'topups' : 'orders';
    const result = await api.get(
      '/lists/' +
        resource +
        '?' +
        query({
          shopId: shop.value.id,
          status: tab.value === 'pending' ? 'PENDING' : tab.value === 'available' ? 'ACTIVE' : '',
          page: page.value,
          size: 20,
        }),
    );
    rows.value = reset ? result.items : [...rows.value, ...result.items];
    total.value = result.total;
  } catch (e) {
    error.value = (e as Error).message;
    fail(e);
  } finally {
    loading.value = false;
  }
}
async function activate(o: Row) {
  busy.value = true;
  try {
    await showConfirmDialog({
      title: '确认激活订单',
      message: o.pointId + '\n订单 ¥' + money(o.total) + '\n激活后发放奖金。',
      confirmButtonText: '确认激活',
    });
    await api.post('/orders/' + o.id + '/activate');
    await hydrate();
    await load();
    showSuccessToast('订单已激活');
  } catch (e) {
    if (e instanceof Error) fail(e);
  } finally {
    busy.value = false;
  }
}
async function cancel(o: Row) {
  busy.value = true;
  try {
    await showConfirmDialog({
      title: '撤销订单',
      message: '订单 ' + o.id + '\n将冲回对应实时奖金，退款归入店铺受限充值积分；已发货记录保留。',
      confirmButtonText: '确认撤销',
    });
    await api.post('/orders/' + o.id + '/cancel', {
      reason: '店铺确认撤销：按原订单冲正（演示操作）',
    });
    await hydrate();
    await load();
    showSuccessToast('订单已撤销');
  } catch (e) {
    if (e instanceof Error) fail(e);
  } finally {
    busy.value = false;
  }
}
async function requestTopup() {
  busy.value = true;
  try {
    await api.post('/topups', { shopId: shop.value.id, amount: topup.value });
    showTopup.value = false;
    topup.value = '';
    tab.value = 'topups';
    await load();
    showSuccessToast('已提交充值申请');
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
async function openShipping() {
  loading.value = true;
  try {
    const result = await api.get(
      '/lists/orders?' + query({ shopId: shop.value.id, status: 'ACTIVE', size: 200 }),
    );
    eligible.value = [];
    for (const o of result.items) {
      const d = await api.get('/orders/' + o.id);
      if (d.availableQuantity > 0)
        eligible.value.push({
          ...o,
          availableQuantity: d.availableQuantity,
          shipQuantity: d.availableQuantity,
        });
    }
    selected.value = [];
    shippingForm.value = { recipient: '', phone: shop.value.phone, address: '', pickup: false };
    showShipping.value = true;
  } catch (e) {
    fail(e);
  } finally {
    loading.value = false;
  }
}
async function requestShipping() {
  busy.value = true;
  try {
    await api.post('/shipments', {
      ...shippingForm.value,
      shopId: shop.value.id,
      lines: eligible.value
        .filter((o) => selected.value.includes(o.id))
        .map((o) => ({ orderId: o.id, quantity: o.shipQuantity })),
    });
    showShipping.value = false;
    tab.value = 'shipments';
    await load();
    showSuccessToast('发货申请已提交');
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
watch(tab, () => load());
onMounted(() => load());
</script>
<template>
  <section class="store-hero">
    <div>
      <h2>{{ shop.nickname }}</h2>
      <p>{{ shop.id }} · {{ label(shop.shopType) }}</p>
    </div>
    <span class="store-symbol">山</span>
    <div class="store-balance">
      <span
        >充值积分余额<strong>¥{{ money(shop.recharge?.balance) }}</strong></span
      ><button @click="showTopup = true">申请充值 ＋</button>
    </div>
  </section>
  <section class="store-actions">
    <button :disabled="!canShip || loading" @click="openShipping">
      <span>▱</span><b>申请发货</b
      ><small>{{
        canShip ? '' : '充值积分需达到 ¥' + money(state.config.rules?.shippingThreshold)
      }}</small></button
    ><button @click="router.push('/register')"><span>♧</span><b>新增会员</b></button
    ><button
      @click="
        stockForm = {
          toId: state.catalog.shops.find((s: Row) => s.id !== shop.id)?.id || '',
          amount: '',
          paymentPassword: '',
        };
        showStock = true;
      "
    >
      <span>⇄</span><b>店铺转货</b>
    </button>
  </section>
  <div class="mobile-section-heading">
    <h2>店铺业务</h2>
    <button @click="hydrate().then(() => load())">刷新</button>
  </div>
  <van-tabs v-model:active="tab"
    ><van-tab title="待激活" name="pending" /><van-tab title="待发货" name="available" /><van-tab
      title="发货记录"
      name="shipments" /><van-tab title="充值审批" name="topups" /></van-tabs
  ><van-notice-bar v-if="error" :text="error" color="#a25247" background="#fff2eb" />
  <section class="store-order-list">
    <article v-for="o in rows" :key="o.id" class="mobile-card">
      <template v-if="tab === 'shipments'"
        ><div class="order-card-head">
          <b>{{ o.recipient }}</b
          ><van-tag plain>{{ label(o.status) }}</van-tag>
        </div>
        <p>{{ o.id }}</p>
        <p>{{ o.pickup ? '自提' : (o.carrier || '待登记物流') + ' ' + (o.tracking || '') }}</p>
        <small>{{ dateTime(o.createdAt) }}</small></template
      >
      <template v-else-if="tab === 'topups'"
        ><div class="order-card-head">
          <b>申请充值 ¥{{ money(o.amount) }}</b
          ><van-tag plain>{{ label(o.status) }}</van-tag>
        </div>
        <p>{{ o.id }}</p>
        <small>{{ dateTime(o.createdAt) }}</small></template
      >
      <template v-else
        ><div class="order-card-head">
          <b>{{ o.pointId }}</b
          ><van-tag plain>{{ label(o.orderType) }} · {{ o.quantity }}瓶</van-tag>
        </div>
        <p>{{ o.id }}</p>
        <div class="order-card-bottom">
          <span>{{ dateTime(o.createdAt) }}</span
          ><strong>¥{{ money(o.total) }}</strong>
        </div>
        <div class="store-order-actions">
          <button class="danger-text" :disabled="busy" @click="cancel(o)">撤销</button
          ><button @click="router.push('/orders/' + o.id)">查看详情</button
          ><van-button
            v-if="tab === 'pending'"
            type="primary"
            size="small"
            round
            :loading="busy"
            @click="activate(o)"
            >激活订单</van-button
          >
        </div></template
      >
    </article>
  </section>
  <van-loading v-if="loading" class="center-loading">加载中</van-loading
  ><van-empty
    v-if="!rows.length && !loading && !error"
    description="当前暂无待处理记录"
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
  >
  <van-popup v-model:show="showStock" position="bottom" round closeable class="wallet-popup"
    ><h2>店铺转货</h2>
    <label class="native-select-label"
      >接收店铺<select v-model="stockForm.toId">
        <option
          v-for="s in state.catalog.shops.filter((x: Row) => x.id !== shop.id)"
          :key="s.id"
          :value="s.id"
        >
          {{ s.nickname }} · {{ s.id }}
        </option>
      </select></label
    ><van-field
      v-model="stockForm.amount"
      label="充值积分"
      inputmode="decimal"
      placeholder="请输入金额"
    /><van-field
      v-model="stockForm.paymentPassword"
      type="password"
      inputmode="numeric"
      label="支付密码"
      maxlength="6"
    />
    <p class="inline-note">受限积分需财务处理。</p>
    <van-button block round type="primary" :loading="busy" @click="stockTransfer"
      >确认转货</van-button
    ></van-popup
  >
  <van-popup v-model:show="showTopup" position="bottom" round closeable class="wallet-popup"
    ><h2>申请店铺充值</h2>
    <p class="muted">审批通过后到账。</p>
    <van-field
      v-model="topup"
      label="充值金额"
      inputmode="decimal"
      placeholder="请输入充值金额"
    /><van-button block round type="primary" :loading="busy" @click="requestTopup"
      >提交申请</van-button
    ></van-popup
  >
  <van-popup v-model:show="showShipping" position="bottom" round closeable class="shipping-popup"
    ><h2>申请合并 / 分批发货</h2>
    <div class="form-inline">
      <span>到店自提</span
      ><van-switch v-model="shippingForm.pickup" size="21" @change="selected = []" />
    </div>
    <van-field v-model="shippingForm.recipient" label="收货人" placeholder="请输入姓名" /><van-field
      v-model="shippingForm.phone"
      label="电话"
      inputmode="numeric"
    /><van-field
      v-if="!shippingForm.pickup"
      v-model="shippingForm.address"
      label="地址"
      placeholder="请输入收货地址"
    /><van-checkbox-group v-model="selected"
      ><div
        v-for="o in eligible.filter((x) => !!x.pickup === !!shippingForm.pickup)"
        :key="o.id"
        class="ship-choice"
      >
        <van-checkbox :name="o.id"
          ><b>{{ label(o.orderType) }} · {{ o.pointId }}</b
          ><small>{{ o.id }} · 可申请{{ o.availableQuantity }}瓶</small></van-checkbox
        ><van-stepper
          v-model="o.shipQuantity"
          :min="1"
          :max="o.availableQuantity"
        /></div></van-checkbox-group
    ><van-empty v-if="!eligible.length" description="暂无可申请发货的订单" /><van-button
      block
      round
      type="primary"
      :disabled="!selected.length"
      :loading="busy"
      @click="requestShipping"
      >提交发货申请（{{ selected.length }}笔）</van-button
    ></van-popup
  >
</template>
