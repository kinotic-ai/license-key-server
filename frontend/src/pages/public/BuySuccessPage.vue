<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { PageHeader, showErrorToast } from '@kinotic-ai/frontend-common'
import type { CheckoutKey } from '@/api/model'
import { fetchCheckoutKey } from '@/api/rest'
import { downloadKey, formatDate } from '@/util/format'

/**
 * Where Stripe Checkout returns to. The license is created when Stripe's webhook arrives, which can be after the
 * browser lands here, so the page polls until the key is ready, then shows it this once.
 */
const POLL_MS = 2000

const route = useRoute()
const toast = useToast()
const sessionId = String(route.query.session_id ?? '')
const result = ref<CheckoutKey>({ status: 'PENDING' })
let timer: ReturnType<typeof setTimeout> | null = null

async function poll() {
  try {
    result.value = await fetchCheckoutKey(sessionId)
  } catch (err) {
    showErrorToast(toast, 'The key could not be fetched', err)
  }
  if (result.value.status === 'PENDING') {
    timer = setTimeout(poll, POLL_MS)
  }
}

async function copy() {
  await navigator.clipboard.writeText(result.value.key ?? '')
  toast.add({ severity: 'success', summary: 'Key copied', life: 2500 })
}

onMounted(() => {
  if (sessionId) {
    poll()
  }
})

onBeforeUnmount(() => {
  if (timer) {
    clearTimeout(timer)
  }
})
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader title="Thank you" description="Your Kinotic Enterprise license is on its way." />
    <div v-if="!sessionId" class="app-muted-text text-sm">This page is reached from Stripe Checkout.</div>
    <div v-else-if="result.status === 'PENDING'" class="app-card-surface rounded-xl border p-5 text-sm">
      <i class="pi pi-spin pi-spinner mr-2" /> Waiting for Stripe to confirm the payment… your key appears here as soon as it is issued.
    </div>
    <div v-else-if="result.status === 'READY'" class="app-card-surface flex flex-col gap-4 rounded-xl border p-5">
      <p class="app-heading-text text-sm">
        Here is your license key. It is valid until <strong>{{ formatDate(result.expiresAt) }}</strong>. Paste it into your
        Kinotic deployment to enable the Enterprise Features.
      </p>
      <pre class="max-h-48 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-surface-100 p-3 font-mono text-xs text-surface-800 dark:bg-surface-950 dark:text-surface-200">{{ result.key }}</pre>
      <div class="flex flex-wrap gap-2">
        <Button label="Copy" icon="pi pi-copy" @click="copy" />
        <Button label="Download" icon="pi pi-download" severity="secondary" outlined @click="downloadKey('new', result.key ?? '')" />
      </div>
      <p class="app-muted-text text-sm">
        The key was also emailed to your billing contact, and it is always available in
        <RouterLink to="/account" class="underline">your account</RouterLink>, where you sign in with a code sent to that address.
      </p>
    </div>
    <div v-else class="app-card-surface rounded-xl border p-5 text-sm">
      <p class="app-heading-text">This page has already shown the key.</p>
      <p class="app-muted-text mt-2">
        It was emailed to your billing contact, and you can copy it any time from
        <RouterLink to="/account" class="underline">your account</RouterLink>.
      </p>
    </div>
  </div>
</template>
