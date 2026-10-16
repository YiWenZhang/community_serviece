<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import { useAuth } from "../stores";
import { api, fail, label, type Row } from "../api";
const auth = useAuth(),
  rows = ref<Row[]>([]),
  loading = ref(false),
  error = ref(""),
  open = ref(false),
  saving = ref(false),
  editing = ref<Row | null>(null);
const form = reactive<Row>({
  code: "",
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
    code: "",
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
  <div class="page-heading">
    <div>
      <h1>通知规则</h1>
      <p>维护生日关怀与疫苗提醒规则，启用后按工作人员范围自动生成。</p>
    </div>
    <el-button type="primary" :icon="Plus" @click="create">新增规则</el-button>
  </div>
  <p class="hint">
    已启用规则在每小时第 10
    分钟扫描。自动通知按规则、月份和老人去重；默认示例规则未启用。疫苗资格未知的老人不会被自动纳入接种提醒。
  </p>
  <section class="panel">
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      class="mb"
      :closable="false"
    /><el-table v-loading="loading" :data="rows" empty-text="暂无通知规则"
      ><el-table-column
        prop="code"
        label="规则代码"
        min-width="200"
      /><el-table-column
        prop="name"
        label="规则名称"
        min-width="170"
      /><el-table-column label="服务类型" min-width="115"
        ><template #default="{ row }">{{
          label(row.noticeType)
        }}</template></el-table-column
      ><el-table-column
        prop="ruleVersion"
        label="规则版本"
        width="100"
      /><el-table-column label="状态" width="100"
        ><template #default="{ row }"
          ><span
            class="status"
            :class="Number(row.enabled) ? 'ACTIVE' : 'DISABLED'"
            >{{ Number(row.enabled) ? "已启用" : "未启用" }}</span
          ></template
        ></el-table-column
      ><el-table-column label="操作" width="100"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="edit(row)"
            >编辑</el-button
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
        <el-form-item label="规则代码 *"
          ><el-input
            v-model="form.code"
            placeholder="大写英文、数字或下划线"
            maxlength="32" /></el-form-item
        ><el-form-item label="规则名称 *"
          ><el-input v-model="form.name" maxlength="80" /></el-form-item
        ><el-form-item label="通知类型"
          ><el-select v-model="form.noticeType"
            ><el-option label="生日关怀" value="BIRTHDAY" /><el-option
              label="疫苗接种提醒"
              value="VACCINE" /></el-select></el-form-item
        ><el-form-item label="提前准备天数"
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
</template>
