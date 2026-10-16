<script setup lang="ts">
import { label } from "../api";
defineProps<{ value?: string }>();
</script>
<template>
  <span class="status" :class="value">{{ label(value) }}</span>
</template>
