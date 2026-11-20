<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { api, fail } from "../api";
import { useAuth } from "../stores";
import { passwordStrength } from "../passwords";
const auth = useAuth(),
  router = useRouter();
const form = reactive({
  currentPassword: "",
  newPassword: "",
  confirmPassword: "",
});
const saving = ref(false),
  defaultPassword = ref(false);
const strength = computed(() => passwordStrength(form.newPassword));
onMounted(async () => {
  try {
    defaultPassword.value = (
      await api("/auth/password-status")
    ).defaultPassword;
  } catch (e) {
    fail(e);
  }
});
async function save() {
  if (saving.value) return;
  if (!form.currentPassword || !strength.value.valid)
    return ElMessage.warning(
      "请填写当前密码，新密码至少6个字符且不超过72个UTF-8字节",
    );
  if (form.newPassword !== form.confirmPassword)
    return ElMessage.warning("两次新密码不一致");
  saving.value = true;
  try {
    await api("/auth/password", "POST", { ...form });
    Object.assign(form, {
      currentPassword: "",
      newPassword: "",
      confirmPassword: "",
    });
    auth.clearSession();
    await router.replace("/login");
    ElMessage.success("密码已修改，原登录会话已失效，请使用新密码登录");
  } catch (e) {
    fail(e);
  } finally {
    saving.value = false;
  }
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>修改密码</h1>
      <p>管理自己的登录密码，所有岗位均可使用。</p>
    </div>
  </div>
  <section class="panel account-security">
    <el-alert
      v-if="defaultPassword"
      title="当前使用初始密码123456，强度为弱，建议修改。"
      type="warning"
      :closable="false"
      show-icon
      class="mb"
    />
    <p class="hint">
      密码至少6个字符，最多72个UTF-8字节。建议组合大小写字母、数字和符号。修改成功后，所有旧登录会话都会失效。
    </p>
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="当前密码"
        ><el-input
          v-model="form.currentPassword"
          type="password"
          show-password
          autocomplete="current-password"
          maxlength="128"
      /></el-form-item>
      <el-form-item label="新密码"
        ><el-input
          v-model="form.newPassword"
          type="password"
          show-password
          autocomplete="new-password"
          maxlength="72"
      /></el-form-item>
      <div
        v-if="form.newPassword"
        class="password-strength"
        role="status"
        aria-live="polite"
      >
        <span>密码强度：{{ strength.label }}</span
        ><el-progress
          :percentage="strength.percent"
          :show-text="false"
          :color="strength.color"
        /><small class="muted">{{ strength.bytes }} / 72 字节</small>
      </div>
      <el-form-item label="确认新密码"
        ><el-input
          v-model="form.confirmPassword"
          type="password"
          show-password
          autocomplete="new-password"
          maxlength="72"
      /></el-form-item>
      <el-button native-type="submit" type="primary" :loading="saving"
        >保存新密码</el-button
      >
    </el-form>
  </section>
</template>
<style scoped>
.account-security {
  max-width: 680px;
}
.password-strength {
  margin: -4px 0 20px;
  display: grid;
  gap: 8px;
}
</style>
