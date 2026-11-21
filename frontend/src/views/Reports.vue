<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, reactive } from "vue";
import { ElMessage } from "element-plus";
import {
  Download,
  Bell,
  CircleCheckFilled,
  Star,
  Tickets,
  Timer,
  DataAnalysis,
  ArrowDown,
  ArrowUp,
} from "@element-plus/icons-vue";
import {
  api,
  query,
  fail,
  date,
  idempotency,
  download,
  type Row,
} from "../api";
import { useAuth } from "../stores";
import Status from "../components/Status.vue";
import BarChart from "../components/BarChart.vue";
import DonutChart from "../components/DonutChart.vue";
import StatCard from "../components/StatCard.vue";
import MetricBars from "../components/MetricBars.vue";
const auth = useAuth(),
  reportCommunity = ref(""),
  month = ref(new Date().toLocaleDateString("sv-SE").slice(0, 7)),
  data = ref<Row | null>(null),
  loading = ref(false),
  exporting = ref(false),
  error = ref(""),
  exports = ref<Row[]>([]),
  detailsOpen = ref(false),
  detailGroup = ref<MetricGroup | null>(null),
  detailOpen = ref(false);
const filters = reactive({ ownerId: "", noticeType: "", campaignId: "" }),
  dateMode = ref("month"),
  dateRange = ref<string[]>([]),
  workers = ref<Row[]>([]),
  campaigns = ref<Row[]>([]);
function reportQuery() {
  return {
    month: month.value,
    communityId: reportCommunity.value || null,
    ownerId: filters.ownerId || null,
    noticeType: filters.noticeType || null,
    campaignId: filters.campaignId || null,
    startDate: dateMode.value === "range" ? dateRange.value?.[0] : null,
    endDate: dateMode.value === "range" ? dateRange.value?.[1] : null,
  };
}
async function loadFilters() {
  try {
    const r = await api(
      "/reports/filters?" + query({ communityId: reportCommunity.value }),
    );
    workers.value = r.workers;
    campaigns.value = r.campaigns;
  } catch (e) {
    fail(e);
  }
}
async function communityChanged() {
  filters.ownerId = "";
  filters.campaignId = "";
  await loadFilters();
}
function dimensionText(value: string) {
  const parts = value.split(":");
  if (parts[0] === "CAMPAIGN") {
    const c = campaigns.value.find((c) => String(c.id) === parts[1]);
    return (
      (c?.name || "周期 " + parts[1]) +
      (parts[2] === "STATUS" ? " / " + statusText(parts[3]) : "")
    );
  }
  return dimensionNames[value] || statusText(value);
}
function statusText(value: string) {
  return (
    (
      {
        NOT_VACCINATED: "未接种",
        PARTIAL: "部分完成",
        COMPLETED: "已完成接种",
        UNKNOWN: "待核实",
        PENDING: "待联系",
        CONFIRMED: "已确认接收",
        UNREACHABLE: "未联系上",
        REFUSED: "拒绝接收",
      } as Row
    )[value] || value
  );
}
let alive = true;
const metricNames: Row = {
  NOTICE_COVERAGE: "通知覆盖率",
  SERVICE_CLOSURE: "服务闭环率",
  SATISFACTION: "满意度",
  FEEDBACK_RATE: "反馈率",
  VACCINATION_RATE: "疫苗接种率",
  FOLLOWUP_ON_TIME: "跟进按期完成率",
  CHANNEL_USAGE: "渠道使用率",
  NOTICE_STATUS_COUNT: "接收状态数量",
  VACCINE_STATUS_COUNT: "疫苗接种状态人数",
  ELIGIBILITY_UNKNOWN: "接种资格待核实人数",
};
const dimensionNames: Row = {
  HOME_VISIT: "上门告知",
  FAMILY_RELAY: "家属转告",
  BULLETIN: "公告栏张贴",
  SMS: "短信通知",
};
const percent = (v: any) =>
  v === null || v === undefined
    ? "无有效样本"
    : `${(Number(v) * 100).toFixed(2)}%`;
