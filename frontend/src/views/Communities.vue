<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
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
  view = ref<Row | null>(null);
const form = reactive<Row>({
  code: "",
  name: "",
  description: "",
  status: "ACTIVE",
  version: 0,
});
const redactionConfirmed = ref(false);
async function load() {
  loading.value = true;
  try {
    rows.value = await api("/communities");
    await auth.refreshCommunities();
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
  } catch (e) {
    fail(e);
  }
}
onMounted(load);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>社区管理</h1>
      <p>
        维护全部社区资料、图标和状态；在用户管理中分配网格员可进入的一个或多个社区。
      </p>
    </div>
    <el-button type="primary" @click="edit()">新增社区</el-button>
  </div>
  <div v-loading="loading" class="community-cards">
    <section v-for="c in rows" :key="c.id" class="panel community-card">
      <div class="community-header">
        <img
          v-if="c.hasIcon"
          :src="`/api/v1/communities/${c.id}/icon?v=${c.version}`"
          alt="社区图标"
        />
        <div v-else class="community-icon">邻</div>
        <div>
          <h2>{{ c.name }}</h2>
          <small class="muted">{{ c.code }}</small>
        </div>
        <el-tag :type="c.status === 'ACTIVE' ? 'success' : 'info'">{{
          c.status === "ACTIVE" ? "启用" : "停用"
        }}</el-tag>
      </div>
      <p class="community-intro">{{ c.description || "尚未填写社区介绍。" }}</p>
      <div class="community-actions">
        <el-button @click="edit(c)">编辑资料</el-button
        ><el-button @click="members(c)">查看网格员</el-button
        ><el-button type="primary" plain @click="auth.switchCommunity(c.id)"
          >进入社区</el-button
        >
      </div>
    </section>
  </div>
  <el-dialog
    v-model="open"
    :title="editing ? '编辑社区' : '新增社区'"
    width="620px"
    destroy-on-close
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
    :model-value="!!view"
    :title="`${view?.name || ''} · 已分配网格员`"
    width="600px"
    @close="view = null"
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
.community-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 370px), 1fr));
  gap: 22px;
}
.community-header {
  display: flex;
  align-items: center;
  gap: 14px;
}
.community-header img,
.community-icon {
  width: 58px;
  height: 58px;
  border-radius: 14px;
  object-fit: contain;
  background: #edf4ec;
}
.community-icon {
  display: grid;
  place-items: center;
  color: #497860;
  font-size: 26px;
}
.community-header h2 {
  margin: 0 0 5px;
}
.community-header .el-tag {
  margin-left: auto;
}
.community-intro {
  white-space: pre-wrap;
  line-height: 1.8;
  color: #73867a;
  font-size: 14px;
  margin: 22px 0;
  min-height: 60px;
}
.community-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.community-actions .el-button {
  margin: 0;
}
</style>
