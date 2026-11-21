<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { api, fail } from "../api";
const form = reactive({
  version: 0,
  enabled: false,
  endpoint: "",
  apiKey: "",
  signature: "",
  templateId: "",
  hasKey: false,
});
const loading = ref(false),
  saving = ref(false);
async function load() {
  loading.value = true;
  try {
    Object.assign(
      form,
      {
        enabled: false,
        endpoint: "",
        signature: "",
        templateId: "",
        hasKey: false,
      },
      await api("/sms/settings"),
    );
    form.apiKey = "";
  } catch (e) {
    fail(e);
  } finally {
    loading.value = false;
  }
}
async function save() {
  saving.value = true;
  try {
    const result = await api("/sms/settings", "PUT", {
      version: form.version,
      enabled: form.enabled,
      endpoint: form.endpoint,
      apiKey: form.apiKey || null,
      signature: form.signature,
      templateId: form.templateId,
    });
    Object.assign(form, result);
    form.apiKey = "";
    ElMessage.success("短信配置已保存");
  } catch (e) {
    fail(e);
  } finally {
    saving.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>短信平台设置 💬</h1>
      <p>为当前社区配置已备案的短信签名、模板与平台接口。</p>
    </div>
  </div>
  <section class="panel" v-loading="loading">
    <el-alert
      title="短信仅发送至老人档案中已登记的家属手机号；平台受理不代表老人已确认接收。未配置的平台不会发送短信。"
      type="info"
      :closable="false"
      class="mb"
    />
    <el-form label-position="top" @submit.prevent="save"
      ><div class="form-grid">
        <el-form-item label="HTTPS接口地址" class="span-2"
          ><el-input
            v-model="form.endpoint"
            maxlength="500"
            placeholder="备案平台的兼容网关地址，例如 https://sms.your-domain.cn/send"
        /></el-form-item>
        <el-form-item label="接口密钥"
          ><el-input
            v-model="form.apiKey"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="
              form.hasKey ? '已保存，留空保持原密钥' : '填写平台密钥'
            "
            maxlength="500"
        /></el-form-item>
        <el-form-item label="备案短信签名"
          ><el-input v-model="form.signature" maxlength="80"
        /></el-form-item>
        <el-form-item label="备案模板编号"
          ><el-input v-model="form.templateId" maxlength="100"
        /></el-form-item>
        <el-form-item label="启用状态"
          ><el-switch v-model="form.enabled" active-text="启用短信发送"
        /></el-form-item>
      </div>
      <el-button type="primary" :loading="saving" @click="save"
        >保存短信配置</el-button
      ></el-form
    >
    <div class="detail-section">
      <h3>平台接口约定</h3>
      <p class="hint">
        使用HTTPS
        JSON网关，Bearer密钥认证，支持Idempotency-Key去重。网关必须返回
        accepted=true 和
        messageId，系统才登记“平台已受理”。供应商原生接口格式不同的，需要部署适配网关；接入约定见项目《功能核对与管理员操作流程》。密钥加密保存且不会回显。
      </p>
    </div>
  </section>
</template>
