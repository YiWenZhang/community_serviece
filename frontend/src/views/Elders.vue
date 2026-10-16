<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import {
  Plus,
  Search,
  Refresh,
  User,
  CircleCheck,
  Calendar,
  WarningFilled,
  ArrowUp,
} from "@element-plus/icons-vue";
import { useAuth } from "../stores";
import { api, query, fail, label, date, type Row } from "../api";
import Status from "../components/Status.vue";
import StatCard from "../components/StatCard.vue";
const auth = useAuth(),
  rows = ref<Row[]>([]),
  total = ref(0),
  page = ref(1),
  loading = ref(false),
  error = ref(""),
  stats = ref<Row>({});
const filters = reactive({
  elderNo: "",
  birthMonth: "",
  vaccinationStatus: "",
  needId: "",
});
const editOpen = ref(false),
  saving = ref(false),
  editing = ref<Row | null>(null);
const form = reactive<Row>({
  gridId: "",
  sex: "UNKNOWN",
  birthMonth: "",
  needIds: [],
  remark: "",
  status: "ACTIVE",
});
const detail = ref<Row | null>(null),
  history = ref<Row>({}),
  detailOpen = ref(false);
const vaccineOpen = ref(false),
  vaccineForm = reactive<Row>({}),
  vaccineElder = ref<Row>({});
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const result = await api(
      "/elders?" + query({ ...filters, page: page.value, pageSize: 20 }),
    );
    rows.value = result.items;
    total.value = Number(result.total);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
