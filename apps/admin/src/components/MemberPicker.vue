<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { api, state } from '../state';
import { query, type Row } from '@chuyu/shared';
const props = defineProps<{ modelValue: string; clearable?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [string] }>();
const found = ref<Row[]>([]),
  chosen = ref<Row | null>(null),
  busy = ref(false);
const options = computed(() => {
  const map = new Map<string, Row>();
  [...state.points, ...found.value, ...(chosen.value ? [chosen.value] : [])].forEach((p) =>
    map.set(p.id, p),
  );
  return [...map.values()];
});
async function search(value: string) {
  if (value.trim().length < 4) return;
  busy.value = true;
  try {
    found.value = (
      await api.get('/lists/members?' + query({ search: value.trim(), size: 20 }))
    ).items;
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = false;
  }
}
watch(
  () => props.modelValue,
  async (id) => {
    if (!id || options.value.some((p) => p.id === id)) return;
    try {
      chosen.value = await api.get('/members/' + encodeURIComponent(id));
    } catch {
      chosen.value = { id, nickname: id };
    }
  },
  { immediate: true },
);
</script>
<template>
  <el-select
    :model-value="modelValue"
    :clearable="clearable"
    filterable
    remote
    :remote-method="search"
    :loading="busy"
    placeholder="输入完整点位号、电话或昵称"
    @update:model-value="(v: string) => emit('update:modelValue', String(v || ''))"
    ><el-option
      v-for="p in options"
      :key="p.id"
      :value="p.id"
      :label="p.nickname + ' / ' + p.id + (p.active === false ? ' · 已冻结' : '')"
  /></el-select>
</template>
