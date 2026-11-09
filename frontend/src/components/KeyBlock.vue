<script setup lang="ts">
import { ref } from 'vue'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import type { IssuedKey } from '@/api/model'
import { downloadKey, formatDate } from '@/util/format'

/**
 * One issued key: when it was issued and expires, the key itself in a monospace block, and copy and download
 * buttons.
 */
const props = defineProps<{
  issuedKey: IssuedKey
  /** Hides the key text behind a button, for a list where only one key is wanted at a time. */
  collapsed?: boolean
}>()

const toast = useToast()
const revealed = ref(!props.collapsed)

async function copy() {
  await navigator.clipboard.writeText(props.issuedKey.key)
  toast.add({ severity: 'success', summary: 'Key copied', life: 2500 })
}
</script>

<template>
  <div class="app-card-surface rounded-xl border p-4">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <div class="min-w-0 text-sm">
        <div class="app-heading-text font-medium">Issued {{ formatDate(issuedKey.issuedAt) }}</div>
        <div class="app-muted-text">
          Expires {{ formatDate(issuedKey.expiresAt) }}
          · <span class="font-mono text-xs">{{ issuedKey.jti }}</span>
        </div>
      </div>
      <div class="flex gap-2">
        <Button v-if="!revealed" label="Show" severity="secondary" outlined size="small" @click="revealed = true" />
        <Button label="Copy" icon="pi pi-copy" severity="secondary" outlined size="small" @click="copy" />
        <Button label="Download" icon="pi pi-download" severity="secondary" outlined size="small"
                @click="downloadKey(issuedKey.jti, issuedKey.key)" />
      </div>
    </div>
    <pre v-if="revealed"
         class="mt-3 max-h-40 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-surface-100 p-3 font-mono text-xs text-surface-800 dark:bg-surface-950 dark:text-surface-200">{{ issuedKey.key }}</pre>
  </div>
</template>