async function loadStats() {
  try {
    const month = new Date().getMonth() + 1;
    const [all, vaccinated, birthdays] = await Promise.all([
      api("/elders?pageSize=1"),
      api("/elders?vaccinationStatus=COMPLETED&pageSize=1"),
      api(`/elders?birthMonth=${month}&pageSize=1`),
    ]);
    stats.value = {
      total: Number(all.total),
      vaccinated: Number(vaccinated.total),
      birthdays: Number(birthdays.total),
    };
  } catch {
    /* 统计卡片失败不阻断主列表 */
  }
}
const vaccinationRate = computed(() =>
  stats.value.total
    ? `${((stats.value.vaccinated / stats.value.total) * 100).toFixed(1)}%`
    : "—",
);
function create() {
  editing.value = null;
  Object.assign(form, {
    gridId: auth.dictionaries.grids[0]?.id || "",
    sex: "UNKNOWN",
    birthMonth: "",
    needIds: [],
    remark: "",
    status: "ACTIVE",
  });
  editOpen.value = true;
}
async function edit(row: Row) {
  try {
    const d = await api(`/elders/${row.id}`);
    editing.value = d;
    Object.assign(form, {
      gridId: d.gridId,
      sex: d.sex,
      birthMonth: `${d.birthYear}-${String(d.birthMonth).padStart(2, "0")}`,
      needIds: d.needIds,
      remark: d.remark || "",
      status: d.status,
    });
    editOpen.value = true;
  } catch (e) {
    fail(e);
  }
}
async function save() {
  if (saving.value) return;
  if (!form.gridId || !form.birthMonth)
    return ElMessage.warning("请填写网格与出生年月");
  saving.value = true;
  try {
    const profile = {
      gridId: form.gridId,
      sex: form.sex,
      birthMonth: form.birthMonth,
      needIds: form.needIds,
      remark: form.remark || null,
    };
    if (editing.value)
      await api(`/elders/${editing.value.id}`, "PATCH", {
        version: editing.value.version,
        profile,
        status: form.status,
      });
    else await api("/elders", "POST", profile);
    ElMessage.success("档案已保存");
    editOpen.value = false;
    await load();
  } catch (e) {
    fail(e);
  } finally {
    saving.value = false;
  }
}
async function show(row: Row) {
  try {
    detail.value = await api(`/elders/${row.id}`);
    history.value = await api(`/elders/${row.id}/history`);
    detailOpen.value = true;
  } catch (e) {
    fail(e);
  }
}
function campaignName(id: string) {
  return (
    auth.dictionaries.vaccineCampaigns.find((v: Row) => v.id === id)?.name ||
    `周期 ${id}`
  );
}
async function vaccine(row: Row) {
  try {
    vaccineElder.value = await api(`/elders/${row.id}`);
    Object.assign(vaccineForm, {
      campaignId: auth.dictionaries.vaccineCampaigns[0]?.id || "",
      reason: "",
    });
    selectCampaign();
    vaccineOpen.value = true;
  } catch (e) {
    fail(e);
  }
}
function selectCampaign() {
  const current = vaccineElder.value.vaccines?.find(
    (v: Row) => v.campaignId === vaccineForm.campaignId,
  );
  Object.assign(vaccineForm, {
    version: current?.version || 0,
    eligibility: current?.eligibility || "UNKNOWN",
    vaccinationStatus: current?.vaccinationStatus || "UNKNOWN",
    appointmentDate: current?.appointmentDate || null,
    vaccinatedOn: null,
    doseNo: 1,
  });
}
async function saveVaccine() {
  if (!vaccineForm.campaignId || !vaccineForm.reason?.trim())
    return ElMessage.warning("请选择周期并填写核验说明");
  saving.value = true;
  try {
    await api(`/elders/${vaccineElder.value.id}/vaccines`, "PUT", {
      ...vaccineForm,
      appointmentDate: vaccineForm.appointmentDate || null,
      vaccinatedOn: vaccineForm.vaccinatedOn || null,
      doseNo: ["PARTIAL", "COMPLETED"].includes(vaccineForm.vaccinationStatus)
        ? vaccineForm.doseNo
        : null,
    });
    ElMessage.success("核验记录已保存");
    vaccineOpen.value = false;
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
  await loadStats();
});
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>老人档案</h1>
      <p>维护本人负责的基础资料、服务需求与接种核验记录。</p>
    </div>
    <el-button
      v-if="auth.can('elder:create')"
      type="primary"
      :icon="Plus"
      @click="create"
      >登记老人</el-button
    >
  </div>
  <section class="stat-tiles">
    <StatCard
      label="总人数"
      :value="stats.total ?? '—'"
      suffix="人"
      :icon="User"
      tone="blue"
      hint="本人负责全部档案"
    />
    <StatCard
      label="已接种疫苗"
      :value="stats.vaccinated ?? '—'"
      suffix="人"
      :icon="CircleCheck"
      tone="green"
      :hint="`接种率 ${vaccinationRate}`"
      hint-tone="up"
      :hint-icon="ArrowUp"
    />
    <StatCard
      label="本月生日"
      :value="stats.birthdays ?? '—'"
      suffix="人"
      :icon="Calendar"
      tone="gold"
      hint="需发送生日关怀"
      hint-tone="warn"
      :hint-icon="WarningFilled"
    />
  </section>
  <section class="panel">
    <div class="toolbar">
      <el-input
        v-model="filters.elderNo"
        clearable
        placeholder="完整老人编号"
        @keyup.enter="
          page = 1;
          load();
        "
      /><el-select v-model="filters.birthMonth" clearable placeholder="出生月份"
        ><el-option
          v-for="m in 12"
          :key="m"
          :value="String(m)"
          :label="`${m} 月`" /></el-select
      ><el-select
        v-model="filters.vaccinationStatus"
        clearable
        placeholder="接种状态"
        ><el-option
          v-for="v in ['UNKNOWN', 'NOT_VACCINATED', 'PARTIAL', 'COMPLETED']"
          :key="v"
          :value="v"
          :label="v === 'COMPLETED' ? '已完成接种' : label(v)" /></el-select
      ><el-button
        :icon="Search"
        @click="
          page = 1;
          load();
        "
        >查询</el-button
      ><el-button :icon="Refresh" text aria-label="刷新档案" @click="load" />
    </div>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      class="mb"
    /><el-table
      v-loading="loading"
      :data="rows"
      empty-text="暂无本人负责的老人档案，可登记或由管理员移交。"
      ><el-table-column
        prop="elderNo"
        label="老人编号"
        min-width="175"
      /><el-table-column label="性别" width="75"
        ><template #default="{ row }">{{
          label(row.sex)
        }}</template></el-table-column
      ><el-table-column label="出生年月" min-width="120"
        ><template #default="{ row }"
          >{{ row.birthYear }} 年 {{ row.birthMonth }} 月</template
        ></el-table-column
      ><el-table-column label="状态" width="100"
        ><template #default="{ row }"
          ><Status :value="row.status" /></template></el-table-column
      ><el-table-column label="更新时间" min-width="165"
        ><template #default="{ row }">{{
          date(row.infoUpdatedAt)
        }}</template></el-table-column
      ><el-table-column label="操作" min-width="190"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="show(row)">详情</el-button
          ><el-button
            v-if="auth.can('elder:update')"
            link
            type="primary"
            @click="edit(row)"
            >编辑</el-button
          ><el-button
            v-if="auth.can('vaccine:verify')"
            link
            type="primary"
            @click="vaccine(row)"
            >接种核验</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="20"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </div>
  </section>
  <el-dialog
    v-model="editOpen"
    :title="editing ? '编辑老人档案' : '登记老人档案'"
    width="650px"
    destroy-on-close
    ><p class="hint">
      仅登记业务所需信息。编号自动生成，不录入姓名、手机号、身份证或家庭住址。
    </p>
    <el-form label-position="top" @submit.prevent="save"
      ><div class="form-grid">
        <el-form-item label="所属网格 *"
          ><el-select v-model="form.gridId"
            ><el-option
              v-for="g in auth.dictionaries.grids"
              :key="g.id"
              :label="g.name"
              :value="g.id" /></el-select></el-form-item
        ><el-form-item label="性别"
          ><el-select v-model="form.sex"
            ><el-option label="男" value="MALE" /><el-option
              label="女"
              value="FEMALE" /><el-option
              label="待核实"
              value="UNKNOWN" /></el-select></el-form-item
        ><el-form-item label="出生年月 *"
          ><el-date-picker
            v-model="form.birthMonth"
            type="month"
            value-format="YYYY-MM"
            placeholder="选择年月" /></el-form-item
        ><el-form-item v-if="editing" label="档案状态"
          ><el-select v-model="form.status"
            ><el-option label="启用" value="ACTIVE" /><el-option
              label="停用"
              value="INACTIVE" /><el-option
              label="归档"
              value="ARCHIVED" /></el-select></el-form-item
        ><el-form-item label="服务需求" class="span-2"
          ><el-checkbox-group v-model="form.needIds"
            ><el-checkbox
              v-for="n in auth.dictionaries.serviceNeeds"
              :key="n.id"
              :value="n.id"
              >{{ n.name }}</el-checkbox
            ></el-checkbox-group
          ></el-form-item
        ><el-form-item label="脱敏业务备注" class="span-2"
          ><el-input
            v-model="form.remark"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
        /></el-form-item></div></el-form
    ><template #footer
      ><el-button @click="editOpen = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="save"
        >保存档案</el-button
      ></template
    ></el-dialog
  >
  <el-dialog
    v-model="detailOpen"
    title="老人档案详情"
    width="800px"
    destroy-on-close
    ><template v-if="detail"
      ><el-descriptions :column="2" border
        ><el-descriptions-item label="老人编号">{{
          detail.elderNo
        }}</el-descriptions-item
        ><el-descriptions-item label="出生年月"
          >{{ detail.birthYear }}-{{
            String(detail.birthMonth).padStart(2, "0")
          }}</el-descriptions-item
        ><el-descriptions-item label="性别">{{
          label(detail.sex)
        }}</el-descriptions-item
        ><el-descriptions-item label="档案状态">{{
          label(detail.status)
        }}</el-descriptions-item
        ><el-descriptions-item label="备注" :span="2">{{
          detail.remark || "—"
        }}</el-descriptions-item></el-descriptions
      >
      <div class="detail-section">
        <h3>周期接种状态</h3>
        <el-table :data="detail.vaccines" empty-text="暂无核验记录"
          ><el-table-column label="服务周期" min-width="180"
            ><template #default="{ row }">{{
              campaignName(row.campaignId)
            }}</template></el-table-column
          ><el-table-column label="资格"
            ><template #default="{ row }">{{
              label(row.eligibility)
            }}</template></el-table-column
          ><el-table-column label="接种状态"
            ><template #default="{ row }">{{
              row.vaccinationStatus === "COMPLETED"
                ? "已完成接种"
                : label(row.vaccinationStatus)
            }}</template></el-table-column
          ><el-table-column prop="appointmentDate" label="预约日期"
        /></el-table>
      </div>
      <div class="detail-section">
        <h3>核验历史</h3>
        <el-table :data="history.vaccinations || []" empty-text="暂无核验历史"
          ><el-table-column label="时间" min-width="170"
            ><template #default="{ row }">{{
              date(row.effectiveAt)
            }}</template></el-table-column
          ><el-table-column prop="reason" label="核验说明" min-width="220"
        /></el-table></div></template
  ></el-dialog>
  <el-dialog
    v-model="vaccineOpen"
    title="疫苗资格与接种核验"
    width="680px"
    destroy-on-close
    ><p class="hint">
      {{ vaccineElder.elderNo }} ·
      请依据真实核验记录填写；确认通知不等于完成接种。
    </p>
    <el-form label-position="top"
      ><div class="form-grid">
        <el-form-item label="服务周期 *" class="span-2"
          ><el-select v-model="vaccineForm.campaignId" @change="selectCampaign"
            ><el-option
              v-for="c in auth.dictionaries.vaccineCampaigns"
              :key="c.id"
              :label="c.name"
              :value="c.id" /></el-select></el-form-item
        ><el-form-item label="接种资格"
          ><el-select v-model="vaccineForm.eligibility"
            ><el-option
              v-for="v in ['UNKNOWN', 'ELIGIBLE', 'INELIGIBLE']"
              :key="v"
              :label="label(v)"
              :value="v" /></el-select></el-form-item
        ><el-form-item label="接种状态"
          ><el-select v-model="vaccineForm.vaccinationStatus"
            ><el-option
              v-for="v in ['UNKNOWN', 'NOT_VACCINATED', 'PARTIAL', 'COMPLETED']"
              :key="v"
              :label="v === 'COMPLETED' ? '已完成接种' : label(v)"
              :value="v" /></el-select></el-form-item
        ><el-form-item label="预约日期"
          ><el-date-picker
            v-model="vaccineForm.appointmentDate"
            type="date"
            value-format="YYYY-MM-DD" /></el-form-item
        ><el-form-item
          v-if="
            ['PARTIAL', 'COMPLETED'].includes(vaccineForm.vaccinationStatus)
          "
          label="实际接种日期 *"
          ><el-date-picker
            v-model="vaccineForm.vaccinatedOn"
            type="date"
            value-format="YYYY-MM-DD" /></el-form-item
        ><el-form-item
          v-if="
            ['PARTIAL', 'COMPLETED'].includes(vaccineForm.vaccinationStatus)
          "
          label="针次 *"
          ><el-input-number
            v-model="vaccineForm.doseNo"
            :min="1"
            :max="20" /></el-form-item
        ><el-form-item label="核验或更正说明 *" class="span-2"
          ><el-input
            v-model="vaccineForm.reason"
            type="textarea"
            :rows="2"
            maxlength="300"
        /></el-form-item></div></el-form
    ><template #footer
      ><el-button @click="vaccineOpen = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="saveVaccine"
        >保存核验记录</el-button
      ></template
    ></el-dialog
  >
</template>
