<script setup lang="ts">
import Tag from 'primevue/tag'
import type { LicenseStatus } from '@/api/model'
import { statusLabel, statusSeverity } from '@/util/format'

defineProps<{
  status: LicenseStatus | null | undefined
}>()
</script>

<template>
  <Tag :value="statusLabel(status)" :severity="statusSeverity(status)" />
</template>
