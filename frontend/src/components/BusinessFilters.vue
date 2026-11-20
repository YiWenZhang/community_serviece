<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useAuth } from "../stores";
import { api, fail, type Row } from "../api";
import { businessTypes } from "../work";
const props = defineProps<{
  model: Row;
  statuses?: { code: string; name: string }[];
}>();
const emit = defineEmits<{ change: []; reset: [] }>();
const auth = useAuth(),
  workers = ref<Row[]>([]);
onMounted(async () => {
  if (!auth.user?.roles?.includes("ADMIN")) return;
  try {
    workers.value = (await api("/work/filters")).workers;
  } catch (e) {
    fail(e);
  }
});
</script>
<template>
  <el-form class="business-filters" label-position="top" @submit.prevent>
    <el-form-item label="业务类型">
      <el-select
        v-model="props.model.noticeType"
        clearable
        placeholder="全部业务"
        @change="emit('change')"
      >
        <el-option
          v-for="b in businessTypes"
          :key="b.code"
          :value="b.code"
          :label="b.name"
        />
      </el-select>
    </el-form-item>
    <el-form-item v-if="statuses" label="工作状态">
      <el-select
        v-model="props.model.status"
        placeholder="选择状态"
        @change="emit('change')"
      >
        <el-option
          v-for="s in statuses"
          :key="s.code"
          :value="s.code"
          :label="s.name"
        />
      </el-select>
    </el-form-item>
    <el-form-item v-if="auth.user?.roles?.includes('ADMIN')" label="负责网格员">
      <el-select
        v-model="props.model.ownerId"
        clearable
        filterable
        placeholder="全部网格员"
        @change="emit('change')"
      >
        <el-option
          v-for="w in workers"
          :key="w.id"
          :value="String(w.id)"
          :label="`${w.displayName}（${w.username}）`"
        />
      </el-select>
    </el-form-item>
    <slot />
    <div class="filter-actions">
      <el-button @click="emit('reset')">重置</el-button
      ><el-button type="primary" plain @click="emit('change')">刷新</el-button>
    </div>
  </el-form>
</template>
