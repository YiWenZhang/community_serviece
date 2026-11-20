<script setup lang="ts">
import { computed } from "vue";

type InlinePart = { text: string; bold: boolean };
type Block = { type: "heading" | "list" | "quote" | "para"; parts: InlinePart[] };

const props = defineProps<{ text?: string | null }>();

function clean(input: unknown): string {
  return String(input ?? "")
    .replace(/\uFEFF/g, "")
    .replace(/[\u200B-\u200D\u2060]/g, "")
    .replace(/\r\n?/g, "\n")
    .split("\n")
    .map((line) => line.replace(/[ \t]+$/g, ""))
    .join("\n")
    .replace(/\n{3,}/g, "\n\n");
}

function inline(text: string): InlinePart[] {
  const parts: InlinePart[] = [];
  const re = /\*\*([^*\n]+)\*\*/g;
  let last = 0;
  let match: RegExpExecArray | null;
  while ((match = re.exec(text)) !== null) {
    if (match.index > last) parts.push({ text: text.slice(last, match.index), bold: false });
    parts.push({ text: match[1], bold: true });
    last = match.index + match[0].length;
  }
  if (last < text.length) parts.push({ text: text.slice(last), bold: false });
  return parts.length ? parts : [{ text, bold: false }];
}

const blocks = computed<Block[]>(() => {
  const result: Block[] = [];
  for (const raw of clean(props.text).split("\n")) {
    const line = raw.trim();
    if (!line) continue;
    let match = line.match(/^#{1,6}\s*(.+)$/) || line.match(/^[一二三四五六七八九十百]+、\s*(.+)$/);
    if (match) {
      result.push({ type: "heading", parts: inline(match[1]) });
      continue;
    }
    match = line.match(/^>\s*(.*)$/);
    if (match) {
      result.push({ type: "quote", parts: inline(match[1]) });
      continue;
    }
    match = line.match(/^(?:[-*•]\s+|\d+[.)、]\s*)(.+)$/);
    if (match) {
      result.push({ type: "list", parts: inline(match[1]) });
      continue;
    }
    result.push({ type: "para", parts: inline(line) });
  }
  return result;
});
</script>
<template>
  <div class="answer-content">
    <template v-for="(block, index) in blocks" :key="index">
      <div v-if="block.type === 'heading'" class="answer-heading">
        <template v-for="(part, i) in block.parts" :key="i">
          <strong v-if="part.bold">{{ part.text }}</strong
          ><span v-else>{{ part.text }}</span>
        </template>
      </div>
      <div v-else-if="block.type === 'list'" class="answer-list-item">
        <span class="answer-list-dot" aria-hidden="true" />
        <span class="answer-list-text">
          <template v-for="(part, i) in block.parts" :key="i">
            <strong v-if="part.bold">{{ part.text }}</strong
            ><span v-else>{{ part.text }}</span>
          </template>
        </span>
      </div>
      <blockquote v-else-if="block.type === 'quote'" class="answer-quote">
        <template v-for="(part, i) in block.parts" :key="i">
          <strong v-if="part.bold">{{ part.text }}</strong
          ><span v-else>{{ part.text }}</span>
        </template>
      </blockquote>
      <p v-else class="answer-para">
        <template v-for="(part, i) in block.parts" :key="i">
          <strong v-if="part.bold">{{ part.text }}</strong
          ><span v-else>{{ part.text }}</span>
        </template>
      </p>
    </template>
    <p v-if="!blocks.length" class="answer-empty">暂无答复内容</p>
  </div>
</template>
<style scoped>
.answer-content {
  background: linear-gradient(180deg, #fbfdfa 0%, #f6f9f4 100%);
  border: 1px solid #e3ede1;
  border-radius: 12px;
  padding: 16px 20px;
  margin: 12px 0 18px;
  box-shadow: 0 4px 16px rgba(40, 80, 60, 0.06),
    inset 0 1px 0 rgba(255, 255, 255, 0.8);
  animation: answerFade 0.4s ease both;
  overflow-wrap: anywhere;
}
.answer-heading {
  font-size: 15px;
  font-weight: 650;
  color: #24483e;
  margin: 14px 0 8px;
  padding-left: 10px;
  border-left: 3px solid #5fc7b0;
  line-height: 1.6;
}
.answer-heading:first-child {
  margin-top: 2px;
}
.answer-list-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  font-size: 14px;
  line-height: 1.9;
  color: #354d42;
  margin: 4px 0;
}
.answer-list-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #7fb9a1;
  margin-top: 10px;
  box-shadow: 0 0 0 3px rgba(127, 185, 161, 0.16);
}
.answer-quote {
  margin: 10px 0;
  padding: 10px 14px;
  border-left: 3px solid #9dbb8d;
  background: rgba(236, 244, 232, 0.7);
  border-radius: 0 8px 8px 0;
  font-size: 13px;
  color: #4d6656;
  line-height: 1.8;
}
.answer-para {
  margin: 8px 0;
  font-size: 14px;
  line-height: 1.95;
  color: #354d42;
}
.answer-content strong {
  font-weight: 650;
  color: #2a5244;
}
.answer-empty {
  margin: 0;
  font-size: 13px;
  color: #8a9a8f;
}
@keyframes answerFade {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
