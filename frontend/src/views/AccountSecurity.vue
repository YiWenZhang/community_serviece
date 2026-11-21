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
      <h1>修改密码 🔐</h1>
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
      <el-button
        class="save-btn"
        native-type="submit"
        type="primary"
        :loading="saving"
        >保存新密码</el-button
      >
    </el-form>
  </section>
</template>
<style scoped>
.page-heading h1 {
  font-size: 32px !important;
  letter-spacing: -1px;
}
.page-heading p {
  font-size: 15px !important;
}
.account-security {
  position: relative;
  overflow: hidden;
  max-width: 760px;
  margin: 0 auto;
  background: linear-gradient(180deg, #ffffff 0%, #fbfcf9 100%);
  border: 1px solid #e3ebe3;
  border-radius: 18px;
  padding: 38px 42px 42px;
  box-shadow: 0 18px 50px rgba(24, 60, 50, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
}
.account-security::before {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  height: 4px;
  background: var(--grad-brand);
}
.account-security :deep(.hint) {
  font-size: 14px;
  line-height: 1.9;
  padding: 16px 20px;
}
.account-security :deep(.el-form-item) {
  margin-bottom: 24px;
}
.account-security :deep(.el-form-item__label) {
  font-size: 14px !important;
  color: #385448 !important;
  font-weight: 600;
}
.account-security :deep(.el-input__wrapper) {
  height: 52px;
  border-radius: 11px;
  background: #fbfdfa;
  box-shadow: 0 0 0 1px #dce6dd inset;
  transition: box-shadow 0.2s ease, background 0.2s ease;
}
.account-security :deep(.el-input__wrapper:hover) {
  background: #fff;
  box-shadow: 0 0 0 1px #a8ccb7 inset;
}
.account-security :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow: 0 0 0 1px #3aa6a0 inset !important;
}
.account-security :deep(.el-input__inner) {
  font-size: 15px !important;
  height: 50px !important;
}
.password-strength {
  margin: -8px 0 24px;
  display: grid;
  gap: 10px;
  padding: 14px 16px;
  background: linear-gradient(135deg, #f7faf5, #f0f5ec);
  border: 1px solid #e3ede1;
  border-radius: 10px;
}
.password-strength > span {
  font-size: 13px;
  font-weight: 600;
  color: #385448;
}
.password-strength small {
  font-size: 12px !important;
  color: #8a9a8f !important;
}
.save-btn {
  height: 50px;
  padding: 0 34px;
  font-size: 15px !important;
  border-radius: 11px !important;
  margin-top: 8px;
  box-shadow: 0 8px 20px rgba(47, 125, 110, 0.28) !important;
  transition: transform 0.18s ease, box-shadow 0.18s ease !important;
}
.save-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 26px rgba(47, 125, 110, 0.34) !important;
}
@media (max-width: 760px) {
  .account-security {
    padding: 28px 22px 30px;
    border-radius: 14px;
  }
  .page-heading h1 {
    font-size: 27px !important;
  }
}
</style>
