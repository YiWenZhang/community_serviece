<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { Search, Grid, List, ArrowRight } from "@element-plus/icons-vue";
import { api, http, fail, type Row } from "../api";
import { useAuth } from "../stores";
const auth = useAuth(),
  rows = ref<Row[]>([]),
  open = ref(false),
  saving = ref(false),
  loading = ref(false),
  editing = ref<Row | null>(null),
  icon = ref<File | null>(null),
  workers = ref<Row[]>([]),
  view = ref<Row | null>(null),
  viewOpen = ref(false),
  viewTitle = ref(""),
  workerIds = ref<Record<number, boolean>>({}),
  keyword = ref(""),
  statusFilter = ref("ALL"),
  sortBy = ref("name"),
  viewMode = ref<"card" | "list">("card"),
  expanded = ref<Record<number, boolean>>({});
const form = reactive<Row>({
  code: "",
  name: "",
  description: "",
  status: "ACTIVE",
  version: 0,
});
const redactionConfirmed = ref(false);

const totalCount = computed(() => rows.value.length);
const activeCount = computed(
  () => rows.value.filter((c) => c.status === "ACTIVE").length,
);
const inactiveCount = computed(() => totalCount.value - activeCount.value);
const workerCount = computed(() => Object.keys(workerIds.value).length);
const filteredRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  let list = rows.value.filter((c) => {
    const matchKw =
      !kw ||
      String(c.name || "").toLowerCase().includes(kw) ||
      String(c.code || "").toLowerCase().includes(kw);
    const matchStatus =
      statusFilter.value === "ALL" || c.status === statusFilter.value;
    return matchKw && matchStatus;
  });
  list = [...list].sort((a, b) => {
    if (sortBy.value === "status")
      return String(a.status).localeCompare(String(b.status));
    return String(a.name || "").localeCompare(String(b.name || ""), "zh");
  });
  return list;
});
const abilityKeywords = [
  "综合受理",
  "午间助餐",
  "数字助老",
  "邻里议事",
  "健康讲座",
  "上门探访",
  "助洁服务",
  "康复理疗",
];
function abilities(c: Row): string[] {
  const text = String(c.description || "");
  return abilityKeywords.filter((k) => text.includes(k)).slice(0, 3);
}
function introSummary(c: Row): string {
  const text = String(c.description || "");
  const first = text.split(/[。；;\n]/)[0].trim();
  return first && first !== text.trim() ? first : text.slice(0, 40);
}
function needsExpand(c: Row): boolean {
  return String(c.description || "").length > 120;
}

