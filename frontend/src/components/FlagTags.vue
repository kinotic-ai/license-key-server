<script setup lang="ts">
import Tag from 'primevue/tag'
import type { LicenseFlag } from '@/api/model'

/** A license's flags, each as a tag staff can read at a glance. */
defineProps<{
  flags: LicenseFlag[]
}>()

const LABELS: Record<LicenseFlag, string> = { REVENUE_UNCONFIRMED: 'Revenue unconfirmed', POSSIBLY_COPIED: 'Possibly copied' }
</script>

<template>
  <span class="inline-flex flex-wrap gap-1">
    <Tag v-for="flag in flags" :key="flag" severity="warn" :value="LABELS[flag]" />
  </span>
</template>
