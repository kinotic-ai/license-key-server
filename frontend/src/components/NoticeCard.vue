<script setup lang="ts">
import type { Component } from 'vue'

/**
 * What happened or what comes next, as a card: a tinted icon, a title, the text in the default slot, and the
 * {@code actions} slot for where to go from here.
 */
defineProps<{
  icon: Component
  /** Classes of the icon tile's colour, one of TINTS. */
  tint: string
  title: string
}>()
</script>

<template>
  <div class="app-card-surface flex gap-4 rounded-xl border p-5 text-sm">
    <span :class="['flex h-9 w-9 shrink-0 items-center justify-center rounded-lg', tint]">
      <component :is="icon" :size="18" :stroke-width="1.75" aria-hidden="true" />
    </span>
    <div class="min-w-0">
      <h2 class="app-heading-text font-medium">{{ title }}</h2>
      <div class="app-muted-text mt-1"><slot /></div>
      <div v-if="$slots.actions" class="mt-4"><slot name="actions" /></div>
    </div>
  </div>
</template>
