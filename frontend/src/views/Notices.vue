<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  Plus,
  Bell,
  Timer,
  CircleCheckFilled,
  WarningFilled,
  CircleCloseFilled,
  ArrowUp,
} from "@element-plus/icons-vue";
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
import StatCard from "../components/StatCard.vue";
const auth = useAuth(),
  tab = ref("recipients"),
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
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const result = await api(
      (tab.value === "batches" ? "/notices?" : "/notice-recipients?") +
        query({ page: page.value, pageSize: 20 }),
    );
    if (tab.value === "batches") batches.value = result.items;
    else recipients.value = result.items;
    total.value = Number(result.total);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
async function loadStats() {
  try {
    const [all, pending, confirmed, unreachable, refused] = await Promise.all([
      api("/notice-recipients?pageSize=1"),
      api("/notice-recipients?receiptStatus=PENDING&pageSize=1"),
      api("/notice-recipients?receiptStatus=CONFIRMED&pageSize=1"),
      api("/notice-recipients?receiptStatus=UNREACHABLE&pageSize=1"),
      api("/notice-recipients?receiptStatus=REFUSED&pageSize=1"),
    ]);
    stats.value = {
      total: Number(all.total),
      pending: Number(pending.total),
      confirmed: Number(confirmed.total),
      unreachable: Number(unreachable.total),
      refused: Number(refused.total),
    };
  } catch {
    /* 统计卡片失败不阻断主列表 */
  }
}
const confirmRate = computed(() =>
  stats.value.total
    ? `${((stats.value.confirmed / stats.value.total) * 100).toFixed(1)}%`
    : "—",
);
const refusedRate = computed(() =>
  stats.value.total
    ? `${((stats.value.refused / stats.value.total) * 100).toFixed(1)}%`
    : "—",
);
async function create() {
  try {
    const result = await api("/elders?pageSize=100");
    elderOptions.value = result.items;
    Object.assign(draft, {
      type: "BIRTHDAY",
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
async function saveDraft() {
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
    await api("/notices", "POST", {
      ...draft,
      campaignId: draft.type === "VACCINE" ? draft.campaignId : null,
    });
    ElMessage.success("通知草稿已创建，请激活后开展通知");
    createOpen.value = false;
    tab.value = "batches";
    page.value = 1;
    await load();
  } catch (e) {
    fail(e);
  } finally {
    saving.value = false;
  }
}
async function activate(row: Row) {
  try {
    await ElMessageBox.confirm(
      "激活后冻结通知内容和名单，并开始记录接收与跟进。",
      "激活通知",
      { type: "info", confirmButtonText: "确认激活", cancelButtonText: "取消" },
    );
    await api(`/notices/${row.id}/activate`, "POST", { version: row.version });
    ElMessage.success("通知已激活");
    await load();
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
  } catch (e) {
    if (e !== "cancel" && e !== "close") fail(e);
  }
}
async function show(row: Row, kind: string) {
  try {
    detail.value = await api(
      kind === "batch" ? `/notices/${row.id}` : `/notice-recipients/${row.id}`,
    );
    detailKind.value = kind;
    detailOpen.value = true;
  } catch (e) {
    fail(e);
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
      "请说明重开原因，原触达历史会保留。",
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
  <div class="page-heading">
    <div>
      <h1>通知管理</h1>
      <p>分别记录通知批次、每位老人的接收情况和每一次触达。</p>
    </div>
    <el-button
      v-if="auth.can('notice:create')"
      type="primary"
      :icon="Plus"
      @click="create"
      >新建通知</el-button
    >
  </div>
  <section class="stat-tiles">
    <StatCard
      label="通知总数"
      :value="stats.total ?? '—'"
      suffix="条"
      :icon="Bell"
      tone="blue"
      hint="本人负责通知对象"
    />
    <StatCard
      label="待发送"
      :value="stats.pending ?? '—'"
      suffix="条"
      :icon="Timer"
      tone="gold"
      hint="等待首次触达"
      hint-tone="warn"
    />
    <StatCard
      label="已确认接收"
      :value="stats.confirmed ?? '—'"
      suffix="条"
      :icon="CircleCheckFilled"
      tone="green"
      :hint="`接收率 ${confirmRate}`"
      hint-tone="up"
      :hint-icon="ArrowUp"
    />
    <StatCard
      label="未联系上"
      :value="stats.unreachable ?? '—'"
      suffix="条"
      :icon="WarningFilled"
      tone="red"
      hint="需跟进处理"
      hint-tone="danger"
    />
    <StatCard
      label="拒绝接收"
      :value="stats.refused ?? '—'"
      suffix="条"
      :icon="CircleCloseFilled"
      tone="gray"
      :hint="`占比 ${refusedRate}`"
    />
  </section>
  <section class="panel">
    <el-tabs
      v-model="tab"
      @tab-change="
        page = 1;
        load();
      "
      ><el-tab-pane label="接收对象" name="recipients" /><el-tab-pane
        label="通知批次"
        name="batches" /></el-tabs
    ><el-alert
      v-if="error"
      :title="error"
      type="error"
      class="mb"
      :closable="false"
    />
    <el-table
      v-if="tab === 'recipients'"
      v-loading="loading"
      :data="recipients"
      empty-text="暂无本人负责的通知对象"
      ><el-table-column prop="id" label="对象编号" width="90" /><el-table-column
        prop="noticeId"
        label="批次编号"
        width="100"
      /><el-table-column
        prop="elderName"
        label="老人姓名"
        min-width="115"
      /><el-table-column label="接收状态" min-width="120"
        ><template #default="{ row }"
          ><Status :value="row.receiptStatus" /></template></el-table-column
      ><el-table-column label="服务状态" min-width="100"
        ><template #default="{ row }"
          ><Status :value="row.workflowStatus" /></template></el-table-column
      ><el-table-column label="操作" min-width="240"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="show(row, 'recipient')"
            >记录</el-button
          ><el-button
            v-if="
              auth.can('notice:receive') &&
              ['OPEN', 'FOLLOWING'].includes(row.workflowStatus)
            "
            link
            type="primary"
            @click="openAttempt(row)"
            >登记触达</el-button
          ><el-button
            v-if="auth.can('feedback:create')"
            link
            type="primary"
            @click="openFeedback(row)"
            >满意度</el-button
          ><el-button
            v-if="
              auth.can('notice:receive') &&
              ['CLOSED', 'CANCELLED'].includes(row.workflowStatus)
            "
            link
            type="primary"
            @click="reopen(row)"
            >重开</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <el-table
      v-else
      v-loading="loading"
      :data="batches"
      empty-text="暂无通知批次"
      ><el-table-column prop="id" label="编号" width="75" /><el-table-column
        prop="title"
        label="通知标题"
        min-width="200"
      /><el-table-column label="通知类型" min-width="110"
        ><template #default="{ row }">{{
          label(row.type)
        }}</template></el-table-column
      ><el-table-column label="状态" width="100"
        ><template #default="{ row }"
          ><Status :value="row.status" /></template></el-table-column
      ><el-table-column label="操作" min-width="190"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="show(row, 'batch')"
            >详情</el-button
          ><el-button
            v-if="auth.can('notice:create') && row.status === 'DRAFT'"
            link
            type="primary"
            @click="activate(row)"
            >激活</el-button
          ><el-button
            v-if="
              auth.can('notice:create') &&
              ['DRAFT', 'ACTIVE'].includes(row.status)
            "
            link
            type="danger"
            @click="cancel(row)"
            >取消</el-button
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
  <el-dialog
    v-model="createOpen"
    title="新建通知草稿"
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
        ><el-form-item label="目标月份 *"
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
        ><el-form-item label="接收老人 *" class="span-2"
          ><el-select
            v-model="draft.elderIds"
            multiple
            filterable
            placeholder="选择本人负责的老人"
            ><el-option
              v-for="e in elderOptions"
              :key="e.id"
              :label="`${e.name || '待补录'}（${e.elderNo}）`"
              :value="e.id" /></el-select
          ><small class="selection-help"
            >选择器列出本人最近 100 条档案；通知只会发给选中的对象。</small
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
      ><el-button type="primary" :loading="saving" @click="saveDraft"
        >创建草稿</el-button
      ></template
    ></el-dialog
  >
  <el-dialog
    v-model="attemptOpen"
    title="登记触达结果"
    width="690px"
    destroy-on-close
    ><p class="hint">
      通知对象 #{{ target.id }} · 如实记录接收情况，通知和跟进会同步更新。
    </p>
    <el-form label-position="top"><AttemptFields v-model="attempt" /></el-form
    ><template #footer
      ><el-button @click="attemptOpen = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="saveAttempt"
        >保存触达记录</el-button
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
    :title="detailKind === 'batch' ? '通知批次详情' : '服务记录'"
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
        <h3>本人可见的接收对象</h3>
        <el-table :data="detail.recipients || []"
          ><el-table-column prop="id" label="对象编号" /><el-table-column
            prop="elderName"
            label="老人姓名" /><el-table-column label="接收状态"
            ><template #default="{ row }"
              ><Status :value="row.receiptStatus" /></template></el-table-column
        ></el-table></div></template
    ><template v-else
      ><el-descriptions :column="2" border
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
        <h3>触达历史</h3>
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
