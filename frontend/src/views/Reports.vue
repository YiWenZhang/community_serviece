<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from "vue";
import { ElMessage } from "element-plus";
import { Download } from "@element-plus/icons-vue";
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
const auth = useAuth(),
  month = ref(new Date().toLocaleDateString("sv-SE").slice(0, 7)),
  data = ref<Row | null>(null),
  loading = ref(false),
  exporting = ref(false),
  error = ref(""),
  exports = ref<Row[]>([]);
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
async function load() {
  if (!month.value) return;
  loading.value = true;
  error.value = "";
  try {
    data.value = await api("/reports/metrics?" + query({ month: month.value }));
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
      { query: { month: month.value }, format },
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
            ? "查看本社区服务汇总"
            : "查看本人负责范围的服务汇总"
        }}，保留每个指标的分子与分母。
      </p>
    </div>
  </div>
  <section class="panel">
    <div class="toolbar">
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
    <p v-if="data" class="muted">
      快照 {{ date(data.snapshotAt) }} · 口径 {{ data.metricVersion }} ·
      比例按实际有效样本计算
    </p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </section>
  <section v-loading="loading" class="metrics-grid">
    <article
      v-for="m in data?.metrics || []"
      :key="m.code + m.dimension"
      class="panel"
    >
      <div class="muted">
        {{ metricNames[m.code] || m.code }}
        <small v-if="m.dimension !== 'ALL'"
          >·
          {{
            dimensionNames[m.dimension] ||
            m.dimension.replace("CAMPAIGN:", "周期 ")
          }}</small
        >
      </div>
      <div class="metric-number">{{ percent(m.value) }}</div>
      <el-progress
        :percentage="
          m.value == null
            ? 0
            : Math.min(100, Math.max(0, Number(m.value) * 100))
        "
        :show-text="false"
        :stroke-width="5"
        color="#8ca97a"
      />
      <p class="metric-denom">
        分子 {{ m.numerator }} / 分母 {{ m.denominator }}
      </p>
    </article>
  </section>
  <section class="panel report-list">
    <div class="panel-head">
      <div>
        <h2>本次会话的导出</h2>
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
      /><el-table-column label="操作" min-width="145"
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
