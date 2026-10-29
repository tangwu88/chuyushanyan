<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { showConfirmDialog, showSuccessToast, showDialog } from 'vant';
import { useRoute } from 'vue-router';
import { api, state, selectedPoint, hydrate, fail } from '../state';
import {
  money,
  businessText,
  percent,
  query,
  dateTime,
  availableBalance,
  label,
  cents,
  fromCents,
  proportionalMoney,
  type Row,
} from '@chuyu/shared';
const route = useRoute(),
  walletKind = ref(route.query.kind === 'RECHARGE' ? 'RECHARGE' : 'REWARD'),
  view = ref('ledger'),
  start = ref(''),
  end = ref(''),
  trace = ref<Row | null>(null);
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
  recipient = ref<Row | null>(null),
  bankName = ref(''),
  accountName = ref(''),
  identityNumber = ref('');
const wallet = computed(() =>
  walletKind.value === 'REWARD' ? point.value.reward : point.value.recharge,
);
const available = computed(() => availableBalance(wallet.value));
const fee = computed(() => proportionalMoney(amount.value, state.config.rules?.withdrawalFee)),
  tax = computed(() => proportionalMoney(amount.value, state.config.rules?.tax)),
  net = computed(() => fromCents(cents(amount.value) - cents(fee.value) - cents(tax.value)));
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
      '/lists/' +
        (view.value === 'withdrawals' ? 'withdrawals' : 'ledger') +
        '?' +
        query({
          pointId: state.pointId,
          kind: walletKind.value,
          direction: direction.value,
          page: page.value,
          size: 20,
          start: start.value,
          end: end.value,
          includeArchived: true,
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
    bankName.value = '';
    accountName.value = '';
    identityNumber.value = '';
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
      await showConfirmDialog({
        title: '确认提现申请',
        message:
          '申请 ¥' +
          money(amount.value) +
          '\n手续费 ¥' +
          money(fee.value) +
          '\n税额 ¥' +
          money(tax.value) +
          '\n预计到账 ¥' +
          money(net.value) +
          '\n提交后冻结申请金额。',
        confirmButtonText: '确认提交',
      });
      const result = await api.post('/withdrawals', {
        pointId: state.pointId,
        amount: amount.value,
        bankAccount: bank.value,
        paymentPassword: paymentPassword.value,
        bankName: bankName.value,
        accountName: accountName.value,
        identityNumber: identityNumber.value,
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
    if (action.value === 'withdraw') view.value = 'withdrawals';
    await hydrate();
    await load();
    showSuccessToast(action.value === 'withdraw' ? '已冻结申请额度' : '操作已完成');
  } catch (e) {
    if (e instanceof Error) fail(e);
  } finally {
    busy.value = false;
  }
}
async function refresh() {
  try {
    await hydrate();
    await load();
  } catch (e) {
    fail(e);
  }
}
async function showTrace(id: string) {
  try {
    trace.value = await api.get('/funds/trace/' + id);
  } catch (e) {
    fail(e);
  }
}
function changeWallet() {
  view.value = 'ledger';
  load();
}
onMounted(() => load());
</script>
<template>
  <van-tabs v-model:active="walletKind" @change="changeWallet"
    ><van-tab title="奖励积分" name="REWARD" /><van-tab title="充值积分" name="RECHARGE"
  /></van-tabs>
  <section class="wallet-hero">
    <span>可用{{ label(walletKind) }}</span
    ><strong>{{ money(available) }}</strong>
    <div>
      <span>余额 ¥{{ money(wallet?.balance) }}</span
      ><span>冻结 ¥{{ money(wallet?.frozen) }}</span
      ><span v-if="walletKind === 'RECHARGE'">受限 ¥{{ money(wallet?.restricted) }}</span>
    </div>
    <p>{{ point.nickname }} · {{ point.id }}</p>
  </section>
  <section v-if="walletKind === 'REWARD'" class="wallet-actions">
    <button
      :disabled="!state.config.transferEnabled || Number(available) < 1"
      @click="open('transfer')"
    >
      <span>↗</span>转账</button
    ><button :disabled="Number(available) <= 0" @click="open('withdraw')"><span>▱</span>提现</button
    ><button :disabled="Number(available) <= 0" @click="open('convert')"><span>⇄</span>转换</button>
  </section>
  <p
    v-if="walletKind === 'REWARD' && !state.config.transferEnabled"
    class="inline-note wallet-note"
  >
    转账暂未开放。
  </p>
  <p v-if="walletKind === 'REWARD' && Number(available) < 0" class="inline-note wallet-note">
    撤销奖金已扣回，后续奖励先抵扣欠额。
  </p>
  <div class="mobile-section-heading">
    <h2>{{ view === 'withdrawals' ? '提现记录' : '积分流水' }}</h2>
    <button @click="refresh">刷新</button>
  </div>
  <van-tabs v-if="walletKind === 'REWARD'" v-model:active="view" @change="load()"
    ><van-tab name="ledger" title="积分流水" /><van-tab name="withdrawals" title="提现记录"
  /></van-tabs>
  <details class="mobile-card compact-filter">
    <summary>日期筛选</summary>
    <div>
      <label>开始日期<input v-model="start" type="date" /></label
      ><label>结束日期<input v-model="end" type="date" /></label
      ><button @click="load()">查询</button>
    </div>
  </details>
  <van-tabs v-if="view === 'ledger'" v-model:active="direction" @change="load()"
    ><van-tab title="全部" name="" /><van-tab title="收入" name="IN" /><van-tab
      title="支出"
      name="OUT"
  /></van-tabs>
  <van-notice-bar v-if="error" :text="error" color="#a25247" background="#fff2eb" />
  <section v-if="view === 'ledger'" class="ledger-list">
    <article v-for="entry in rows" :key="entry.id">
      <div>
        <h4>{{ businessText(entry.description) }}</h4>
        <p>{{ dateTime(entry.createdAt) }}</p>
        <button v-if="entry.originId" class="trace-link" @click="showTrace(entry.originId)">
          查看关联业务 ›
        </button>
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
  <section v-else class="store-order-list">
    <article v-for="item in rows" :key="item.id" class="mobile-card">
      <div class="order-card-head">
        <b>提现 ¥{{ money(item.gross) }}</b
        ><van-tag :type="item.status === 'REJECTED' ? 'danger' : 'primary'" plain>{{
          label(item.status)
        }}</van-tag>
      </div>
      <p>{{ dateTime(item.createdAt) }}</p>
      <p>{{ item.bankMask }}</p>
      <p>手续费 ¥{{ money(item.fee) }} · 税额 ¥{{ money(item.tax) }}</p>
      <p>预计到账 ¥{{ money(item.net) }}</p>
      <p v-if="item.reason">审批说明：{{ item.reason }}</p>
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
      <template v-if="action === 'withdraw'"
        ><van-field v-model="bankName" label="开户行" /><van-field
          v-model="accountName"
          label="开户姓名"
        />
        <van-field v-model="identityNumber" label="证件号码" placeholder="选填" />
        <p class="inline-note">
          手续费 ¥{{ money(fee) }} · 税额 ¥{{ money(tax) }} · 预计到账 ¥{{ money(net) }}
        </p></template
      >
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
  <van-popup
    :show="!!trace"
    position="bottom"
    round
    closeable
    class="wallet-popup"
    @close="trace = null"
    ><h2>关联业务</h2>
    <p class="muted">{{ trace?.id }}</p>
    <article v-for="entry in trace?.entries || []" :key="entry.id" class="mobile-card">
      <b>{{ businessText(entry.description) }}</b>
      <p>{{ label(entry.businessKind) }} · {{ dateTime(entry.createdAt) }}</p>
      <strong>{{ money(entry.delta) }}</strong>
      <p>余额 ¥{{ money(entry.balanceAfter) }}</p>
    </article>
    <article v-for="award in trace?.awards || []" :key="award.id" class="mobile-card">
      <b>{{ label(award.awardType) }}</b>
      <p>毛奖 ¥{{ money(award.gross) }} · 管理费 ¥{{ money(award.fee) }}</p>
      <strong>实得 ¥{{ money(award.net) }}</strong>
      <p>{{ award.explanation }}</p>
      <van-tag v-if="award.reversed" type="danger">已冲回</van-tag>
    </article></van-popup
  >
</template>
<style scoped>
.trace-link {
  border: 0;
  background: none;
  padding: 5px 0;
  color: #426955;
  font-size: 12px;
}
.compact-filter {
  padding: 12px 16px;
  margin: 12px 18px;
}
.compact-filter summary {
  font-size: 13px;
  color: #657667;
}
.compact-filter > div {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 12px;
}
.compact-filter label {
  font-size: 12px;
}
.compact-filter input {
  display: block;
  max-width: 145px;
  border: 1px solid #dce2d7;
  padding: 8px;
  border-radius: 6px;
}
.compact-filter button {
  border: 0;
  background: #e3ebde;
  border-radius: 6px;
  padding: 8px 16px;
  align-self: end;
}
</style>
