<script setup lang="ts">
import { useRoute } from "vue-router";
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { useAuth } from "../stores";
import { api, query, fail, date, label, idempotency, type Row } from "../api";
import Status from "../components/Status.vue";
import AttemptFields from "../components/AttemptFields.vue";
import WorkContact from "../components/WorkContact.vue";
import BusinessFilters from "../components/BusinessFilters.vue";
import WorkMetrics from "../components/WorkMetrics.vue";
const route = useRoute();
const auth = useAuth(),
  rows = ref<Row[]>([]),
  page = ref(1),
  total = ref(0),
  loading = ref(false),
  error = ref(""),
  stats = ref<Row>({});
const filters = reactive({
  status: "OPEN",
  deadline: route.query.overdue === "true" ? "OVERDUE" : "ALL",
  ownerId: String(route.query.ownerId || ""),
  taskType: "",
  recipientId: String(route.query.recipientId || ""),
  noticeType: String(route.query.type || ""),
});
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
const admin = computed(() => auth.user?.roles?.includes("ADMIN"));
const states = [
  { code: "OPEN", name: "未完成" },
  { code: "PENDING", name: "待处理" },
  { code: "PROCESSING", name: "处理中" },
  { code: "COMPLETED", name: "已完成" },
  { code: "CANCELLED", name: "已取消" },
  { code: "", name: "全部状态" },
];
function filterChanged() {
  page.value = 1;
  load();
  loadStats();
}
function resetFilters() {
  Object.assign(filters, {
    status: "OPEN",
    deadline: "ALL",
    ownerId: "",
    taskType: "",
    noticeType: "",
    recipientId: "",
  });
  filterChanged();
}
function nextStep(row: Row) {
  if (row.status === "COMPLETED") return "本次跟进已完成，可查看执行记录";
  if (row.status === "CANCELLED") return "任务已取消，可查看历史记录";
  return row.taskType === "PRE_VACCINE_REMINDER"
    ? "按约定日期再次通知，记录接种提醒结果"
    : row.taskType === "VERIFY_APPOINTMENT"
      ? "核实实际预约日期并更新老人档案"
      : "再次上门或联系家属，确认接收情况";
}
let loadEpoch = 0,
  statsEpoch = 0;
