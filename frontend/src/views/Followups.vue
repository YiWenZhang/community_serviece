<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import {
  Tickets,
  Timer,
  RefreshRight,
  CircleCheckFilled,
  WarningFilled,
  ArrowUp,
} from "@element-plus/icons-vue";
import { useAuth } from "../stores";
import { api, query, fail, date, label, idempotency, type Row } from "../api";
import Status from "../components/Status.vue";
import AttemptFields from "../components/AttemptFields.vue";
import StatCard from "../components/StatCard.vue";
const auth = useAuth(),
  rows = ref<Row[]>([]),
  page = ref(1),
  total = ref(0),
  loading = ref(false),
  error = ref(""),
  stats = ref<Row>({});
const filters = reactive({ status: "PENDING", overdue: false });
const open = ref(false),
  detailOpen = ref(false),
  detail = ref<Row>({}),
  task = ref<Row>({}),
  saving = ref(false),
  useAttempt = ref(true),
  requestKey = ref("");
const form = reactive<Row>({
    outcome: "RESOLVED",
    resultText: "",
    nextDueAt: null,
  }),
  attempt = ref<Row>({});
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const r = await api(
      "/followups?" + query({ ...filters, page: page.value, pageSize: 20 }),
    );
    rows.value = r.items;
    total.value = Number(r.total);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
