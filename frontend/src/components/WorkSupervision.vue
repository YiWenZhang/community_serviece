<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { api, query, type Row } from "../api";
import BusinessFilters from "./BusinessFilters.vue";
import WorkMetrics from "./WorkMetrics.vue";
import { useAuth } from "../stores";
const auth = useAuth();
const filters = reactive<Row>({ noticeType: "", ownerId: "" }),
  rows = ref<Row[]>([]),
  loading = ref(false),
  error = ref("");
let epoch = 0;
async function load() {
  const request = ++epoch;
  loading.value = true;
  error.value = "";
  try {
    const r = await api("/work/overview?" + query(filters));
    if (request === epoch) rows.value = r.items;
  } catch (e) {
    if (request === epoch) error.value = (e as Error).message;
  } finally {
    if (request === epoch) loading.value = false;
  }
}
function reset() {
  Object.assign(filters, { noticeType: "", ownerId: "" });
  load();
}
const sum = (key: string) =>
  error.value || loading.value
    ? "—"
    : rows.value.reduce((n, row) => n + Number(row[key] || 0), 0);
const metrics = computed(() => [
  {
    label: "网格员",
    value: error.value || loading.value ? "—" : rows.value.length,
  },
  { label: "联系工作", value: sum("total") },
  { label: "已闭环", value: sum("completed") },
  { label: "未完成联系", value: sum("pending") },
  { label: "逾期联系", value: sum("overdue"), urgent: true },
]);
onMounted(load);
</script>
<template>
  <section class="panel business-panel">
    <div class="panel-head">
      <div>
        <h2>网格员工作完成情况</h2>
        <p>当前社区累计进度，按负责人查看联系闭环及跟进结果。</p>
      </div>
      <router-link
        v-if="auth.can('notice:rule:manage')"
        class="text-link"
        to="/rules"
        >配置自动规则 →</router-link
      >
    </div>
    <BusinessFilters :model="filters" @change="load" @reset="reset" />
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      class="mb"
    />
    <WorkMetrics :items="metrics" />
    <el-table
      :data="rows"
      v-loading="loading"
      empty-text="当前社区尚未分配网格员"
    >
      <el-table-column label="负责网格员" min-width="145"
        ><template #default="{ row }"
          ><strong>{{ row.displayName }}</strong
          ><small class="work-subtitle"
            >{{ row.username
            }}{{ row.status !== "ACTIVE" ? " · 账号已停用" : "" }}</small
          ></template
        ></el-table-column
      >
      <el-table-column prop="elderCount" label="负责老人" min-width="90" />
      <el-table-column label="联系闭环进度" min-width="210"
        ><template #default="{ row }"
          ><el-progress
            :percentage="
              row.total ? Math.round((row.completed / row.total) * 100) : 0
            "
            :stroke-width="6"
          /><small class="work-subtitle"
            >{{ row.completed }} / {{ row.total }} 条已闭环</small
          ></template
        ></el-table-column
      >
      <el-table-column prop="pending" label="未完成联系" min-width="105" />
      <el-table-column prop="unreachable" label="未联系上" min-width="95" />
      <el-table-column label="逾期联系" min-width="95"
        ><template #default="{ row }"
          ><span :class="{ 'work-urgent': Number(row.overdue) > 0 }">{{
            row.overdue
          }}</span></template
        ></el-table-column
      >
      <el-table-column label="跟进任务" min-width="145"
        ><template #default="{ row }"
          >待办 {{ row.followupPending }} / 完成
          {{ row.followupCompleted }}</template
        ></el-table-column
      >
      <el-table-column label="查看记录" min-width="185" fixed="right"
        ><template #default="{ row }"
          ><router-link
            :to="{
              path: '/notices',
              query: { ownerId: row.id, type: filters.noticeType },
            }"
            class="text-link"
            >联系进度</router-link
          ><router-link
            :to="{
              path: '/followups',
              query: { ownerId: row.id, type: filters.noticeType },
            }"
            class="text-link supervision-link"
            >跟进进度</router-link
          ></template
        ></el-table-column
      >
    </el-table>
    <p class="table-note">
      联系闭环率＝已闭环联系数 /
      有效联系总数，不含草稿和取消记录；闭环不代表满意或已接种。跟进按任务计数，同一联系可能有多次跟进。
    </p>
  </section>
</template>
