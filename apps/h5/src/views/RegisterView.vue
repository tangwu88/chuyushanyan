<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { showDialog } from 'vant';
import { api, state, selectedPoint, hydrate, fail } from '../state';
const router = useRouter(),
  point = selectedPoint(),
  busy = ref(false),
  form = ref({ nickname: '', phone: '', parentId: state.pointId, subPoint: false });
function change() {
  form.value.phone = form.value.subPoint ? point.phone : '';
}
async function submit() {
  busy.value = true;
  try {
    const result = await api.post('/members', form.value);
    await hydrate();
    if (!form.value.subPoint)
      state.assisted.push({
        id: result.id,
        nickname: form.value.nickname,
        phone: form.value.phone,
      });
    await showDialog({
      title: '会员点位已建立',
      message:
        '点位 ' +
        result.id +
        (form.value.subPoint
          ? '\n请按注册先后顺序激活。'
          : '\n登录账号为手机号，初始密码为手机号后六位，首次登录需修改。'),
    });
    router.push('/order?type=NEW&point=' + result.id);
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <van-form @submit="submit"
    ><div class="mobile-card">
      <div class="form-inline">
        <span>创建同手机号小号</span
        ><van-switch v-model="form.subPoint" size="21" @change="change" />
      </div>
      <van-field v-model="form.nickname" label="昵称" placeholder="请输入昵称" /><van-field
        v-model="form.phone"
        label="手机号"
        inputmode="numeric"
        :readonly="form.subPoint"
        placeholder="至少6位数字，保留前导零"
      /><label class="native-select-label"
        >推荐点位<select v-model="form.parentId">
          <option v-for="p in state.points" :key="p.id" :value="p.id">
            {{ p.nickname }} · {{ p.id }}
          </option>
        </select></label
      >
    </div>
    <p class="inline-note">主小号合计最多13个，每点最多邀请3个小号，按注册顺序激活。</p>
    <van-button block round type="primary" :loading="busy" native-type="submit"
      >创建并前往下单</van-button
    ></van-form
  >
</template>
