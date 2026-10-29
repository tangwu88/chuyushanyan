<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { api, state, hydrate, can } from './state';
import { label, type Row } from '@chuyu/shared';
import DashboardView from './views/DashboardView.vue';
import DataView from './views/DataView.vue';
import RulesView from './views/RulesView.vue';
const route = useRoute(),
  router = useRouter();
const page = computed(() => String(route.params.page || 'dashboard'));
const nav = [
  { id: 'dashboard', name: '工作台', mark: '◈', permission: 'reports:read' },
  { id: 'members', name: '会员与关系', mark: '♧', permission: 'members:read' },
  { id: 'orders', name: '交易与履约', mark: '▤', permission: 'orders:read' },
  { id: 'finance', name: '资金与结算', mark: '▥', permission: 'funds:read' },
  { id: 'rules', name: '规则中心', mark: '⌘', permission: 'rules:read' },
  { id: 'system', name: '系统设置', mark: '⚙', permission: 'audit:read' },
];
const visibleNav = computed(() => {
  const configured: Row[] = state.config.menus || [];
  if (!configured.length)
    return nav
      .filter((n) => can(n.permission) || (n.id === 'system' && can('system:write')))
      .map((n) => ({ ...n, route: '/' + n.id, children: [] as Row[] }));
  return configured
    .filter((m) => !m.parentId && m.type !== 'BUTTON' && m.active)
    .map((m) => {
      const children = configured.filter(
        (c) =>
          c.parentId === m.id &&
          c.type !== 'BUTTON' &&
          c.active &&
          (!c.permission || can(c.permission)),
      );
      const route = String(m.route || children[0]?.route || '');
      const id = route.split('?')[0].slice(1);
      const base = nav.find((n) => n.id === id);
      return {
        id,
        name: m.name,
        mark: m.icon || base?.mark || '',
        permission: m.permission || base?.permission || '',
        route,
        children,
      };
    })
    .filter(
      (n) =>
        nav.some((base) => base.id === n.id) &&
        (can(n.permission) || (n.id === 'system' && can('system:write')) || n.children.length),
    );
});
const title = computed(() => nav.find((n) => n.id === page.value)?.name || '经营管理');
const username = ref('admin'),
  password = ref(''),
  busy = ref(false),
  error = ref(''),
  oldPassword = ref(''),
  newPassword = ref(''),
  paymentPassword = ref('');
async function login() {
  busy.value = true;
  error.value = '';
  try {
    state.user = await api.login(username.value, password.value);
    if (!state.user.mustChangePassword) {
      await hydrate();
      if (!visibleNav.value.some((n) => n.id === page.value))
        await router.replace('/' + (visibleNav.value[0]?.id || 'dashboard'));
    }
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function change() {
  busy.value = true;
  try {
    await api.post('/auth/password', {
      oldPassword: oldPassword.value,
      newPassword: newPassword.value,
      newPaymentPassword: paymentPassword.value,
    });
    await hydrate();
    ElMessage.success('密码已更新');
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function logout() {
  try {
    await api.post('/auth/logout');
  } finally {
    api.token = '';
    state.user = null;
    password.value = '';
  }
}
onMounted(async () => {
  if (api.token)
    try {
      await hydrate();
    } catch (e) {
      state.user = null;
      error.value = (e as Error).message;
    }
});
</script>
<template>
  <main v-if="!state.user" class="login-layout">
    <section class="login-story">
      <span class="wordmark">初语山言 <small>CHUYU SHANYAN</small></span>
      <div class="mountain-art"><i /><i /><i /></div>
      <small>初语山言 · 经营管理</small>
    </section>
    <section class="login-panel">
      <div class="login-box">
        <h2>管理后台</h2>
        <el-form label-position="top" @submit.prevent="login"
          ><el-form-item label="账号"
            ><el-input v-model="username" autocomplete="username" size="large" /></el-form-item
          ><el-form-item label="密码"
            ><el-input
              v-model="password"
              type="password"
              show-password
              autocomplete="current-password"
              size="large"
              @keyup.enter="login" /></el-form-item
          ><el-alert
            v-if="error"
            :title="error"
            type="error"
            :closable="false"
            show-icon
          /><el-button
            type="primary"
            size="large"
            class="full login-button"
            :loading="busy"
            @click="login"
            >登录工作台 →</el-button
          ></el-form
        >
      </div>
    </section>
  </main>
  <main v-else-if="state.user.mustChangePassword" class="password-page">
    <el-card
      ><h2>设置新密码</h2>
      <p>首次登录请更新登录密码，可同时设置支付密码。</p>
      <el-input v-model="oldPassword" type="password" placeholder="原密码" /><el-input
        v-model="newPassword"
        type="password"
        placeholder="新密码，至少10位"
      /><el-input
        v-model="paymentPassword"
        type="password"
        placeholder="新的6位支付密码（可选）"
      /><el-alert v-if="error" :title="error" type="error" /><el-button
        type="primary"
        :loading="busy"
        @click="change"
        >保存并继续</el-button
      ></el-card
    >
  </main>
  <div v-else class="admin-shell">
    <aside class="sidebar">
      <a class="brand" href="#/dashboard"
        ><span class="brand-symbol">山</span><span>初语山言<small>经营管理平台</small></span></a
      >
      <p class="nav-caption">业务管理</p>
      <nav>
        <template v-for="n in visibleNav" :key="n.route"
          ><router-link :to="n.route" :class="{ active: page === n.id }"
            ><span class="nav-icon">{{ n.mark }}</span
            >{{ n.name }}</router-link
          ><router-link
            v-for="child in n.children"
            :key="child.id"
            :to="child.route"
            class="nav-child"
            >{{ child.name }}</router-link
          ></template
        >
      </nav>
      <div class="sidebar-bottom">
        <span class="environment-dot" />演示环境<a
          href="http://127.0.0.1:15174"
          target="_blank"
          rel="noopener"
          >打开会员端 ↗</a
        >
      </div>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <div class="breadcrumb">初语山言 <span>/</span> {{ title }}</div>
        <div class="topbar-right">
          <el-dropdown @command="logout"
            ><button class="user-menu">
              <span class="avatar">{{ state.user.displayName.slice(0, 1) }}</span
              >{{ label(state.user.role) }} ⌄</button
            ><template #dropdown
              ><el-dropdown-menu
                ><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu
              ></template
            ></el-dropdown
          >
        </div>
      </header>
      <div class="page-container">
        <DashboardView v-if="page === 'dashboard' && can('reports:read')" /><RulesView
          v-else-if="page === 'rules' && can('rules:read')"
        /><DataView
          v-else-if="visibleNav.some((n) => n.id === page)"
          :key="page"
          :section="page"
        /><el-empty v-else description="当前账号没有此页面权限" />
      </div>
    </div>
  </div>
</template>
