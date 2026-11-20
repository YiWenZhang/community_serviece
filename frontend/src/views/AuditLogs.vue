<script setup lang="ts">
import { onMounted, ref, reactive } from "vue";
import { api, query, fail, date, type Row } from "../api";
import { useAuth } from "../stores";
const auth = useAuth(),
  rows = ref<Row[]>([]),
  page = ref(1),
  total = ref(0),
  loading = ref(false),
  detail = ref<Row | null>(null),
  filters = reactive({ communityId: "", actor: "", action: "", result: "" });
const names: Row = {
  USER_CREATED: "创建账号",
  USER_CHANGED: "变更账号与角色",
  USER_COMMUNITIES_CHANGED: "调整社区授权",
  ROLE_PERMISSIONS_CHANGED: "变更角色权限",
  PASSWORD_CHANGED: "本人修改密码",
  PASSWORD_RESET: "重置应用密码",
  ELDER_CREATED: "登记老人",
  ELDER_CHANGED: "更新老人档案",
  ELDER_TRANSFERRED: "移交老人归属",
  NOTICE_CREATED: "创建通知",
  NOTICE_ACTIVATED: "发送通知",
  FOLLOWUP_RECORDED: "记录跟进",
  COMMUNITY_CREATED: "新增社区",
  COMMUNITY_UPDATED: "编辑社区资料",
  COMMUNITY_ICON_UPDATED: "上传社区图标",
  LOGIN: "登录",
  LOGOUT: "退出登录",
  HTTP_GET: "读取数据",
  HTTP_POST: "提交操作",
  HTTP_PUT: "更新操作",
  HTTP_PATCH: "修改操作",
  HTTP_DELETE: "删除操作",
};
async function load() {
  loading.value = true;
  try {
    const r = await api(
      "/audit-logs?" + query({ ...filters, page: page.value, pageSize: 20 }),
    );
    rows.value = r.items;
    total.value = Number(r.total);
  } catch (e) {
    fail(e);
  } finally {
    loading.value = false;
  }
}
function summary(row: Row) {
  try {
    return JSON.stringify(
      typeof row.changeSummaryJson === "string"
        ? JSON.parse(row.changeSummaryJson)
        : row.changeSummaryJson,
      null,
      2,
    );
  } catch {
    return row.changeSummaryJson;
  }
}
onMounted(load);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>操作日志</h1>
      <p>
        追溯全部社区的业务操作、权限调整和访问结果；日志不记录密码、模型密钥或请求正文。
      </p>
    </div>
  </div>
  <section class="panel">
    <div class="toolbar">
      <el-select v-model="filters.communityId" clearable placeholder="全部社区"
        ><el-option
          v-for="c in auth.communities"
          :key="c.id"
          :value="c.id"
          :label="c.name" /></el-select
      ><el-input
        v-model="filters.actor"
        placeholder="操作账号"
        maxlength="64"
      /><el-input
        v-model="filters.action"
        placeholder="操作代码，如 ELDER_CREATED"
        maxlength="80"
      /><el-select v-model="filters.result" clearable placeholder="全部结果"
        ><el-option label="成功" value="SUCCESS" /><el-option
          label="失败"
          value="FAILED" /><el-option
          label="拒绝访问"
          value="DENIED" /></el-select
      ><el-button
        type="primary"
        @click="
          page = 1;
          load();
        "
        >查询日志</el-button
      >
    </div>
    <el-table v-loading="loading" :data="rows"
      ><el-table-column label="操作时间" min-width="175"
        ><template #default="{ row }">{{
          date(row.occurredAt)
        }}</template></el-table-column
      ><el-table-column label="操作人" min-width="130"
        ><template #default="{ row }"
          >{{ row.username || row.systemActor }}
          <div class="muted">{{ row.displayName }}</div></template
        ></el-table-column
      ><el-table-column
        prop="communityName"
        label="社区"
        min-width="130"
      /><el-table-column label="操作内容" min-width="190"
        ><template #default="{ row }"
          >{{ names[row.action] || row.action }}
          <div class="muted">
            {{ row.targetType }} · {{ row.targetId }}
          </div></template
        ></el-table-column
      ><el-table-column label="结果" width="90"
        ><template #default="{ row }"
          ><el-tag
            :type="
              row.result === 'SUCCESS'
                ? 'success'
                : row.result === 'DENIED'
                  ? 'warning'
                  : 'danger'
            "
            >{{
              row.result === "SUCCESS"
                ? "成功"
                : row.result === "DENIED"
                  ? "已拒绝"
                  : "失败"
            }}</el-tag
          ></template
        ></el-table-column
      ><el-table-column label="详情" width="110" class-name="actions-column"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="detail = row"
            >查看</el-button
          ></template
        ></el-table-column
      ></el-table
    ><el-pagination
      v-model:current-page="page"
      :total="total"
      :page-size="20"
      layout="total,prev,pager,next"
      @current-change="load"
    />
  </section>
  <el-dialog
    :model-value="!!detail"
    title="操作详情"
    width="650px"
    @close="detail = null"
    ><template v-if="detail"
      ><p>
        {{ detail.username || detail.systemActor }} ·
        {{ date(detail.occurredAt) }}
      </p>
      <p class="muted">追踪编号：{{ detail.traceId }}</p>
      <pre style="white-space: pre-wrap; overflow-wrap: anywhere">{{
        summary(detail)
      }}</pre>
    </template></el-dialog
  >
</template>