async function loadStats() {
  try {
    const [all, pending, processing, completed, overdue] = await Promise.all([
      api("/followups?pageSize=1"),
      api("/followups?status=PENDING&pageSize=1"),
      api("/followups?status=PROCESSING&pageSize=1"),
      api("/followups?status=COMPLETED&pageSize=1"),
      api("/followups?overdue=true&pageSize=1"),
    ]);
    stats.value = {
      total: Number(all.total),
      pending: Number(pending.total),
      processing: Number(processing.total),
      completed: Number(completed.total),
      overdue: Number(overdue.total),
    };
  } catch {
    /* 统计卡片失败不阻断主列表 */
  }
}
const completionRate = computed(() =>
  stats.value.total
    ? `${((stats.value.completed / stats.value.total) * 100).toFixed(1)}%`
    : "—",
);
async function show(row: Row) {
  try {
    detail.value = await api(`/followups/${row.id}`);
    detailOpen.value = true;
  } catch (e) {
    fail(e);
  }
}
async function execute(row: Row) {
  try {
    task.value = await api(`/followups/${row.id}`);
    const recipient = await api(`/notice-recipients/${task.value.recipientId}`);
    Object.assign(form, {
      outcome: "RESOLVED",
      resultText: "",
      nextDueAt: null,
    });
    attempt.value = {
      version: recipient.version,
      channel: "FAMILY_RELAY",
      result: "CONFIRMED",
      unreachableReason: "NO_ONE_HOME",
      reasonText: null,
      feedbackQuote: null,
      familyNameMasked: "",
      familyRelation: "",
      relayConfirmed: false,
      fileIds: [],
    };
    useAttempt.value = task.value.taskType !== "VERIFY_APPOINTMENT";
    requestKey.value = idempotency();
    open.value = true;
  } catch (e) {
    fail(e);
  }
}
function outcomeChanged() {
  if (form.outcome === "REFUSED") {
    useAttempt.value = true;
    attempt.value.result = "REFUSED";
  } else if (form.outcome === "RESOLVED") {
    useAttempt.value = task.value.taskType !== "VERIFY_APPOINTMENT";
    attempt.value.result = "CONFIRMED";
  } else if (form.outcome === "RETRY_REQUIRED") {
    useAttempt.value = true;
    attempt.value.result = "UNREACHABLE";
  } else useAttempt.value = false;
}
async function save() {
  if (saving.value) return;
  if (!form.resultText.trim()) return ElMessage.warning("请填写本次跟进结果");
  if (form.outcome === "RETRY_REQUIRED" && !form.nextDueAt)
    return ElMessage.warning("请选择下次跟进期限");
  saving.value = true;
  try {
    await api(
      `/followups/${task.value.id}/records`,
      "POST",
      {
        version: task.value.version,
        outcome: form.outcome,
        resultText: form.resultText,
        nextDueAt: form.nextDueAt
          ? new Date(form.nextDueAt).toISOString()
          : null,
        attempt: useAttempt.value
          ? {
              ...attempt.value,
              unreachableReason:
                attempt.value.result === "UNREACHABLE"
                  ? attempt.value.unreachableReason
                  : null,
            }
          : null,
      },
      requestKey.value,
    );
    ElMessage.success("跟进结果已保存");
    open.value = false;
    await load();
  } catch (e) {
    fail(e);
  } finally {
    saving.value = false;
  }
}
onMounted(async () => {
  await load();
  await loadStats();
});
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>服务跟进</h1>
      <p>及时回应未完成的服务，保留每一次跟进结果。</p>
    </div>
    <el-button @click="load">刷新任务</el-button>
  </div>
  <section class="stat-tiles">
    <StatCard
      label="跟进任务总数"
      :value="stats.total ?? '—'"
      suffix="条"
      :icon="Tickets"
      tone="blue"
      hint="全部跟进任务"
    />
    <StatCard
      label="待处理任务"
      :value="stats.pending ?? '—'"
      suffix="条"
      :icon="Timer"
      tone="gold"
      :hint="
        stats.overdue ? `其中 ${stats.overdue} 项已逾期` : '暂无逾期任务'
      "
      hint-tone="warn"
      :hint-icon="WarningFilled"
    />
    <StatCard
      label="处理中任务"
      :value="stats.processing ?? '—'"
      suffix="条"
      :icon="RefreshRight"
      tone="brand"
      hint="正在跟进处理"
    />
    <StatCard
      label="已完成任务"
      :value="stats.completed ?? '—'"
      suffix="条"
      :icon="CircleCheckFilled"
      tone="green"
      :hint="`完成率 ${completionRate}`"
      hint-tone="up"
      :hint-icon="ArrowUp"
    />
  </section>
  <section class="panel">
    <div class="toolbar">
      <el-select
        v-model="filters.status"
        clearable
        placeholder="全部任务状态"
        @change="
          page = 1;
          load();
        "
        ><el-option
          v-for="v in ['PENDING', 'PROCESSING', 'COMPLETED', 'CANCELLED']"
          :key="v"
          :label="label(v)"
          :value="v" /></el-select
      ><el-checkbox
        v-model="filters.overdue"
        @change="
          page = 1;
          load();
        "
        >仅看逾期任务</el-checkbox
      >
    </div>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      class="mb"
    /><el-table
      v-loading="loading"
      :data="rows"
      empty-text="暂无符合条件的跟进任务"
      ><el-table-column prop="id" label="任务编号" width="90" /><el-table-column
        prop="recipientId"
        label="通知对象"
        width="100"
      /><el-table-column label="任务类型" min-width="125"
        ><template #default="{ row }">{{
          label(row.taskType)
        }}</template></el-table-column
      ><el-table-column label="截止时间" min-width="175"
        ><template #default="{ row }"
          ><span :style="Number(row.overdue) ? 'color:#b16d50' : ''">{{
            date(row.dueAt)
          }}</span></template
        ></el-table-column
      ><el-table-column label="状态" min-width="100"
        ><template #default="{ row }"
          ><Status :value="row.status" /></template></el-table-column
      ><el-table-column label="操作" min-width="155"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="show(row)">历史记录</el-button
          ><el-button
            v-if="
              auth.can('followup:update') &&
              ['PENDING', 'PROCESSING'].includes(row.status)
            "
            link
            type="primary"
            @click="execute(row)"
            >处理</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="20"
        :total="total"
        layout="total,prev,pager,next"
        @current-change="load"
      />
    </div>
  </section>
  <el-dialog v-model="open" title="记录本次跟进" width="720px" destroy-on-close
    ><p class="hint">
      任务 #{{ task.id }} ·
      {{
        label(task.taskType)
      }}。完成跟进需要填写实际结果，系统会同步维护通知状态。
    </p>
    <el-alert
      v-if="task.taskType === 'VERIFY_APPOINTMENT'"
      title="解决预约核实时，请先在老人档案中保存真实预约日期。"
      type="info"
      :closable="false"
      class="mb"
    /><el-form label-position="top"
      ><div class="form-grid">
        <el-form-item label="跟进结果"
          ><el-select v-model="form.outcome" @change="outcomeChanged"
            ><el-option label="已解决" value="RESOLVED" /><el-option
              label="需要再次跟进"
              value="RETRY_REQUIRED" /><el-option
              label="拒绝接收"
              value="REFUSED" /><el-option
              label="不再适用或终结服务"
              value="NO_LONGER_APPLICABLE" /></el-select></el-form-item
        ><el-form-item
          v-if="form.outcome === 'RETRY_REQUIRED'"
          label="下次跟进期限 *"
          ><el-date-picker
            v-model="form.nextDueAt"
            type="datetime"
            placeholder="选择未来时间" /></el-form-item
        ><el-form-item label="本次执行结果 *" class="span-2"
          ><el-input
            v-model="form.resultText"
            type="textarea"
            :rows="2"
            maxlength="1000"
            placeholder="客观记录本次服务情况，不包含敏感个人信息" /></el-form-item
        ><el-form-item class="span-2"
          ><el-checkbox v-model="useAttempt"
            >本次发生了触达，同时记录接收结果</el-checkbox
          ></el-form-item
        >
      </div>
      <AttemptFields v-if="useAttempt" v-model="attempt" /></el-form
    ><template #footer
      ><el-button @click="open = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="save"
        >提交跟进结果</el-button
      ></template
    ></el-dialog
  >
  <el-dialog
    v-model="detailOpen"
    title="跟进历史"
    width="740px"
    destroy-on-close
    ><el-descriptions :column="2" border
      ><el-descriptions-item label="任务编号">{{
        detail.id
      }}</el-descriptions-item
      ><el-descriptions-item label="任务状态">{{
        label(detail.status)
      }}</el-descriptions-item
      ><el-descriptions-item label="任务类型">{{
        label(detail.taskType)
      }}</el-descriptions-item
      ><el-descriptions-item label="截止时间">{{
        date(detail.dueAt)
      }}</el-descriptions-item></el-descriptions
    >
    <div class="detail-section">
      <el-table :data="detail.records || []" empty-text="尚未记录跟进结果"
        ><el-table-column label="执行时间" min-width="170"
          ><template #default="{ row }">{{
            date(row.performedAt)
          }}</template></el-table-column
        ><el-table-column prop="resultText" label="执行结果" min-width="250"
      /></el-table></div
  ></el-dialog>
</template>