async function load() {
  const epoch = ++loadEpoch;
  loading.value = true;
  error.value = "";
  try {
    const r = await api(
      "/followups?" + query({ ...filters, page: page.value, pageSize: 20 }),
    );
    if (epoch !== loadEpoch) return;
    rows.value = r.items;
    total.value = Number(r.total);
  } catch (e) {
    if (epoch === loadEpoch) error.value = (e as Error).message;
  } finally {
    if (epoch === loadEpoch) loading.value = false;
  }
}
async function loadStats() {
  const epoch = ++statsEpoch;
  const scope = {
    noticeType: filters.noticeType,
    ownerId: filters.ownerId,
    taskType: filters.taskType,
    recipientId: filters.recipientId,
  };
  try {
    const [all, pending, completed, overdue] = await Promise.all([
      api("/followups?" + query({ ...scope, pageSize: 1 })),
      api("/followups?" + query({ ...scope, pageSize: 1, status: "OPEN" })),
      api(
        "/followups?" + query({ ...scope, pageSize: 1, status: "COMPLETED" }),
      ),
      api(
        "/followups?" + query({ ...scope, pageSize: 1, deadline: "OVERDUE" }),
      ),
    ]);
    if (epoch === statsEpoch)
      stats.value = {
        total: all.total,
        pending: pending.total,
        completed: completed.total,
        overdue: overdue.total,
      };
  } catch {
    if (epoch === statsEpoch) stats.value = {};
  }
}
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
    task.value.elderId = recipient.elderId;
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
    await loadStats();
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
  <div class="page-heading business-heading">
    <div>
      <h1>{{ admin ? "跟进进度 📈" : "服务跟进 🤝" }}</h1>
      <p>
        {{
          admin
            ? "查看各网格员的跟进完成情况及执行记录，优先检查逾期任务。"
            : "按期限处理本人未完成跟进，记录结果后系统同步更新通知状态。"
        }}
      </p>
    </div>
  </div>
  <section class="panel business-panel">
    <BusinessFilters
      :model="filters"
      :statuses="states"
      @change="filterChanged"
      @reset="resetFilters"
    >
      <el-form-item label="跟进类型"
        ><el-select
          v-model="filters.taskType"
          clearable
          placeholder="全部类型"
          @change="filterChanged"
          ><el-option
            v-for="t in [
              'RECONTACT',
              'VERIFY_APPOINTMENT',
              'PRE_VACCINE_REMINDER',
            ]"
            :key="t"
            :value="t"
            :label="label(t)" /></el-select
      ></el-form-item>
      <el-form-item label="截止范围"
        ><el-select v-model="filters.deadline" @change="filterChanged"
          ><el-option label="全部期限" value="ALL" /><el-option
            label="已逾期"
            value="OVERDUE" /><el-option
            label="今日到期"
            value="TODAY" /><el-option
            label="未来7天（含今日）"
            value="NEXT7" /></el-select
      ></el-form-item>
    </BusinessFilters>
    <WorkMetrics
      :items="[
        { label: '跟进任务', value: stats.total ?? '—' },
        { label: '未完成', value: stats.pending ?? '—' },
        { label: '已逾期', value: stats.overdue ?? '—', urgent: true },
        { label: '已完成', value: stats.completed ?? '—' },
      ]"
    />
    <div v-if="filters.recipientId" class="list-scope">
      <span>仅查看从联系清单进入的这位老人跟进记录</span
      ><el-button
        link
        @click="
          filters.recipientId = '';
          filterChanged();
        "
        >清除对象筛选</el-button
      >
    </div>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      class="mb"
    />
    <el-table
      class="desktop-work-table"
      v-loading="loading"
      :data="rows"
      empty-text="暂无符合条件的跟进任务"
    >
      <el-table-column label="通知事项" min-width="225"
        ><template #default="{ row }"
          ><strong class="work-title">{{ row.noticeTitle }}</strong
          ><small class="work-subtitle"
            >{{ label(row.noticeType) }} · {{ label(row.taskType) }}</small
          ></template
        ></el-table-column
      >
      <el-table-column label="服务对象" min-width="155"
        ><template #default="{ row }"
          ><strong>{{ row.elderName }}</strong
          ><small class="work-subtitle">{{ row.elderNo }}</small></template
        ></el-table-column
      >
      <el-table-column prop="assigneeName" label="负责人" min-width="100" />
      <el-table-column label="工作状态" min-width="100"
        ><template #default="{ row }"><Status :value="row.status" /></template
      ></el-table-column>
      <el-table-column label="下一步 / 截止时间" min-width="250"
        ><template #default="{ row }"
          ><span>{{ nextStep(row) }}</span
          ><small
            class="work-subtitle"
            :class="{ 'work-urgent': Number(row.overdue) > 0 }"
            >{{ Number(row.overdue) > 0 ? "已逾期 · " : ""
            }}{{ date(row.dueAt) }}</small
          ></template
        ></el-table-column
      >
      <el-table-column label="操作" min-width="215" fixed="right"
        ><template #default="{ row }"
          ><el-button
            v-if="
              auth.can('followup:update') &&
              ['PENDING', 'PROCESSING'].includes(row.status)
            "
            type="primary"
            @click="execute(row)"
            >记录跟进结果</el-button
          ><el-button link @click="show(row)">查看记录</el-button></template
        ></el-table-column
      >
    </el-table>
    <div class="mobile-work-list" v-loading="loading">
      <article v-for="row in rows" :key="row.id">
        <small>{{ label(row.noticeType) }} · {{ label(row.taskType) }}</small>
        <h3>{{ row.noticeTitle }}</h3>
        <p>{{ row.elderName }} · 负责人 {{ row.assigneeName }}</p>
        <Status :value="row.status" />
        <p>{{ nextStep(row) }}</p>
        <p :class="{ 'work-urgent': Number(row.overdue) > 0 }">
          {{ Number(row.overdue) > 0 ? "已逾期 · " : "" }}{{ date(row.dueAt) }}
        </p>
        <div>
          <el-button
            v-if="
              auth.can('followup:update') &&
              ['PENDING', 'PROCESSING'].includes(row.status)
            "
            type="primary"
            @click="execute(row)"
            >记录跟进结果</el-button
          ><el-button @click="show(row)">查看记录</el-button>
        </div>
      </article>
      <p v-if="!rows.length && !loading" class="hint">暂无符合条件的跟进任务</p>
    </div>
    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="20"
        :total="total"
        layout="total,prev,pager,next"
        @current-change="load"
      />
    </div>
    <p class="table-note">
      汇总按业务、负责人及跟进类型统计；工作状态和截止范围用于筛选下方任务。已完成记录保留执行历史。
    </p>
  </section>
  <el-dialog v-model="open" title="记录本次跟进" width="720px" destroy-on-close
    ><p class="hint">
      {{ task.noticeTitle }} · {{ task.elderName }}（{{ task.elderNo }}） ·
      经办人：{{ task.assigneeName }} ·
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
    /><WorkContact :elder-id="task.elderId" /><el-form label-position="top"
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
      ><el-descriptions-item label="通知事项">{{
        detail.noticeTitle
      }}</el-descriptions-item
      ><el-descriptions-item label="服务对象"
        >{{ detail.elderName }}（{{ detail.elderNo }}）</el-descriptions-item
      ><el-descriptions-item label="负责人">{{
        detail.assigneeName
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
        ><el-table-column label="处理结果" min-width="130"
          ><template #default="{ row }">{{
            label(row.outcome)
          }}</template></el-table-column
        ><el-table-column
          prop="resultText"
          label="执行说明"
          min-width="250"
        /><el-table-column label="下次期限" min-width="170"
          ><template #default="{ row }">{{
            row.nextDueAt ? date(row.nextDueAt) : "—"
          }}</template></el-table-column
        ></el-table
      >
    </div></el-dialog
  >
</template>
