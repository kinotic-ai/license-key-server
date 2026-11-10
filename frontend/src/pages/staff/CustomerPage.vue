<script setup lang="ts">
import { onMounted, ref } from 'vue'
import Button from 'primevue/button'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { FactList, PageHeader, TimePill, showErrorToast } from '@kinotic-ai/frontend-common'
import { Building2, CreditCard, Mail } from '@lucide/vue'
import type { CustomerDetail, LicenseWithKeys } from '@/api/model'
import { staffCustomerService } from '@/api/services'
import KeyBlock from '@/components/KeyBlock.vue'
import LicenseStatusTag from '@/components/LicenseStatusTag.vue'
import FlagTags from '@/components/FlagTags.vue'
import { billingPeriodName, formatDate, formatMoney, planName, publicityLabel } from '@/util/format'

/**
 * One customer: its contacts, each license with its billing, price list, paid-through date, flags, yearly confirmations,
 * keys and the actions on it, and its agreement acceptances with their publicity consent.
 */
const props = defineProps<{
  customerId: string
}>()

const toast = useToast()
const confirm = useConfirm()
const detail = ref<CustomerDetail | null>(null)
const busy = ref<string | null>(null)

onMounted(load)

async function load() {
  try {
    detail.value = await staffCustomerService.getCustomerDetail(props.customerId)
  } catch (err) {
    showErrorToast(toast, 'The customer could not be loaded', err)
  }
}

function facts() {
  const customer = detail.value!.customer
  return [
    { label: 'Legal name', icon: Building2, value: customer.legalName },
    { label: 'Stripe customer', icon: CreditCard, value: customer.stripeCustomerId, mono: true },
    ...detail.value!.contacts.map(contact => ({ label: `${contact.role === 'BILLING' ? 'Billing' : 'Technical'} contact`, icon: Mail, value: `${contact.name} <${contact.email}>` }))
  ]
}

async function act(entry: LicenseWithKeys, action: () => Promise<unknown>, success: string, failure: string) {
  busy.value = entry.license.id
  try {
    await action()
    toast.add({ severity: 'success', summary: success, life: 4000 })
    await load()
  } catch (err) {
    showErrorToast(toast, failure, err, { life: 8000 })
  } finally {
    busy.value = null
  }
}

function revoke(entry: LicenseWithKeys) {
  confirm.require({
    header: 'Revoke this license?',
    message: 'No further key will be issued. Keys already issued keep working until they expire.',
    icon: 'pi pi-exclamation-triangle',
    acceptProps: { label: 'Revoke', severity: 'danger' },
    rejectProps: { label: 'Cancel', severity: 'secondary', outlined: true },
    accept: () => act(entry, () => staffCustomerService.revokeLicense(entry.license.id), 'License revoked', 'The license could not be revoked')
  })
}

function replace(entry: LicenseWithKeys) {
  confirm.require({
    header: 'Issue a replacement key?',
    message: 'A new key covering the date the license is paid through is issued and emailed to the customer\'s contacts.',
    icon: 'pi pi-key',
    acceptProps: { label: 'Issue key' },
    rejectProps: { label: 'Cancel', severity: 'secondary', outlined: true },
    accept: () => act(entry, () => staffCustomerService.issueReplacementKey(entry.license.id), 'Replacement key issued', 'The key could not be issued')
  })
}

function resend(entry: LicenseWithKeys) {
  act(entry, () => staffCustomerService.resendKey(entry.license.id), 'Key email sent again', 'The key email could not be sent')
}
</script>

