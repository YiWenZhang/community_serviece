<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  House,
  User,
  Bell,
  Finished,
  DataAnalysis,
  Avatar,
  Setting,
  SwitchButton,
  ChatDotRound,
  Document,
  OfficeBuilding,
  List,
} from "@element-plus/icons-vue";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import { useAuth } from "./stores";
import { fail, label } from "./api";
const auth = useAuth(),
  route = useRoute(),
  router = useRouter();
const menus = computed(() =>
  [
    { to: "/", name: "服务工作台", icon: House, show: true },
    {
      to: "/communities",
      name: "社区管理",
      icon: OfficeBuilding,
      show: auth.can("system:community:manage"),
    },
    {
      to: "/audit-logs",
      name: "操作日志",
      icon: List,
      show: auth.can("audit:read"),
    },
    {
      to: "/policy-chat",
      name: "政策问答助手",
      icon: ChatDotRound,
      show: auth.can("ai:chat"),
    },
    {
      to: "/knowledge",
      name: "政策知识库",
      icon: Document,
      show: auth.can("ai:knowledge:manage"),
    },
    {
      to: "/ai-models",
      name: "AI 模型配置",
      icon: Setting,
      show:
        auth.can("ai:model:manage") &&
        auth.can("system:config:manage") &&
        auth.user?.roles?.includes("ADMIN"),
    },
    {
      to: "/elders",
      name: "老人档案",
      icon: User,
      show: auth.can("elder:read"),
    },
    {
      to: "/notices",
      name: "通知管理",
      icon: Bell,
      show: auth.can("notice:read"),
    },
    {
      to: "/followups",
      name: "服务跟进",
      icon: Finished,
      show: auth.can("followup:read"),
    },
    {
      to: "/reports",
      name: "统计报表",
      icon: DataAnalysis,
      show: auth.can("report:read:own") || auth.can("report:read:community"),
    },
    {
      to: "/users",
      name: "用户管理",
      icon: Avatar,
      show: auth.can("system:user:manage"),
    },
    {
      to: "/rules",
      name: "通知规则",
      icon: Setting,
      show: auth.can("notice:rule:manage"),
    },
  ].filter((m) => m.show),
);
const today = new Date().toLocaleDateString("zh-CN", {
  month: "long",
  day: "numeric",
  weekday: "long",
});
async function logout() {
  try {
    await auth.logout();
    router.push("/login");
  } catch (e) {
    fail(e);
  }
}
async function switchCommunity(id: string) {
  try {
    await auth.switchCommunity(id);
  } catch (e) {
    fail(e);
  }
}
function expired() {
  if (auth.user) {
    auth.user = null;
    router.replace("/login");
  }
}
onMounted(() => window.addEventListener("session-expired", expired));
onBeforeUnmount(() => window.removeEventListener("session-expired", expired));
</script>
<template>
  <el-config-provider :locale="zhCn">
    <router-view v-if="route.path === '/login'" />
    <div v-else-if="auth.user" class="app-layout">
      <aside class="sidebar">
        <router-link to="/" class="brand"
          ><span class="brand-mark">邻</span
          ><span>邻里<small>社区银龄服务</small></span></router-link
        >
        <div class="nav-caption">社区工作空间</div>
        <nav aria-label="主导航">
          <router-link
            v-for="item in menus"
            :key="item.to"
            :to="item.to"
            :class="{ active: route.path === item.to }"
            ><el-icon><component :is="item.icon" /></el-icon
            ><span>{{ item.name }}</span></router-link
          >
        </nav>
        <div class="sidebar-bottom">
          <span class="connection-dot" /> 已连接业务服务<small
            >社区编号 {{ auth.user.communityId }} · 按角色授权访问</small
          >
        </div>
      </aside>
      <div class="main-layout">
        <header class="topbar">
          <div class="breadcrumb">
            社区工作空间 <span>/</span> <strong>{{ route.meta.title }}</strong>
          </div>
          <div class="topbar-right">
            <el-select
              :model-value="auth.communityId"
              :disabled="auth.switching"
              style="width: 180px"
              aria-label="当前社区"
              @change="switchCommunity"
              ><el-option
                v-for="c in auth.communities"
                :key="c.id"
                :label="c.name"
                :value="c.id"
            /></el-select>
            <span class="today">{{ today }}</span><span class="avatar">{{
              auth.user.username.slice(0, 1).toUpperCase()
            }}</span>
            <div class="identity">
              <strong>{{ auth.user.username }}</strong><small>{{
                auth.user.roles.map(label).join(" / ")
              }}</small>
            </div>
            <el-button text aria-label="退出登录" @click="logout"
              ><el-icon><SwitchButton /></el-icon
            ></el-button>
          </div>
        </header>
        <main class="page-content">
          <router-view :key="route.fullPath + auth.communityId" />
        </main>
        <footer class="page-footer">
          邻里社区银龄服务 <span>以关怀连接社区，以记录保障服务</span>
        </footer>
      </div>
    </div>
    <div v-else class="initial-loading">正在连接社区服务…</div>
  </el-config-provider>
</template>
