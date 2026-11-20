<script setup lang="ts">
import { ref, computed } from "vue";
import { api, http, fail, type Row } from "../api";
import { ElMessage } from "element-plus";
const model = defineModel<Row>({ required: true });
const uploading = ref(false),
  confirmed = ref(false),
  uploadedName = ref("");
const needsEvidence = computed(
  () =>
    model.value.channel === "BULLETIN" ||
    (model.value.channel === "HOME_VISIT" &&
      model.value.result === "CONFIRMED"),
);
function channelChanged() {
  model.value.result =
    model.value.channel === "BULLETIN" ? "POSTED" : "UNREACHABLE";
  model.value.fileIds = [];
  uploadedName.value = "";
  model.value.familyNameMasked = null;
  model.value.familyRelation = null;
  model.value.relayConfirmed = null;
}
async function upload(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  if (!confirmed.value) {
    ElMessage.warning("请先确认图片已脱敏");
    return;
  }
  uploading.value = true;
  try {
    const form = new FormData();
    form.append("file", file);
    form.append("redactionConfirmed", "true");
    const response = await http.post("/files", form);
    const id = response.data.data.id;
    await api(`/files/${id}/finalize`, "POST", { redactionConfirmed: true });
    model.value.fileIds = [id];
    uploadedName.value = file.name;
    ElMessage.success("凭证已上传");
  } catch (e) {
    fail(e);
  } finally {
    uploading.value = false;
  }
}
</script>
<template>
  <div class="form-grid">
    <el-form-item label="通知方式"
      ><el-select v-model="model.channel" @change="channelChanged"
        ><el-option label="上门告知" value="HOME_VISIT" /><el-option
          label="家属转告"
          value="FAMILY_RELAY" /><el-option
          label="公告栏张贴"
          value="BULLETIN" /></el-select
    ></el-form-item>
    <el-form-item label="接收结果"
      ><el-select v-model="model.result"
        ><template v-if="model.channel !== 'BULLETIN'"
          ><el-option label="未联系上" value="UNREACHABLE" /><el-option
            label="已确认接收"
            value="CONFIRMED" /><el-option
            label="拒绝接收"
            value="REFUSED" /></template
        ><el-option
          v-else
          label="已张贴（不代表个人接收）"
          value="POSTED" /></el-select
    ></el-form-item>
    <el-form-item v-if="model.result === 'UNREACHABLE'" label="未联系原因"
      ><el-select v-model="model.unreachableReason"
        ><el-option label="上门无人" value="NO_ONE_HOME" /><el-option
          label="电话无人接"
          value="NO_ANSWER" /><el-option
          label="其他"
          value="OTHER" /></el-select
    ></el-form-item>
    <el-form-item label="原因说明"
      ><el-input
        v-model="model.reasonText"
        maxlength="300"
        placeholder="拒绝接收或其他原因时必填"
    /></el-form-item>
    <template v-if="model.channel === 'FAMILY_RELAY'"
      ><el-form-item label="家属脱敏称谓"
        ><el-input
          v-model="model.familyNameMasked"
          placeholder="例如：张某"
          maxlength="40" /></el-form-item
      ><el-form-item label="与老人关系"
        ><el-input
          v-model="model.familyRelation"
          placeholder="例如：女儿"
          maxlength="32" /></el-form-item
      ><el-form-item class="span-2"
        ><el-checkbox v-model="model.relayConfirmed"
          >家属已明确确认转告</el-checkbox
        ></el-form-item
      ></template
    >
    <el-form-item label="客观反馈原话" class="span-2"
      ><el-input
        v-model="model.feedbackQuote"
        type="textarea"
        :rows="2"
        maxlength="500"
        placeholder="如实记录；不填写手机号、证件号和家庭地址"
    /></el-form-item>
    <div v-if="needsEvidence" class="evidence span-2">
      <strong>{{
        model.channel === "BULLETIN" ? "上传张贴照片" : "上传签字确认图片"
      }}</strong>
      <p>仅支持 PNG / JPEG，最大 20MB。</p>
      <el-checkbox v-model="confirmed">我已遮盖图片中的无关个人信息</el-checkbox
      ><input
        aria-label="上传脱敏凭证"
        type="file"
        accept="image/png,image/jpeg"
        :disabled="!confirmed || uploading"
        @change="upload"
      /><small v-if="uploading">正在上传，请稍候…</small
      ><small v-else-if="uploadedName">已上传：{{ uploadedName }}</small>
    </div>
  </div>
</template>
