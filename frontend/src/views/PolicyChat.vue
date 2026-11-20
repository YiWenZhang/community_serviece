<script setup lang="ts">
import { nextTick, onMounted, ref } from "vue";
import { ElMessageBox } from "element-plus";
import { api, http, fail, type Row } from "../api";
import AnswerRichText from "../components/AnswerRichText.vue";
const status = ref<Row>({}),
  conversations = ref<Row[]>([]),
  current = ref<string | null>(null),
  messages = ref<Row[]>([]),
  question = ref(""),
  sending = ref(false),
  loading = ref(false),
  error = ref(""),
  pending = ref(""),
  log = ref<HTMLElement | null>(null),
  source = ref<Row | null>(null);
const examples = [
  "社区助餐服务如何申请，需要哪些材料？",
  "家属转告的疫苗通知可以直接算已接种吗？",
  "独居老人的探访发现异常后应该怎么办？",
];
let retry: { question: string; requestId: string } | null = null;
async function load() {
  try {
    [status.value, conversations.value] = await Promise.all([
      api("/ai/status"),
      api("/ai/conversations"),
    ]);
  } catch (e) {
    error.value = (e as Error).message;
  }
}
async function select(id: string) {
  if (sending.value) return;
  loading.value = true;
  error.value = "";
  try {
    messages.value = await api(`/ai/conversations/${id}/messages`);
    current.value = id;
    retry = null;
    await scroll();
  } catch (e) {
    fail(e);
  } finally {
    loading.value = false;
  }
}
function fresh() {
  if (sending.value) return;
  current.value = null;
  messages.value = [];
  question.value = "";
  error.value = "";
  retry = null;
}
async function scroll() {
  await nextTick();
  log.value?.scrollTo({ top: log.value.scrollHeight, behavior: "smooth" });
}
async function send(text?: string) {
  const q = (text || question.value).trim();
  if (!q || sending.value || loading.value) return;
  sending.value = true;
  error.value = "";
  pending.value = q;
  question.value = "";
  await scroll();
  try {
    if (!current.value) {
      const c = await api("/ai/conversations", "POST", {});
      current.value = c.id;
    }
    if (!retry || retry.question !== q)
      retry = { question: q, requestId: crypto.randomUUID() };
    const r = await http.post(
      `/ai/conversations/${current.value}/messages`,
      retry,
      { timeout: 180000 },
    );
    messages.value.push(r.data.data);
    retry = null;
    await load();
  } catch (e) {
    error.value = (e as Error).message;
    question.value = q;
  } finally {
    sending.value = false;
    pending.value = "";
    await scroll();
  }
}
async function remove(id: string) {
  if (sending.value) return;
  try {
    await ElMessageBox.confirm("删除这段政策咨询记录？", "删除对话", {
      type: "warning",
    });
    await api(`/ai/conversations/${id}`, "DELETE");
    if (current.value === id) fresh();
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") fail(e);
  }
}
async function download(c: Row) {
  try {
    const r = await http.get(`/ai/documents/${c.documentId}/content`, {
      responseType: "blob",
    });
    const url = URL.createObjectURL(r.data);
    const a = document.createElement("a");
    a.href = url;
    a.download = c.safeName;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    fail(e);
  }
}
onMounted(load);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>政策问答助手</h1>
      <p>围绕养老、社区服务与疫苗业务提问，答案附带知识库原文。</p>
    </div>
    <el-tag :type="status.configured ? 'success' : 'warning'">{{
      status.configured ? status.model : "尚未配置模型"
    }}</el-tag>
  </div>
  <el-alert
    v-if="!status.configured"
    class="mb"
    title="请最高管理员在“AI 模型配置”中启用对话模型，并由管理员上传政策文档。"
    type="warning"
    :closable="false"
  />
  <el-alert
    v-else-if="
      Number(status.currentIndexDocuments) < Number(status.readyDocuments)
    "
    class="mb"
    title="部分文档使用了旧向量模型，请管理员重新解析后再查询。"
    type="warning"
    :closable="false"
  />
  <div class="policy-layout">
    <aside class="panel conversation-list">
      <el-button type="primary" plain :disabled="sending" @click="fresh"
        >＋ 新建政策咨询</el-button
      >
      <p class="muted conversation-label">最近咨询 · 仅本人可见</p>
      <div
        v-for="c in conversations"
        :key="c.id"
        class="conversation-row"
        :class="{ selected: current === c.id }"
      >
        <button
          class="conversation-title"
          :disabled="sending"
          @click="select(c.id)"
        >
          {{ c.title }}</button
        ><button
          class="delete-conversation"
          :disabled="sending"
          aria-label="删除对话"
          @click="remove(c.id)"
        >
          ×
        </button>
      </div>
      <p v-if="!conversations.length" class="muted">还没有咨询记录。</p>
      <div class="retrieval-info">
        <strong>{{ status.currentIndexDocuments || 0 }} 份可检索文档</strong
        ><small>{{ status.embeddingModel || "正在连接知识库" }}</small>
      </div>
    </aside>
    <section v-loading="loading" class="panel policy-chat">
      <div ref="log" class="chat-log" aria-live="polite">
        <div v-if="!messages.length && !sending" class="chat-welcome">
          <div class="chat-symbol">策</div>
          <h2>业务有疑问，先查政策依据</h2>
          <p>我会检索已入库且在有效期内的文档，按原文解释办理条件与流程。</p>
          <div class="example-questions">
            <button
              v-for="example in examples"
              :key="example"
              :disabled="!status.configured"
              @click="send(example)"
            >
              {{ example }} <span>↗</span>
            </button>
          </div>
        </div>
        <article v-for="m in messages" :key="m.id" class="chat-turn">
          <div class="user-question">
            <span>你</span>
            <p>{{ m.question }}</p>
          </div>
          <div class="assistant-answer">
            <span class="assistant-badge">策</span>
            <div class="answer-body">
              <strong>政策助手</strong>
              <AnswerRichText class="answer-text" :text="m.answer" />
              <div v-if="m.citations?.length" class="citation-list">
                <small>政策原文依据 · 点击查看</small
                ><button
                  v-for="c in m.citations"
                  :key="c.number"
                  class="citation-card"
                  @click="source = c"
                >
                  <div>
                    <b>[{{ c.number }}] {{ c.title }}</b
                    ><el-tag v-if="c.isDemo" size="small" type="warning"
                      >演示</el-tag
                    >
                  </div>
                  <small
                    >{{ c.section
                    }}{{ c.pageNo ? ` · 第 ${c.pageNo} 页` : "" }} ·
                    {{ c.policyNo || "未填写文号" }}</small
                  >
                  <p>
                    {{ c.content.slice(0, 125)
                    }}{{ c.content.length > 125 ? "…" : "" }}
                  </p>
                </button>
              </div>
              <small class="muted"
                >{{ m.model || "知识库检索" }} ·
                请核对政策适用地区与有效期</small
              >
            </div>
          </div>
        </article>
        <article v-if="sending" class="chat-turn">
          <div class="user-question">
            <span>你</span>
            <p>{{ pending }}</p>
          </div>
          <div class="assistant-answer">
            <span class="assistant-badge">策</span>
            <p class="muted">正在检索政策原文并生成答复，请稍候…</p>
          </div>
        </article>
      </div>
      <div class="chat-compose">
        <el-alert
          v-if="error"
          :title="error"
          type="error"
          :closable="false"
          class="mb"
        /><el-input
          v-model="question"
          type="textarea"
          :rows="3"
          resize="none"
          maxlength="2000"
          placeholder="请输入政策或办理流程问题，Ctrl + Enter 发送"
          aria-label="政策问题"
          :disabled="sending"
          @keydown.ctrl.enter.prevent="send()"
        />
        <div class="compose-footer">
          <small
            >答案来自知识库；依据不足时会明确说明。演示规范不作为真实政务依据。</small
          ><el-button
            type="primary"
            :disabled="!question.trim() || !status.configured || loading"
            :loading="sending"
            @click="send()"
            >发送问题</el-button
          >
        </div>
      </div>
    </section>
  </div>
  <el-drawer
    :model-value="!!source"
    title="政策原文引用"
    size="min(560px, 100vw)"
    @close="source = null"
    ><template v-if="source"
      ><h2>[{{ source.number }}] {{ source.title }}</h2>
      <el-alert
        v-if="source.isDemo"
        class="mb"
        title="虚构演示规范，不作为真实政务依据"
        type="warning"
        :closable="false"
      />
      <p class="muted">
        发布单位：{{ source.issuer || "未填写" }}<br />文号：{{
          source.policyNo || "未填写"
        }}<br />生效日期：{{ source.effectiveDate || "未限制"
        }}<br />失效日期：{{ source.expiryDate || "未限制" }}<br />出处：{{
          source.section
        }}{{ source.pageNo ? `，第 ${source.pageNo} 页` : "" }}
      </p>
      <blockquote class="source-original">{{ source.content }}</blockquote>
      <p class="muted">
        保存于该次问答的原文快照，字符位置 {{ source.startOffset }}–{{
          source.endOffset
        }}。历史引用不代表政策当前仍有效。
      </p>
      <el-button @click="download(source)">下载政策原文件</el-button></template
    ></el-drawer
  >
