<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showSuccessToast } from 'vant';
import { api, state, hydrate, selectedPoint, fail } from './state';
import { money, grade, label, availableBalance } from '@chuyu/shared';
import ProductArt from './components/ProductArt.vue';
import OrderView from './views/OrderView.vue';
import WalletView from './views/WalletView.vue';
import WorkbenchView from './views/WorkbenchView.vue';
import OrdersView from './views/OrdersView.vue';
import RegisterView from './views/RegisterView.vue';
const route = useRoute(),
  router = useRouter(),
  page = computed(() => String(route.params.page || 'home')),
  point = computed(selectedPoint);
const username = ref('member'),
  password = ref(''),
  busy = ref(false),
  error = ref(''),
  showPoints = ref(false),
  oldPassword = ref(''),
  newPassword = ref(''),
  newPayment = ref('');
const isShop = computed(() => state.points.some((p) => p.shopType !== 'NONE' && p.active));
const titles: Record<string, string> = {
  home: '初语山言',
  order: '确认下单',
  workbench: '店铺工作台',
  my: '我的',
  wallet: '奖励积分',
  orders: route.params.id ? '订单详情' : '我的订单',
  register: '新增会员',
  password: '账户设置',
};
async function login(demo?: string) {
  busy.value = true;
  error.value = '';
  try {
    state.user = await api.login(demo || username.value, demo ? 'ChuyuDemo!2026' : password.value);
    if (state.user.role !== 'MEMBER') {
      api.token = '';
      state.user = null;
      throw new Error('请使用会员或店铺账号登录');
    }
    if (!state.user.mustChangePassword) await hydrate();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function changePassword() {
  busy.value = true;
  try {
    await api.post('/auth/password', {
      oldPassword: oldPassword.value,
      newPassword: newPassword.value,
      newPaymentPassword: newPayment.value,
    });
    await hydrate();
    showSuccessToast('密码已更新');
    router.push('/my');
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
function choose(p: any) {
  state.pointId = p.id;
  sessionStorage.setItem('chuyu.point', p.id);
  showPoints.value = false;
}
async function logout() {
  try {
    await api.post('/auth/logout');
  } catch {}
  api.token = '';
  state.user = null;
}
onMounted(async () => {
  if (api.token)
    try {
      await hydrate();
    } catch (e) {
      state.user = null;
      fail(e);
    }
});
</script>
<template>
  <main v-if="!state.user" class="mobile-login">
    <div class="login-brand">
      山<span>初语山言<small>CHUYU SHANYAN</small></span>
    </div>
    <div class="login-illustration"><ProductArt /></div>
    <h1>会员登录</h1>
    <van-form @submit="login()"
      ><van-cell-group inset
        ><van-field
          v-model="username"
          name="username"
          label="账号"
          placeholder="请输入账号"
          autocomplete="username" /><van-field
          v-model="password"
          type="password"
          name="password"
          label="密码"
          placeholder="请输入登录密码"
          autocomplete="current-password"
      /></van-cell-group>
      <p v-if="error" class="form-error">{{ error }}</p>
      <van-button
        block
        round
        type="primary"
        native-type="submit"
        :loading="busy"
        class="login-submit"
        >登录会员账户</van-button
      ></van-form
    >
  </main>
  <main v-else-if="state.user.mustChangePassword" class="mobile-password">
    <h2>设置新的登录密码</h2>
    <p class="muted">首次登录请更新密码后继续。</p>
    <van-field v-model="oldPassword" type="password" label="原密码" /><van-field
      v-model="newPassword"
      type="password"
      label="新密码"
      placeholder="至少10位"
    /><van-field
      v-model="newPayment"
      type="password"
      label="支付密码"
      placeholder="新的6位数字（可选）"
    /><van-button block round type="primary" :loading="busy" @click="changePassword"
      >保存并继续</van-button
    >
  </main>
  <div v-else class="mobile-app">
    <header class="mobile-header">
      <button
        v-if="!['home', 'my', 'workbench', 'order'].includes(page) || route.params.id"
        class="back-button"
        aria-label="返回"
        @click="router.back()"
      >
        ‹
      </button>
      <div v-else class="small-brand">山</div>
      <strong
        >{{ page === 'orders' && route.params.id ? '订单详情' : titles[page] || '初语山言'
        }}<small class="env-mini">演示</small></strong
      ><button class="point-button" @click="showPoints = true">
        {{ point.isMain ? '主号' : '小号' + point.sequenceNo }} ⌄
      </button>
    </header>
    <div class="point-context">
      <span>{{ point.nickname }}</span
      ><span>{{ point.id }} · {{ grade(point.grade) }}</span>
    </div>
    <main class="mobile-content">
      <template v-if="page === 'home'"
        ><section class="home-shortcuts">
          <button @click="router.push('/orders')"><span>▤</span>我的订单</button
          ><button @click="router.push('/wallet')"><span>◈</span>奖励积分</button
          ><button @click="router.push('/register')"><span>♧</span>新增会员</button
          ><button v-if="isShop" @click="router.push('/workbench')"><span>⌂</span>店铺工作台</button
          ><button v-else @click="router.push('/my')"><span>▦</span>我的账户</button>
        </section>
        <div class="mobile-section-heading"><h2>商品</h2></div>
        <article v-for="product in state.catalog.products" :key="product.id" class="product-card">
          <div class="product-picture"><ProductArt /></div>
          <div class="product-copy">
            <h3>{{ product.name }}</h3>
            <p>150mL / 瓶 · {{ product.weightKg }}kg</p>
            <strong>¥{{ money(product.firstPrice) }}</strong>
            <div>
              <button @click="router.push('/order?product=' + product.id)">购买</button
              ><button
                v-if="Number(point.grade) >= 1"
                class="subtle-button"
                @click="router.push('/order?type=REPEAT')"
              >
                会员复购
              </button>
            </div>
          </div>
        </article></template
      >
      <OrderView
        v-else-if="page === 'order'"
        :key="state.pointId + String(route.query.type || '')"
      />
      <WalletView v-else-if="page === 'wallet'" :key="state.pointId" />
      <WorkbenchView v-else-if="page === 'workbench' && isShop" />
      <OrdersView
        v-else-if="page === 'orders'"
        :key="state.pointId + String(route.params.id || '')"
        :order-id="String(route.params.id || '')"
      />
      <RegisterView v-else-if="page === 'register'" />
      <template v-else-if="page === 'password'"
        ><div class="mobile-card">
          <h2>更新密码</h2>
          <van-field v-model="oldPassword" type="password" label="原密码" /><van-field
            v-model="newPassword"
            type="password"
            label="新密码"
            placeholder="至少10位"
          /><van-field
            v-model="newPayment"
            type="password"
            label="支付密码"
            placeholder="6位数字（可选）"
          /><van-button block round type="primary" :loading="busy" @click="changePassword"
            >保存</van-button
          >
        </div></template
      >
      <template v-else-if="page === 'my'"
        ><section class="profile-card">
          <span class="profile-avatar">{{ point.nickname?.slice(0, 1) }}</span>
          <div>
            <h2>{{ point.nickname }}</h2>
            <p>
              {{ grade(point.grade) }}
              <span v-if="point.directorGrade">· {{ point.directorGrade }}星董事</span>
            </p>
            <small>{{ point.id }} · {{ label(point.shopType) }}</small>
          </div>
          <button @click="showPoints = true">切换账户 ›</button>
        </section>
        <section class="account-summary">
          <button @click="router.push('/wallet')">
            <span>可用奖励积分</span><strong>{{ money(availableBalance(point.reward)) }}</strong
            ><small>冻结 {{ money(point.reward?.frozen) }}</small>
          </button>
          <div>
            <span>充值积分</span><strong>{{ money(point.recharge?.balance) }}</strong
            ><small>受限 {{ money(point.recharge?.restricted) }}</small>
          </div>
        </section>
        <section class="performance-card mobile-card">
          <h3>当前点位业绩</h3>
          <div>
            <span
              >个人业绩<b>¥{{ money(point.own) }}</b></span
            ><span
              >团队业绩<b>¥{{ money(point.team) }}</b></span
            ><span
              >小市场业绩<b>¥{{ money(point.small) }}</b></span
            >
          </div>
        </section>
        <van-cell-group inset
          ><van-cell title="我的订单" is-link @click="router.push('/orders')" /><van-cell
            title="我的主号 / 小号"
            :value="state.points.length + '个点位'"
            is-link
            @click="showPoints = true" /><van-cell
            title="奖励积分"
            label="转账 / 提现 / 转换"
            is-link
            @click="router.push('/wallet')" /><van-cell
            v-if="isShop"
            title="店铺工作台"
            is-link
            @click="router.push('/workbench')" /><van-cell
            title="新增会员 / 小号"
            is-link
            @click="router.push('/register')" /><van-cell
            title="账户与支付密码"
            is-link
            @click="router.push('/password')" /><van-cell
            title="退出登录"
            @click="logout" /></van-cell-group
      ></template>
      <van-empty v-else description="当前账户暂无此功能" />
    </main>
    <van-tabbar
      v-if="['home', 'order', 'workbench', 'my'].includes(page)"
      route
      fixed
      placeholder
      safe-area-inset-bottom
      ><van-tabbar-item to="/home" icon="wap-home-o" :class="{ chosen: page === 'home' }"
        >首页</van-tabbar-item
      ><van-tabbar-item to="/order" icon="shopping-cart-o" :class="{ chosen: page === 'order' }"
        >下单</van-tabbar-item
      ><van-tabbar-item
        v-if="isShop"
        to="/workbench"
        icon="shop-o"
        :class="{ chosen: page === 'workbench' }"
        >工作台</van-tabbar-item
      ><van-tabbar-item to="/my" icon="user-o" :class="{ chosen: page === 'my' }"
        >我的</van-tabbar-item
      ></van-tabbar
    >
    <van-popup v-model:show="showPoints" position="bottom" round closeable class="point-popup"
      ><h3>切换当前账户</h3>
      <p class="muted">余额、订单与业绩会随账户切换</p>
      <button
        v-for="p in state.points"
        :key="p.id"
        :class="{ selected: p.id === state.pointId }"
        @click="choose(p)"
      >
        <div>
          <b>{{ p.nickname }}</b
          ><small>{{ p.id }} · {{ grade(p.grade) }}</small>
        </div>
        <span>{{ p.id === state.pointId ? '✓' : p.activated ? '已激活' : '待激活' }}</span>
      </button></van-popup
    >
  </div>
</template>
