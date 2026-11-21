<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import { api, query, fail, type Row } from "../api";
import { useAuth } from "../stores";
import Status from "../components/Status.vue";
const auth = useAuth(),
  router = useRouter(),
  rows = ref<Row[]>([]),
  roles = ref<Row[]>([]),
  page = ref(1),
  total = ref(0),
  loading = ref(false),
  error = ref(""),
  open = ref(false),
  saving = ref(false),
  editing = ref<Row | null>(null);
const form = reactive<Row>({
  username: "",
  displayName: "",
  status: "ACTIVE",
  roleIds: [],
  communityIds: [],
});
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const r = await api("/users?" + query({ page: page.value, pageSize: 20 }));
    rows.value = r.items;
    total.value = Number(r.total);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
function create() {
  editing.value = null;
  Object.assign(form, {
    username: "",
    displayName: "",
    status: "ACTIVE",
    roleIds: [],
    communityIds:
      auth.communityId && auth.communityId !== "0" ? [auth.communityId] : [],
  });
  open.value = true;
}
function edit(row: Row) {
  editing.value = { ...row };
  Object.assign(form, {
    username: row.username,
    displayName: row.displayName,
    status: row.status,
    roleIds: [...(row.roleIds || [])],
    communityIds: [...(row.communityIds || [])],
  });
  open.value = true;
}
const roleName = (id: string) =>
  roles.value.find((r) => r.id === id)?.name || id;
async function save() {
  if (saving.value) return;
  if (!form.username || !form.displayName || !form.roleIds.length)
    return ElMessage.warning("请填写账号、显示名并选择角色");
  saving.value = true;
  try {
    if (editing.value) {
      await api(`/users/${editing.value.id}`, "PATCH", {
        version: editing.value.version,
        displayName: form.displayName,
        status: form.status,
        roleIds: form.roleIds,
        communityIds: form.communityIds,
      });
      if (editing.value.id === auth.user?.id) {
        open.value = false;
        auth.clearSession();
        await router.push("/login");
        ElMessage.success("自己的账号权限已更新，请重新登录");
        return;
      }
    } else
      await api("/users", "POST", {
        username: form.username,
        displayName: form.displayName,
        roleIds: form.roleIds,
        communityIds: form.communityIds,
      });
    ElMessage.success("工作人员账号已保存");
    open.value = false;
    await load();
  } catch (e) {
    fail(e);
  } finally {
    saving.value = false;
  }
}
async function resetPassword(row: Row) {
  try {
    await ElMessageBox.confirm(
      `将“${row.username}”的密码重置为123456？其所有旧登录会话将失效。`,
      "重置应用密码",
      { type: "warning", confirmButtonText: "确认重置" },
    );
    await api(`/users/${row.id}/reset-password`, "POST", {
      version: row.version,
    });
    if (row.id === auth.user?.id) {
      auth.clearSession();
      await router.replace("/login");
    } else await load();
    ElMessage.success("密码已重置为123456，请通知用户自行修改");
  } catch (e) {
    if (e !== "cancel" && e !== "close") fail(e);
  }
}
onMounted(async () => {
  try {
    roles.value = await api("/roles");
  } catch (e) {
    fail(e);
  }
  await load();
});
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>用户管理 👥</h1>
      <p>
        管理全部工作人员，按岗位授予权限，并分配网格员可进入的一个或多个社区。
      </p>
    </div>
    <el-button type="primary" :icon="Plus" @click="create"
      >新增工作人员</el-button
    >
  </div>
  <el-alert
    title="新账号初始密码统一为123456（弱）。工作人员按姓名和账号管理，网格员可分配一个或多个社区。"
    type="info"
    :closable="false"
    show-icon
    class="user-note"
  />
  <section class="panel">
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      class="mb"
    /><el-table v-loading="loading" :data="rows" empty-text="暂无工作人员"
      ><el-table-column
        prop="username"
        label="应用账号"
        min-width="160"
      /><el-table-column
        prop="displayName"
        label="显示名称"
        min-width="150"
      /><el-table-column label="当前角色" min-width="190"
        ><template #default="{ row }">{{
          (row.roleIds || []).map(roleName).join(" / ") || "无角色"
        }}</template></el-table-column
      ><el-table-column label="授权社区" min-width="190"
        ><template #default="{ row }">{{
          (row.communityIds || [])
            .map(
              (id: string) =>
                auth.communities.find((c) => c.id === id)?.name || id,
            )
            .join("、") || "按管理员或统计员岗位访问全部社区"
        }}</template></el-table-column
      ><el-table-column label="状态" width="110"
        ><template #default="{ row }"
          ><Status :value="row.status" /></template></el-table-column
      ><el-table-column label="操作" min-width="230" class-name="actions-column"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="edit(row)">编辑</el-button
          ><el-button link type="warning" @click="resetPassword(row)"
            >重置为123456</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="20"
        :total="total"
        layout="total,prev,pager,next"
        @current-change="load"
      />
    </div>
  </section>
  <el-dialog
    v-model="open"
    :title="editing ? '编辑工作人员' : '新增工作人员'"
    width="630px"
    destroy-on-close
    ><p class="hint">
      应用初始密码为123456，与MySQL密码不同，用户可在右上角修改密码。修改用户密码、角色或状态后，该用户原会话会失效。管理员可管理全部社区的老人；网格员在分配社区内仍只操作本人负责的记录。统计员只查看汇总及报表。
    </p>
    <el-form label-position="top"
      ><div class="form-grid">
        <el-form-item label="应用账号 *"
          ><el-input
            v-model="form.username"
            :disabled="!!editing"
            maxlength="64"
            autocomplete="off" /></el-form-item
        ><el-form-item label="显示名称 *"
          ><el-input v-model="form.displayName" maxlength="64" /></el-form-item
        ><el-form-item v-if="editing" label="账号状态"
          ><el-select v-model="form.status"
            ><el-option value="ACTIVE" label="启用" /><el-option
              value="DISABLED"
              label="停用" /></el-select></el-form-item
        ><el-form-item label="分配社区（网格员至少一个）" class="span-2"
          ><el-select
            v-model="form.communityIds"
            multiple
            placeholder="选择可进入的社区"
            ><el-option
              v-for="c in auth.communities.filter((c) => c.status === 'ACTIVE')"
              :key="c.id"
              :label="c.name"
              :value="c.id" /></el-select></el-form-item
        ><el-form-item label="角色 *" class="span-2"
          ><el-checkbox-group v-model="form.roleIds"
            ><el-checkbox v-for="r in roles" :key="r.id" :value="r.id">{{
              r.name
            }}</el-checkbox></el-checkbox-group
          ></el-form-item
        >
      </div></el-form
    ><template #footer
      ><el-button @click="open = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="save"
        >保存账号</el-button
      ></template
    ></el-dialog
  >
</template>
