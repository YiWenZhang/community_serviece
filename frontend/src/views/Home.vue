<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useAuth } from "../stores";
import { api, fail, date, type Row } from "../api";
import Status from "../components/Status.vue";
const auth = useAuth(),
  stats = ref<Row>({}),
  tasks = ref<Row[]>([]),
  loading = ref(false),
  error = ref("");
async function load() {
  loading.value = true;
  error.value = "";
  try {
    if (auth.can("elder:read")) {
      const result = await api("/elders?pageSize=1");
      stats.value.elders = result.total;
    }
    if (auth.can("notice:read")) {
      const result = await api("/notice-recipients?pageSize=1");
      stats.value.notices = result.total;
    }
    if (auth.can("followup:read")) {
      const result = await api("/followups?status=PENDING&pageSize=5");
      stats.value.pending = result.total;
      tasks.value = result.items;
      const overdue = await api("/followups?overdue=true&pageSize=1");
      stats.value.overdue = overdue.total;
    }
    if (auth.can("system:user:manage")) {
      const result = await api("/users?pageSize=1");
      stats.value.users = result.total;
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : "加载失败";
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section class="welcome">
    <div>
      <span class="eyebrow">今日工作 · COMMUNITY CARE</span>
      <h1>每一次服务，都有回应。</h1>
      <p>你好，{{ auth.user?.username }}。从这里开始今天的社区关怀。</p>
    </div>
    <div class="welcome-art" aria-hidden="true">
      <span>关</span><span>怀</span><i />
    </div>
  </section>
  <el-alert
    v-if="error"
    :title="error"
    type="error"
    show-icon
    :closable="false"
    class="mb"
  />
  <section v-loading="loading" class="stat-grid">
    <article v-if="auth.can('elder:read')" class="stat-card">
      <span>本人负责老人</span
      ><strong>{{ stats.elders ?? "—" }}<small>人</small></strong>
      <p>档案信息与服务需求</p>
    </article>
    <article v-if="auth.can('notice:read')" class="stat-card">
      <span>本人通知对象</span
      ><strong>{{ stats.notices ?? "—" }}<small>条</small></strong>
      <p>按每位老人分别记录</p>
    </article>
    <article v-if="auth.can('followup:read')" class="stat-card">
      <span>待处理跟进</span
      ><strong>{{ stats.pending ?? "—" }}<small>项</small></strong>
      <p>及时处理，保障服务闭环</p>
    </article>
    <article v-if="auth.can('followup:read')" class="stat-card accent">
      <span>逾期待跟进</span
      ><strong>{{ stats.overdue ?? "—" }}<small>项</small></strong>
      <p>包含待处理和处理中的任务</p>
    </article>
    <article v-if="auth.can('system:user:manage')" class="stat-card">
      <span>本社区工作人员</span
      ><strong>{{ stats.users ?? "—" }}<small>位</small></strong>
      <p>含启用及停用的应用账号</p>
    </article>
  </section>
  <div class="home-grid">
    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>{{ auth.can("followup:read") ? "待办跟进" : "开始使用" }}</h2>
          <p>把服务落实到每一个环节</p>
        </div>
        <router-link v-if="auth.can('followup:read')" to="/followups"
          >查看全部 →</router-link
        >
      </div>
      <template v-if="auth.can('followup:read')"
        ><el-table :data="tasks" empty-text="暂无待处理跟进任务"
          ><el-table-column
            prop="id"
            label="任务编号"
            width="100"
          /><el-table-column prop="taskType" label="任务类型"
            ><template #default="{ row }"
              ><Status :value="row.taskType" /></template></el-table-column
          ><el-table-column label="截止时间" min-width="150"
            ><template #default="{ row }">{{
              date(row.dueAt)
            }}</template></el-table-column
          ></el-table
        ></template
      >
      <div v-else class="onboarding">
        <span class="step-number">01</span>
        <div>
          <h3>
            {{
              auth.can("system:user:manage")
                ? "先为工作人员开通访问权限"
                : "查看服务统计"
            }}
          </h3>
          <p>
            {{
              auth.can("system:user:manage")
                ? "在用户管理中设置密码、启用网格员与统计员。管理员默认不读取老人业务明细。"
                : "选择月份查看汇总指标，也可以导出 Excel 或 Word 月报。"
            }}
          </p>
          <router-link
            :to="auth.can('system:user:manage') ? '/users' : '/reports'"
            class="text-link"
            >{{
              auth.can("system:user:manage") ? "进入用户管理" : "查看统计报表"
            }}
            →</router-link
          >
        </div>
      </div>
    </section>
    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>服务小提示</h2>
          <p>清楚记录，安心服务</p>
        </div>
      </div>
      <ul class="care-tips">
        <li>
          <span>01</span>
          <div>
            <strong>保护个人信息</strong>
            <p>不录入身份证、手机号码和详细家庭地址。</p>
          </div>
        </li>
        <li>
          <span>02</span>
          <div>
            <strong>区分触达与接收</strong>
            <p>公告已张贴，不等于老人已确认接收。</p>
          </div>
        </li>
        <li>
          <span>03</span>
          <div>
            <strong>跟进需要真实结果</strong>
            <p>确认接收不会自动改成“已完成接种”。</p>
          </div>
        </li>
      </ul>
    </section>
  </div>
</template>
