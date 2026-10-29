<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showConfirmDialog, showSuccessToast } from 'vant';
import { api, state, selectedPoint, hydrate, fail } from '../state';
import {
  money,
  grade,
  label,
  availableBalance,
  query,
  cents,
  fromCents,
  type Row,
} from '@chuyu/shared';
const route = useRoute(),
  router = useRouter(),
  point = selectedPoint();
const buyers = computed(() => [
  ...state.points,
  ...state.assisted.filter((p) => !state.points.some((n) => n.id === p.id)),
  ...extraBuyers.value.filter(
    (p) => ![...state.points, ...state.assisted].some((n) => n.id === p.id),
  ),
]);
const extraBuyers = ref<Row[]>([]),
  buyerSearch = ref(''),
  buyerResults = ref<Row[]>([]);
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
  remark: '',
});
const quote = ref<Row | null>(null),
  busy = ref(false),
  created = ref<Row | null>(null);
const orderMessages = computed(() =>
  state.content.announcements
    .filter(
      (r: Row) =>
        (r.type === 'ALL' || r.type === form.value.type) &&
        (!r.channel || r.channel === product.value.channel),
    )
    .map((r: Row) => r.content)
    .join('　'),
);
const payer = computed(() => state.points.find((p) => p.id === form.value.payerId) || {}),
  product = computed(
    () => state.catalog.products.find((p: Row) => p.id === form.value.productId) || {},
  ),
  buyer = computed(() => buyers.value.find((p) => p.id === form.value.pointId) || {});
const storeOwned = computed(() =>
  state.points.some((p) => p.id === form.value.shopId && p.shopType !== 'NONE' && p.active),
);
const estimate = computed(() =>
  fromCents(
    cents(
      form.value.type === 'REPEAT'
        ? Number(buyer.value.grade) === 1
          ? product.value.repeatOne
          : product.value.repeatOther
        : product.value.firstPrice,
    ) * BigInt(Math.max(0, Number(form.value.quantity) || 0)),
  ),
);
const useRatio = computed(() =>
  cents(estimate.value) > 0n
    ? Number((cents(form.value.rewardAmount) * 10000n) / cents(estimate.value)) / 100
    : 0,
);
function usePercent(value: number | number[]) {
  const pct = Math.min(70, Math.max(0, Math.round(Array.isArray(value) ? value[0] : value)));
  form.value.rewardAmount = fromCents((cents(estimate.value) * BigInt(pct)) / 100n);
  quote.value = null;
}
async function findBuyer() {
  try {
    buyerResults.value = await api.get('/members/lookup?' + query({ account: buyerSearch.value }));
    if (!buyerResults.value.length) throw new Error('未找到有效会员');
  } catch (e) {
    fail(e);
  }
}
function chooseBuyer(p: Row) {
  extraBuyers.value = [...extraBuyers.value.filter((x) => x.id !== p.id), p];
  form.value.pointId = p.id;
  if (!p.activated) form.value.type = 'NEW';
  buyerResults.value = [];
  quote.value = null;
}
watch(
  () => form.value.shopId,
  () => {
    if (!storeOwned.value) form.value.paymentMethod = 'DEMO';
    quote.value = null;
  },
);
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
        ' ' +
        q.buyerName +
        '\n推荐人 ' +
        (q.parentName || '无') +
        ' ' +
        (q.parentId || '') +
        '\n店铺 ' +
        q.shopName +
        ' ' +
        q.shopId +
        '\n商品 ' +
        q.productName +
        '\n' +
        label(q.type) +
        ' ' +
        q.quantity +
        '瓶\n奖励积分 ¥' +
        money(q.rewardAmount) +
        '\n积分付款账户 ' +
        q.payerId +
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
  <van-notice-bar v-if="orderMessages" :text="orderMessages" />
  <template v-if="!created"
    ><van-form @submit="submit"
      ><div class="purchase-types">
        <button
          v-for="t in ['NEW', 'FIRST', 'REPEAT']"
          :key="t"
          type="button"
          :class="{ selected: form.type === t }"
          :disabled="
            !String(product.purchaseTypes || 'NEW,FIRST,REPEAT')
              .split(',')
              .includes(t)
          "
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
        <template v-if="storeOwned"
          ><van-field v-model="buyerSearch" label="查找会员" placeholder="完整点位号或手机号"
            ><template #button
              ><van-button size="small" native-type="button" @click="findBuyer"
                >查找</van-button
              ></template
            ></van-field
          >
          <div class="recipient-options">
            <button v-for="p in buyerResults" :key="p.id" type="button" @click="chooseBuyer(p)">
              <b>{{ p.nickname }}</b
              ><span>{{ p.id }} · {{ p.activated ? '已激活' : '待激活' }}</span>
            </button>
          </div></template
        >
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
        <van-field
          v-model="form.remark"
          label="备注"
          type="textarea"
          maxlength="1000"
          rows="2"
          placeholder="选填"
        />
      </section>
      <section class="mobile-card order-form">
        <h3>支付方式</h3>
        <label
          >积分付款账户<select v-model="form.payerId" @change="quote = null">
            <option v-for="p in state.points" :key="p.id" :value="p.id">
              {{ p.nickname }} · {{ p.id }}
            </option>
          </select></label
        >
        <p class="muted">可用奖励积分 ¥{{ money(availableBalance(payer.reward)) }}</p>
        <van-field
          v-model="form.rewardAmount"
          :disabled="form.type === 'REPEAT'"
          label="奖励积分"
          inputmode="decimal"
          :placeholder="form.type === 'REPEAT' ? '复购不可使用积分' : '首购 / 新人最高70%'"
          @update:model-value="quote = null"
        />
        <div v-if="form.type !== 'REPEAT'" class="reward-ratio">
          <span>使用比例 {{ useRatio.toFixed(0) }}%</span
          ><van-slider
            :model-value="useRatio"
            :min="0"
            :max="70"
            :step="1"
            aria-label="奖励积分抵扣比例"
            @update:model-value="usePercent"
          />
          <div>
            <button v-for="pct in [0, 30, 70]" :key="pct" type="button" @click="usePercent(pct)">
              {{ pct }}%
            </button>
          </div>
        </div>
        <label
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
          <dt>购买账户</dt>
          <dd>{{ quote.buyerName }} · {{ quote.pointId }}</dd>
          <dt>推荐人</dt>
          <dd>{{ quote.parentName || '无' }} {{ quote.parentId }}</dd>
          <dt>代办店铺</dt>
          <dd>{{ quote.shopName }} · {{ quote.shopId }}</dd>
          <dt>商品</dt>
          <dd>{{ quote.productName }} × {{ quote.quantity }}</dd>
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
<style scoped>
.reward-ratio {
  padding: 12px 0 6px;
}
.reward-ratio > span {
  display: block;
  font-size: 12px;
  margin-bottom: 20px;
  color: #6d8067;
}
.reward-ratio > div {
  display: flex;
  justify-content: space-between;
  margin: 14px 0 4px;
}
.reward-ratio button {
  border: 1px solid #dce4d4;
  border-radius: 6px;
  padding: 5px 12px;
  background: #f3f6ed;
  color: #5b7953;
}
.purchase-types button:disabled {
  opacity: 0.4;
}
.quote-card dd {
  overflow-wrap: anywhere;
  max-width: 220px;
}
</style>
