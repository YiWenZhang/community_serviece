<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { useAuth } from "../stores";
const auth = useAuth(),
  router = useRouter();
const form = reactive({ username: "admin_demo", password: "" });
const loading = ref(false),
  error = ref("");
async function submit() {
  if (!form.username || !form.password || loading.value) return;
  loading.value = true;
  error.value = "";
  try {
    await auth.login(form.username, form.password);
    form.password = "";
    await router.replace("/");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "登录失败";
  } finally {
    loading.value = false;
  }
}
</script>
<template>
  <div class="login-page">
    <section class="login-story">
      <div class="brand">
        <span class="brand-mark">邻</span
        ><span>邻里<small>社区银龄服务</small></span>
      </div>
      <div class="login-copy">
        <span class="eyebrow">COMMUNITY CARE</span>
        <h1>用心服务，<br />让关怀更近一步。</h1>
        <p>
          从一份档案、一次通知到一次跟进，<br />记录每一份服务，让社区关怀有始有终。
        </p>
        <div class="story-line">
          <span>01 档案管理</span><i /><span>02 通知触达</span><i /><span
            >03 服务闭环</span
          >
        </div>
      </div>
      <small>社区工作人员工作平台</small>
      <div class="orbit orbit-one" />
      <div class="orbit orbit-two" />
    </section>
    <section class="login-panel">
      <form class="login-card" @submit.prevent="submit">
        <span class="eyebrow">欢迎回来</span>
        <h2>登录工作空间</h2>
        <p class="muted">使用你的应用账号，开始今天的社区服务。</p>
        <label class="field-label" for="username">应用账号</label
        ><el-input
          id="username"
          v-model="form.username"
          size="large"
          autocomplete="username"
          placeholder="请输入应用账号"
        /><label class="field-label" for="password">应用密码</label
        ><el-input
          id="password"
          v-model="form.password"
          type="password"
          show-password
          size="large"
          autocomplete="current-password"
          placeholder="请输入应用登录密码"
        />
        <p v-if="error" class="error-text" role="alert">{{ error }}</p>
        <el-button
          type="primary"
          native-type="submit"
          size="large"
          class="login-submit"
          :loading="loading"
          :disabled="!form.username || !form.password"
          >登录工作空间 <span>→</span></el-button
        >
        <div class="login-help">
          <strong>第一次使用？</strong>
          <p>
            管理员账号为
            admin_demo，密码是首次启动后端时自行设置的应用密码。它与 MySQL
            密码不同。
          </p>
          <p>网格员、统计员需先由管理员启用并设置密码。</p>
        </div>
      </form>
    </section>
  </div>
</template>
