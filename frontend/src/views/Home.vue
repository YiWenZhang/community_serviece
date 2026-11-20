<script setup lang="ts">
import { computed } from "vue";
import { useAuth } from "../stores";
import WorkDesk from "../components/WorkDesk.vue";
import WorkSupervision from "../components/WorkSupervision.vue";
import BirthdayCare from "../components/BirthdayCare.vue";
const auth = useAuth();
const admin = computed(() => auth.user?.roles?.includes("ADMIN"));
const community = computed(() =>
  auth.communities.find((c) => c.id === auth.communityId),
);
</script>
<template>
  <div class="page-heading business-heading">
    <div>
      <h1>
        {{
          admin
            ? "工作监督"
            : auth.can("notice:read")
              ? "我的工作"
              : "统计工作台"
        }}
      </h1>
      <p>
        {{ auth.user?.displayName }}，{{
          admin
            ? "查看网格员工作进度，维护社区授权与自动规则。"
            : auth.can("notice:read")
              ? "处理本人负责的联系与跟进，及时记录实际结果。"
              : "查询社区服务指标并导出报表。"
        }}
      </p>
    </div>
  </div>
  <WorkSupervision v-if="admin && auth.can('notice:read')" />
  <WorkDesk v-else-if="auth.can('notice:read')" />
  <section v-else class="panel">
    <h2>服务统计与上报</h2>
    <p>
      按社区、经办人、业务类型和时间范围查看服务指标，导出 Excel / Word 报表。
    </p>
    <router-link to="/reports" class="text-link">进入统计报表 →</router-link>
  </section>
  <BirthdayCare v-if="auth.can('elder:read')" />
  <section v-if="community" class="panel community-brief">
    <h2>{{ community.name }}</h2>
    <p>{{ community.description }}</p>
  </section>
</template>
