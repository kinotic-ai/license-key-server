<script setup lang="ts">
/** A short procedure as numbered steps, each a title and a line of detail; the caller's class lays the list out. */
defineProps<{
  steps: { title: string, detail: string }[]
}>()
</script>

<template>
  <ol>
    <li v-for="(step, index) in steps" :key="step.title" class="flex gap-3 text-sm">
      <span class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-surface-100 text-xs font-semibold text-surface-700 dark:bg-surface-800 dark:text-surface-200">{{ index + 1 }}</span>
      <div>
        <div class="app-heading-text font-medium">{{ step.title }}</div>
        <p class="app-muted-text mt-1">{{ step.detail }}</p>
      </div>
    </li>
  </ol>
</template>
