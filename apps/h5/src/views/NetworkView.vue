<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { api, state, fail } from '../state';
import { grade, label, type Row } from '@chuyu/shared';
const data = ref<Row>({}),
  busy = ref(false),
  page = ref(1);
async function load(id: string, current = 1) {
  busy.value = true;
  try {
    data.value = await api.get('/network/' + id + '?page=' + current);
    page.value = current;
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
onMounted(() => load(state.pointId));
</script>
<template>
  <section class="mobile-card">
    <h2>{{ data.root?.nickname }}</h2>
    <p>{{ data.root?.id }} · {{ grade(data.root?.grade) }}</p>
    <van-button
      v-if="state.points.some((p) => p.id === data.root?.parentId)"
      size="small"
      plain
      @click="load(data.root.parentId)"
      >查看上级</van-button
    >
  </section>
  <p class="inline-note">直属点位 {{ data.total || 0 }} 个</p>
  <section class="network-list">
    <button
      v-for="node in data.children || []"
      :key="node.id"
      class="mobile-card"
      @click="load(node.id)"
    >
      <b>{{ node.nickname }}</b
      ><span>{{ node.id }} · {{ grade(node.grade) }}</span
      ><small>{{ node.phone }} · {{ label(node.shopType) }}</small
      ><small>直属点位 {{ node.childCount }} ›</small>
    </button>
  </section>
  <van-loading v-if="busy" class="center-loading" /><van-empty
    v-if="!busy && !data.children?.length"
    description="暂无下级点位"
  /><van-pagination
    v-if="Number(data.total) > 100"
    :model-value="page"
    :total-items="Number(data.total)"
    :items-per-page="100"
    @change="(p: number) => load(data.root.id, p)"
  />
</template>
<style scoped>
.network-list {
  display: grid;
  gap: 10px;
}
.network-list button {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  text-align: left;
  color: #35523e;
}
.network-list span,
.network-list small {
  font-size: 12px;
  color: #6a8062;
}
</style>
