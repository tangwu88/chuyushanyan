<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, globalCan, hydrate, state } from '../state';
import { dateTime, label, money, safeHtml, type Row } from '@chuyu/shared';
const props = defineProps<{ resource: string }>();
const rows = ref<Row[]>([]),
  loading = ref(false),
  error = ref(''),
  visible = ref(false),
  busy = ref(false),
  form = ref<Row>({}),
  search = ref(''),
  page = ref(1),
  size = ref(20),
  editor = ref<HTMLElement>();
const systemResources = ['roles', 'departments', 'posts', 'menus', 'parameters', 'job-definitions'];
const editable = computed(() =>
  globalCan(systemResources.includes(props.resource) ? 'system:write' : 'rules:write'),
);
const names: Record<string, string> = {
  roles: '角色权限',
  departments: '部门',
  posts: '岗位',
  menus: '菜单与按钮',
  freights: '运费',
  announcements: '订单消息',
  banners: '轮播图',
  agreements: '协议',
  appearance: '页面颜色',
  parameters: '保留策略',
  'job-definitions': '定时任务配置',
};
const filtered = computed(() =>
  rows.value.filter((r) => !search.value || [r.id, r.name].includes(search.value)),
);
const roots = computed(() => {
  if (!['departments', 'menus'].includes(props.resource) || search.value) return filtered.value;
  const nodes = new Map<string, Row>(
    filtered.value.map((row) => [row.id, { ...row, children: [] } as Row]),
  );
  const top: Row[] = [];
  nodes.forEach((row) => {
    const parent = nodes.get(row.parentId);
    if (parent) parent.children.push(row);
    else top.push(row);
  });
  return top;
});
const paged = computed(() =>
  roots.value.slice((page.value - 1) * size.value, page.value * size.value),
);
const permissions = computed(() =>
  Array.from(
    new Set(['system:write', ...(Object.values(state.config.roles || {}).flat() as string[])]),
  ).filter((p) => p !== 'self' && (form.value.id === 'ADMIN' || p !== '*')),
);
const defaults: Record<string, Row> = {
  roles: { permissions: [], dataScope: 'ALL' },
  departments: { parentId: '' },
  posts: {},
  menus: {
    type: 'MENU',
    parentId: '',
    route: '/members',
    component: 'members',
    permission: 'members:read',
    icon: '',
  },
  freights: { channel: '初语山言', maxWeight: '1', amount: '0' },
  announcements: { channel: '初语山言', type: 'ALL', content: '' },
  banners: { imageUrl: '', link: '' },
  agreements: { content: '' },
  appearance: { homeBackground: '#f4f5ed', pageBackground: '#f4f5ed', richBackground: '#ffffff' },
  parameters: { archiveDays: 90, auditDays: 365, loginDays: 90 },
  'job-definitions': { cron: '0 0 1 * * *', zone: 'Asia/Shanghai' },
};
const textFields: Record<string, Array<[string, string]>> = {
  posts: [],
  menus: [
    ['route', '路由地址'],
    ['component', '页面名称'],
    ['permission', '权限标识'],
    ['icon', '图标'],
  ],
  freights: [
    ['channel', '渠道'],
    ['maxWeight', '重量上限（kg）'],
    ['amount', '运费（元）'],
  ],
  announcements: [
    ['channel', '渠道'],
    ['content', '消息内容'],
  ],
  banners: [
    ['imageUrl', '图片地址'],
    ['link', '跳转链接'],
  ],
  parameters: [
    ['archiveDays', '订单与积分归档天数'],
    ['auditDays', '操作日志保留天数'],
    ['loginDays', '登录日志保留天数'],
  ],
  'job-definitions': [['cron', 'Cron表达式']],
};
async function load() {
  loading.value = true;
  error.value = '';
  try {
    rows.value = await api.get('/management/' + props.resource);
    page.value = 1;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
watch(
  () => props.resource,
  () => {
    search.value = '';
    visible.value = false;
    load();
  },
  { immediate: true },
);
async function open(row: Row = {}) {
  form.value = {
    ...JSON.parse(JSON.stringify(defaults[props.resource] || {})),
    active: true,
    sortNo: 0,
    name: '',
    ...JSON.parse(JSON.stringify(row)),
  };
  visible.value = true;
  await nextTick();
  initEditor();
}
function initEditor() {
  if (editor.value) editor.value.innerHTML = safeHtml(form.value.content);
}
async function save() {
  busy.value = true;
  try {
    if (props.resource === 'agreements')
      form.value.content = safeHtml(editor.value?.innerHTML || form.value.content);
    await api.post(
      '/management/' +
        props.resource +
        (form.value.id ? '/' + encodeURIComponent(form.value.id) + '/update' : ''),
      form.value,
    );
    visible.value = false;
    await hydrate();
    await load();
    ElMessage.success('配置已保存');
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = false;
  }
}
async function remove(row: Row) {
  try {
    await ElMessageBox.confirm('删除“' + row.name + '”？', '删除配置', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    });
    await api.post('/management/' + props.resource + '/' + encodeURIComponent(row.id) + '/delete', {
      reason: '后台删除配置',
    });
    await hydrate();
    await load();
    ElMessage.success('已删除');
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message);
  }
}
async function upload(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  if (
    file.size > 2 * 1024 * 1024 ||
    !['image/png', 'image/jpeg', 'image/webp'].includes(file.type)
  ) {
    ElMessage.error('请选择2MB以内的PNG/JPEG/WebP图片');
    return;
  }
  const reader = new FileReader();
  reader.onload = () => {
    form.value.imageUrl = String(reader.result);
  };
  reader.readAsDataURL(file);
}
function format(command: string) {
  editor.value?.focus();
  document.execCommand(command);
  form.value.content = editor.value?.innerHTML || '';
}
async function run(row: Row) {
  try {
    const monthly = String(row.id).startsWith('MONTHLY');
    const date = new Date(
      (state.config.today || new Date().toLocaleDateString('sv-SE')) + 'T12:00:00',
    );
    const defaultPeriod = monthly
      ? new Date(date.getFullYear(), date.getMonth() - 1, 1).toLocaleDateString('sv-SE').slice(0, 7)
      : new Date(date.getFullYear(), date.getMonth(), date.getDate() - 1).toLocaleDateString(
          'sv-SE',
        );
    const { value } = await ElMessageBox.prompt(
      '账期 ' + (monthly ? 'YYYY-MM' : 'YYYY-MM-DD'),
      '运行“' + row.name + '”',
      { inputValue: defaultPeriod, confirmButtonText: '运行', cancelButtonText: '取消' },
    );
    busy.value = true;
    await api.post('/jobs/' + row.id + '/run', { period: value });
    ElMessage.success('任务已完成');
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="surface managed-surface">
    <div class="list-toolbar">
      <div>
        <el-input
          v-model="search"
          placeholder="精确搜索名称 / 编号"
          clearable
          @input="page = 1"
        /><el-button :loading="loading" @click="load">刷新</el-button>
      </div>
      <el-button
        v-if="editable && !['appearance', 'parameters', 'job-definitions'].includes(resource)"
        type="primary"
        @click="open()"
        >新增{{ names[resource] }}</el-button
      >
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table
      v-loading="loading"
      :data="paged"
      row-key="id"
      default-expand-all
      empty-text="暂无配置"
    >
      <el-table-column prop="name" label="名称" min-width="170" />
      <el-table-column
        v-if="['roles', 'posts', 'menus', 'departments'].includes(resource)"
        prop="id"
        label="编号"
        min-width="130"
      />
      <el-table-column v-if="resource === 'roles'" label="数据权限" min-width="130"
        ><template #default="{ row }">{{ label(row.dataScope) }}</template></el-table-column
      >
      <el-table-column
        v-if="['departments', 'menus'].includes(resource)"
        prop="parentId"
        label="上级"
        min-width="120"
      />
      <el-table-column v-if="resource === 'menus'" prop="route" label="路由" min-width="170" />
      <el-table-column v-if="resource === 'freights'" prop="channel" label="渠道" min-width="130" />
      <el-table-column
        v-if="resource === 'freights'"
        prop="maxWeight"
        label="重量上限 / kg"
        min-width="140"
      />
      <el-table-column v-if="resource === 'freights'" label="运费" min-width="110"
        ><template #default="{ row }">¥{{ money(row.amount) }}</template></el-table-column
      >
      <el-table-column
        v-if="resource === 'announcements'"
        prop="content"
        label="消息内容"
        min-width="260"
        show-overflow-tooltip
      />
      <el-table-column v-if="resource === 'banners'" label="图片" width="150"
        ><template #default="{ row }"
          ><img
            v-if="row.imageUrl"
            :src="row.imageUrl"
            :alt="row.name"
            class="config-thumb" /></template
      ></el-table-column>
      <el-table-column
        v-if="resource === 'job-definitions'"
        prop="cron"
        label="执行时间（上海）"
        min-width="200"
      />
      <el-table-column label="状态" width="100"
        ><template #default="{ row }"
          ><el-tag :type="row.active ? 'success' : 'info'">{{
            row.active ? '启用' : '停用'
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column prop="sortNo" label="排序" width="80" />
      <el-table-column label="更新时间" min-width="175"
        ><template #default="{ row }">{{ dateTime(row.updatedAt) }}</template></el-table-column
      >
      <el-table-column label="操作" fixed="right" width="200"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="open(row)">{{
            editable ? '编辑' : '查看'
          }}</el-button
          ><el-button
            v-if="editable && !['appearance', 'parameters', 'job-definitions'].includes(resource)"
            link
            type="danger"
            @click="remove(row)"
            >删除</el-button
          ><el-button
            v-if="
              resource === 'job-definitions' &&
              (['DAILY', 'MONTHLY', 'MONTHLY_REPEAT'].includes(row.id)
                ? globalCan('funds:write')
                : globalCan('system:write'))
            "
            link
            type="primary"
            :loading="busy"
            @click="run(row)"
            >运行一次</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <div class="pagination">
      <span>共{{ filtered.length }}条</span
      ><el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="roots.length"
        :page-sizes="[20, 50, 100]"
        layout="sizes,prev,pager,next"
      />
    </div>
  </section>
  <el-dialog
    v-model="visible"
    :title="names[resource]"
    width="760px"
    destroy-on-close
    @opened="initEditor"
    :close-on-click-modal="false"
    ><el-form :disabled="!editable" label-position="top" class="dialog-form">
      <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item
      ><el-form-item
        v-if="!form.id && ['roles', 'posts', 'menus', 'departments'].includes(resource)"
        label="编号"
        ><el-input v-model="form.code"
      /></el-form-item>
      <el-form-item v-if="['departments', 'menus'].includes(resource)" label="上级"
        ><el-select v-model="form.parentId" clearable
          ><el-option label="无上级" value="" /><el-option
            v-for="r in rows.filter((x) => x.id !== form.id)"
            :key="r.id"
            :value="r.id"
            :label="r.name" /></el-select
      ></el-form-item>
      <el-form-item v-if="resource === 'roles'" label="数据权限"
        ><el-select v-model="form.dataScope"
          ><el-option
            v-for="s in ['ALL', 'DEPARTMENT', 'SELF']"
            :key="s"
            :value="s"
            :label="label(s)" /></el-select
      ></el-form-item>
      <el-form-item v-if="resource === 'roles'" label="操作权限" class="wide"
        ><el-checkbox-group v-model="form.permissions" class="permission-options"
          ><el-checkbox v-for="p in permissions" :key="p" :value="p">{{
            label(p)
          }}</el-checkbox></el-checkbox-group
        ></el-form-item
      >
      <el-form-item v-if="resource === 'menus'" label="类型"
        ><el-select v-model="form.type"
          ><el-option label="目录" value="DIRECTORY" /><el-option
            label="菜单"
            value="MENU" /><el-option label="按钮" value="BUTTON" /></el-select
      ></el-form-item>
      <el-form-item
        v-for="field in textFields[resource] || []"
        :key="field[0]"
        :label="field[1]"
        :class="{ wide: field[0] === 'content' || field[0] === 'imageUrl' }"
        ><el-input
          v-model="form[field[0]]"
          :type="field[0] === 'content' ? 'textarea' : 'text'"
          :rows="3"
      /></el-form-item>
      <el-form-item v-if="resource === 'banners'" label="上传图片" class="wide"
        ><input type="file" accept="image/png,image/jpeg,image/webp" @change="upload" /><img
          v-if="form.imageUrl"
          :src="form.imageUrl"
          alt="轮播图预览"
          class="config-preview"
      /></el-form-item>
      <el-form-item v-if="resource === 'announcements'" label="消息类型"
        ><el-select v-model="form.type"
          ><el-option label="全部订单" value="ALL" /><el-option
            v-for="t in ['NEW', 'FIRST', 'REPEAT']"
            :key="t"
            :value="t"
            :label="label(t)" /></el-select
      ></el-form-item>
      <el-form-item
        v-if="resource === 'appearance'"
        v-for="field in [
          ['homeBackground', '首页背景'],
          ['pageBackground', '其他页面背景'],
          ['richBackground', '协议页面背景'],
        ]"
        :key="field[0]"
        :label="field[1]"
        ><el-color-picker v-model="form[field[0]]" /><el-input v-model="form[field[0]]"
      /></el-form-item>
      <el-form-item v-if="resource === 'agreements'" label="协议内容" class="wide"
        ><div class="rich-editor-shell">
          <div v-if="editable" class="rich-toolbar">
            <el-button size="small" @mousedown.prevent="format('bold')">加粗</el-button
            ><el-button size="small" @mousedown.prevent="format('italic')">斜体</el-button
            ><el-button size="small" @mousedown.prevent="format('insertUnorderedList')"
              >列表</el-button
            >
          </div>
          <div
            ref="editor"
            :contenteditable="editable"
            role="textbox"
            aria-label="协议内容"
            aria-multiline="true"
            class="rich-editor"
            @input="form.content = ($event.target as HTMLElement).innerHTML"
          /></div
      ></el-form-item>
      <el-form-item label="排序"
        ><el-input-number v-model="form.sortNo" :min="0" :max="99999" /></el-form-item
      ><el-form-item label="启用"><el-switch v-model="form.active" /></el-form-item> </el-form
    ><template #footer
      ><el-button @click="visible = false">关闭</el-button
      ><el-button v-if="editable" type="primary" :loading="busy" @click="save"
        >保存</el-button
      ></template
    ></el-dialog
  >
</template>
<style scoped>
.managed-surface {
  padding: 22px;
}
.config-thumb {
  width: 120px;
  height: 64px;
  object-fit: cover;
  border-radius: 6px;
}
.config-preview {
  display: block;
  max-width: 100%;
  max-height: 220px;
  margin-top: 14px;
}
.permission-options {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 18px;
}
.rich-editor-shell {
  width: 100%;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  overflow: hidden;
}
.rich-toolbar {
  padding: 8px;
  border-bottom: 1px solid var(--el-border-color);
}
.rich-editor {
  min-height: 240px;
  padding: 14px;
  outline: none;
  line-height: 1.7;
}
.managed-surface .list-toolbar .el-input {
  max-width: 280px;
}
</style>
