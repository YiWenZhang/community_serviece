<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
const props = defineProps<{
  items: { label: string; value: number; display: string; hint?: string }[];
}>();
const mounted = ref(false);
onMounted(() => requestAnimationFrame(() => (mounted.value = true)));
const max = computed(() =>
  Math.max(1, ...props.items.map((i) => Number(i.value) || 0)),
);
</script>
<template>
  <div class="metric-bars">
    <div
      v-for="(item, index) in items"
      :key="item.label + index"
      class="metric-bar-row"
    >
      <span class="metric-bar-label" :title="item.label">{{ item.label }}</span>
      <div class="metric-bar-track">
        <div
          class="metric-bar-fill"
          :class="'metric-bar-tone-' + (index % 6)"
          :style="{
            width: mounted
              ? Math.max(2, ((Number(item.value) || 0) / max) * 100) + '%'
              : '0%',
            transitionDelay: index * 0.04 + 's',
          }"
        />
      </div>
      <span class="metric-bar-value">{{ item.display }}</span>
    </div>
  </div>
</template>
<style scoped>
.metric-bars {
  display: grid;
  gap: 14px;
  padding: 4px 0;
}
.metric-bar-row {
  display: flex;
  align-items: center;
  gap: 14px;
}
.metric-bar-label {
  width: 170px;
  flex: none;
  text-align: right;
  font-size: 13px;
  color: #4a6055;
  line-height: 1.5;
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.metric-bar-track {
  flex: 1;
  min-width: 80px;
  height: 20px;
  background: #eef2ee;
  border-radius: 10px;
  overflow: hidden;
}
.metric-bar-fill {
  height: 100%;
  border-radius: 10px;
  background: linear-gradient(90deg, #5fc7b0, #2f7d6e);
  transition: width 0.85s cubic-bezier(0.2, 0.7, 0.2, 1);
  box-shadow: 0 2px 8px rgba(47, 125, 110, 0.22);
}
.metric-bar-tone-1 { background: linear-gradient(90deg, #7aa5f5, #3d6fd0); }
.metric-bar-tone-2 { background: linear-gradient(90deg, #e6c188, #c98f4f); }
.metric-bar-tone-3 { background: linear-gradient(90deg, #8fbcb3, #4f8b7e); }
.metric-bar-tone-4 { background: linear-gradient(90deg, #ef9d90, #cf5b4e); }
.metric-bar-tone-5 { background: linear-gradient(90deg, #b6a5e0, #7a68b8); }
.metric-bar-value {
  width: 80px;
  flex: none;
  text-align: right;
  font-size: 14px;
  font-weight: 700;
  color: #24483e;
}
@media (max-width: 760px) {
  .metric-bar-label { width: 110px; font-size: 12px; }
  .metric-bar-value { width: 64px; font-size: 13px; }
}
</style>
