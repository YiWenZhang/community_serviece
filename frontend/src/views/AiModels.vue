<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, http, fail, type Row } from "../api";
const rows = ref<Row[]>([]),
  loading = ref(false),
  open = ref(false),
  saving = ref(false),
  working = ref<string | null>(null),
  editing = ref<Row | null>(null);
const form = reactive<Row>({
  name: "",
  kind: "CHAT",
  apiFormat: "OPENAI",
  baseUrl: "https://api.deepseek.com",
  model: "deepseek-flash",
  apiKey: "",
  version: 0,
});
async function load() {
  loading.value = true;
  try {
    rows.value = await api("/ai/providers");
  } catch (e) {
    fail(e);
  } finally {
    loading.value = false;
  }
}
function edit(row?: Row) {
  editing.value = row || null;
  Object.assign(
    form,
    row
      ? { ...row, apiKey: "" }
      : {
          name: "DeepSeek Flash",
          kind: "CHAT",
          apiFormat: "OPENAI",
          baseUrl: "https://api.deepseek.com",
          model: "deepseek-flash",
          apiKey: "",
          version: 0,
        },
  );
  open.value = true;
}
function preset(format: string) {
  form.apiFormat = format;
  form.baseUrl =
    format === "ANTHROPIC"
      ? "https://api.deepseek.com/anthropic"
      : "https://api.deepseek.com";
  form.model = "deepseek-flash";
}
async function save() {
  saving.value = true;
  try {
    await api(
      editing.value ? `/ai/providers/${editing.value.id}` : "/ai/providers",
      editing.value ? "PUT" : "POST",
      {
        name: form.name,
        kind: form.kind,
        apiFormat: form.apiFormat,
        baseUrl: form.baseUrl,
        model: form.model,
        apiKey: form.apiKey,
        version: form.version,
      },
    );
    open.value = false;
    form.apiKey = "";
    ElMessage.success("配置已保存，请测试连接后启用");
    await load();
  } catch (e) {
    fail(e);
  } finally {
    saving.value = false;
  }
}
async function activate(row: Row) {
  working.value = row.id;
  try {
    await api(`/ai/providers/${row.id}/activation`, "POST", {
      active: !row.active,
    });
    ElMessage.success(
      row.kind === "EMBEDDING"
        ? "向量配置已切换，请重新解析知识库文档"
        : "对话模型已切换",
    );
    await load();
  } catch (e) {
    fail(e);
  } finally {
    working.value = null;
  }
}
async function test(row: Row) {
  working.value = row.id;
  try {
    const r = await http.post(
      `/ai/providers/${row.id}/test`,
      {},
      { timeout: 150000 },
    );
    ElMessage.success(
      r.data.data.dimensions
        ? `连接成功，向量维度 ${r.data.data.dimensions}`
        : "连接成功，支持结构化JSON输出",
    );
  } catch (e) {
    fail(e);
  } finally {
    working.value = null;
  }
}
async function remove(row: Row) {
  try {
    await ElMessageBox.confirm(`删除模型配置“${row.name}”？`, "删除配置", {
      type: "warning",
    });
    await api(`/ai/providers/${row.id}`, "DELETE");
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") fail(e);
  }
}
onMounted(load);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>AI 模型配置</h1>
      <p>维护多个服务商配置，一键切换政策解析与问答使用的模型。</p>
    </div>
    <el-button type="primary" @click="edit()">新增模型配置</el-button>
  </div>
  <el-alert
    class="mb"
    :closable="false"
    title="仅最高权限管理员可访问。密钥加密保存，列表和编辑接口均不返回原密钥。编辑当前配置前请先停用。"
    type="info"
  />
  <section class="panel">
    <el-table
      v-loading="loading"
      :data="rows"
      empty-text="暂无配置，点击右上角添加 DeepSeek Flash"
    >
      <el-table-column
        prop="name"
        label="配置名称"
        min-width="150"
      /><el-table-column label="用途" width="100"
        ><template #default="{ row }">{{
          row.kind === "CHAT" ? "对话 / 解析" : "语义向量"
        }}</template></el-table-column
      >
      <el-table-column label="协议 / 模型" min-width="180"
        ><template #default="{ row }"
          ><strong>{{ row.model }}</strong>
          <div class="muted">{{ row.apiFormat }}</div></template
        ></el-table-column
      >
      <el-table-column
        prop="baseUrl"
        label="API 地址"
        min-width="210"
      /><el-table-column label="状态" width="90"
        ><template #default="{ row }"
          ><el-tag :type="row.active ? 'success' : 'info'">{{
            row.active ? "使用中" : "未启用"
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column label="操作" width="260"
        ><template #default="{ row }"
          ><el-button
            link
            type="primary"
            :loading="working === row.id"
            @click="test(row)"
            >测试连接</el-button
          ><el-button
            link
            type="primary"
            :disabled="working !== null"
            @click="activate(row)"
            >{{ row.active ? "停用" : "启用" }}</el-button
          ><el-button link :disabled="!!row.active" @click="edit(row)"
            >编辑</el-button
          ><el-button
            link
            type="danger"
            :disabled="!!row.active"
            @click="remove(row)"
            >删除</el-button
          ></template
        ></el-table-column
      >
    </el-table>
  </section>
  <section class="panel ai-notes">
    <h2>向量模型如何选择</h2>
    <p>
      对话与文档结构化默认使用 DeepSeek Flash，支持 OpenAI Chat Completions 和
      Anthropic Messages 两种协议。
    </p>
    <p>
      未启用独立向量服务时，系统使用本地中文特征向量与 BM25
      混合检索，无需额外密钥。本地特征向量不具备神经网络模型的语义理解能力。
    </p>
    <p>
      需要更强的同义表达召回时，可添加 OpenAI Embeddings
      兼容服务（1～1024维）。请填写该服务自己的密钥与模型；对话模型不能直接当作
      Embedding 模型。切换向量模型后，请在知识库重新解析文档。
    </p>
  </section>
  <el-dialog
    v-model="open"
    :title="editing ? '编辑模型配置' : '新增模型配置'"
    width="660px"
    destroy-on-close
    @closed="form.apiKey = ''"
  >
    <el-form label-position="top"
      ><div class="form-grid">
        <el-form-item label="配置名称 *"
          ><el-input v-model="form.name" maxlength="80" /></el-form-item
        ><el-form-item label="用途"
          ><el-select v-model="form.kind" :disabled="!!editing"
            ><el-option value="CHAT" label="对话与文档解析" /><el-option
              value="EMBEDDING"
              label="语义向量 Embedding" /></el-select
        ></el-form-item>
        <el-form-item label="接口格式 *" class="span-2"
          ><el-select v-model="form.apiFormat"
            ><el-option label="OpenAI 兼容" value="OPENAI" /><el-option
              label="Anthropic Messages"
              value="ANTHROPIC"
              :disabled="form.kind === 'EMBEDDING'" /></el-select
        ></el-form-item>
        <div v-if="form.kind === 'CHAT'" class="span-2">
          <el-button size="small" @click="preset('OPENAI')"
            >填入 DeepSeek / OpenAI 示例</el-button
          ><el-button size="small" @click="preset('ANTHROPIC')"
            >填入 DeepSeek / Anthropic 示例</el-button
          >
        </div>
        <el-form-item label="API 基础地址 *" class="span-2"
          ><el-input
            v-model="form.baseUrl"
            placeholder="https://api.deepseek.com 或服务商的 /v1 地址"
            maxlength="500"
          /><small class="muted"
            >自动追加 /chat/completions、/messages 或
            /embeddings；也支持填写完整接口地址。</small
          ></el-form-item
        >
        <el-form-item label="模型名 *" class="span-2"
          ><el-input v-model="form.model" maxlength="160"
        /></el-form-item>
        <el-form-item
          :label="editing ? 'API 密钥（留空保留已存密钥）' : 'API 密钥 *'"
          class="span-2"
          ><el-input
            v-model="form.apiKey"
            type="password"
            show-password
            autocomplete="new-password"
            maxlength="512"
        /></el-form-item></div
    ></el-form>
    <template #footer
      ><el-button @click="open = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="save"
        >保存配置</el-button
      ></template
    >
  </el-dialog>
</template>
<style scoped>
.ai-notes {
  margin-top: 24px;
  line-height: 1.9;
}
.ai-notes p {
  font-size: 14px;
  color: #60736c;
}
</style>
