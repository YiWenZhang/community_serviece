<script setup lang="ts">
import { nextTick, onMounted, ref } from "vue";
import { ElMessageBox } from "element-plus";
import {
  Plus,
  ChatLineSquare,
  Document,
  Search,
  CircleCheck,
  Lock,
  Promotion,
  InfoFilled,
} from "@element-plus/icons-vue";
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
  { icon: Document, text: "社区助餐服务如何申请，需要哪些材料？", tag: "办理流程" },
  { icon: CircleCheck, text: "家属转告的疫苗通知可以直接算已接种吗？", tag: "业务认定" },
  { icon: Lock, text: "独居老人的探访发现异常后应该怎么办？", tag: "应急处置" },
];
const features = [
  { icon: Search, title: "原文检索", desc: "只查已入库且在有效期内的政策文档" },
  { icon: Document, title: "依据可查", desc: "每条回答都附原文出处，点开即核对" },
  { icon: Lock, title: "隐私保护", desc: "自动脱敏，仅你可见自己的咨询记录" },
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
      <h1>政策问答助手 📚</h1>
      <p>围绕养老、社区服务与疫苗业务提问，答案附带知识库原文，可溯源、可核对。</p>
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
      <div class="conversation-decor" aria-hidden="true">
        <span class="decor-orb decor-orb-one" />
        <span class="decor-orb decor-orb-two" />
      </div>
      <el-button
        class="new-conversation-btn"
        type="primary"
        :disabled="sending"
        @click="fresh"
        ><el-icon><Plus /></el-icon> 新建政策咨询</el-button
      >
      <p class="muted conversation-label">
        <el-icon><ChatLineSquare /></el-icon> 最近咨询 · 仅本人可见
      </p>
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
      <div v-if="!conversations.length" class="conversation-empty">
        <span>🗂️</span>
        <p>还没有咨询记录<br />点击上方按钮开始提问吧</p>
      </div>
      <div class="retrieval-info">
        <span class="retrieval-icon"><el-icon><Document /></el-icon></span>
        <div>
          <strong>{{ status.currentIndexDocuments || 0 }} 份可检索文档</strong>
          <small>{{ status.embeddingModel || "正在连接知识库" }}</small>
        </div>
      </div>
    </aside>
    <section
      v-loading="loading"
      class="panel policy-chat"
      :class="{ 'policy-chat-welcome': !messages.length && !sending }"
    >
      <div ref="log" class="chat-log" aria-live="polite">
        <div v-if="!messages.length && !sending" class="chat-welcome">
          <div class="welcome-glow" aria-hidden="true" />
          <div class="chat-symbol">策</div>
          <h2>业务有疑问，先查政策依据 👋</h2>
          <p>我会检索已入库且在有效期内的文档，按原文解释办理条件与流程。</p>
          <div class="feature-cards">
            <div v-for="f in features" :key="f.title" class="feature-card">
              <span class="feature-icon"><el-icon><component :is="f.icon" /></el-icon></span>
              <strong>{{ f.title }}</strong>
              <small>{{ f.desc }}</small>
            </div>
          </div>
          <p class="example-title">试试这样问 👇</p>
          <div class="example-questions">
            <button
              v-for="example in examples"
              :key="example.text"
              :disabled="!status.configured"
              @click="send(example.text)"
            >
              <span class="example-icon"><el-icon><component :is="example.icon" /></el-icon></span>
              <span class="example-main"
                ><small>{{ example.tag }}</small>{{ example.text }}</span
              >
              <span class="example-arrow"><el-icon><Promotion /></el-icon></span>
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
              <strong><span class="answer-badge-dot" />政策助手 · 依据解读</strong>
              <AnswerRichText class="answer-text" :text="m.answer" />
              <div v-if="m.citations?.length" class="citation-list">
                <small><el-icon><InfoFilled /></el-icon> 政策原文依据 · 点击查看原文</small
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
          placeholder="请输入政策或办理流程问题，按 Enter 发送，Shift + Enter 换行"
          aria-label="政策问题"
          :disabled="sending"
          @keydown.enter.exact.prevent="send()"
        />
        <div class="compose-footer">
          <small
            >答案来自知识库；依据不足时会明确说明。演示规范不作为真实政务依据。💡</small
          ><el-button
            class="send-btn"
            type="primary"
            :icon="Promotion"
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
  align-items: stretch;
}
.conversation-list {
  padding: 20px 16px;
  display: flex;
  flex-direction: column;
  height: 100%;
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
  padding: 14px 12px;
  text-align: left;
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #2a4a3f;
  cursor: pointer;
  font-size: 15px;
}
.delete-conversation {
  border: 0;
  background: none;
  color: #85958d;
  cursor: pointer;
  padding: 8px;
  font-size: 18px;
}
.retrieval-info {
  margin-top: auto;
  padding: 28px 8px 0;
  font-size: 13px;
  color: #5a6d63;
}
.retrieval-info small {
  display: block;
  line-height: 1.6;
  margin-top: 8px;
  font-size: 12px !important;
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
  padding: 20px 30px;
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
    height: auto;
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

/* ---- 全面美化 ---- */
.new-conversation-btn {
  width: 100%;
  height: 42px;
  font-size: 14px;
  font-weight: 600;
  border-radius: 10px;
  box-shadow: 0 6px 16px rgba(34, 117, 106, 0.22);
  transition: transform 0.2s ease, box-shadow 0.2s ease !important;
}
.new-conversation-btn:hover:not(.is-disabled) {
  transform: translateY(-2px);
  box-shadow: 0 10px 22px rgba(34, 117, 106, 0.32);
}
.conversation-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px !important;
}
.conversation-empty {
  text-align: center;
  padding: 30px 10px;
  color: #93a199;
}
.conversation-empty span {
  font-size: 34px;
  display: block;
  margin-bottom: 10px;
  opacity: 0.75;
}
.conversation-empty p {
  font-size: 12px;
  line-height: 1.8;
  margin: 0;
}
.retrieval-info {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: auto;
  padding: 14px !important;
  background: linear-gradient(135deg, #f0f7f2, #e7f1ea);
  border: 1px solid #dcebe0;
  border-radius: 12px;
}
.retrieval-icon {
  flex: none;
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: #fff;
  color: #2f7d6e;
  font-size: 18px;
  box-shadow: 0 2px 8px rgba(47, 125, 110, 0.12);
}
.retrieval-info strong {
  font-size: 15px;
  color: #2a5244;
}
.retrieval-info small {
  font-size: 11px !important;
  margin-top: 4px !important;
}

.chat-welcome {
  position: relative;
  max-width: 680px;
  margin: 8px auto;
  padding: 26px 30px 24px;
  background: linear-gradient(180deg, #ffffff 0%, #fbfdf9 100%);
  border: 1px solid #e6ede5;
  border-radius: 18px;
  box-shadow: 0 12px 36px rgba(28, 58, 52, 0.07),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
  overflow: hidden;
}
.welcome-glow {
  position: absolute !important;
  top: -90px;
  left: 50%;
  transform: translateX(-50%);
  width: 320px;
  height: 200px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(95, 199, 176, 0.18), transparent 70%);
  filter: blur(10px);
  pointer-events: none;
}
.chat-welcome > *:not(.welcome-glow) {
  position: relative;
}
.chat-symbol {
  width: 72px;
  height: 72px;
  font-size: 32px;
  margin-bottom: 18px;
}
.chat-welcome h2 {
  font-size: 21px;
  font-weight: 700;
  color: #24483e;
  margin: 0 0 8px;
}
.feature-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin: 24px 0 26px;
}
.feature-card {
  padding: 16px 12px;
  border-radius: 12px;
  background: linear-gradient(135deg, #f7faf5, #eef4ec);
  border: 1px solid #e3ede1;
  text-align: center;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
.feature-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 18px rgba(40, 80, 60, 0.1);
}
.feature-icon {
  display: inline-grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: linear-gradient(135deg, #3aa6a0, #22756a);
  color: #fff;
  font-size: 17px;
  margin-bottom: 8px;
  box-shadow: 0 4px 10px rgba(34, 117, 106, 0.24);
}
.feature-card strong {
  display: block;
  font-size: 13px;
  color: #2a5244;
  margin-bottom: 4px;
}
.feature-card small {
  display: block;
  font-size: 11px;
  color: #7d8f84;
  line-height: 1.6;
}
.example-title {
  font-size: 13px;
  font-weight: 600;
  color: #5f7a68;
  margin: 0 0 12px;
}
.example-questions button {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
}
.example-icon {
  flex: none;
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  background: #e7f3ef;
  color: #2f7d6e;
  font-size: 15px;
}
.example-main {
  flex: 1;
  text-align: left;
  font-size: 13px;
  color: #3a594c;
}
.example-main small {
  display: block;
  font-size: 11px;
  color: #6ba28f;
  margin-bottom: 2px;
}
.example-arrow {
  color: #6ba28f;
  font-size: 14px;
}

.answer-body > strong {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #2a5244;
}
.answer-badge-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #3aa6a0;
  box-shadow: 0 0 0 3px rgba(58, 166, 160, 0.18);
}
.citation-list > small {
  display: flex;
  align-items: center;
  gap: 5px;
}
.citation-list > small::before {
  display: none;
}
.chat-compose {
  background: linear-gradient(180deg, #ffffff, #fafcf9);
  box-shadow: 0 -6px 20px rgba(28, 58, 52, 0.04);
}
.send-btn {
  height: 40px;
  padding: 0 22px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  box-shadow: 0 6px 16px rgba(34, 117, 106, 0.26);
  transition: transform 0.18s ease, box-shadow 0.18s ease !important;
}
.send-btn:hover:not(.is-disabled) {
  transform: translateY(-2px);
  box-shadow: 0 10px 22px rgba(34, 117, 106, 0.34);
}
/* ---- 侧边栏：淡绿渐变 + 浮动光斑 + 装饰 ---- */
.conversation-list {
  position: relative;
  overflow: hidden;
  background: linear-gradient(165deg, #f4faf5 0%, #eaf4ee 45%, #e2efe7 100%);
  border: 1px solid #dcebdf;
  border-radius: 16px;
  box-shadow: 0 10px 30px rgba(40, 90, 65, 0.09),
    inset 0 1px 0 rgba(255, 255, 255, 0.85);
  transition: box-shadow 0.3s ease, transform 0.3s ease;
}
.conversation-list:hover {
  box-shadow: 0 16px 40px rgba(40, 90, 65, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
}
.conversation-decor {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
  border-radius: inherit;
}
.conversation-decor::before {
  content: "";
  position: absolute;
  left: 0;
  top: 0;
  height: 4px;
  width: 100%;
  background: linear-gradient(90deg, #5fc7b0, #3aa6a0, #8fbcb3);
}
.decor-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(8px);
  animation: orbFloat 9s ease-in-out infinite;
}
.decor-orb-one {
  width: 180px;
  height: 180px;
  top: -70px;
  right: -80px;
  background: radial-gradient(circle, rgba(95, 199, 176, 0.28), transparent 70%);
}
.decor-orb-two {
  width: 150px;
  height: 150px;
  bottom: 60px;
  left: -90px;
  background: radial-gradient(circle, rgba(143, 188, 179, 0.25), transparent 70%);
  animation-delay: -4s;
}
@keyframes orbFloat {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-18px);
  }
}
.conversation-list > *:not(.conversation-decor) {
  position: relative;
  z-index: 1;
}
.new-conversation-btn {
  box-shadow: 0 8px 20px rgba(34, 117, 106, 0.28);
}
.conversation-row {
  background: rgba(255, 255, 255, 0.45);
  border: 1px solid rgba(220, 235, 224, 0.7);
  transition: background 0.25s ease, box-shadow 0.25s ease,
    border-color 0.25s ease, transform 0.25s ease;
}
.conversation-row:hover {
  background: rgba(255, 255, 255, 0.8);
  transform: translateX(3px);
}
.conversation-row.selected {
  background: linear-gradient(90deg, #ffffff, #f3faf6);
  border-color: #a8ccb7;
  box-shadow: inset 3px 0 0 #3aa6a0, 0 4px 14px rgba(40, 90, 65, 0.1);
}
.retrieval-info {
  background: rgba(255, 255, 255, 0.75);
  border: 1px solid rgba(220, 235, 224, 0.85);
}

/* ---- 问答卡片：与侧边栏统一的淡绿渐变风格 ---- */
.policy-chat {
  position: relative;
  background: linear-gradient(165deg, #f4faf5 0%, #eaf4ee 48%, #e3f0e8 100%);
  border: 1px solid #dcebdf;
  border-radius: 16px;
  box-shadow: 0 10px 30px rgba(40, 90, 65, 0.09),
    inset 0 1px 0 rgba(255, 255, 255, 0.85);
  overflow: hidden;
  transition: box-shadow 0.3s ease;
}
.policy-chat:hover {
  box-shadow: 0 16px 40px rgba(40, 90, 65, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
}
.chat-log {
  background: transparent;
}
.chat-welcome {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.96) 0%, rgba(251, 253, 249, 0.9) 100%);
  border: 1px solid #dcebdf;
  box-shadow: 0 12px 36px rgba(40, 90, 65, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
}
.chat-compose {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(244, 250, 245, 0.95));
  border-top: 1px solid #dcebdf;
  box-shadow: 0 -6px 20px rgba(40, 90, 65, 0.05);
}

/* 欢迎态：高度自适应，卡片完整展示，输入区自然下移 */
.policy-chat-welcome {
  height: auto !important;
  min-height: min(780px, calc(100vh - 240px));
}
.policy-chat-welcome .chat-log {
  overflow: visible;
}
/* ---- 紧凑化：一屏完整展示欢迎内容 ---- */
.chat-welcome {
  max-width: 640px;
  margin: 2px auto;
  padding: 16px 22px 14px;
}
.chat-symbol {
  width: 56px;
  height: 56px;
  font-size: 26px;
  margin-bottom: 10px;
}
.chat-welcome h2 {
  font-size: 19px;
  margin-bottom: 5px;
}
.chat-welcome > p {
  font-size: 13px !important;
  margin: 4px 0 !important;
}
.feature-cards {
  gap: 10px;
  margin: 14px 0 16px;
}
.feature-card {
  padding: 10px 8px;
}
.feature-icon {
  width: 28px;
  height: 28px;
  font-size: 14px;
  margin-bottom: 5px;
}
.feature-card strong {
  font-size: 12px;
}
.feature-card small {
  font-size: 10px;
}
.example-title {
  font-size: 12px;
  margin-bottom: 8px;
}
.example-questions {
  gap: 8px;
  margin-top: 0;
}
.example-questions button {
  padding: 8px 10px !important;
}
.example-icon {
  width: 26px;
  height: 26px;
  font-size: 13px;
}
.policy-chat-welcome {
  min-height: 0;
}
@media (max-width: 760px) {
  .feature-cards {
    grid-template-columns: 1fr;
  }
  .chat-welcome {
    padding: 16px 18px;
  }
}
</style>