</template>
<style scoped>
.policy-layout {
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr);
  gap: 20px;
}
.conversation-list {
  padding: 20px 14px;
  display: flex;
  flex-direction: column;
  max-height: 760px;
  overflow: auto;
}
.conversation-label {
  margin: 26px 8px 14px;
}
.conversation-row {
  display: flex;
  border-radius: 7px;
  margin-bottom: 5px;
}
.conversation-row {
  transition: background 0.2s ease, box-shadow 0.2s ease;
}
.conversation-row:hover {
  background: #f3f8f4;
}
.conversation-row.selected {
  background: linear-gradient(90deg, #e4f0e9, #eef5ef);
  box-shadow: inset 3px 0 0 #3aa6a0;
}
.conversation-title {
  background: none;
  border: 0;
  padding: 12px;
  text-align: left;
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #36564d;
  cursor: pointer;
  font-size: 13px;
}
.delete-conversation {
  border: 0;
  background: none;
  color: #85958d;
  cursor: pointer;
  padding: 8px;
}
.retrieval-info {
  margin-top: auto;
  padding: 28px 8px 0;
  font-size: 12px;
  color: #60766c;
}
.retrieval-info small {
  display: block;
  line-height: 1.6;
  margin-top: 8px;
}
.policy-chat {
  padding: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  height: min(780px, calc(100vh - 240px));
  min-height: 580px;
}
.chat-log {
  flex: 1;
  overflow: auto;
  padding: 30px;
}
.chat-welcome {
  max-width: 560px;
  margin: 20px auto;
  text-align: center;
}
.chat-symbol {
  background: linear-gradient(135deg, #e8f3d9, #cfe4c2);
  color: #2f6b4a;
  border-radius: 20px;
  font-size: 28px;
  width: 64px;
  height: 64px;
  display: grid;
  place-items: center;
  margin: 0 auto 20px;
  box-shadow: 0 8px 22px rgba(63, 120, 82, 0.18),
    inset 0 1px 0 rgba(255, 255, 255, 0.8);
}
.chat-welcome p {
  color: #7a8b82;
  font-size: 14px;
  line-height: 1.8;
}
.example-questions {
  display: grid;
  gap: 12px;
  margin-top: 28px;
}
.example-questions button {
  border: 1px solid #e0e9e1;
  background: linear-gradient(135deg, #fbfdfa, #f4f8f2);
  border-radius: 10px;
  padding: 16px;
  text-align: left;
  font-size: 13px;
  color: #426355;
  cursor: pointer;
  box-shadow: 0 2px 10px rgba(40, 80, 60, 0.05);
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
}
.example-questions button:hover:not(:disabled) {
  transform: translateY(-2px);
  border-color: #a8ccb7;
  box-shadow: 0 8px 20px rgba(40, 80, 60, 0.1);
}
.example-questions button span {
  float: right;
}
.user-question {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  align-items: flex-start;
  margin-bottom: 25px;
}
.user-question span {
  order: 2;
  background: #e1ecdb;
  border-radius: 8px;
  padding: 8px;
  font-size: 12px;
}
.user-question p {
  background: #edf4ee;
  border-radius: 12px 0 12px 12px;
  padding: 14px 18px;
  max-width: 85%;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 14px;
  line-height: 1.8;
}
.assistant-answer {
  display: flex;
  gap: 14px;
  margin-bottom: 38px;
}
.assistant-badge {
  background: linear-gradient(135deg, #3aa6a0, #22756a);
  color: white;
  font-size: 12px;
  border-radius: 9px;
  height: 32px;
  min-width: 32px;
  display: grid;
  place-items: center;
  box-shadow: 0 4px 12px rgba(34, 117, 106, 0.3);
}
.answer-body {
  min-width: 0;
  flex: 1;
}
.answer-body > strong {
  font-size: 13px;
}
.source-original {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.9;
  font-size: 14px;
}
.answer-text {
  white-space: normal;
}
.citation-list {
  display: grid;
  gap: 9px;
  margin-bottom: 16px;
}
.citation-list > small {
  color: #5f7a68;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
}
.citation-list > small::before {
  content: "";
  width: 6px;
  height: 6px;
  border-radius: 2px;
  background: #679278;
}
.citation-card {
  border: 1px solid #e3ede1;
  border-radius: 10px;
  background: linear-gradient(135deg, #fbfdfa, #f3f8f2);
  text-align: left;
  padding: 14px 16px;
  color: #345c4b;
  cursor: pointer;
  box-shadow: 0 3px 12px rgba(40, 80, 60, 0.06);
  transition: transform 0.25s ease, box-shadow 0.25s ease, border-color 0.25s ease;
}
.citation-card:hover {
  transform: translateX(4px);
  border-color: #a8ccb7;
  box-shadow: 0 8px 20px rgba(40, 80, 60, 0.12);
}
.citation-card > div {
  display: flex;
  justify-content: space-between;
  gap: 10px;
}
.citation-card b {
  font-size: 13px;
  color: #2a5244;
  font-weight: 650;
}
.citation-card small {
  font-size: 11px;
  display: block;
  margin: 7px 0;
  color: #72887a;
}
.citation-card p {
  font-size: 12px;
  line-height: 1.8;
  color: #556b5c;
  margin: 0;
}
.chat-compose {
  border-top: 1px solid #e7ece6;
  padding: 20px 24px;
  background: #fff;
  border-radius: 0 0 12px 12px;
}
.compose-footer {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  margin-top: 12px;
}
.compose-footer small {
  font-size: 11px;
  color: #86978b;
  line-height: 1.6;
}
.source-original {
  margin: 24px 0;
  padding: 20px;
  background: linear-gradient(135deg, #f7faf5, #eef4eb);
  border-left: 3px solid #679278;
  border-radius: 0 10px 10px 0;
  box-shadow: 0 3px 14px rgba(40, 80, 60, 0.06);
  color: #3d5648;
}
@media (max-width: 1000px) {
  .policy-layout {
    grid-template-columns: 1fr;
  }
  .conversation-list {
    max-height: 210px;
  }
  .retrieval-info {
    display: none;
  }
  .policy-chat {
    height: 720px;
  }
  .chat-log {
    padding: 20px;
  }
  .compose-footer {
    align-items: flex-end;
  }
  .compose-footer small {
    max-width: 65%;
  }
}
</style>
