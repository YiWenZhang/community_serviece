<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
const props = defineProps<{
  data: { label: string; value: number }[];
  suffix?: string;
  max?: number;
  color?: string;
}>();
const mounted = ref(false);
onMounted(() => requestAnimationFrame(() => (mounted.value = true)));
const peak = computed(() =>
  props.max ?? Math.max(100, ...props.data.map((d) => Number(d.value) || 0)),
);
const bars = computed(() =>
  props.data.map((d) => {
    const value = Number(d.value) || 0;
    return {
      label: d.label,
      value,
      height: mounted.value ? Math.max(2, (value / peak.value) * 100) : 0,
    };
  }),
);
const fmt = (v: number) =>
  `${Number.isInteger(v) ? v : v.toFixed(1)}${props.suffix || ""}`;
const fill = computed(
  () =>
    props.color ||
    "linear-gradient(180deg, #5fc7b0 0%, #2f7d6e 100%)",
);
</script>
<template>
  <div class="bar-chart">
    <div class="bar-plot">
      <div v-for="b in bars" :key="b.label" class="bar-col">
        <div class="bar-fill" :style="{ height: b.height + '%', background: fill }">
          <span class="bar-value">{{ fmt(b.value) }}</span>
        </div>
      </div>
    </div>
    <div class="bar-axis">
      <span v-for="b in bars" :key="b.label">{{ b.label }}</span>
    </div>
  </div>
</template>