<template>
  <div v-if="detail">
    <PageHeader :title="detail.customer.legalName" :description="`Customer since ${formatDate(detail.customer.createdAt)}`">
      <template #eyebrow>
        <RouterLink to="/customers" class="hover:underline">Customers</RouterLink>
      </template>
    </PageHeader>

    <div class="flex flex-col gap-8">
      <FactList :facts="facts()" />

      <section v-for="entry in detail.licenses" :key="entry.license.id" class="flex flex-col gap-4">
        <div class="app-card-surface rounded-xl border p-5">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <div class="flex items-center gap-3">
              <h2 class="app-heading-text text-lg font-medium">{{ planName(entry.license.plan) }} plan</h2>
              <LicenseStatusTag :status="entry.license.status" />
            </div>
            <div class="flex flex-wrap gap-2">
              <Button label="Resend key email" icon="pi pi-envelope" severity="secondary" outlined size="small"
                      :disabled="!entry.keys.length" :loading="busy === entry.license.id" @click="resend(entry)" />
              <Button label="Replacement key" icon="pi pi-key" severity="secondary" outlined size="small"
                      :disabled="entry.license.status === 'REVOKED'" :loading="busy === entry.license.id" @click="replace(entry)" />
              <Button label="Revoke" icon="pi pi-ban" severity="danger" outlined size="small"
                      :disabled="entry.license.status === 'REVOKED'" :loading="busy === entry.license.id" @click="revoke(entry)" />
            </div>
          </div>
          <dl class="mt-4 grid gap-4 text-sm sm:grid-cols-4">
            <div><dt class="app-muted-text">Billing</dt><dd class="app-heading-text mt-1">{{ billingPeriodName(entry.license.billingPeriod) }}</dd></div>
            <div><dt class="app-muted-text">Price list · terms</dt><dd class="app-heading-text mt-1">{{ entry.license.priceList }} · {{ entry.license.termsVersion }}</dd></div>
            <div><dt class="app-muted-text">Paid through</dt><dd class="mt-1"><TimePill :date="entry.paidThrough" /></dd></div>
            <div><dt class="app-muted-text">License year</dt><dd class="app-heading-text mt-1">{{ formatDate(entry.license.termStart) }} – {{ formatDate(entry.license.termEnd) }}</dd></div>
            <div><dt class="app-muted-text">Last payment</dt><dd class="mt-1"><TimePill :date="entry.license.lastPaymentAt" /></dd></div>
            <div><dt class="app-muted-text">Stripe subscription</dt><dd class="mt-1 font-mono text-xs">{{ entry.license.stripeSubscriptionId ?? '—' }}</dd></div>
            <div v-if="entry.license.cancelsAt"><dt class="app-muted-text">Cancels</dt><dd class="app-heading-text mt-1">{{ formatDate(entry.license.cancelsAt) }}</dd></div>
            <div v-if="entry.license.pastDueSince"><dt class="app-muted-text">Past due since</dt><dd class="app-heading-text mt-1">{{ formatDate(entry.license.pastDueSince) }}</dd></div>
            <div v-if="entry.license.flags.length"><dt class="app-muted-text">Flags</dt><dd class="mt-1"><FlagTags :flags="entry.license.flags" /></dd></div>
          </dl>
          <div v-if="entry.confirmations.length" class="mt-4">
            <h3 class="app-heading-text text-sm font-medium">Yearly confirmations</h3>
            <table class="mt-2 w-full text-left text-sm">
              <thead class="app-muted-text">
                <tr><th class="py-1 pr-4 font-medium">Anniversary</th><th class="py-1 pr-4 font-medium">Annual revenue</th><th class="py-1 pr-4 font-medium">Capital raised</th><th class="py-1 pr-4 font-medium">Plan</th><th class="py-1 font-medium">Confirmed by</th></tr>
              </thead>
              <tbody>
                <tr v-for="confirmation in entry.confirmations" :key="confirmation.id" class="app-surface-divider border-t">
                  <td class="py-2 pr-4">{{ formatDate(confirmation.anniversary) }}</td>
                  <td class="py-2 pr-4 tabular-nums">{{ formatMoney(confirmation.figures.annualRevenue, 'usd') }}</td>
                  <td class="py-2 pr-4 tabular-nums">{{ formatMoney(confirmation.figures.capitalRaised, 'usd') }}</td>
                  <td class="py-2 pr-4">{{ planName(confirmation.licensePlan) }} → {{ planName(confirmation.plan) }}</td>
                  <td class="py-2">{{ confirmation.confirmedByName }}, {{ formatDate(confirmation.confirmedAt) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
        <KeyBlock v-for="(key, index) in entry.keys" :key="key.jti" :issued-key="key" :collapsed="index > 0" />
      </section>
      <p v-if="!detail.licenses.length" class="app-muted-text text-sm">No license yet.</p>

      <section class="app-card-surface rounded-xl border p-5">
        <h2 class="app-heading-text font-medium">Agreement acceptances</h2>
        <table class="mt-3 w-full text-left text-sm">
          <thead class="app-muted-text">
            <tr><th class="py-1 pr-4 font-medium">Accepted</th><th class="py-1 pr-4 font-medium">By</th><th class="py-1 pr-4 font-medium">Terms version</th><th class="py-1 font-medium">Publicity consent</th></tr>
          </thead>
          <tbody>
            <tr v-for="acceptance in detail.acceptances" :key="acceptance.id" class="app-surface-divider border-t">
              <td class="py-2 pr-4">{{ formatDate(acceptance.acceptedAt) }}</td>
              <td class="py-2 pr-4">{{ acceptance.acceptedByName }} &lt;{{ acceptance.acceptedByEmail }}&gt;</td>
              <td class="py-2 pr-4 font-mono text-xs">{{ acceptance.termsVersion }}</td>
              <td class="py-2">{{ publicityLabel(acceptance.publicityConsent) }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </div>
  </div>
</template>
