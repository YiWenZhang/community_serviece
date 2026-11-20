<script setup lang="ts">
import { onMounted, onBeforeUnmount, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, http, fail, date, type Row } from "../api";
const rows = ref<Row[]>([]),
  loading = ref(false),
  error = ref(""),
  q = ref(""),
  page = ref(1),
  total = ref(0),
  open = ref(false),
  saving = ref(false),
  detail = ref<Row | null>(null),
  file = ref<File | null>(null);
const form = reactive<Row>({
  title: "",
  category: "ELDERCARE",
  issuer: "",
  policyNo: "",
  effectiveDate: "",
  expiryDate: "",
  isDemo: false,
  redactionConfirmed: false,
});
const categories: Row = {
  ELDERCARE: "养老服务",
  COMMUNITY: "社区服务",
  VACCINE: "疫苗服务",
  OTHER: "其他规范",
};
const states: Row = {
  PENDING: "等待解析",
  PROCESSING: "AI 解析中",
  READY: "已入库",
  FAILED: "解析失败",
  DISABLED: "已停用",
};
let timer: ReturnType<typeof setInterval> | undefined;
async function load(quiet = false) {
  if (!quiet) loading.value = true;
  error.value = "";
  try {
    const r = await api(
      `/ai/documents?page=${page.value}&pageSize=20&q=${encodeURIComponent(q.value)}`,
    );
    rows.value = r.items;
    total.value = Number(r.total);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
function create() {
  Object.assign(form, {
    title: "",
    category: "ELDERCARE",
    issuer: "",
    policyNo: "",
    effectiveDate: "",
    expiryDate: "",
    isDemo: false,
    redactionConfirmed: false,
  });
  file.value = null;
  open.value = true;
}
function selectFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] || null;
  if (file.value && !form.title)
    form.title = file.value.name.replace(/\.[^.]+$/, "");
  if (file.value?.name.includes("演示")) form.isDemo = true;
}
async function upload() {
  if (!file.value || !form.title)
    return ElMessage.warning("请选择文档并填写标题");
  if (!form.redactionConfirmed)
    return ElMessage.warning("请先确认文档及图片已经脱敏");
  saving.value = true;
  try {
    const body = new FormData();
    body.append("file", file.value);
    for (const [key, v] of Object.entries(form))
      if (v !== "" && v !== null) body.append(key, String(v));
    await http.post("/ai/documents", body, { timeout: 150000 });
    open.value = false;
    ElMessage.success("文档已上传，正在排队解析；可在列表查看进度");
    page.value = 1;
    await load();
  } catch (e) {
    fail(e);
  } finally {
    saving.value = false;
  }
}
async function inspect(row: Row) {
  try {
    detail.value = await api(`/ai/documents/${row.id}`);
  } catch (e) {
    fail(e);
  }
}
async function action(row: Row, action: string) {
  try {
    if (action === "delete")
      await ElMessageBox.confirm(
        `删除“${row.title}”？删除后不再用于问答。`,
        "删除文档",
        { type: "warning" },
      );
    await api(`/ai/documents/${row.id}/action`, "POST", {
      action,
      version: row.version,
    });
    ElMessage.success("操作完成");
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") fail(e);
  }
}
async function download(row: Row) {
  try {
    const r = await http.get(`/ai/documents/${row.id}/content`, {
      responseType: "blob",
    });
    const url = URL.createObjectURL(r.data);
    const a = document.createElement("a");
    a.href = url;
    a.download = row.safeName;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    fail(e);
  }
}
onMounted(() => {
  load();
  timer = setInterval(() => {
    if (rows.value.some((r) => ["PENDING", "PROCESSING"].includes(r.status)))
      load(true);
  }, 4000);
});
onBeforeUnmount(() => clearInterval(timer));
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>政策知识库</h1>
      <p>让业务规范成为可检索、有出处的政策依据。</p>
    </div>
    <el-button type="primary" @click="create">上传政策文档</el-button>
  </div>
  <p class="hint">
    支持 PDF、DOCX、UTF-8 TXT / Markdown，单文件不超过 20MB、正文不超过 12
    万字。扫描件请先 OCR。AI 解析完成后自动入库。
  </p>
  <el-alert
    v-if="error"
    class="mb"
    type="error"
    :title="error"
    :closable="false"
  />
  <section class="panel">
    <div class="knowledge-search">
      <el-input
        v-model="q"
        placeholder="搜索标题或文号"
        clearable
        @keyup.enter="
          page = 1;
          load();
        "
      /><el-button
        @click="
          page = 1;
          load();
        "
        >查询</el-button
      ><el-button @click="load()">刷新进度</el-button>
    </div>
    <el-table
      v-loading="loading"
      :data="rows"
      empty-text="知识库尚无政策，请上传有效的政策原文或社区内部服务规范"
    >
      <el-table-column label="政策文档" min-width="240"
        ><template #default="{ row }"
          ><strong>{{ row.title }}</strong>
          <el-tag v-if="row.isDemo" type="warning" size="small"
            >演示规范</el-tag
          >
          <div class="muted">
            {{ row.policyNo || "未填写文号" }} ·
            {{ row.issuer || "未填写发布单位" }}
          </div></template
        ></el-table-column
      >
      <el-table-column label="分类" width="110"
        ><template #default="{ row }">{{
          categories[row.category]
        }}</template></el-table-column
      >
      <el-table-column label="入库状态" min-width="180"
        ><template #default="{ row }"
          ><el-tag
            :type="
              row.status === 'READY'
                ? 'success'
                : row.status === 'FAILED'
                  ? 'danger'
                  : 'info'
            "
            >{{ states[row.status] }}</el-tag
          >
          <div v-if="row.errorSummary" class="knowledge-error">
            {{ row.errorSummary }}
          </div>
          <div v-else class="muted">
            {{ row.chunkCount }} 个原文片段
          </div></template
        ></el-table-column
      >
      <el-table-column label="政策日期" min-width="155"
        ><template #default="{ row }"
          ><div>{{ row.effectiveDate || "未限制生效日期" }}</div>
          <small class="muted">{{
            row.expiryDate ? `有效至 ${row.expiryDate}` : "未限制失效日期"
          }}</small></template
        ></el-table-column
      >
      <el-table-column label="操作" width="340" class-name="actions-column"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="inspect(row)"
            >查看解析</el-button
          ><el-button link @click="download(row)">下载</el-button
          ><el-button
            link
            :disabled="['PROCESSING', 'PENDING'].includes(row.status)"
            @click="action(row, 'retry')"
            >重新解析</el-button
          ><el-button
            v-if="['READY', 'DISABLED'].includes(row.status)"
            link
            @click="action(row, row.status === 'READY' ? 'disable' : 'enable')"
            >{{ row.status === "READY" ? "停用" : "启用" }}</el-button
          ><el-button
            link
            type="danger"
            :disabled="row.status === 'PROCESSING'"
            @click="action(row, 'delete')"
            >删除</el-button
          ></template
        ></el-table-column
      > </el-table
    ><el-pagination
      v-model:current-page="page"
      :page-size="20"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="load()"
    />
  </section>
  <el-dialog v-model="open" title="上传政策文档" width="680px" destroy-on-close
    ><el-form label-position="top"
      ><div class="form-grid">
        <el-form-item label="文档文件 *" class="span-2"
          ><input
            aria-label="文档文件"
            type="file"
            accept=".pdf,.docx,.txt,.md"
            @change="selectFile"
        /></el-form-item>
        <el-form-item label="文档标题 *" class="span-2"
          ><el-input v-model="form.title" maxlength="200" /></el-form-item
        ><el-form-item label="政策分类"
          ><el-select v-model="form.category"
            ><el-option
              v-for="(name, key) in categories"
              :key="key"
              :label="name"
              :value="key" /></el-select
        ></el-form-item>
        <el-form-item label="发文单位"
          ><el-input v-model="form.issuer" maxlength="160" /></el-form-item
        ><el-form-item label="政策文号" class="span-2"
          ><el-input v-model="form.policyNo" maxlength="100"
        /></el-form-item>
        <el-form-item label="生效日期"
          ><el-date-picker
            v-model="form.effectiveDate"
            value-format="YYYY-MM-DD"
            type="date" /></el-form-item
        ><el-form-item label="失效日期"
          ><el-date-picker
            v-model="form.expiryDate"
            value-format="YYYY-MM-DD"
            type="date"
        /></el-form-item>
        <el-form-item class="span-2"
          ><el-checkbox v-model="form.isDemo"
            >这是演示规范，答复中必须注明不作为真实政务依据</el-checkbox
          ></el-form-item
        >
      </div></el-form
    ><el-checkbox v-model="form.redactionConfirmed"
      >确认文档及图片已脱敏，不含身份证号、家庭住址或原始手机号</el-checkbox
    >
    <template #footer
      ><el-button @click="open = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="upload"
        >上传并解析</el-button
      ></template
    ></el-dialog
  >
  <el-drawer
    :model-value="!!detail"
    title="政策解析与原文"
    size="min(960px, 100vw)"
    @close="detail = null"
    ><template v-if="detail"
      ><h2>{{ detail.title }}</h2>
      <p class="muted">
        上传于 {{ date(detail.createdAt) }} · {{ states[detail.status] }}
      </p>
      <el-alert
        v-if="detail.isDemo"
        title="虚构演示规范，不作为真实政务依据"
        type="warning"
        :closable="false"
      />
      <h3 class="detail-heading">结构化摘要</h3>
      <p class="policy-original">
        {{ detail.summary || "解析完成后会显示摘要。" }}
      </p>
      <el-collapse
        ><el-collapse-item
          v-for="chunk in detail.chunks"
          :key="chunk.id"
          :title="`片段 ${chunk.ordinal} · ${chunk.section}${chunk.pageNo ? ' · 第' + chunk.pageNo + '页' : ''}`"
          :name="chunk.id"
          ><p><strong>摘要：</strong>{{ chunk.summary }}</p>
          <p class="muted">关键词：{{ chunk.keywords }}</p>
          <blockquote class="policy-original">{{ chunk.content }}</blockquote>
          <small class="muted"
            >原文字符位置 {{ chunk.startOffset }}–{{ chunk.endOffset }}</small
          ></el-collapse-item
        ></el-collapse
      >
      <h3 class="detail-heading">提取全文</h3>
      <pre class="policy-original">{{ detail.extractedText }}</pre>
    </template></el-drawer
  >
</template>
<style scoped>
.knowledge-search {
  display: flex;
  gap: 12px;
  margin-bottom: 22px;
}
.knowledge-search .el-input {
  max-width: 350px;
}
.knowledge-error {
  font-size: 12px;
  color: #c44848;
  line-height: 1.6;
  margin-top: 6px;
}
.detail-heading {
  margin-top: 28px;
}
.policy-original {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  line-height: 1.9;
}
blockquote {
  margin: 12px 0;
  background: #f4f7f5;
  border-left: 3px solid #6ca093;
  padding: 16px;
}
.el-pagination {
  margin-top: 20px;
}
</style>
