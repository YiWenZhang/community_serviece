<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useAuth } from "../stores";
import { api, type Row } from "../api";
import { nextWork } from "../work";
import WorkMetrics from "./WorkMetrics.vue";
const auth = useAuth(),
  rows = ref<Row[]>([]),
  counts = ref<Row>({}),
  error = ref(""),
  loading = ref(false);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [todo, retry, overdue, drafts] = await Promise.all([
      api("/notice-recipients?workState=TODO&pageSize=5"),
      api("/notice-recipients?workState=RETRY&pageSize=1"),
      api("/notice-recipients?workState=OVERDUE&pageSize=1"),
      auth.can("notice:create")
        ? api("/notices?status=DRAFT&pageSize=1")
        : Promise.resolve({ total: 0 }),
    ]);
    if (todo.items.length && !todo.items[0].noticeTitle)
      throw Error("请停止旧后端后重新构建并启动，再刷新工作清单。");
    rows.value = todo.items;
    counts.value = {
      todo: todo.total,
      retry: retry.total,
      overdue: overdue.total,
      drafts: drafts.total,
    };
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section class="panel work-desk" v-loading="loading">
    <div class="panel-head">
      <div>
        <h2>我的待办工作</h2>
        <p>优先处理逾期工作，再完成首次联系和再次跟进。</p>
      </div>
      <el-button @click="load">刷新工作清单</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <WorkMetrics
      :items="[
        { label: '待处理联系', value: counts.todo ?? '—' },
        { label: '未联系上', value: counts.retry ?? '—' },
        { label: '已逾期', value: counts.overdue ?? '—', urgent: true },
        { label: '草稿待发布', value: counts.drafts ?? '—' },
      ]"
    />
    <div class="desk-quick-links">
      <router-link to="/notices?workState=TODO"
        ><span>查看联系待办</span></router-link
      ><router-link to="/notices?workState=RETRY"
        ><span>查看未联系上</span></router-link
      ><router-link to="/notices?workState=OVERDUE" class="urgent"
        ><span>查看逾期工作</span></router-link
      ><router-link
        v-if="auth.can('notice:create')"
        to="/notices?view=items&amp;status=DRAFT"
        ><span>发布通知草稿</span></router-link
      >
    </div>
    <article v-for="row in rows" :key="row.id" class="desk-work-row">
      <div>
        <strong>{{ row.noticeTitle }}</strong>
        <p>
          {{ row.elderName }} · 负责人 {{ row.ownerName }} · {{ nextWork(row) }}
        </p>
      </div>
      <router-link
        :to="
          row.nextTaskId
            ? { path: '/followups', query: { recipientId: row.id } }
            : {
                path: '/notices',
                query: { noticeId: row.noticeId, workState: 'TODO' },
              }
        "
        >去处理 →</router-link
      >
    </article>
    <p v-if="!loading && !error && !rows.length" class="hint">
      当前没有待处理联系，可查看通知事项或安排新的社区服务。
    </p>
  </section>
</template>
