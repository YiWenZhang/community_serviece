<script setup lang="ts">
defineProps<{
  items: { label: string; value: string | number; urgent?: boolean }[];
}>();
</script>
<template>
  <div class="work-metrics">
    <div v-for="item in items" :key="item.label">
      <span>{{ item.label }}</span><strong
        :class="{ 'work-urgent': item.urgent }"
        >{{ item.value }}</strong
      >
    </div>
  </div>
</template>
