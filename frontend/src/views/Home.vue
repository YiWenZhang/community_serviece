<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useAuth } from "../stores";
import { api, fail, date, type Row } from "../api";
import Status from "../components/Status.vue";
import StatCard from "../components/StatCard.vue";
import {
  User,
  Bell,
  Timer,
  WarningFilled,
  Avatar,
} from "@element-plus/icons-vue";
const auth = useAuth(),
  stats = ref<Row>({}),
  tasks = ref<Row[]>([]),
  loading = ref(false),
  error = ref("");
const community = computed(() =>
  auth.communities.find((c) => c.id === auth.communityId),
);
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
  <section v-if="community" class="panel mb community-overview">
    <img
      v-if="community.hasIcon"
      :src="`/api/v1/communities/${community.id}/icon?v=${community.version}`"
      alt="社区图标"
    />
    <div>
      <h2>{{ community.name }}</h2>
      <p>{{ community.description || "欢迎使用本社区老年服务工作台。" }}</p>
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
  <section v-loading="loading" class="stat-tiles">
    <StatCard
      v-if="auth.can('elder:read')"
      :label="
        auth.user?.roles?.includes('ADMIN') ? '当前社区老人' : '本人负责老人'
      "
      :value="stats.elders ?? '—'"
      suffix="人"
      :icon="User"
      tone="blue"
      hint="档案信息与服务需求"
    />
    <StatCard
      v-if="auth.can('notice:read')"
      :label="
        auth.user?.roles?.includes('ADMIN')
          ? '当前社区通知对象'
          : '本人通知对象'
      "
      :value="stats.notices ?? '—'"
      suffix="条"
      :icon="Bell"
      tone="brand"
      hint="按每位老人分别记录"
    />
    <StatCard
      v-if="auth.can('followup:read')"
      label="待处理跟进"
      :value="stats.pending ?? '—'"
      suffix="项"
      :icon="Timer"
      tone="gold"
      hint="及时处理，保障服务闭环"
    />
    <StatCard
      v-if="auth.can('followup:read')"
      label="逾期待跟进"
      :value="stats.overdue ?? '—'"
      suffix="项"
      :icon="WarningFilled"
      tone="red"
      hint="包含待处理和处理中的任务"
      hint-tone="danger"
    />
    <StatCard
      v-if="auth.can('system:user:manage')"
      label="全部工作人员"
      :value="stats.users ?? '—'"
      suffix="位"
      :icon="Avatar"
      tone="green"
      hint="含启用及停用的应用账号"
    />
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
