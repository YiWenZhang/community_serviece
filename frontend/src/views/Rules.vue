<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import { useAuth } from "../stores";
import { api, fail, label, idempotency, type Row } from "../api";
const auth = useAuth(),
  rows = ref<Row[]>([]),
  loading = ref(false),
  error = ref(""),
  open = ref(false),
  saving = ref(false),
  editing = ref<Row | null>(null);
const form = reactive<Row>({
  code: "CARE_" + Date.now(),
  name: "",
  noticeType: "BIRTHDAY",
  leadDays: 7,
  campaignId: null,
  enabled: false,
});
async function load() {
  loading.value = true;
  error.value = "";
  try {
    rows.value = await api("/notice-rules");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
function create() {
  editing.value = null;
  Object.assign(form, {
    code: "CARE_" + Date.now(),
    name: "",
    noticeType: "BIRTHDAY",
    leadDays: 7,
    campaignId: null,
    enabled: false,
  });
  open.value = true;
}
function edit(row: Row) {
  editing.value = { ...row };
  let config: Row = {};
  try {
    config =
      typeof row.configJson === "string"
        ? JSON.parse(row.configJson)
        : row.configJson;
  } catch {
    /* 旧参数使用兼容默认值。 */
  }
  Object.assign(form, {
    code: row.code,
    name: row.name,
    noticeType: row.noticeType,
    leadDays: Number(
      config.leadDays ?? config.prepare_days_before_next_month ?? 7,
    ),
    campaignId: config.campaignId ? String(config.campaignId) : null,
    enabled: Number(row.enabled) === 1,
  });
  open.value = true;
}
async function save() {
  if (!form.code || !form.name)
    return ElMessage.warning("请填写规则代码和名称");
  saving.value = true;
  try {
    const body = {
      ...form,
      code: form.code.toUpperCase(),
      campaignId: form.noticeType === "VACCINE" ? form.campaignId : null,
      version: editing.value?.version || 0,
    };
    await api(
      editing.value ? `/notice-rules/${editing.value.id}` : "/notice-rules",
      editing.value ? "PUT" : "POST",
      body,
    );
    ElMessage.success("规则已保存");
    open.value = false;
    await load();
  } catch (e) {
    fail(e);
  } finally {
    saving.value = false;
  }
}
const generationOpen = ref(false),
  generationRule = ref<Row>({}),
  generationMonth = ref(
    new Date()
      .toLocaleDateString("sv-SE", { timeZone: "Asia/Shanghai" })
      .slice(0, 7),
  ),
  generationJobs = ref<Row[]>([]),
  generating = ref(false),
  generationKey = ref("");
function openGeneration(row: Row) {
  generationRule.value = row;
  generationJobs.value = [];
  generationKey.value = idempotency();
  generationOpen.value = true;
}
async function refreshJobs() {
  try {
    generationJobs.value = await Promise.all(
      generationJobs.value.map((j) => api(`/jobs/${j.id}`)),
    );
  } catch (e) {
    fail(e);
  }
}
async function generateNow() {
  generating.value = true;
  try {
    const r = await api(
      `/notice-rules/${generationRule.value.id}/generate`,
      "POST",
      { ruleId: generationRule.value.id, period: generationMonth.value },
      generationKey.value,
    );
    generationJobs.value = r.jobs;
    ElMessage.success(`已为${r.queuedWorkers}位网格员开始安排通知`);
    await refreshJobs();
  } catch (e) {
    fail(e);
  } finally {
    generating.value = false;
  }
}
onMounted(async () => {
  try {
    await auth.loadDictionaries();
  } catch (e) {
    fail(e);
  }
  await load();
});
</script>
<template>
  <div class="page-heading business-heading">
    <div>
      <h1>自动规则配置 ⚙️</h1>
      <p>
        配置生日关怀、疫苗提醒的筛选条件，系统按档案负责人分配联系工作。临时讲座等事项由网格员在“通知办理”中登记。
      </p>
    </div>
    <el-button type="primary" :icon="Plus" @click="create"
      >新增自动安排</el-button
    >
  </div>
  <p class="hint">
    启用后仅自动建立联系名单，不会自动打电话或发短信。系统在每小时第 10
    分钟扫描本月及提前准备窗口内的生日月份。自动通知按规则、月份和老人去重；初始规则未启用，启用后也可点击“立即生成”。疫苗资格未知的老人不会被自动纳入接种提醒。
  </p>
  <section class="panel business-panel">
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      class="mb"
      :closable="false"
    /><el-table v-loading="loading" :data="rows" empty-text="暂无通知规则"
      ><el-table-column
        prop="name"
        label="自动安排名称"
        min-width="170"
      /><el-table-column label="服务类型" min-width="115"
        ><template #default="{ row }">{{
          label(row.noticeType)
        }}</template></el-table-column
      ><el-table-column label="状态" width="100"
        ><template #default="{ row }"
          ><span
            class="status"
            :class="Number(row.enabled) ? 'ACTIVE' : 'DISABLED'"
            >{{ Number(row.enabled) ? "已启用" : "未启用" }}</span
          ></template
        ></el-table-column
      ><el-table-column label="操作" min-width="210" class-name="actions-column"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="edit(row)">编辑</el-button
          ><el-button
            link
            type="primary"
            :disabled="!Number(row.enabled)"
            @click="openGeneration(row)"
            >立即安排联系名单</el-button
          ></template
        ></el-table-column
      ></el-table
    >
  </section>
  <el-dialog
    v-model="open"
    :title="editing ? '编辑通知规则' : '新增通知规则'"
    width="630px"
    destroy-on-close
    ><el-form label-position="top"
      ><div class="form-grid">
        <el-form-item v-if="false" label="内部代码"
          ><el-input
            v-model="form.code"
            placeholder="大写英文、数字或下划线"
            maxlength="32" /></el-form-item
        ><el-form-item label="自动安排名称 *"
          ><el-input v-model="form.name" maxlength="80" /></el-form-item
        ><el-form-item label="通知类型"
          ><el-select v-model="form.noticeType"
            ><el-option label="生日关怀" value="BIRTHDAY" /><el-option
              label="疫苗接种提醒"
              value="VACCINE" /></el-select></el-form-item
        ><el-form-item
          v-if="form.noticeType === 'BIRTHDAY'"
          label="提前准备下月名单（天）"
          ><el-input-number
            v-model="form.leadDays"
            :min="0"
            :max="30" /></el-form-item
        ><el-form-item
          v-if="form.noticeType === 'VACCINE'"
          label="疫苗服务周期 *"
          class="span-2"
          ><el-select v-model="form.campaignId"
            ><el-option
              v-for="c in auth.dictionaries.vaccineCampaigns"
              :key="c.id"
              :label="c.name"
              :value="c.id" /></el-select></el-form-item
        ><el-form-item label="启用状态" class="span-2"
          ><el-switch
            v-model="form.enabled"
            active-text="启用规则"
            inactive-text="暂不启用"
        /></el-form-item></div></el-form
    ><template #footer
      ><el-button @click="open = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="save"
        >保存规则</el-button
      ></template
    ></el-dialog
  >
  <el-dialog v-model="generationOpen" title="立即安排联系名单" width="650px"
    ><p>
      {{
        generationRule.name
      }}：按本社区每位网格员的负责范围创建通知，已生成的老人自动跳过。
    </p>
    <el-date-picker
      v-model="generationMonth"
      type="month"
      value-format="YYYY-MM"
      :clearable="false"
    /><el-button type="primary" :loading="generating" @click="generateNow"
      >开始安排</el-button
    ><el-button @click="refreshJobs">刷新安排进度</el-button
    ><el-table :data="generationJobs"
      ><el-table-column prop="workerName" label="负责网格员" /><el-table-column
        prop="status"
        label="状态" /><el-table-column prop="errorSummary" label="处理说明"
    /></el-table>
    <p class="hint">
      “已完成”表示名单安排完毕；没有符合条件的老人时不新增通知。结果在“通知管理”中查看。
    </p></el-dialog
  >
</template>
