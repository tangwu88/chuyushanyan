<script setup lang="ts">
import { ref, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showConfirmDialog, showSuccessToast } from 'vant';
import { api, state, selectedPoint, hydrate, fail } from '../state';
import { money, grade, label, availableBalance, type Row } from '@chuyu/shared';
const route = useRoute(),
  router = useRouter(),
  point = selectedPoint();
const buyers = computed(() => [
  ...state.points,
  ...state.assisted.filter((p) => !state.points.some((n) => n.id === p.id)),
]);
const form = ref<Row>({
  pointId: String(route.query.point || state.pointId),
  payerId: state.pointId,
  shopId: state.catalog.shops[0]?.id || '',
  productId: String(route.query.product || state.catalog.products[0]?.id || ''),
  type: String(route.query.type || (point.activated ? 'FIRST' : 'NEW')),
  quantity: 1,
  rewardAmount: '0',
  paymentMethod: 'DEMO',
  pickup: false,
});
const quote = ref<Row | null>(null),
  busy = ref(false),
  created = ref<Row | null>(null);
async function calculate() {
  busy.value = true;
  quote.value = null;
  try {
    quote.value = await api.post('/quotes', form.value);
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
async function submit() {
  busy.value = true;
  try {
    const q = await api.post('/quotes', form.value);
    quote.value = q;
    await showConfirmDialog({
      title: '确认演示订单',
      message:
        '购买点位 ' +
        q.pointId +
        '\n' +
        label(q.type) +
        ' ' +
        q.quantity +
        '瓶\n奖励积分 ¥' +
        money(q.rewardAmount) +
        '\n' +
        (q.paymentMethod === 'STORE' ? '店铺充值积分' : '演示现金') +
        ' ¥' +
        money(q.cashAmount) +
        '\n',
      confirmButtonText: '确认创建',
    });
    created.value = await api.post('/orders', form.value);
    showSuccessToast('订单已创建');
  } catch (e) {
    if (e instanceof Error) fail(e);
  } finally {
    busy.value = false;
  }
}
async function pay() {
  if (!created.value) return;
  busy.value = true;
  try {
    await showConfirmDialog({
      title: '演示支付确认',
      message: '本次为演示支付，不实际扣款。',
      confirmButtonText: '确认演示支付',
    });
    await api.post('/orders/' + created.value.id + '/pay');
    await hydrate();
    showSuccessToast('演示支付完成');
    router.push('/orders/' + created.value.id);
  } catch (e) {
    if (e instanceof Error) fail(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <template v-if="!created"
    ><van-form @submit="submit"
      ><div class="purchase-types">
        <button
          v-for="t in ['NEW', 'FIRST', 'REPEAT']"
          :key="t"
          type="button"
          :class="{ selected: form.type === t }"
          @click="
            form.type = t;
            form.rewardAmount = '0';
            quote = null;
          "
        >
          <b>{{ label(t) }}</b
          ><small>{{
            t === 'NEW' ? '注册后激活' : t === 'FIRST' ? '购买 / 补差' : '等级价'
          }}</small>
        </button>
      </div>
      <section class="mobile-card order-form">
        <h3>购买信息</h3>
        <label
          >购买会员点位<select v-model="form.pointId" @change="quote = null">
            <option v-for="p in buyers" :key="p.id" :value="p.id">
              {{ p.nickname }} · {{ p.id }}
            </option>
          </select></label
        ><label
          >代办店铺<select v-model="form.shopId" @change="quote = null">
            <option v-for="s in state.catalog.shops" :key="s.id" :value="s.id">
              {{ s.nickname }} · {{ s.id }}
            </option>
          </select></label
        ><label
          >商品<select v-model="form.productId" @change="quote = null">
            <option v-for="p in state.catalog.products" :key="p.id" :value="p.id">
              {{ p.name }}
            </option>
          </select></label
        >
        <div class="form-inline">
          <span>购买数量 <small>瓶</small></span
          ><van-stepper v-model="form.quantity" :min="1" :max="10000" @change="quote = null" />
        </div>
        <div class="form-inline">
          <span>自提取货</span><van-switch v-model="form.pickup" size="21" @change="quote = null" />
        </div>
      </section>
      <section class="mobile-card order-form">
        <h3>支付方式</h3>
        <p class="muted">
          积分付款账户 {{ form.payerId }} · 可用 ¥{{ money(availableBalance(point.reward)) }}
        </p>
        <van-field
          v-model="form.rewardAmount"
          :disabled="form.type === 'REPEAT'"
          label="奖励积分"
          inputmode="decimal"
          :placeholder="form.type === 'REPEAT' ? '复购不可使用积分' : '首购 / 新人最高70%'"
          @update:model-value="quote = null"
        /><label
          >余款支付<select v-model="form.paymentMethod" @change="quote = null">
            <option value="DEMO">演示现金支付</option>
            <option
              v-if="state.points.some((p) => p.id === form.shopId && p.shopType !== 'NONE')"
              value="STORE"
            >
              店铺充值积分
            </option>
          </select></label
        >
      </section>
      <section v-if="quote" class="mobile-card quote-card">
        <h3>本次报价</h3>
        <dl>
          <dt>商品金额</dt>
          <dd>¥{{ money(quote.total) }}</dd>
          <dt>奖励积分抵扣</dt>
          <dd>¥{{ money(quote.rewardAmount) }}</dd>
          <dt>{{ quote.paymentMethod === 'STORE' ? '充值积分支付' : '演示现金支付' }}</dt>
          <dd>¥{{ money(quote.cashAmount) }}</dd>
          <template v-if="form.type === 'REPEAT'"
            ><dt>本月下单前剩余额度</dt>
            <dd>{{ quote.remainingQuota }} 瓶</dd></template
          >
        </dl>
      </section>
      <div class="order-actions">
        <van-button
          block
          round
          plain
          type="primary"
          :loading="busy"
          native-type="button"
          @click="calculate"
          >计算报价</van-button
        ><van-button block round type="primary" :loading="busy" native-type="submit"
          >确认创建订单</van-button
        >
      </div></van-form
    ></template
  >
  <section v-else class="mobile-card created-card">
    <div class="success-symbol">✓</div>
    <h2>订单已创建</h2>
    <p>{{ created.id }}</p>
    <p>
      应付总额 <strong>¥{{ money(created.total) }}</strong>
    </p>
    <van-button block round type="primary" :loading="busy" @click="pay">确认演示支付</van-button
    ><van-button block round plain @click="router.push('/orders/' + created.id)"
      >查看订单</van-button
    >
  </section>
</template>
