<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '../state';
import { grade, label, query, type Row } from '@chuyu/shared';
const props = defineProps<{ rootId: string }>(),
  emit = defineEmits<{ detail: [string] }>();
const data = ref<Row>({}),
  page = ref(1),
  busy = ref(false),
  error = ref(''),
  search = ref(''),
  highlight = ref<number | null>(null),
  zoom = ref(1),
  viewport = ref<HTMLElement>();
let drag: { x: number; y: number; left: number; top: number } | null = null;
async function load(id: string, current = 1) {
  busy.value = true;
  error.value = '';
  try {
    data.value = await api.get('/network/' + id + '?page=' + current);
    page.value = current;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function locate() {
  try {
    const rows: Row[] = await api.get('/members/lookup?' + query({ account: search.value }));
    if (rows.length !== 1) {
      ElMessage.info(rows.length ? '此手机号有多个点位，请输入具体点位编号' : '没有找到点位');
      return;
    }
    await load(rows[0].id);
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
function start(event: PointerEvent) {
  if ((event.target as HTMLElement).closest('button') || !viewport.value) return;
  drag = {
    x: event.clientX,
    y: event.clientY,
    left: viewport.value.scrollLeft,
    top: viewport.value.scrollTop,
  };
  viewport.value.setPointerCapture(event.pointerId);
}
function move(event: PointerEvent) {
  if (!drag || !viewport.value) return;
  viewport.value.scrollLeft = drag.left - (event.clientX - drag.x);
  viewport.value.scrollTop = drag.top - (event.clientY - drag.y);
}
watch(
  () => props.rootId,
  (id) => load(id),
  { immediate: true },
);
</script>
<template>
  <div v-loading="busy">
    <div class="network-tools">
      <el-input
        v-model="search"
        placeholder="完整点位编号或手机号"
        @keyup.enter="locate"
      /><el-button @click="locate">定位</el-button
      ><el-select v-model="highlight" clearable placeholder="高亮等级"
        ><el-option v-for="g in 8" :key="g" :value="g - 1" :label="grade(g - 1)" /></el-select
      ><label>缩放<el-slider v-model="zoom" :min="0.6" :max="1.6" :step="0.1" /></label>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="detail-banner">
      <div>
        <b>{{ data.root?.nickname }} · {{ data.root?.id }}</b>
        <p>
          {{ data.root?.phone }} · {{ grade(data.root?.grade) }} · {{ label(data.root?.shopType) }}
        </p>
      </div>
      <div>
        <el-button v-if="data.root?.parentId" @click="load(data.root.parentId)">查看上级</el-button
        ><el-button @click="emit('detail', data.root.id)">会员详情</el-button>
      </div>
    </div>
    <div
      ref="viewport"
      class="network-viewport"
      @pointerdown="start"
      @pointermove="move"
      @pointerup="drag = null"
      @pointercancel="drag = null"
    >
      <div class="network-grid" :style="{ zoom }">
        <article
          v-for="node in data.children || []"
          :key="node.id"
          :class="{ highlighted: highlight !== null && node.grade === highlight }"
        >
          <button @click="load(node.id)">
            <b>{{ node.nickname }}</b
            ><span>{{ node.id }}</span
            ><span>{{ node.phone }}</span
            ><span>{{ grade(node.grade) }} · {{ label(node.shopType) }}</span
            ><small>直属点位 {{ node.childCount }}</small></button
          ><el-button link type="primary" @click="emit('detail', node.id)">会员详情</el-button>
        </article>
      </div>
      <el-empty v-if="!busy && !data.children?.length" description="暂无下级点位" />
    </div>
    <div class="pagination">
      <span>直属点位 {{ data.total || 0 }} 个</span
      ><el-pagination
        :current-page="page"
        :page-size="100"
        :total="Number(data.total || 0)"
        layout="prev,pager,next"
        @current-change="(p: number) => load(data.root.id, p)"
      />
    </div>
  </div>
</template>
<style scoped>
.network-tools {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 18px;
}
.network-tools > .el-input {
  width: 220px;
}
.network-tools > .el-select {
  width: 150px;
}
.network-tools label {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 180px;
}
.network-tools .el-slider {
  width: 120px;
}
.network-viewport {
  max-height: 480px;
  overflow: auto;
  cursor: grab;
}
.network-grid article {
  border: 1px solid #dbe2da;
  border-radius: 12px;
  background: #f9faf6;
  padding: 8px;
}
.network-grid article.highlighted {
  border: 2px solid #947b42;
  background: #f5edce;
}
.network-grid article > button {
  width: 100%;
  border: none;
  background: none;
  text-align: left;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.network-grid article > button span,
.network-grid small {
  font-size: 12px;
  color: #66796d;
}
.detail-banner p {
  font-size: 12px;
  font-weight: 400;
}
</style>