async function load() {
  loading.value = true;
  try {
    rows.value = await api("/communities");
    await auth.refreshCommunities();
    const ids: Record<number, boolean> = {};
    await Promise.all(
      rows.value.map(async (c) => {
        try {
          const list = await api(`/communities/${c.id}/workers`);
          if (Array.isArray(list))
            list.forEach((w: Row) => {
              ids[Number(w.id)] = true;
            });
        } catch {
          /* 单个社区读取失败不计入 */
        }
      }),
    );
    workerIds.value = ids;
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
    row || {
      code: "",
      name: "",
      description: "",
      status: "ACTIVE",
      version: 0,
    },
  );
  icon.value = null;
  redactionConfirmed.value = false;
  open.value = true;
}
async function save() {
  if (saving.value) return;
  if (icon.value && !redactionConfirmed.value)
    return ElMessage.warning("请确认图标已脱敏");
  saving.value = true;
  try {
    const result = await api(
      editing.value ? `/communities/${editing.value.id}` : "/communities",
      editing.value ? "PUT" : "POST",
      {
        code: form.code,
        name: form.name,
        description: form.description,
        status: form.status,
        version: form.version,
      },
    );
    if (icon.value) {
      const body = new FormData();
      body.append("file", icon.value);
      body.append("redactionConfirmed", "true");
      await http.post(`/communities/${result.id}/icon`, body);
    }
    open.value = false;
    ElMessage.success("社区资料已保存");
    await load();
  } catch (e) {
    fail(e);
  } finally {
    saving.value = false;
  }
}
async function members(row: Row) {
  try {
    workers.value = await api(`/communities/${row.id}/workers`);
    view.value = row;
    viewTitle.value = `${row.name} · 已分配网格员`;
    viewOpen.value = true;
  } catch (e) {
    fail(e);
  }
}
onMounted(load);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>社区管理 🏘️</h1>
      <p>
        维护全部社区资料、图标和状态；在用户管理中分配网格员可进入的一个或多个社区。
      </p>
    </div>
    <el-button class="create-community-btn" type="primary" :icon="Grid" @click="edit()"
      >新增社区</el-button
    >
  </div>

  <div v-if="rows.length" class="community-overview">
    <div class="overview-item">
      <span class="overview-label">社区总数</span>
      <strong class="overview-number brand-text">{{ totalCount }}<small>个</small></strong>
    </div>
    <div class="overview-item">
      <span class="overview-label">启用中</span>
      <strong class="overview-number green-text">{{ activeCount }}<small>个</small></strong>
    </div>
    <div class="overview-item">
      <span class="overview-label">已停用</span>
      <strong class="overview-number warm-text">{{ inactiveCount }}<small>个</small></strong>
    </div>
    <div class="overview-item">
      <span class="overview-label">网格员总数</span>
      <strong class="overview-number brand-text">{{ workerCount }}<small>名</small></strong>
    </div>
  </div>

  <div v-if="rows.length" class="community-toolbar">
    <el-input
      v-model="keyword"
      :prefix-icon="Search"
      placeholder="搜索社区名称或代码"
      clearable
      class="community-search"
    />
    <el-select v-model="statusFilter" class="community-filter">
      <el-option value="ALL" label="全部状态" />
      <el-option value="ACTIVE" label="启用" />
      <el-option value="INACTIVE" label="停用" />
    </el-select>
    <el-select v-model="sortBy" class="community-filter">
      <el-option value="name" label="按名称" />
      <el-option value="status" label="按状态" />
    </el-select>
    <div class="view-switch">
      <button :class="{ active: viewMode === 'card' }" @click="viewMode = 'card'">
        <el-icon><Grid /></el-icon>卡片
      </button>
      <button :class="{ active: viewMode === 'list' }" @click="viewMode = 'list'">
        <el-icon><List /></el-icon>列表
      </button>
    </div>
  </div>

  <div
    v-loading="loading"
    class="community-cards"
    :class="{ 'community-list-mode': viewMode === 'list' }"
  >
    <section
      v-for="(c, index) in filteredRows"
      :key="c.id"
      class="panel community-card"
      :class="{ 'community-inactive': c.status !== 'ACTIVE' }"
      :style="{ '--i': index }"
    >
      <div class="community-header">
        <div class="community-badge">
          <img
            v-if="c.hasIcon"
            :src="`/api/v1/communities/${c.id}/icon?v=${c.version}`"
            alt="社区图标"
          />
          <div v-else class="community-icon">邻</div>
          <span
            class="badge-status-dot"
            :class="c.status === 'ACTIVE' ? 'dot-active' : 'dot-inactive'"
          />
        </div>
        <div class="community-heading">
          <h2>{{ c.name }}</h2>
          <span class="community-code">{{ c.code }}</span>
        </div>
        <span
          class="status-pill"
          :class="c.status === 'ACTIVE' ? 'pill-active' : 'pill-inactive'"
        >
          <i />{{ c.status === "ACTIVE" ? "启用" : "已停用" }}
        </span>
      </div>
      <div class="community-divider" />
      <div v-if="introSummary(c)" class="community-summary">
        <span>◈</span>{{ introSummary(c) }}
      </div>
      <p
        class="community-intro"
        :class="{ 'intro-clamped': !expanded[c.id] && needsExpand(c) }"
      >
        {{ c.description || "尚未填写社区介绍。" }}
      </p>
      <button
        v-if="needsExpand(c)"
        class="expand-btn"
        @click="expanded[c.id] = !expanded[c.id]"
      >
        {{ expanded[c.id] ? "收起全文 ▴" : "展开全文 ▾" }}
      </button>
      <div v-if="abilities(c).length" class="ability-tags">
        <span v-for="a in abilities(c)" :key="a" class="ability-tag">{{ a }}</span>
      </div>
      <div class="community-actions">
        <div class="secondary-actions">
          <el-button class="soft-btn" @click="edit(c)">编辑资料</el-button>
          <el-button class="soft-btn" @click="members(c)">查看网格员</el-button>
        </div>
        <el-button
          :type="c.status === 'ACTIVE' ? 'primary' : 'default'"
          :plain="c.status !== 'ACTIVE'"
          class="enter-btn"
          @click="auth.switchCommunity(c.id)"
          >{{ c.status === "ACTIVE" ? "进入社区" : "查看社区" }}
          <el-icon v-if="c.status === 'ACTIVE'"><ArrowRight /></el-icon
        ></el-button>
      </div>
    </section>
    <div v-if="!filteredRows.length && rows.length" class="community-empty">
      <span>🔍</span>
      <p>没有匹配的社区<br />试试调整搜索词或筛选条件</p>
    </div>
    <div v-if="!rows.length" class="community-empty">
      <span class="empty-illustration">🏘️</span>
      <h3>还没有创建社区</h3>
      <p>新增一个社区，开始维护社区资料与服务范围</p>
      <el-button type="primary" :icon="Grid" @click="edit()">新增社区</el-button>
    </div>
  </div>
  <el-dialog
    v-model="open"
    :title="editing ? '编辑社区' : '新增社区'"
    width="620px"
  ><el-form label-position="top"
      ><el-form-item label="社区代码 *"
        ><el-input
          v-model="form.code"
          maxlength="32"
          placeholder="大写英文、数字、下划线或连字符" /></el-form-item
      ><el-form-item label="社区名称 *"
        ><el-input v-model="form.name" maxlength="80" /></el-form-item
      ><el-form-item label="社区介绍"
        ><el-input
          v-model="form.description"
          type="textarea"
          :rows="5"
          maxlength="2000"
          placeholder="填写服务特色和介绍，不填写居民个人敏感信息" /></el-form-item
      ><el-form-item label="社区图标（PNG/JPEG，最大2MB）"
        ><input
          type="file"
          accept=".png,.jpg,.jpeg"
          aria-label="社区图标"
          @change="
            icon = ($event.target as HTMLInputElement).files?.[0] || null
          " /></el-form-item
      ><el-form-item label="状态"
        ><el-select v-model="form.status"
          ><el-option value="ACTIVE" label="启用" /><el-option
            value="INACTIVE"
            label="停用" /></el-select></el-form-item></el-form
    ><el-checkbox v-if="icon" v-model="redactionConfirmed"
      >确认图标已脱敏，不含身份证、家庭住址或原始手机号</el-checkbox
    >
    <template #footer
      ><el-button @click="open = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="save"
        >保存社区</el-button
      ></template
    ></el-dialog
  >
  <el-dialog
    v-model="viewOpen"
    :title="viewTitle"
    width="600px"
    :close-on-click-modal="false"
  ><el-table
      :data="workers"
      empty-text="尚未分配网格员，请到用户管理中设置社区范围"
      ><el-table-column prop="username" label="账号" /><el-table-column
        prop="displayName"
        label="显示名"
    /></el-table>
    <p class="hint">
      同一社区中的网格员仍然分别负责自己的老人和通知，分配社区不会自动移交老人。
    </p>
    <template #footer
      ><router-link to="/users"
        ><el-button type="primary">前往用户管理分配</el-button></router-link
      ></template
    ></el-dialog
  >
