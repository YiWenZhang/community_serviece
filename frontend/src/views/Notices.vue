<script setup lang="ts">
import { useRoute, useRouter } from "vue-router";
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import { useAuth } from "../stores";
import {
  api,
  query,
  date,
  fail,
  label,
  idempotency,
  download,
  type Row,
} from "../api";
import Status from "../components/Status.vue";
import AttemptFields from "../components/AttemptFields.vue";
import WorkContact from "../components/WorkContact.vue";
import BusinessFilters from "../components/BusinessFilters.vue";
import WorkMetrics from "../components/WorkMetrics.vue";
const route = useRoute(),
  router = useRouter();
const workStates = [
  { code: "TODO", name: "待处理" },
  { code: "FIRST", name: "待首次联系" },
  { code: "RETRY", name: "未联系上" },
  { code: "OVERDUE", name: "已逾期" },
  { code: "CLOSED", name: "已结束" },
  { code: "ALL", name: "全部记录" },
];
const filters = reactive<Row>({
  noticeType: String(route.query.type || ""),
  status: String(route.query.workState || "TODO"),
  ownerId: String(route.query.ownerId || ""),
});
const business = computed({
    get: () => filters.noticeType,
    set: (v) => (filters.noticeType = v),
  }),
  workState = computed({
    get: () => filters.status,
    set: (v) => (filters.status = v),
  }),
  noticeFilter = ref(String(route.query.noticeId || "")),
  itemStatus = ref(String(route.query.status || ""));
const admin = computed(() => auth.user?.roles?.includes("ADMIN"));
function filterChanged() {
  page.value = 1;
  load();
  loadStats();
}
function resetFilters() {
  Object.assign(filters, { noticeType: "", status: "TODO", ownerId: "" });
  noticeFilter.value = "";
  itemStatus.value = "";
  filterChanged();
}
function nextStep(row: Row) {
  if (row.noticeStatus === "DRAFT") return "尚未发布，请先发布通知事项";
  if (!["OPEN", "FOLLOWING"].includes(row.workflowStatus))
    return row.receiptStatus === "REFUSED"
      ? "已记录拒绝，尊重本人意愿"
      : "本次工作已结束";
  if (row.nextTaskId)
    return (
      (row.receiptStatus === "CONFIRMED"
        ? "已确认初次通知，继续处理后续提醒"
        : "请按期再次联系或核实") +
      (row.nextDueAt ? " · " + date(row.nextDueAt) : "")
    );
  return row.receiptStatus === "UNREACHABLE"
    ? "再次联系，记录本次结果"
    : "请上门或联系家属，记录实际结果";
}
function openContacts(row: Row) {
  noticeFilter.value = row.id;
  tab.value = "recipients";
  workState.value = "ALL";
  page.value = 1;
  load();
}
function handle(row: Row) {
  if (row.nextTaskId)
    router.push({ path: "/followups", query: { recipientId: row.id } });
  else openAttempt(row);
}
function statusName(status: string) {
  return (
    (
      {
        DRAFT: "待发布",
        ACTIVE: "执行中",
        COMPLETED: "已完成",
        CANCELLED: "已取消",
      } as Row
    )[status] || status
  );
}
const auth = useAuth(),
  tab = ref(route.query.view === "items" ? "batches" : "recipients"),
  batches = ref<Row[]>([]),
  recipients = ref<Row[]>([]),
  page = ref(1),
  total = ref(0),
  loading = ref(false),
  error = ref(""),
  saving = ref(false),
  stats = ref<Row>({});
const createOpen = ref(false),
  elderOptions = ref<Row[]>([]),
  draft = reactive<Row>({
    type: "BIRTHDAY",
    title: "",
    content: "",
    elderIds: [],
    campaignId: null,
    periodKey: "",
  });
const detailOpen = ref(false),
  detail = ref<Row>({}),
  detailKind = ref("recipient");
const attemptOpen = ref(false),
  target = ref<Row>({}),
  attempt = ref<Row>({}),
  requestKey = ref("");
const feedbackOpen = ref(false),
  feedback = reactive<Row>({
    revision: 0,
    rating: "SATISFIED",
    respondentType: "ELDER",
    text: "",
  });
let loadEpoch = 0,
  statsEpoch = 0;
