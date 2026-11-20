<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from "vue";
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
const auth = useAuth(),
  reportCommunity = ref(""),
  month = ref(new Date().toLocaleDateString("sv-SE").slice(0, 7)),
  data = ref<Row | null>(null),
  loading = ref(false),
  exporting = ref(false),
  error = ref(""),
  exports = ref<Row[]>([]),
  detailsOpen = ref(false);
let alive = true;
const metricNames: Row = {
  NOTICE_COVERAGE: "通知覆盖率",
  SERVICE_CLOSURE: "服务闭环率",
  SATISFACTION: "满意度",
  FEEDBACK_RATE: "反馈率",
  VACCINATION_RATE: "疫苗接种率",
  FOLLOWUP_ON_TIME: "跟进按期完成率",
  CHANNEL_USAGE: "渠道使用率",
};
const dimensionNames: Row = {
  HOME_VISIT: "上门告知",
  FAMILY_RELAY: "家属转告",
  BULLETIN: "公告栏张贴",
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
    { code: "SERVICE_CLOSURE", label: "服务闭环率", tone: "green", icon: CircleCheckFilled },
    { code: "SATISFACTION", label: "满意度", tone: "gold", icon: Star },
    { code: "FEEDBACK_RATE", label: "反馈率", tone: "blue", icon: Tickets },
    { code: "FOLLOWUP_ON_TIME", label: "按期完成率", tone: "brand", icon: Timer },
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
async function load() {
  if (!month.value) return;
  loading.value = true;
  error.value = "";
  try {
    data.value = await api(
      "/reports/metrics?" +
        query({ month: month.value, communityId: reportCommunity.value }),
    );
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
  exporting.value = true;
  try {
    const r = await api(
      "/report-jobs",
      "POST",
      {
        query: {
          month: month.value,
          communityId: reportCommunity.value || null,
        },
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
onMounted(load);
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
        placeholder="全部社区"
        clearable
        ><el-option label="全部社区" value="" /><el-option
          v-for="c in auth.communities"
          :key="c.id"
          :value="c.id"
          :label="c.name"
      /></el-select>
      <el-date-picker
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
      <span>快照 {{ date(data.snapshotAt) }}</span>
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
          <p>按触达方式统计当月尝试次数</p>
        </div>
      </div>
      <div class="chart-body">
        <DonutChart
          v-if="channelTotal"
          :data="channelData"
          center-label="触达次数"
        />
        <p v-else class="chart-empty">本月暂无触达记录</p>
      </div>
    </article>
  </section>
  <div v-if="data" class="details-bar">
    <el-button
      class="details-toggle"
      :icon="detailsOpen ? ArrowUp : ArrowDown"
      @click="detailsOpen = !detailsOpen"
      >{{ detailsOpen ? "收起指标明细" : "查看指标明细" }}</el-button
    >
    <span class="details-count"
      >共 {{ data.metrics?.length || 0 }} 项指标 · 含分子与分母</span
    >
  </div>
  <el-collapse-transition>
    <section v-show="detailsOpen" v-loading="loading" class="metrics-grid">
      <article
        v-for="(m, index) in data?.metrics || []"
        :key="m.code + m.dimension"
        class="metric-card"
        :style="{ animationDelay: `${Math.min(Number(index), 12) * 0.04}s` }"
      >
        <div class="metric-card-head">
          <span>{{ metricNames[m.code] || m.code }}</span>
          <small v-if="m.dimension !== 'ALL'">{{
            dimensionNames[m.dimension] ||
            m.dimension.replace("CAMPAIGN:", "周期 ")
          }}</small>
          <el-icon v-else class="metric-card-icon"><DataAnalysis /></el-icon>
        </div>
        <div class="metric-number">{{ percent(m.value) }}</div>
        <el-progress
          class="metric-progress"
          :percentage="
            m.value == null
              ? 0
              : Math.min(100, Math.max(0, Number(m.value) * 100))
          "
          :show-text="false"
          :stroke-width="8"
          color="#5fc7b0"
        />
        <p class="metric-denom">
          分子 <b>{{ m.numerator }}</b> <span>·</span> 分母 {{ m.denominator }}
        </p>
      </article>
    </section>
  </el-collapse-transition>
  <section class="panel report-list">
    <div class="panel-head">
      <div>
        <h2><el-icon class="head-icon"><Download /></el-icon>本次会话的导出</h2>
        <p>生成完成后下载，刷新页面前请保存所需文件。</p>
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
      /><el-table-column label="操作" min-width="160" class-name="actions-column"
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