</template>
<style scoped>
.create-community-btn {
  height: 40px;
  padding: 0 20px;
  border-radius: 10px;
  font-weight: 600;
  box-shadow: 0 6px 16px rgba(34, 117, 106, 0.25);
}

/* 概览指标条 */
.community-overview {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 18px;
}
.overview-item {
  background: #fff;
  border: 1px solid #e8eee7;
  border-radius: 14px;
  padding: 16px 20px;
  box-shadow: 0 2px 10px rgba(28, 58, 52, 0.05);
}
.overview-label {
  display: block;
  font-size: 12px;
  color: #7b8c86;
  margin-bottom: 6px;
}
.overview-number {
  font-size: 26px;
  font-weight: 650;
  line-height: 1.2;
}
.overview-number small {
  font-size: 12px;
  font-weight: 400;
  color: #9aa89f;
  margin-left: 5px;
}
.brand-text {
  background: linear-gradient(120deg, #2f7d6e, #5fc7b0);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.green-text {
  color: #3f8f5f;
}
.warm-text {
  color: #a08a6f;
}

/* 筛选工具条 */
.community-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  background: #fff;
  border: 1px solid #e8eee7;
  border-radius: 12px;
  padding: 12px 16px;
  margin-bottom: 20px;
  box-shadow: 0 2px 10px rgba(28, 58, 52, 0.05);
}
.community-search {
  width: 260px;
}
.community-filter {
  width: 140px;
}
.view-switch {
  margin-left: auto;
  display: flex;
  background: #f2f6f2;
  border-radius: 9px;
  padding: 3px;
  gap: 3px;
}
.view-switch button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 0;
  background: transparent;
  border-radius: 7px;
  padding: 6px 12px;
  font-size: 12px;
  color: #7b8c86;
  cursor: pointer;
  transition: all 0.2s ease;
}
.view-switch button.active {
  background: #fff;
  color: #2f7d6e;
  font-weight: 600;
  box-shadow: 0 2px 6px rgba(28, 58, 52, 0.1);
}

