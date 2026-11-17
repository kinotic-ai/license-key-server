<script setup lang="ts">
import { ref } from 'vue'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { KeyRound } from '@lucide/vue'
import type { IssuedKey } from '@/api/model'
import { downloadKey, formatDate } from '@/util/format'

/** One issued key: when it expires and was issued, copy and download buttons, and the key itself once shown. */
const props = defineProps<{
  issuedKey: IssuedKey
}>()

const toast = useToast()
const revealed = ref(false)

async function copy() {
  await navigator.clipboard.writeText(props.issuedKey.key)
  toast.add({ severity: 'success', summary: 'Key copied', life: 2500 })
}
</script>

<template>
  <div class="rounded-lg bg-surface-50 px-3 py-2.5 dark:bg-surface-800/60">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <div class="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-sm">
        <KeyRound :size="15" :stroke-width="1.75" class="app-muted-text shrink-0" aria-hidden="true" />
        <span class="app-heading-text font-medium">Expires {{ formatDate(issuedKey.expiresAt) }}</span>
        <span class="app-muted-text text-xs">
          issued {{ formatDate(issuedKey.issuedAt) }} · <span class="font-mono">{{ issuedKey.jti }}</span>
        </span>
      </div>
      <div class="flex gap-2">
        <Button :label="revealed ? 'Hide' : 'Show'" severity="secondary" text size="small" @click="revealed = !revealed" />
        <Button label="Copy" icon="pi pi-copy" severity="secondary" outlined size="small" @click="copy" />
        <Button label="Download" icon="pi pi-download" severity="secondary" outlined size="small"
                @click="downloadKey(issuedKey.jti, issuedKey.key)" />
      </div>
    </div>
    <pre v-if="revealed"
         class="mt-2.5 max-h-40 overflow-auto whitespace-pre-wrap break-all rounded-md bg-surface-0 p-3 font-mono text-xs text-surface-800 dark:bg-surface-950 dark:text-surface-200">{{ issuedKey.key }}</pre>
  </div>
</template>
