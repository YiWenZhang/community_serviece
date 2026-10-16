<script setup lang="ts">
import type { Component } from "vue";
defineProps<{
  label: string;
  value: string | number;
  suffix?: string;
  icon: Component;
  tone?: string;
  hint?: string;
  hintTone?: string;
  hintIcon?: Component;
}>();
</script>
<template>
  <article class="stat-tile" :class="'tone-' + (tone || 'brand')">
    <div class="stat-tile-main">
      <span class="stat-tile-label">{{ label }}</span>
      <strong class="stat-tile-value"
        >{{ value }}<small v-if="suffix">{{ suffix }}</small></strong
      >
      <p
        v-if="hint"
        class="stat-tile-hint"
        :class="'hint-' + (hintTone || 'muted')"
      >
        <el-icon v-if="hintIcon"><component :is="hintIcon" /></el-icon>
        <span>{{ hint }}</span>
      </p>
    </div>
    <span class="stat-tile-icon"
      ><el-icon><component :is="icon" /></el-icon
    ></span>
  </article>
</template>