/* 卡片网格 */
.community-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 22px;
}
.community-card {
  position: relative;
  overflow: hidden;
  padding: 24px;
  background: linear-gradient(180deg, #ffffff 0%, #fbfcf9 100%);
  border: 1px solid #e5ebe5;
  border-radius: 18px;
  box-shadow: 0 4px 18px rgba(28, 58, 52, 0.07);
  display: flex;
  flex-direction: column;
  animation: cardIn 0.5s ease both;
  animation-delay: calc(var(--i) * 50ms);
  transition: transform 0.3s ease, box-shadow 0.3s ease;
}
.community-card::before {
  content: "";
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 4px;
  background: linear-gradient(180deg, #5fc7b0, #22756a);
  transition: top 0.3s ease, bottom 0.3s ease;
}
.community-card:hover {
  transform: translateY(-6px);
  box-shadow: 0 16px 36px rgba(28, 58, 52, 0.13);
}
.community-card:hover::before {
  top: 15%;
  bottom: 15%;
}
@keyframes cardIn {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 停用态 */
.community-inactive {
  filter: saturate(0.55);
  opacity: 0.82;
}
.community-inactive::before {
  background: #cfd8d2;
}

.community-header {
  display: flex;
  align-items: center;
  gap: 14px;
}
.community-badge {
  position: relative;
  flex: none;
}
.community-badge img,
.community-icon {
  width: 64px;
  height: 64px;
  border-radius: 18px;
  object-fit: contain;
  background: #fff;
  box-shadow: 0 6px 14px rgba(28, 58, 52, 0.1);
  transition: transform 0.25s ease;
}
.community-card:hover .community-badge img,
.community-card:hover .community-icon {
  transform: scale(1.04);
}
.community-icon {
  display: grid;
  place-items: center;
  color: #2f7d6e;
  font-size: 28px;
  background: linear-gradient(135deg, #eaf4ee, #d9eadf);
  box-shadow: inset 0 0 0 1px rgba(47, 125, 110, 0.12);
}
.badge-status-dot {
  position: absolute;
  right: -2px;
  bottom: -2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid #fff;
}
.dot-active {
  background: #4caf72;
}
.dot-inactive {
  background: #b6c0ba;
}
.community-heading {
  min-width: 0;
  flex: 1;
}
.community-heading h2 {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 650;
  color: #1d3b34;
  line-height: 1.3;
}
.community-code {
  font-size: 11px;
  letter-spacing: 0.5px;
  color: #6b8378;
  background: #f1f5f0;
  padding: 2px 8px;
  border-radius: 6px;
}
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 4px 11px;
  border-radius: 999px;
  white-space: nowrap;
}
.status-pill i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.pill-active {
  color: #3f8f5f;
  background: #eef7ea;
}
.pill-active i {
  background: #4caf72;
}
.pill-inactive {
  color: #8a9a8f;
  background: #f2f4f2;
}
.pill-inactive i {
  background: #aab5ae;
}
.community-divider {
  height: 1px;
  background: #eef2ee;
  margin: 16px 0;
}
.community-summary {
  font-size: 13px;
  color: #2f7d6e;
  font-style: italic;
  line-height: 1.6;
  margin-bottom: 8px;
}
.community-summary span {
  margin-right: 6px;
}
.community-intro {
  white-space: pre-wrap;
  line-height: 1.85;
  color: #6d8077;
  font-size: 13px;
  margin: 0;
  flex: 1;
}
.intro-clamped {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.expand-btn {
  align-self: flex-start;
  border: 0;
  background: none;
  color: #2f7d6e;
  font-size: 12px;
  cursor: pointer;
  padding: 4px 0 0;
}
.ability-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}
.ability-tag {
  background: #f4f8f2;
  color: #5d7268;
  border: 1px solid #e6eee3;
  border-radius: 999px;
  padding: 3px 10px;
  font-size: 11.5px;
}
.community-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border-top: 1px dashed #eef2ee;
  margin-top: 16px;
  padding-top: 14px;
}
.secondary-actions {
  display: flex;
  gap: 8px;
}
.soft-btn {
  height: 30px;
  padding: 0 12px;
  border-radius: 8px;
  border: 1px solid #d8e8dc;
  background: linear-gradient(135deg, #f0f7f2, #e7f1ea);
  color: #2f7d6e;
  font-size: 12.5px;
  font-weight: 600;
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
}
.soft-btn:hover {
  transform: translateY(-1px);
  border-color: #a8ccb7;
  box-shadow: 0 4px 10px rgba(47, 125, 110, 0.16);
  color: #1f6559;
}
.enter-btn {
  border-radius: 9px;
  font-size: 13px;
}
.enter-btn .el-icon {
  transition: transform 0.2s ease;
}
.enter-btn:hover .el-icon {
  transform: translateX(2px);
}

/* 列表视图 */
.community-list-mode {
  grid-template-columns: 1fr;
}
.community-list-mode .community-card {
  padding: 18px 24px;
}
.community-list-mode .community-intro {
  -webkit-line-clamp: 1;
}

.community-empty {
  grid-column: 1 / -1;
  text-align: center;
  padding: 60px 20px;
  background: #fff;
  border: 1px dashed #dce7df;
  border-radius: 16px;
}
.community-empty > span {
  font-size: 48px;
  display: block;
  margin-bottom: 12px;
  opacity: 0.7;
}
.community-empty h3 {
  font-size: 17px;
  color: #2a4a3f;
  margin: 0 0 8px;
}
.community-empty p {
  font-size: 13px;
  color: #8a9a8f;
  line-height: 1.7;
  margin: 0 0 18px;
}

@media (max-width: 1100px) {
  .community-overview {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 900px) {
  .community-cards {
    grid-template-columns: 1fr;
  }
}
</style>