const shortMetricNames: Row = {
  NOTICE_COVERAGE: "通知覆盖",
  SERVICE_CLOSURE: "服务闭环",
  SATISFACTION: "满意度",
  FEEDBACK_RATE: "反馈率",
  FOLLOWUP_ON_TIME: "按期跟进",
};
const coreBars = computed<{ label: string; value: number }[]>(() =>
  [
    "NOTICE_COVERAGE",
    "SERVICE_CLOSURE",
    "SATISFACTION",
    "FEEDBACK_RATE",
    "FOLLOWUP_ON_TIME",
  ].map((code) => {
    const m = (data.value?.metrics || []).find(
      (x: Row) => x.code === code && x.dimension === "ALL",
    );
    return {
      label: shortMetricNames[code],
      value: m && m.value != null ? Number(m.value) * 100 : 0,
    };
  }),
);
const channelData = computed<{ label: string; value: number }[]>(() =>
  (data.value?.metrics || [])
    .filter((m: Row) => m.code === "CHANNEL_USAGE")
    .map((m: Row) => ({
      label: dimensionNames[m.dimension] || m.dimension,
      value: Number(m.numerator),
    })),
);
const channelTotal = computed(() =>
  channelData.value.reduce((sum, d) => sum + d.value, 0),
);
const coreCards = computed(() =>
  [
    { code: "NOTICE_COVERAGE", label: "通知覆盖率", tone: "brand", icon: Bell },
    {
      code: "SERVICE_CLOSURE",
      label: "服务闭环率",
      tone: "green",
      icon: CircleCheckFilled,
    },
    { code: "SATISFACTION", label: "满意度", tone: "gold", icon: Star },
    { code: "FEEDBACK_RATE", label: "反馈率", tone: "blue", icon: Tickets },
    {
      code: "FOLLOWUP_ON_TIME",
      label: "按期完成率",
      tone: "brand",
      icon: Timer,
    },
  ].map((def) => {
    const m = (data.value?.metrics || []).find(
      (x: Row) => x.code === def.code && x.dimension === "ALL",
    );
    return {
      ...def,
      value: m?.value == null ? "—" : percent(m.value),
      hint: m ? `分子 ${m.numerator} / 分母 ${m.denominator}` : "暂无有效样本",
    };
  }),
);
type MetricGroup = {
  code: string;
  title: string;
  single: boolean;
  value: any;
  numerator: any;
  denominator: any;
  items: { label: string; value: number; display: string }[];
};
const metricGroups = computed<MetricGroup[]>(() => {
  const map = new Map<string, Row[]>();
  for (const m of data.value?.metrics || []) {
    const code = String(m.code);
    if (!map.has(code)) map.set(code, []);
    map.get(code)!.push(m);
  }
  return Array.from(map.entries()).map(([code, metrics]) => {
    const single = metrics.length === 1 && metrics[0].dimension === "ALL";
    const items = metrics.map((m) => {
      const isCount = m.value == null;
      return {
        label:
          m.dimension && m.dimension !== "ALL"
            ? dimensionText(m.dimension)
            : "合计",
        value: Number(isCount ? m.numerator : Number(m.value) * 100) || 0,
        display: isCount ? String(m.numerator) : percent(m.value),
      };
    });
    const first = metrics[0];
    return {
      code,
      title: metricNames[code] || code,
      single,
      value: first.value,
      numerator: first.numerator,
      denominator: first.denominator,
      items,
    };
  });
});
function openDetail(group: MetricGroup) {
  detailGroup.value = group;
  detailOpen.value = true;
}
async function load() {
  if (!month.value) return;
  if (dateMode.value === "range" && dateRange.value?.length !== 2) {
    ElMessage.warning("请选择完整统计日期范围");
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    data.value = await api("/reports/metrics?" + query(reportQuery()));
    const list = await api("/report-jobs?pageSize=100");
    exports.value = list.items.map((r: Row) => ({
      ...r,
      fileId: r.outputFileId,
      month: r.periodStart?.slice(0, 7),
    }));
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
async function check(item: Row) {
  try {
    const r = await api(`/report-jobs/${item.id}`);
    Object.assign(item, {
      status: r.status,
      fileId: r.outputFileId,
      error: r.errorSummary,
    });
    return ["COMPLETED", "FAILED", "CANCELLED"].includes(r.status);
  } catch (e) {
    item.error = (e as Error).message;
    return true;
  }
}
async function exportReport(format: string) {
  if (exporting.value || !month.value) return;
  if (dateMode.value === "range" && dateRange.value?.length !== 2) {
    ElMessage.warning("请选择完整统计日期范围");
    return;
  }
  exporting.value = true;
  try {
    const r = await api(
      "/report-jobs",
      "POST",
      {
        query: reportQuery(),
        format,
      },
      idempotency(),
    );
    const item = {
      id: r.reportJobId,
      jobId: r.jobId,
      month: month.value,
      format,
      status: "PENDING",
      fileId: null,
      error: "",
    };
    exports.value.unshift(item);
    ElMessage.success("报表已进入生成队列");
    for (let i = 0; i < 40 && alive; i++) {
      const current = exports.value.find((e) => e.id === item.id)!;
      if (await check(current)) break;
      await new Promise((resolve) => setTimeout(resolve, 1500));
    }
  } catch (e) {
    fail(e);
  } finally {
    exporting.value = false;
  }
}
async function saveFile(row: Row) {
  try {
    await download(
      row.fileId,
      `养老服务月报-${row.month}.${row.format.toLowerCase()}`,
    );
  } catch (e) {
    fail(e);
  }
}
onMounted(async () => {
  await loadFilters();
  await load();
});
onBeforeUnmount(() => {
  alive = false;
});
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>统计报表</h1>
      <p>
        {{
          auth.can("report:read:community")
            ? "查看全部社区服务汇总，可按社区筛选"
            : "查看本人负责范围的服务汇总"
        }}，保留每个指标的分子与分母。
      </p>
    </div>
    <span v-if="data" class="report-update-tag"
      ><el-icon><DataAnalysis /></el-icon> 快照已更新</span
    >
  </div>
  <section class="panel report-toolbar">
    <div class="toolbar">
      <el-select
        v-if="auth.can('report:read:community')"
        v-model="reportCommunity"
        @change="communityChanged"
        placeholder="全部社区"
        clearable
        ><el-option label="全部社区" value="" /><el-option
          v-for="c in auth.communities"
          :key="c.id"
          :value="c.id"
          :label="c.name"
      /></el-select>
      <el-radio-group v-model="dateMode"
        ><el-radio-button value="month">按月</el-radio-button
        ><el-radio-button value="range"
          >时间范围</el-radio-button
        ></el-radio-group
      >
      <el-date-picker
        v-if="dateMode === 'range'"
        v-model="dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
      />
      <el-select
        v-if="auth.can('report:read:community')"
        v-model="filters.ownerId"
        clearable
        placeholder="全部经办人"
        ><el-option
          v-for="w in workers"
          :key="w.id"
          :value="w.id"
          :label="`${w.displayName}（${w.username}）`"
      /></el-select>
      <el-select
        v-model="filters.noticeType"
        clearable
        placeholder="全部通知类型"
        ><el-option label="生日关怀" value="BIRTHDAY" /><el-option
          label="疫苗提醒"
          value="VACCINE" /><el-option
          label="健康讲座"
          value="LECTURE" /><el-option label="其他政务通知" value="OTHER"
      /></el-select>
      <el-select
        v-model="filters.campaignId"
        clearable
        placeholder="全部疫苗周期"
        ><el-option
          v-for="c in campaigns"
          :key="c.id"
          :value="c.id"
          :label="c.name"
      /></el-select>
      <el-date-picker
        v-if="dateMode === 'month'"
        v-model="month"
        type="month"
        value-format="YYYY-MM"
        placeholder="选择统计月份"
        :clearable="false"
      /><el-button type="primary" :loading="loading" @click="load"
        >查询统计</el-button
      ><template
        v-if="
          auth.can('report:export:community') || auth.can('report:export:own')
        "
        ><el-button
          :icon="Download"
          :loading="exporting"
          @click="exportReport('XLSX')"
          >导出 Excel</el-button
        ><el-button :loading="exporting" @click="exportReport('DOCX')"
          >导出 Word</el-button
        ></template
      >
    </div>
    <div v-if="data" class="report-meta">
      <span>统计范围 {{ data.startDate }} 至 {{ data.endDate }}</span
      ><span>快照 {{ date(data.snapshotAt) }}</span>
      <span>口径 {{ data.metricVersion }}</span>
      <span>比例按实际有效样本计算</span>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </section>
  <section v-if="data" class="stat-tiles report-overview">
    <StatCard
      v-for="card in coreCards"
      :key="card.code"
      :label="card.label"
      :value="card.value"
      :icon="card.icon"
      :tone="card.tone"
      :hint="card.hint"
    />
  </section>
  <section v-if="data" class="chart-grid">
    <article class="panel">
      <div class="panel-head">
        <div>
          <h2>核心指标达成率</h2>
          <p>通知覆盖、服务闭环、满意度、反馈率与按期跟进</p>
        </div>
      </div>
      <div class="chart-body">
        <BarChart :data="coreBars" suffix="%" :max="100" />
      </div>
    </article>
    <article class="panel">
      <div class="panel-head">
        <div>
          <h2>通知渠道使用占比</h2>
          <p>按触达方式统计所选范围内的通知尝试次数</p>
        </div>
      </div>
      <div class="chart-body">
        <DonutChart
          v-if="channelTotal"
          :data="channelData"
          center-label="触达次数"
        />
        <p v-else class="chart-empty">所选范围暂无触达记录</p>
      </div>
    </article>
  </section>
  <section v-if="data" class="panel mb">
    <div class="panel-head">
      <div>
        <h2>疫苗接种状态人数</h2>
        <p>按具体服务周期统计；资格未知不进入接种率分母。</p>
      </div>
    </div>
    <el-table
      class="report-data-table"
      :data="data.vaccineSummary || []"
      empty-text="所选范围暂无已核验的疫苗记录"
      max-height="360"
      ><el-table-column
        prop="campaignName"
        label="疫苗服务周期"
        min-width="210"
      /><el-table-column prop="notVaccinated" label="未接种" /><el-table-column
        prop="partial"
        label="部分完成"
      /><el-table-column prop="allCompleted" label="已完成" /><el-table-column
        prop="unknownStatus"
        label="状态待核实"
      /><el-table-column
        prop="unknownEligibility"
        label="资格待核实"
      /><el-table-column label="符合条件接种率" min-width="150"
        ><template #default="{ row }">{{
          Number(row.eligible)
            ? percent(Number(row.completed) / Number(row.eligible))
            : "无有效样本"
        }}</template></el-table-column
      ></el-table
    >
  </section>
  <section v-if="data" class="panel mb">
    <div class="panel-head">
      <div>
        <h2>服务缺口分析</h2>
        <p>基于已登记状态定位工作缺口，具体拒绝或医学原因需核实原始记录。</p>
      </div>
    </div>
    <el-table
      class="report-data-table"
      :data="data.serviceGaps || []"
      empty-text="本期已登记资料暂无待办缺口"
      max-height="360"
      ><el-table-column
        prop="title"
        label="服务事项"
        min-width="150" /><el-table-column
        prop="count"
        label="缺口数量"
        width="100" /><el-table-column
        prop="analysis"
        label="登记情况"
        min-width="250" /><el-table-column
        prop="suggestion"
        label="后续措施"
        min-width="250"
    /></el-table>
  </section>
  <div v-if="data" class="details-bar">
    <el-button
      class="details-toggle"
      :icon="detailsOpen ? ArrowUp : ArrowDown"
      @click="detailsOpen = !detailsOpen"
      >{{ detailsOpen ? "收起指标明细" : "查看指标明细" }}</el-button
    >
    <span class="details-count"
      >共 {{ metricGroups.length }} 类指标 ·
      {{ data.metrics?.length || 0 }} 项明细</span
    >
  </div>
  <el-collapse-transition>
    <section v-show="detailsOpen" v-loading="loading" class="metrics-grid">
      <article
        v-for="(group, index) in metricGroups"
        :key="group.code"
        class="metric-card metric-card-uniform"
        :class="{ 'metric-card-clickable': !group.single }"
        :style="{ animationDelay: `${Math.min(index, 12) * 0.04}s` }"
        @click="!group.single && openDetail(group)"
      >
        <div class="metric-card-head">
          <span class="metric-title">{{ group.title }}</span>
        </div>
        <template v-if="group.single">
          <div class="metric-number">{{ percent(group.value) }}</div>
          <el-progress
            class="metric-progress"
            :percentage="
              group.value == null
                ? 0
                : Math.min(100, Math.max(0, Number(group.value) * 100))
            "
            :show-text="false"
            :stroke-width="8"
            color="#5fc7b0"
          />
          <p class="metric-denom">
            分子 <b>{{ group.numerator }}</b> <span>·</span> 分母
            {{ group.denominator }}
          </p>
        </template>
        <template v-else>
          <div class="metric-number">{{ group.items.length }}<small>类明细</small></div>
          <p class="metric-denom metric-view-hint">
            <el-icon><DataAnalysis /></el-icon> 点击查看各分类比例对比
          </p>
        </template>
      </article>
    </section>
  </el-collapse-transition>
  <el-dialog
    v-model="detailOpen"
    :title="detailGroup?.title || '指标明细'"
    width="760px"
    class="metric-detail-dialog"
    destroy-on-close
  >
    <div v-if="detailGroup" class="metric-detail-summary">
      <span>共 <b>{{ detailGroup.items.length }}</b> 个分类 · 比例按各项数值相对最大值展示</span>
    </div>
    <div class="metric-detail-chart">
      <MetricBars :items="detailGroup?.items || []" />
    </div>
  </el-dialog>
  <section class="panel report-list">
    <div class="panel-head">
      <div>
        <h2>
          <el-icon class="head-icon"><Download /></el-icon>报表导出记录
        </h2>
        <p>生成完成后下载；历史任务会保留，文件有效期内可再次下载。</p>
      </div>
    </div>
    <el-table :data="exports" empty-text="选择月份，点击上方按钮生成报表"
      ><el-table-column prop="month" label="月份" /><el-table-column
        prop="format"
        label="格式"
      /><el-table-column label="状态"
        ><template #default="{ row }"
          ><Status :value="row.status" /></template></el-table-column
      ><el-table-column
        prop="error"
        label="提示"
        min-width="180"
      /><el-table-column
        label="操作"
        min-width="160"
        class-name="actions-column"
        ><template #default="{ row }"
          ><el-button
            v-if="row.status === 'COMPLETED'"
            link
            type="primary"
            @click="saveFile(row)"
            >下载文件</el-button
          ><el-button v-else link type="primary" @click="check(row)"
            >刷新状态</el-button
          ></template
        ></el-table-column
      ></el-table
    >
  </section>
</template>
