<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'
import { FormDrawer, PageHeader, showErrorToast } from '@kinotic-ai/frontend-common'
import type { CustomerAccount, LicenseWithKeys, Plan } from '@/api/model'
import { customerAccountService } from '@/api/services'
import CompanyFiguresFields from '@/components/CompanyFiguresFields.vue'
import KeyBlock from '@/components/KeyBlock.vue'
import LicenseStatusTag from '@/components/LicenseStatusTag.vue'
import { billingPeriodName, formatDate, formatMoney, planName } from '@/util/format'

/**
 * The customer account: each license's status, plan, billing period, paid-through date and next anniversary, the
 * yearly confirmation of the company's figures when it is due, every key issued with copy and download, and the Stripe
 * Customer Portal for payment details and receipts.
 */
const toast = useToast()
const account = ref<CustomerAccount | null>(null)
const loading = ref(true)
const openingPortal = ref(false)

// the license whose figures are being confirmed
const confirming = ref<LicenseWithKeys | null>(null)
const annualRevenue = ref<number | null>(null)
const capitalRaised = ref<number | null>(null)
const submitting = ref(false)

const subscribed = computed(() => account.value?.licenses.some(l => l.license.plan !== 'FREE'
    && (l.license.status === 'ACTIVE' || l.license.status === 'PAST_DUE')) ?? false)
const hasFreeLicense = computed(() => account.value?.licenses.some(l => l.license.plan === 'FREE' && l.license.status === 'ACTIVE') ?? false)
const pendingRequest = computed(() => account.value?.freeLicenseRequests.some(r => r.status === 'PENDING') ?? false)
const confirmingFree = computed(() => confirming.value?.license.plan === 'FREE')
// the capital raised decides between Free and Small, so a free license and any revenue under $1M give it
const needsCapital = computed(() => confirmingFree.value || (annualRevenue.value !== null && annualRevenue.value < 1_000_000))
const figuresReady = computed(() => annualRevenue.value !== null && (!needsCapital.value || capitalRaised.value !== null))

onMounted(load)

async function load() {
  loading.value = true
  try {
    account.value = await customerAccountService.getAccount()
  } catch (err) {
    showErrorToast(toast, 'Your account could not be loaded', err)
  } finally {
    loading.value = false
  }
}

async function openPortal() {
  openingPortal.value = true
  try {
    window.location.assign(await customerAccountService.createPortalSession())
  } catch (err) {
    showErrorToast(toast, 'Billing could not be opened', err)
    openingPortal.value = false
  }
}

function startConfirming(entry: LicenseWithKeys) {
  confirming.value = entry
  annualRevenue.value = null
  capitalRaised.value = null
}

async function submitConfirmation() {
  if (!confirming.value || !figuresReady.value) {
    return
  }
  submitting.value = true
  try {
    await customerAccountService.confirmRevenue(confirming.value.license.id,
                                                { annualRevenue: annualRevenue.value!, capitalRaised: capitalRaised.value })
    toast.add({ severity: 'success', summary: 'Figures confirmed', detail: 'Thank you. We have emailed you anything that changes.', life: 6000 })
    confirming.value = null
    await load()
  } catch (err) {
    showErrorToast(toast, 'Your figures could not be confirmed', err, { life: 8000 })
  } finally {
    submitting.value = false
  }
}

/** The paid plan a free license's latest confirmed figures set, which it must buy by its anniversary. */
function planToBuy(entry: LicenseWithKeys): Plan | null {
  const latest = entry.confirmations[0]
  return entry.license.plan === 'FREE' && latest && latest.plan !== 'FREE' && latest.anniversary === entry.license.termEnd ? latest.plan : null
}
</script>

