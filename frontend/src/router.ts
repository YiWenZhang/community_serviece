import { createRouter, createWebHashHistory } from "vue-router";
import { useAuth } from "./stores";
import Login from "./views/Login.vue";
import Home from "./views/Home.vue";
import Elders from "./views/Elders.vue";
import Notices from "./views/Notices.vue";
import Followups from "./views/Followups.vue";
import Reports from "./views/Reports.vue";
import Users from "./views/Users.vue";
import Rules from "./views/Rules.vue";

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/login", component: Login, meta: { title: "登录" } },
    { path: "/", component: Home, meta: { title: "服务工作台" } },
    {
      path: "/elders",
      component: Elders,
      meta: { title: "老人档案", permission: "elder:read" },
    },
    {
      path: "/notices",
      component: Notices,
      meta: { title: "通知管理", permission: "notice:read" },
    },
    {
      path: "/followups",
      component: Followups,
      meta: { title: "服务跟进", permission: "followup:read" },
    },
    {
      path: "/reports",
      component: Reports,
      meta: {
        title: "统计报表",
        permissions: ["report:read:own", "report:read:community"],
      },
    },
    {
      path: "/users",
      component: Users,
      meta: { title: "用户管理", permission: "system:user:manage" },
    },
    {
      path: "/rules",
      component: Rules,
      meta: { title: "通知规则", permission: "notice:rule:manage" },
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
router.beforeEach(async (to) => {
  const auth = useAuth();
  try {
    await auth.restore();
  } catch {
    /* 后端暂未启动时仍允许打开登录页。 */
  }
  if (!auth.user && to.path !== "/login") return "/login";
  if (auth.user && to.path === "/login") return "/";
  if (to.meta.permission && !auth.can(to.meta.permission as string)) return "/";
  if (to.meta.permissions && !(to.meta.permissions as string[]).some(auth.can))
    return "/";
  document.title = `${String(to.meta.title || "社区服务")} · 邻里`;
});
