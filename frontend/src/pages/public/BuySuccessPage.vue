<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { CircleCheck, CircleX, Hourglass, MailCheck } from '@lucide/vue'
import { PageHeader, TINTS, showErrorToast } from '@kinotic-ai/frontend-common'
import type { License, LicenseStatus } from '@/api/model'
import { customerAccountService } from '@/api/services'
import NoticeCard from '@/components/NoticeCard.vue'
import { planName } from '@/util/format'

/**
 * Where Stripe Checkout returns to. The license is created when Stripe's webhook arrives, which can be after the
 * browser lands here, so the page polls the account until the license the Checkout Session bought is there, then sends
 * the customer to add its clusters.
 */
const POLL_MS = 2000
// ten minutes of polling, well past the time Stripe takes to deliver the webhook
const MAX_POLLS = 300
const ENDED: LicenseStatus[] = ['LAPSED', 'CANCELED', 'EXPIRED', 'REVOKED']

const route = useRoute()
const toast = useToast()
const sessionId = String(route.query.session_id ?? '')
const license = ref<License | null>(null)
const gaveUp = ref(false)
let polls = 0
let unmounted = false
let timer: ReturnType<typeof setTimeout> | null = null

async function poll() {
  polls++
  try {
    const account = await customerAccountService.getAccount()
    if (!unmounted) {
      license.value = account.licenses.map(entry => entry.license).find(l => l.checkoutSessionId === sessionId) ?? null
    }
  } catch (err) {
    if (!unmounted) {
      showErrorToast(toast, 'Your account could not be loaded', err)
      gaveUp.value = true
    }
  }
  // a bank debit creates the license past due, which is ready once the payment clears
  if (!unmounted && !gaveUp.value && (license.value === null || license.value.status === 'PAST_DUE')) {
    if (polls < MAX_POLLS) {
      timer = setTimeout(poll, POLL_MS)
    } else {
      gaveUp.value = true
    }
  }
}

onMounted(() => {
  if (sessionId) {
    poll()
  }
})

onBeforeUnmount(() => {
  unmounted = true
  if (timer) {
    clearTimeout(timer)
  }
})
</script>

<template>
  <div class="max-w-3xl">
    <PageHeader title="Thank you" description="Your Kinotic Enterprise license is on its way." />
    <div v-if="!sessionId" class="app-muted-text text-sm">This page is reached from Stripe Checkout.</div>
    <NoticeCard v-else-if="license && license.status === 'ACTIVE'" :icon="CircleCheck" :tint="TINTS.green"
                :title="`Your ${planName(license.plan)} license is ready for ${license.seats} ${license.seats === 1 ? 'cluster' : 'clusters'}.`">
      Each cluster gets a key of its own. Copy the cluster id from your Kinotic system console's
      <strong>License</strong> page, add the cluster under Licenses, and paste the key it issues into that page.
      <template #actions>
        <RouterLink to="/account"><Button label="Add your clusters" icon="pi pi-server" /></RouterLink>
      </template>
    </NoticeCard>
    <NoticeCard v-else-if="license && ENDED.includes(license.status)" :icon="CircleX" :tint="TINTS.red" title="This license is no longer active.">
      See its state under Licenses, or write to us if that is unexpected.
      <template #actions>
        <RouterLink to="/account"><Button label="Back to your licenses" severity="secondary" outlined /></RouterLink>
      </template>
    </NoticeCard>
    <NoticeCard v-else-if="gaveUp || license" :icon="MailCheck" :tint="TINTS.blue" title="Your license is on its way.">
      We email your billing contact as soon as Stripe confirms the payment. Then add your clusters under Licenses.
      <template #actions>
        <RouterLink to="/account"><Button label="Back to your licenses" severity="secondary" outlined /></RouterLink>
      </template>
    </NoticeCard>
    <NoticeCard v-else :icon="Hourglass" :tint="TINTS.blue" title="Waiting for Stripe to confirm the payment…">
      This page updates by itself, usually within a few seconds.
    </NoticeCard>
  </div>
</template>