<template>
  <div>
    <PageHeader :title="account?.customer.legalName ?? 'Your account'" description="Your Kinotic Enterprise licenses and every key issued for them.">
      <template #actions>
        <Button v-if="account?.customer.stripeCustomerId" label="Payment details and receipts" icon="pi pi-credit-card" severity="secondary" outlined
                :loading="openingPortal" @click="openPortal" />
        <RouterLink v-if="account && !subscribed" to="/buy?plan=small&billing=monthly"><Button label="Buy a plan" icon="pi pi-shopping-cart" /></RouterLink>
      </template>
    </PageHeader>

    <div v-if="loading" class="app-muted-text text-sm">Loading…</div>
    <div v-else-if="account" class="flex flex-col gap-8">
      <section v-if="account.licenses.length === 0" class="app-card-surface rounded-xl border p-5 text-sm">
        <p class="app-heading-text">No license yet.</p>
        <p class="app-muted-text mt-2">
          <span v-if="pendingRequest">Your free license request is being reviewed.</span>
          <span v-else>
            <RouterLink to="/buy?plan=small&billing=monthly" class="underline">Buy a plan</RouterLink>, or
            <RouterLink to="/free" class="underline">request a free license</RouterLink> if your company qualifies.
          </span>
        </p>
      </section>

      <section v-for="entry in account.licenses" :key="entry.license.id" class="flex flex-col gap-4">
        <div class="app-card-surface rounded-xl border p-5">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <h2 class="app-heading-text text-lg font-medium">{{ planName(entry.license.plan) }} plan</h2>
            <LicenseStatusTag :status="entry.license.status" />
          </div>
          <dl class="mt-4 grid gap-4 text-sm sm:grid-cols-3">
            <div v-if="entry.license.billingPeriod">
              <dt class="app-muted-text">Billing</dt>
              <dd class="app-heading-text mt-1">{{ billingPeriodName(entry.license.billingPeriod) }}</dd>
            </div>
            <div v-if="entry.paidThrough">
              <dt class="app-muted-text">Paid through</dt>
              <dd class="app-heading-text mt-1">{{ formatDate(entry.paidThrough) }}</dd>
            </div>
            <div>
              <dt class="app-muted-text">Next anniversary</dt>
              <dd class="app-heading-text mt-1">{{ formatDate(entry.license.termEnd) }}</dd>
            </div>
            <div v-if="entry.license.cancelsAt">
              <dt class="app-muted-text">Subscription</dt>
              <dd class="app-heading-text mt-1">Ends {{ formatDate(entry.license.cancelsAt) }}</dd>
            </div>
            <div v-if="entry.license.status === 'PAST_DUE'">
              <dt class="app-muted-text">Payment</dt>
              <dd v-if="entry.keys.length" class="mt-1 text-orange-600 dark:text-orange-400">
                A payment failed; update your payment details to keep receiving keys. Unpaid for 30 days, the license lapses.
              </dd>
              <dd v-else class="app-heading-text mt-1">Waiting for your payment to clear; your key is emailed the moment it does.</dd>
            </div>
          </dl>
          <Message v-if="entry.confirmationDueFor" severity="info" class="mt-4">
            Please confirm your company's figures for your anniversary on {{ formatDate(entry.confirmationDueFor) }}.
            <a href="#" class="font-medium underline" @click.prevent="startConfirming(entry)">Confirm your figures</a>
          </Message>
          <Message v-if="planToBuy(entry)" severity="warn" class="mt-4">
            Your figures now set the {{ planName(planToBuy(entry)) }} plan.
            <RouterLink :to="`/buy?plan=${planToBuy(entry)?.toLowerCase()}&billing=monthly`" class="font-medium underline">Buy it</RouterLink>
            by {{ formatDate(entry.license.termEnd) }} to keep your license and its price list.
          </Message>
          <ul v-if="entry.confirmations.length" class="app-muted-text mt-4 space-y-1 text-xs">
            <li v-for="confirmation in entry.confirmations" :key="confirmation.id">
              Confirmed for {{ formatDate(confirmation.anniversary) }}: {{ formatMoney(confirmation.figures.annualRevenue, 'usd') }} revenue<span
                v-if="confirmation.figures.capitalRaised !== null">, {{ formatMoney(confirmation.figures.capitalRaised, 'usd') }} raised</span>
              · {{ planName(confirmation.plan) }}
            </li>
          </ul>
        </div>
        <h3 class="app-heading-text text-sm font-medium">Keys</h3>
        <KeyBlock v-for="(key, index) in entry.keys" :key="key.jti" :issued-key="key" :collapsed="index > 0" />
      </section>

      <section v-if="account.freeLicenseRequests.length && !hasFreeLicense" class="app-card-surface rounded-xl border p-5 text-sm">
        <h3 class="app-heading-text font-medium">Free license requests</h3>
        <ul class="app-muted-text mt-2 space-y-1">
          <li v-for="request in account.freeLicenseRequests" :key="request.id">
            {{ formatDate(request.createdAt) }} · {{ request.status === 'PENDING' ? 'Being reviewed' : request.status === 'APPROVED' ? 'Approved' : 'Not approved' }}
          </li>
        </ul>
      </section>
    </div>

    <FormDrawer :visible="confirming !== null" title="Confirm your figures"
                description="Your plan is set by your company's figures. If they set another plan, your license moves to it at its anniversary, on the price list it keeps."
                @close="confirming = null">
      <form id="confirm-form" class="flex flex-col gap-5" @submit.prevent="submitConfirmation">
        <CompanyFiguresFields v-model:annual-revenue="annualRevenue" v-model:capital-raised="capitalRaised" :always-ask-capital="confirmingFree" />
      </form>
      <template #footer>
        <Button type="button" label="Cancel" severity="secondary" outlined @click="confirming = null" />
        <Button type="submit" form="confirm-form" label="Confirm" :disabled="!figuresReady" :loading="submitting" />
      </template>
    </FormDrawer>
  </div>
</template>
