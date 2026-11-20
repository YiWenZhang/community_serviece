<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, type Row } from "../api";
import { useAuth } from "../stores";
const props = defineProps<{ elderId?: string }>(),
  auth = useAuth(),
  data = ref<Row | null>(null),
  error = ref("");
onMounted(async () => {
  if (!props.elderId || !auth.can("elder:read")) return;
  try {
    data.value = await api(`/elders/${props.elderId}`);
  } catch (e) {
    error.value = (e as Error).message;
  }
});
</script>
<template>
  <div v-if="data" class="work-detail-context">
    <strong>实际联系所需资料</strong>
    <p>
      老人电话：{{ data.phone || "待补录" }}<br />家属：{{
        data.familyName || "待补录"
      }}
      {{ data.familyRelation }}　电话：{{ data.familyPhone || "待补录"
      }}<br />上门地址：{{ data.address || "待补录" }}
    </p>
    <small>仅供当前授权人员办理服务；请勿将联系方式重复写入反馈原话。</small>
  </div>
  <el-alert
    v-else-if="error"
    :title="'联系资料暂未加载：' + error"
    type="warning"
    :closable="false"
  />
</template>
