<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useAuth } from "../stores";
const auth = useAuth();
import { api, query, type Row } from "../api";
const month = ref(
    new Date()
      .toLocaleDateString("sv-SE", { timeZone: "Asia/Shanghai" })
      .slice(0, 7),
  ),
  rows = ref<Row[]>([]),
  total = ref(0),
  page = ref(1),
  loading = ref(false),
  error = ref("");
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const r = await api(
      "/elders/birthday-care?" +
        query({ month: month.value, page: page.value }),
    );
    rows.value = r.items;
    total.value = Number(r.total);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section class="panel mb">
    <div class="panel-head">
      <div>
        <h2>生日月份关怀</h2>
        <p>按出生月份提醒，管理员查看全部社区，网格员仅查看本人负责老人。</p>
      </div>
    </div>
    <div class="toolbar">
      <el-date-picker
        v-model="month"
        type="month"
        value-format="YYYY-MM"
        :clearable="false"
      /><el-button
        type="primary"
        @click="
          page = 1;
          load();
        "
        >查看关怀名单</el-button
      ><span class="hint">共 {{ total }} 名</span
      ><router-link
        v-if="auth.can('notice:rule:manage')"
        to="/rules"
        class="action-chip"
        >配置自动通知规则 →</router-link
      >
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table
      v-loading="loading"
      :data="rows"
      empty-text="所选月份暂无本人范围内的生日老人"
    >
      <el-table-column prop="elderName" label="老人姓名" min-width="100" />
      <el-table-column prop="elderNo" label="老人编号" min-width="175" />
      <el-table-column prop="communityName" label="社区" min-width="90" />
      <el-table-column prop="ownerName" label="负责网格员" min-width="110" />
      <el-table-column label="出生年月" min-width="120"
        ><template #default="{ row }"
          >{{ row.birthYear }}年{{ row.birthMonth }}月</template
        ></el-table-column
      >
      <el-table-column label="关怀需求" min-width="130"
        ><template #default="{ row }">{{
          Number(row.careEnrolled) ? "已登记生日关怀" : "尚未登记关怀需求"
        }}</template></el-table-column
      >
    </el-table>
    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="20"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </div>
    <p class="hint">
      启用生日规则后，系统自动生成已登记生日关怀需求的老人通知；网格员执行后登记结果。系统未收集具体出生日期，按月份组织关怀。
    </p>
  </section>
</template>
