<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
const props = defineProps<{
  data: { label: string; value: number; color?: string }[];
  centerLabel?: string;
}>();
const mounted = ref(false);
onMounted(() => requestAnimationFrame(() => (mounted.value = true)));
const R = 48;
const C = 2 * Math.PI * R;
const palette = ["#3aa6a0", "#5b8def", "#d9a86a", "#8fbcb3", "#e08a7a", "#b6a5e0"];
const total = computed(() =>
  props.data.reduce((sum, d) => sum + (Number(d.value) || 0), 0),
);
const segments = computed(() => {
  let start = 0;
  return props.data.map((d, i) => {
    const value = Number(d.value) || 0;
    const frac = total.value ? value / total.value : 0;
    const len = frac * C;
    const seg = {
      label: d.label,
      percent: frac * 100,
      color: d.color || palette[i % palette.length],
      len,
      offset: -start,
    };
    start += len;
    return seg;
  });
});
</script>
<template>
  <div class="donut-wrap">
    <div class="donut-figure">
      <svg viewBox="0 0 120 120" role="img" :aria-label="centerLabel || '占比图'">
        <circle class="donut-track" cx="60" cy="60" :r="R" />
        <circle
          v-for="s in segments"
          :key="s.label"
          class="donut-seg"
          cx="60"
          cy="60"
          :r="R"
          :stroke="s.color"
          :style="{
            strokeDasharray: mounted ? `${s.len} ${C - s.len}` : `0 ${C}`,
            strokeDashoffset: s.offset,
          }"
        />
      </svg>
      <div class="donut-center">
        <strong>{{ total }}</strong>
        <span>{{ centerLabel || "合计" }}</span>
      </div>
    </div>
    <ul class="donut-legend">
      <li v-for="s in segments" :key="s.label" class="donut-legend-item">
        <span class="donut-dot" :style="{ background: s.color }" />
        <span class="donut-legend-label">{{ s.label }}</span>
        <b>{{ s.percent.toFixed(1) }}%</b>
      </li>
    </ul>
  </div>
</template>
