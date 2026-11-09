<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { FormDrawer, PageHeader, showErrorToast } from '@kinotic-ai/frontend-common'
import type { CustomerAccount } from '@/api/model'
import { customerAccountService } from '@/api/services'
import CertificationFields from '@/components/CertificationFields.vue'
import TermsAcceptance from '@/components/TermsAcceptance.vue'
import KeyBlock from '@/components/KeyBlock.vue'
import LicenseStatusTag from '@/components/LicenseStatusTag.vue'
import { SITE } from '@/states/site'
import { formatDate, planName } from '@/util/format'

/**
 * The customer account: each license's status, plan, billing period and term, every key issued with copy and
 * download, the Stripe Customer Portal for payment details and receipts, and re-certification for a free license.
 */
const toast = useToast()
const account = ref<CustomerAccount | null>(null)
const loading = ref(true)
const openingPortal = ref(false)

const recertifyVisible = ref(false)
const annualRevenue = ref<number | null>(null)
const capitalRaised = ref<number | null>(null)
const intendedUse = ref('')
const accepted = ref(false)
const submitting = ref(false)

const freeLicense = computed(() => account.value?.licenses.find(l => l.license.plan === 'FREE') ?? null)
const subscribed = computed(() => account.value?.licenses.some(l => l.license.plan !== 'FREE'
    && (l.license.status === 'ACTIVE' || l.license.status === 'PAST_DUE')) ?? false)
const pendingRenewal = computed(() => account.value?.freeLicenseRequests.some(r => r.status === 'PENDING') ?? false)
const certificationReady = computed(() => annualRevenue.value !== null && capitalRaised.value !== null && intendedUse.value.trim() && accepted.value)

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

async function submitRecertification() {
  submitting.value = true
  try {
    await customerAccountService.requestFreeLicense({
      annualRevenue: annualRevenue.value ?? 0,
      capitalRaised: capitalRaised.value ?? 0,
      intendedUse: intendedUse.value.trim()
    }, SITE.termsVersion)
    toast.add({ severity: 'success', summary: 'Renewal requested', detail: 'We will email you once it is reviewed.', life: 6000 })
    recertifyVisible.value = false
    await load()
  } catch (err) {
    showErrorToast(toast, 'The renewal could not be requested', err, { life: 8000 })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader :title="account?.customer.legalName ?? 'Your account'" description="Your Kinotic Enterprise licenses and every key issued for them.">
      <template #actions>
        <Button v-if="account?.customer.stripeCustomerId" label="Payment details and receipts" icon="pi pi-credit-card" severity="secondary" outlined
                :loading="openingPortal" @click="openPortal" />
        <Button v-if="freeLicense && !pendingRenewal" label="Re-certify for renewal" icon="pi pi-refresh" severity="secondary" outlined
                @click="recertifyVisible = true" />
        <RouterLink v-if="account && !subscribed" to="/"><Button label="Buy a plan" icon="pi pi-shopping-cart" /></RouterLink>
      </template>
    </PageHeader>

    <div v-if="loading" class="app-muted-text text-sm">Loading…</div>
    <div v-else-if="account" class="flex flex-col gap-8">
      <section v-if="account.licenses.length === 0" class="app-card-surface rounded-xl border p-5 text-sm">
        <p class="app-heading-text">No license yet.</p>
        <p class="app-muted-text mt-2">
          <span v-if="pendingRenewal || account.freeLicenseRequests.length">Your free license request is being reviewed.</span>
          <span v-else>Pick a plan from the <RouterLink to="/" class="underline">plans page</RouterLink>.</span>
        </p>
      </section>

      <section v-for="entry in account.licenses" :key="entry.license.id" class="flex flex-col gap-4">
        <div class="app-card-surface rounded-xl border p-5">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <h2 class="app-heading-text text-lg font-medium">{{ planName(entry.license.plan) }} plan</h2>
            <LicenseStatusTag :status="entry.license.status" />
          </div>
          <dl class="mt-4 grid gap-4 text-sm sm:grid-cols-3">
            <div v-if="entry.license.currentPeriodEnd">
              <dt class="app-muted-text">Current billing period ends</dt>
              <dd class="app-heading-text mt-1">{{ formatDate(entry.license.currentPeriodEnd) }}</dd>
            </div>
            <div>
              <dt class="app-muted-text">License term</dt>
              <dd class="app-heading-text mt-1">{{ formatDate(entry.license.termStart) }} – {{ formatDate(entry.license.termEnd) }}</dd>
            </div>
            <div v-if="entry.license.cancelsAt">
              <dt class="app-muted-text">Subscription</dt>
              <dd class="app-heading-text mt-1">Cancelled; ends {{ formatDate(entry.license.cancelsAt) }}</dd>
            </div>
            <div v-if="entry.license.status === 'PAST_DUE'">
              <dt class="app-muted-text">Payment</dt>
              <dd v-if="entry.keys.length" class="mt-1 text-orange-600 dark:text-orange-400">A payment failed; update your payment details to keep receiving keys.</dd>
              <dd v-else class="app-heading-text mt-1">Waiting for your payment to clear; your key is emailed the moment it does.</dd>
            </div>
          </dl>
        </div>
        <h3 class="app-heading-text text-sm font-medium">Keys</h3>
        <KeyBlock v-for="(key, index) in entry.keys" :key="key.jti" :issued-key="key" :collapsed="index > 0" />
      </section>

      <section v-if="account.freeLicenseRequests.length" class="app-card-surface rounded-xl border p-5 text-sm">
        <h3 class="app-heading-text font-medium">Free license requests</h3>
        <ul class="app-muted-text mt-2 space-y-1">
          <li v-for="request in account.freeLicenseRequests" :key="request.id">
            {{ formatDate(request.createdAt) }} · {{ request.status === 'PENDING' ? 'Being reviewed' : request.status === 'APPROVED' ? 'Approved' : 'Not approved' }}
          </li>
        </ul>
      </section>
    </div>

    <FormDrawer :visible="recertifyVisible" title="Re-certify for renewal"
                description="Confirm your organization still qualifies for the free plan. Minds Ignited reviews it and issues the next year's key."
                @close="recertifyVisible = false">
      <form id="recertify-form" class="flex flex-col gap-5" @submit.prevent="submitRecertification">
        <CertificationFields v-model:annual-revenue="annualRevenue" v-model:capital-raised="capitalRaised" v-model:intended-use="intendedUse" />
        <TermsAcceptance v-model="accepted" />
      </form>
      <template #footer>
        <Button type="button" label="Cancel" severity="secondary" outlined @click="recertifyVisible = false" />
        <Button type="submit" form="recertify-form" label="Request renewal" :disabled="!certificationReady" :loading="submitting" />
      </template>
    </FormDrawer>
  </div>
</template>
