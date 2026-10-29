<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { showConfirmDialog, showSuccessToast, showDialog } from 'vant';
import { api, state, selectedPoint, hydrate, fail } from '../state';
import { money, percent, query, dateTime, availableBalance, type Row } from '@chuyu/shared';
const point = computed(selectedPoint),
  rows = ref<Row[]>([]),
  total = ref(0),
  page = ref(1),
  direction = ref(''),
  loading = ref(false),
  error = ref('');
const action = ref(''),
  show = ref(false),
  busy = ref(false),
  amount = ref(''),
  paymentPassword = ref(''),
  account = ref(''),
  bank = ref(''),
  recipients = ref<Row[]>([]),
  recipient = ref<Row | null>(null);
const available = computed(() => availableBalance(point.value.reward));
const names: Record<string, string> = {
  transfer: '转账奖励积分',
  withdraw: '申请提现',
  convert: '转换充值积分',
};
async function load(reset = true) {
  loading.value = true;
  error.value = '';
  if (reset) page.value = 1;
  try {
    const result = await api.get(
      '/lists/ledger?' +
        query({
          pointId: state.pointId,
          kind: 'REWARD',
          direction: direction.value,
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
async function open(kind: string) {
  try {
    state.config = await api.get('/config');
    if (kind === 'transfer' && !state.config.transferEnabled) {
      await showDialog({ message: '转账暂未开放。' });
      return;
    }
    action.value = kind;
    amount.value = '';
    paymentPassword.value = '';
    account.value = '';
    bank.value = '';
    recipient.value = null;
    recipients.value = [];
    show.value = true;
  } catch (e) {
    fail(e);
  }
}
async function search() {
  try {
    recipients.value = await api.get('/members/lookup?' + query({ account: account.value }));
    recipient.value = null;
    if (!recipients.value.length)
      await showDialog({ message: '没有找到有效收款点位，请核对账户编号或手机号。' });
  } catch (e) {
    fail(e);
  }
}
async function submit() {
  busy.value = true;
  try {
    if (action.value === 'transfer') {
      if (!recipient.value) throw new Error('请先查找并选择收款点位');
      await showConfirmDialog({
        title: '确认收款点位',
        message:
          recipient.value.nickname +
          '\n' +
          recipient.value.id +
          '\n' +
          recipient.value.phone +
          '\n转账 ¥' +
          money(amount.value) +
          '，不另收手续费。',
        confirmButtonText: '确认转账',
      });
      await api.post('/transfers', {
        fromId: state.pointId,
        toId: recipient.value.id,
        amount: amount.value,
        paymentPassword: paymentPassword.value,
      });
    } else if (action.value === 'convert') {
      await showConfirmDialog({
        title: '确认积分转换',
        message: '奖励积分 ¥' + money(amount.value) + ' 将扣除并转换为当前点位的充值积分。',
      });
      await api.post('/conversions', {
        pointId: state.pointId,
        amount: amount.value,
        paymentPassword: paymentPassword.value,
      });
    } else {
      const result = await api.post('/withdrawals', {
        pointId: state.pointId,
        amount: amount.value,
        bankAccount: bank.value,
        paymentPassword: paymentPassword.value,
      });
      await showDialog({
        title: '提现申请已提交',
        message:
          '申请 ¥' +
          money(result.gross) +
          '\n手续费 ¥' +
          money(result.fee) +
          '\n演示税额 ¥' +
          money(result.tax) +
          '\n预计到账 ¥' +
          money(result.net) +
          '\n待财务审核（演示）。',
      });
    }
    show.value = false;
    await hydrate();
    await load();
    showSuccessToast(action.value === 'withdraw' ? '已冻结申请额度' : '操作已完成');
  } catch (e) {
    if (e instanceof Error) fail(e);
  } finally {
    busy.value = false;
  }
}
onMounted(() => load());
</script>
<template>
  <section class="wallet-hero">
    <span>可用奖励积分</span><strong>{{ money(available) }}</strong>
    <div>
      <span>余额 ¥{{ money(point.reward?.balance) }}</span
      ><span>冻结 ¥{{ money(point.reward?.frozen) }}</span>
    </div>
    <p>{{ point.nickname }} · {{ point.id }}</p>
  </section>
  <section class="wallet-actions">
    <button
      :disabled="!state.config.transferEnabled || Number(available) < 1"
      @click="open('transfer')"
    >
      <span>↗</span>转账</button
    ><button :disabled="Number(available) <= 0" @click="open('withdraw')"><span>▱</span>提现</button
    ><button :disabled="Number(available) <= 0" @click="open('convert')"><span>⇄</span>转换</button>
  </section>
  <p v-if="!state.config.transferEnabled" class="inline-note wallet-note">转账暂未开放。</p>
  <p v-if="Number(available) < 0" class="inline-note wallet-note">
    撤销奖金已扣回，后续奖励先抵扣欠额。
  </p>
  <div class="mobile-section-heading">
    <h2>积分流水</h2>
    <button @click="load()">刷新</button>
  </div>
  <van-tabs v-model:active="direction" @change="load()"
    ><van-tab title="全部" name="" /><van-tab title="收入" name="IN" /><van-tab
      title="支出"
      name="OUT"
  /></van-tabs>
  <van-notice-bar v-if="error" :text="error" color="#a25247" background="#fff2eb" />
  <section class="ledger-list">
    <article v-for="entry in rows" :key="entry.id">
      <div>
        <h4>{{ entry.description }}</h4>
        <p>{{ dateTime(entry.createdAt) }}</p>
        <small v-if="entry.originId">来源 {{ entry.originId }}</small>
      </div>
      <div>
        <strong :class="{ positive: Number(entry.delta) > 0, negative: Number(entry.delta) < 0 }"
          >{{ Number(entry.delta) > 0 ? '+' : '' }}{{ money(entry.delta) }}</strong
        >
        <p>余额 {{ money(entry.balanceAfter) }}</p>
        <small v-if="Number(entry.frozenAfter)">冻结 {{ money(entry.frozenAfter) }}</small>
      </div>
    </article>
  </section>
  <van-loading v-if="loading" class="center-loading" size="22">加载中</van-loading
  ><van-empty
    v-if="!loading && !rows.length && !error"
    description="当前账户暂无此类流水"
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
  <van-popup v-model:show="show" position="bottom" round closeable class="wallet-popup"
    ><h2>{{ names[action] }}</h2>
    <p class="muted">当前点位 {{ state.pointId }} · 可用 {{ money(available) }}</p>
    <van-form @submit="submit"
      ><van-field v-model="amount" label="金额" inputmode="decimal" placeholder="请输入金额" />
      <template v-if="action === 'transfer'"
        ><van-field v-model="account" label="转入账户" placeholder="完整点位号或手机号"
          ><template #button
            ><van-button size="small" type="primary" native-type="button" @click="search"
              >查找</van-button
            ></template
          ></van-field
        >
        <div class="recipient-options">
          <button
            v-for="p in recipients"
            :key="p.id"
            type="button"
            :class="{ selected: recipient?.id === p.id }"
            @click="recipient = p"
          >
            <b>{{ p.nickname }}</b
            ><span>{{ p.id }} · {{ p.phone }}</span
            ><i>{{ recipient?.id === p.id ? '✓' : '' }}</i>
          </button>
        </div></template
      >
      <van-field
        v-if="action === 'withdraw'"
        v-model="bank"
        label="银行卡号"
        inputmode="numeric"
        placeholder="请输入银行卡号"
      />
      <van-field
        v-model="paymentPassword"
        label="支付密码"
        type="password"
        maxlength="6"
        inputmode="numeric"
        placeholder="6位支付密码"
      />
      <p class="inline-note">
        {{
          action === 'withdraw'
            ? '提现手续费 ' +
              percent(state.config.rules?.withdrawalFee) +
              '，演示税率 ' +
              percent(state.config.rules?.tax) +
              '，提交后冻结申请金额。'
            : action === 'transfer'
              ? '最少1元，免手续费。'
              : '奖励积分等额转为充值积分。'
        }}
      </p>
      <van-button block round type="primary" native-type="submit" :loading="busy">{{
        action === 'withdraw' ? '提交提现申请' : '确认操作'
      }}</van-button></van-form
    ></van-popup
  >
</template>