async function load() {
  const epoch = ++loadEpoch;
  loading.value = true;
  error.value = "";
  try {
    const result = await api(
      (tab.value === "batches" ? "/notices?" : "/notice-recipients?") +
        query({
          page: page.value,
          pageSize: 20,
          noticeType: business.value,
          workState: tab.value === "recipients" ? workState.value : null,
          noticeId: tab.value === "recipients" ? noticeFilter.value : null,
          status: tab.value === "batches" ? itemStatus.value : null,
          ownerId: filters.ownerId,
        }),
    );
    if (epoch !== loadEpoch) return;
    if (
      tab.value === "recipients" &&
      result.items.length &&
      !result.items[0].noticeTitle
    )
      throw Error("界面已更新，请停止旧后端后重新构建并启动，以加载工作清单。");
    if (tab.value === "batches") batches.value = result.items;
    else recipients.value = result.items;
    total.value = Number(result.total);
  } catch (e) {
    if (epoch === loadEpoch) error.value = (e as Error).message;
  } finally {
    if (epoch === loadEpoch) loading.value = false;
  }
}
async function loadStats() {
  const epoch = ++statsEpoch;
  try {
    const states = ["TODO", "FIRST", "RETRY", "OVERDUE", "CLOSED"];
    const results = await Promise.all(
      states.map((workState) =>
        api(
          "/notice-recipients?" +
            query({
              pageSize: 1,
              workState,
              noticeType: business.value,
              ownerId: filters.ownerId,
            }),
        ),
      ),
    );
    if (epoch !== statsEpoch) return;
    if (results.some((r) => r.items.length && !r.items[0].noticeTitle)) {
      stats.value = {};
      return;
    }
    stats.value = Object.fromEntries(
      states.map((state, i) => [state, Number(results[i].total)]),
    );
  } catch {
    /* 统计卡片失败不阻断主列表 */
  }
}
async function create() {
  try {
    const result = await api("/elders?pageSize=100");
    elderOptions.value = result.items;
    Object.assign(draft, {
      type: business.value || "BIRTHDAY",
      title: "",
      content: "",
      elderIds: [],
      campaignId: null,
      periodKey: new Date().toLocaleDateString("sv-SE").slice(0, 7),
    });
    createOpen.value = true;
  } catch (e) {
    fail(e);
  }
}
async function saveDraft(publish = false) {
  if (saving.value) return;
  if (
    !draft.title.trim() ||
    !draft.content.trim() ||
    !draft.elderIds.length ||
    !draft.periodKey
  )
    return ElMessage.warning("请填写标题、内容、月份并选择老人");
  saving.value = true;
  try {
    const created = await api("/notices", "POST", {
      ...draft,
      campaignId: draft.type === "VACCINE" ? draft.campaignId : null,
    });
    if (publish) {
      try {
        await api(`/notices/${created.id}/activate`, "POST", { version: 0 });
      } catch (e) {
        createOpen.value = false;
        tab.value = "batches";
        itemStatus.value = "DRAFT";
        page.value = 1;
        await load();
        ElMessage.warning(
          "草稿已保存但未发布，请在通知事项中检查后发布，不必重复创建。",
        );
        fail(e);
        return;
      }
    }
    ElMessage.success(
      publish
        ? "已发布，请在待办联系中处理名单"
        : "草稿已保存，请发布后再安排联系",
    );
    createOpen.value = false;
    tab.value = publish ? "recipients" : "batches";
    noticeFilter.value = "";
    workState.value = "TODO";
    itemStatus.value = publish ? "" : "DRAFT";
    page.value = 1;
    await load();
    await loadStats();
  } catch (e) {
    fail(e);
  } finally {
    saving.value = false;
  }
}
async function activate(row: Row) {
  try {
    await ElMessageBox.confirm(
      "发布后，负责网格员将看到联系名单；仍需实际告知老人并记录结果。",
      "发布通知",
      { type: "info", confirmButtonText: "确认发布", cancelButtonText: "取消" },
    );
    await api(`/notices/${row.id}/activate`, "POST", { version: row.version });
    ElMessage.success("通知已发布，工作人员现在可以开始联系");
    await load();
    await loadStats();
  } catch (e) {
    if (e !== "cancel" && e !== "close") fail(e);
  }
}
async function cancel(row: Row) {
  try {
    const result = await ElMessageBox.prompt("请填写取消原因", "取消通知", {
      inputPattern: /\S/,
      inputErrorMessage: "请填写原因",
      confirmButtonText: "确认取消",
      cancelButtonText: "返回",
    });
    await api(`/notices/${row.id}/cancel`, "POST", {
      version: row.version,
      reason: result.value,
    });
    ElMessage.success("已取消");
    await load();
    await loadStats();
  } catch (e) {
    if (e !== "cancel" && e !== "close") fail(e);
  }
}
async function show(row: Row, kind: string) {
  try {
    detail.value = await api(
      kind === "batch" ? `/notices/${row.id}` : `/notice-recipients/${row.id}`,
    );
    if (kind !== "batch")
      detail.value.smsJobs = await api(`/notice-recipients/${row.id}/sms`);
    detailKind.value = kind;
    detailOpen.value = true;
  } catch (e) {
    fail(e);
  }
}
async function sendSms(row: Row) {
  try {
    await ElMessageBox.confirm(
      "短信将发送至老人档案中已备案且获授权的家属手机；平台受理不等于本人确认接收。是否提交？",
      "发送服务短信",
    );
    const r = await api(
      `/notice-recipients/${row.id}/sms`,
      "POST",
      { version: row.version },
      idempotency(),
    );
    ElMessage.success(`短信已排队，作业编号${r.id}；可在记录中查看发送状态`);
  } catch (e) {
    if (e !== "cancel" && e !== "close") fail(e);
  }
}
async function openAttempt(row: Row) {
  try {
    target.value = await api(`/notice-recipients/${row.id}`);
    attempt.value = {
      version: target.value.version,
      channel: "HOME_VISIT",
      result: "UNREACHABLE",
      unreachableReason: "NO_ONE_HOME",
      reasonText: null,
      feedbackQuote: null,
      familyNameMasked: null,
      familyRelation: null,
      relayConfirmed: null,
      fileIds: [],
    };
    requestKey.value = idempotency();
    attemptOpen.value = true;
  } catch (e) {
    fail(e);
  }
}
function normalizedAttempt() {
  return {
    ...attempt.value,
    unreachableReason:
      attempt.value.result === "UNREACHABLE"
        ? attempt.value.unreachableReason
        : null,
  };
}
async function saveAttempt() {
  if (saving.value) return;
  saving.value = true;
  try {
    await api(
      `/notice-recipients/${target.value.id}/attempts`,
      "POST",
      normalizedAttempt(),
      requestKey.value,
    );
    ElMessage.success("触达结果已保存，相关跟进同步更新");
    attemptOpen.value = false;
    await load();
    await loadStats();
  } catch (e) {
    fail(e);
  } finally {
    saving.value = false;
  }
}
async function openFeedback(row: Row) {
  try {
    target.value = await api(`/notice-recipients/${row.id}`);
    const last = target.value.feedback?.[0];
    Object.assign(feedback, {
      revision: Number(last?.revisionNo || 0),
      rating: last?.rating || "SATISFIED",
      respondentType: last?.respondentType || "ELDER",
      text: last?.feedbackText || "",
    });
    feedbackOpen.value = true;
  } catch (e) {
    fail(e);
  }
}
async function saveFeedback() {
  saving.value = true;
  try {
    await api(`/notice-recipients/${target.value.id}/feedback`, "POST", {
      ...feedback,
    });
    ElMessage.success("反馈已记录，原版本会保留");
    feedbackOpen.value = false;
  } catch (e) {
    fail(e);
  } finally {
    saving.value = false;
  }
}
async function reopen(row: Row) {
  try {
    const input = await ElMessageBox.prompt(
      "请说明重开原因，原联系历史会保留。",
      "重开服务",
      { inputPattern: /\S/, inputErrorMessage: "请填写原因" },
    );
    const latest = await api(`/notice-recipients/${row.id}`);
    await api(`/notice-recipients/${row.id}/reopen`, "POST", {
      version: latest.version,
      reason: input.value,
    });
    ElMessage.success("服务已重新开启");
    await load();
    await loadStats();
  } catch (e) {
    if (e !== "cancel" && e !== "close") fail(e);
  }
}
async function readEvidence(id: string) {
  try {
    await download(id, `通知凭证-${id}.png`);
  } catch (e) {
    fail(e);
  }
}
watch(
  () => route.query,
  (q) => {
    business.value = String(q.type || "");
    filters.ownerId = String(q.ownerId || "");
    workState.value = String(q.workState || "TODO");
    noticeFilter.value = String(q.noticeId || "");
    tab.value = q.view === "items" ? "batches" : "recipients";
    itemStatus.value = String(q.status || "");
    page.value = 1;
    load();
    loadStats();
  },
);
onMounted(async () => {
  try {
    await auth.loadDictionaries();
  } catch (e) {
    fail(e);
  }
  await load();
  await loadStats();
});
</script>
<template>
  <div class="page-heading business-heading">
    <div>
      <h1>{{ admin ? "通知进度" : "通知办理" }}</h1>
      <p>
        {{
          admin
            ? "按网格员监督联系结果与闭环情况；管理员不代办业务。"
            : "查看本人负责的通知，实际联系后登记结果。"
        }}
      </p>
    </div>
    <el-button
      v-if="auth.can('notice:create')"
      type="primary"
      :icon="Plus"
      @click="create"
      >新建通知</el-button
    >
  </div>
  <section class="panel business-panel">
    <el-tabs
      v-model="tab"
      @tab-change="
        page = 1;
        load();
      "
    >
      <el-tab-pane
        :label="admin ? '逐人联系进度' : '联系工作清单'"
        name="recipients"
      />
      <el-tab-pane label="通知事项" name="batches" />
    </el-tabs>
    <BusinessFilters
      :model="filters"
      :statuses="tab === 'recipients' ? workStates : undefined"
      @change="filterChanged"
      @reset="resetFilters"
    >
      <el-form-item v-if="tab === 'batches'" label="事项状态"
        ><el-select
          v-model="itemStatus"
          clearable
          placeholder="全部事项"
          @change="filterChanged"
          ><el-option label="待发布" value="DRAFT" /><el-option
            label="执行中"
            value="ACTIVE" /><el-option
            label="已完成"
            value="COMPLETED" /><el-option
            label="已取消"
            value="CANCELLED" /></el-select
      ></el-form-item>
    </BusinessFilters>
    <WorkMetrics
      :items="[
        { label: '未完成联系', value: stats.TODO ?? '—' },
        { label: '待首次联系', value: stats.FIRST ?? '—' },
        { label: '未联系上', value: stats.RETRY ?? '—' },
        { label: '已逾期', value: stats.OVERDUE ?? '—', urgent: true },
        { label: '已结束', value: stats.CLOSED ?? '—' },
      ]"
    />
    <div v-if="noticeFilter && tab === 'recipients'" class="list-scope">
      <span>当前仅查看选中通知的联系记录</span
      ><el-button
        link
        @click="
          noticeFilter = '';
          filterChanged();
        "
        >清除事项筛选</el-button
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
      v-if="tab === 'recipients'"
      class="desktop-work-table"
      v-loading="loading"
      :data="recipients"
      empty-text="当前条件下没有待处理联系，可切换业务或查看全部记录。"
    >
      <el-table-column label="通知事项" min-width="235"
        ><template #default="{ row }"
          ><strong class="work-title">{{
            row.noticeTitle || "请更新后端以显示通知主题"
          }}</strong
          ><small class="work-subtitle">{{
            label(row.noticeType)
          }}</small></template
        ></el-table-column
      >
      <el-table-column label="服务对象" min-width="155"
        ><template #default="{ row }"
          ><strong>{{ row.elderName || "姓名待补录" }}</strong
          ><small class="work-subtitle">{{ row.elderNo }}</small></template
        ></el-table-column
      >
      <el-table-column prop="ownerName" label="负责人" min-width="100" />
      <el-table-column label="接收与服务状态" min-width="125"
        ><template #default="{ row }"
          ><Status :value="row.receiptStatus" /><small class="work-subtitle">{{
            row.noticeStatus === "DRAFT"
              ? "尚未发布"
              : label(row.workflowStatus)
          }}</small></template
        ></el-table-column
      >
      <el-table-column label="下一步 / 截止时间" min-width="250"
        ><template #default="{ row }"
          ><span
            :class="{
              'work-urgent':
                row.nextDueAt &&
                new Date(row.nextDueAt).getTime() < Date.now() &&
                ['OPEN', 'FOLLOWING'].includes(row.workflowStatus),
            }"
            >{{ nextStep(row) }}</span
          ></template
        ></el-table-column
      >
      <el-table-column label="操作" min-width="230" class-name="actions-column"
        ><template #default="{ row }"
          ><el-button
            v-if="
              auth.can('notice:receive') &&
              row.noticeStatus === 'ACTIVE' &&
              ['OPEN', 'FOLLOWING'].includes(row.workflowStatus)
            "
            type="primary"
            @click="handle(row)"
            >{{ row.nextTaskId ? "处理跟进" : "记录联系结果" }}</el-button
          ><el-button link @click="show(row, 'recipient')">查看详情</el-button
          ><el-dropdown
            v-if="auth.can('notice:receive') || auth.can('feedback:create')"
            ><el-button link>更多</el-button
            ><template #dropdown
              ><el-dropdown-menu
                ><el-dropdown-item
                  v-if="
                    auth.can('notice:receive') &&
                    row.noticeStatus === 'ACTIVE' &&
                    ['OPEN', 'FOLLOWING'].includes(row.workflowStatus)
                  "
                  @click="sendSms(row)"
                  >发送备案短信</el-dropdown-item
                ><el-dropdown-item
                  v-if="auth.can('feedback:create')"
                  @click="openFeedback(row)"
                  >记录满意度</el-dropdown-item
                ><el-dropdown-item
                  v-if="
                    auth.can('notice:receive') &&
                    ['CLOSED', 'CANCELLED'].includes(row.workflowStatus)
                  "
                  @click="reopen(row)"
                  >重新安排联系</el-dropdown-item
                ></el-dropdown-menu
              ></template
            ></el-dropdown
          ></template
        ></el-table-column
      >
    </el-table>
    <el-table
      v-else
      v-loading="loading"
      :data="batches"
      empty-text="当前条件下暂无通知事项"
    >
      <el-table-column label="通知事项" min-width="260"
        ><template #default="{ row }"
          ><strong class="work-title">{{ row.title }}</strong
          ><small class="work-subtitle"
            >{{ label(row.type) }} · {{ row.periodKey }} ·
            {{ row.source === "RULE" ? "按条件自动安排" : "人工安排" }}</small
          ></template
        ></el-table-column
      >
      <el-table-column prop="organizerName" label="安排人" min-width="100" />
      <el-table-column label="执行进度" min-width="210"
        ><template #default="{ row }"
          ><strong
            >已确认 {{ row.contactConfirmed }} /
            {{ row.contactTotal }} 人次</strong
          ><small class="work-subtitle">{{
            row.status === "DRAFT"
              ? "尚未发布，名单不能开始执行"
              : "仍有 " + row.contactOpen + " 条未结束工作"
          }}</small></template
        ></el-table-column
      >
      <el-table-column label="状态" width="110"
        ><template #default="{ row }">{{
          statusName(row.status)
        }}</template></el-table-column
      >
      <el-table-column
        label="下一步"
        min-width="265"
        class-name="actions-column"
        ><template #default="{ row }"
          ><el-button
            v-if="auth.can('notice:create') && row.status === 'DRAFT'"
            type="primary"
            @click="activate(row)"
            >发布并安排联系</el-button
          ><el-button v-else type="primary" @click="openContacts(row)"
            >查看联系名单</el-button
          ><el-button link @click="show(row, 'batch')">通知内容</el-button
          ><el-button
            v-if="
              auth.can('notice:create') &&
              ['DRAFT', 'ACTIVE'].includes(row.status)
            "
            link
            type="danger"
            @click="cancel(row)"
            >取消事项</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <div
      v-if="tab === 'recipients'"
      class="mobile-work-list"
      v-loading="loading"
    >
      <article v-for="row in recipients" :key="row.id">
        <small>{{ label(row.noticeType) }}</small>
        <h3>{{ row.noticeTitle }}</h3>
        <p>
          <strong>{{ row.elderName }}</strong> · 负责人 {{ row.ownerName }}
        </p>
        <Status :value="row.receiptStatus" />
        <p>{{ nextStep(row) }}</p>
        <div>
          <el-button
            v-if="
              auth.can('notice:receive') &&
              row.noticeStatus === 'ACTIVE' &&
              ['OPEN', 'FOLLOWING'].includes(row.workflowStatus)
            "
            type="primary"
            @click="handle(row)"
            >{{
              row.nextTaskId ? "处理跟进" : "记录联系结果"
            }}</el-button><el-button @click="show(row, 'recipient')"
            >查看详情</el-button
          ><el-dropdown
            v-if="auth.can('notice:receive') || auth.can('feedback:create')"
            ><el-button link>更多</el-button
            ><template #dropdown
              ><el-dropdown-menu
                ><el-dropdown-item
                  v-if="
                    auth.can('notice:receive') &&
                    row.noticeStatus === 'ACTIVE' &&
                    ['OPEN', 'FOLLOWING'].includes(row.workflowStatus)
                  "
                  @click="sendSms(row)"
                  >发送备案短信</el-dropdown-item
                ><el-dropdown-item
                  v-if="auth.can('feedback:create')"
                  @click="openFeedback(row)"
                  >记录满意度</el-dropdown-item
                ><el-dropdown-item
                  v-if="
                    auth.can('notice:receive') &&
                    ['CLOSED', 'CANCELLED'].includes(row.workflowStatus)
                  "
                  @click="reopen(row)"
                  >重新安排联系</el-dropdown-item
                ></el-dropdown-menu
              ></template
            ></el-dropdown
          >
        </div>
      </article>
      <p v-if="!recipients.length && !loading" class="hint">
        当前没有待处理联系，可切换业务或查看全部记录。
      </p>
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
    <p class="hint">
      这里的数量按“某次通知联系某位老人”计算；同一老人可能有多项工作。发布通知和发送短信都不代表本人已确认接收。
    </p>
  </section>
  <el-dialog
    v-model="createOpen"
    title="新建服务通知"
    width="690px"
    destroy-on-close
    ><el-form label-position="top"
      ><div class="form-grid">
        <el-form-item label="通知类型"
          ><el-select v-model="draft.type"
            ><el-option
              v-for="t in ['BIRTHDAY', 'VACCINE', 'LECTURE', 'OTHER']"
              :key="t"
              :label="label(t)"
              :value="t" /></el-select></el-form-item
        ><el-form-item label="服务月份 *"
          ><el-date-picker
            v-model="draft.periodKey"
            type="month"
            value-format="YYYY-MM" /></el-form-item
        ><el-form-item
          v-if="draft.type === 'VACCINE'"
          label="疫苗周期 *"
          class="span-2"
          ><el-select v-model="draft.campaignId"
            ><el-option
              v-for="c in auth.dictionaries.vaccineCampaigns"
              :key="c.id"
              :label="c.name"
              :value="c.id" /></el-select></el-form-item
        ><el-form-item label="通知标题 *" class="span-2"
          ><el-input v-model="draft.title" maxlength="120" /></el-form-item
        ><el-form-item label="这次需要联系哪些老人 *" class="span-2"
          ><el-select
            v-model="draft.elderIds"
            multiple
            filterable
            placeholder="选择当前权限范围内的老人"
            ><el-option
              v-for="e in elderOptions"
              :key="e.id"
              :label="`${e.name || '待补录'}（${e.elderNo}）`"
              :value="e.id" /></el-select
          ><small class="selection-help"
            >名单来自当前社区可见档案；发布后还需工作人员实际联系。</small
          ></el-form-item
        ><el-form-item label="通知内容 *" class="span-2"
          ><el-input
            v-model="draft.content"
            type="textarea"
            :rows="4"
            maxlength="5000"
        /></el-form-item></div></el-form
    ><template #footer
      ><el-button @click="createOpen = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="saveDraft(false)"
        >保存草稿</el-button
      ><el-button type="primary" :loading="saving" @click="saveDraft(true)"
        >发布并安排联系</el-button
      ></template
    ></el-dialog
  >
  <el-dialog
    v-model="attemptOpen"
    title="记录实际联系结果"
    width="690px"
    destroy-on-close
    ><p class="hint">
      {{ target.noticeTitle }} · {{ target.elderName }}（{{ target.elderNo }}）
      · 负责人：{{ target.ownerName }}。请填写刚刚发生的联系情况。
    </p>
    <WorkContact :elder-id="target.elderId" /><el-form label-position="top"
      ><AttemptFields v-model="attempt" /></el-form
    ><template #footer
      ><el-button @click="attemptOpen = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="saveAttempt"
        >保存联系结果</el-button
      ></template
    ></el-dialog
  >
  <el-dialog
    v-model="feedbackOpen"
    title="满意度反馈"
    width="500px"
    destroy-on-close
    ><el-form label-position="top"
      ><el-form-item label="满意度"
        ><el-radio-group v-model="feedback.rating"
          ><el-radio value="SATISFIED">满意</el-radio
          ><el-radio value="NEUTRAL">一般</el-radio
          ><el-radio value="DISSATISFIED">不满意</el-radio></el-radio-group
        ></el-form-item
      ><el-form-item label="反馈人"
        ><el-select v-model="feedback.respondentType"
          ><el-option label="老人本人" value="ELDER" /><el-option
            label="家属"
            value="FAMILY" /></el-select></el-form-item
      ><el-form-item label="反馈说明"
        ><el-input
          v-model="feedback.text"
          type="textarea"
          maxlength="500" /></el-form-item></el-form
    ><template #footer
      ><el-button @click="feedbackOpen = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="saveFeedback"
        >记录反馈</el-button
      ></template
    ></el-dialog
  >
  <el-dialog
    v-model="detailOpen"
    :title="detailKind === 'batch' ? '通知事项详情' : '服务记录'"
    width="850px"
    destroy-on-close
    ><template v-if="detailKind === 'batch'"
      ><el-descriptions :column="2" border
        ><el-descriptions-item label="标题">{{
          detail.title
        }}</el-descriptions-item
        ><el-descriptions-item label="状态">{{
          label(detail.status)
        }}</el-descriptions-item
        ><el-descriptions-item label="内容" :span="2">{{
          detail.content
        }}</el-descriptions-item></el-descriptions
      >
      <div class="detail-section">
        <h3>当前可见的联系名单</h3>
        <el-table :data="detail.recipients || []"
          ><el-table-column prop="id" label="对象编号" /><el-table-column
            prop="elderName"
            label="老人姓名" /><el-table-column label="接收状态"
            ><template #default="{ row }"
              ><Status :value="row.receiptStatus" /></template></el-table-column
        ></el-table></div
    ></template>
    <template v-else
      ><div class="work-detail-context">
        <h3>{{ detail.noticeTitle }}</h3>
        <p>{{ detail.noticeContent }}</p>
        <p>
          联系老人：{{ detail.elderName }} · {{ detail.elderNo }}　负责人：{{
            detail.ownerName
          }}
        </p>
      </div>
      <el-descriptions :column="2" border
        ><el-descriptions-item label="对象编号">{{
          detail.id
        }}</el-descriptions-item
        ><el-descriptions-item label="服务状态">{{
          label(detail.workflowStatus)
        }}</el-descriptions-item
        ><el-descriptions-item label="接收结果">{{
          label(detail.receiptStatus)
        }}</el-descriptions-item
        ><el-descriptions-item label="最后触达">{{
          date(detail.lastAttemptAt)
        }}</el-descriptions-item></el-descriptions
      >
      <div class="detail-section">
        <h3>联系历史</h3>
        <el-table :data="detail.attempts || []"
          ><el-table-column label="触达时间" min-width="170"
            ><template #default="{ row }">{{
              date(row.attemptedAt)
            }}</template></el-table-column
          ><el-table-column label="方式" width="105"
            ><template #default="{ row }">{{
              label(row.channel)
            }}</template></el-table-column
          ><el-table-column label="结果" width="120"
            ><template #default="{ row }"
              ><Status :value="row.result" /></template></el-table-column
          ><el-table-column
            prop="familyName"
            label="转告家属"
            min-width="100"
          /><el-table-column
            prop="feedbackQuote"
            label="客观反馈"
            min-width="140"
          /><el-table-column label="凭证" width="75"
            ><template #default="{ row }"
              ><el-button
                v-for="f in row.attachments || []"
                :key="f.fileId"
                link
                type="primary"
                @click="readEvidence(f.fileId)"
                >下载</el-button
              ></template
            ></el-table-column
          ></el-table
        >
      </div>
      <div class="detail-section">
        <h3>短信发送作业</h3>
        <el-table :data="detail.smsJobs || []" empty-text="暂无短信发送"
          ><el-table-column prop="id" label="作业编号" /><el-table-column
            prop="status"
            label="状态" /><el-table-column
            prop="errorSummary"
            label="处理说明"
        /></el-table>
        <p class="hint">
          COMPLETED表示平台受理；PENDING/PROCESSING为处理中，FAILED表示未完成。发送不改变本人确认接收状态。
        </p>
      </div>
      <div class="detail-section">
        <h3>满意度历史</h3>
        <el-table :data="detail.feedback || []" empty-text="暂无反馈"
          ><el-table-column
            prop="revisionNo"
            label="版本"
            width="80" /><el-table-column label="评价" width="100"
            ><template #default="{ row }">{{
              label(row.rating)
            }}</template></el-table-column
          ><el-table-column prop="feedbackText" label="反馈内容"
        /></el-table></div></template
  ></el-dialog>
</template>
