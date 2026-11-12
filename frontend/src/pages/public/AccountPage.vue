<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'
import { FormDrawer, PageHeader, showErrorToast } from '@kinotic-ai/frontend-common'
import type { CustomerAccount, LicenseWithKeys, Plan } from '@/api/model'
import { customerAccountService } from '@/api/services'
import ClusterList from '@/components/ClusterList.vue'
import CompanyFiguresFields from '@/components/CompanyFiguresFields.vue'
import LicenseStatusTag from '@/components/LicenseStatusTag.vue'
import { billingPeriodName, formatDate, formatMoney, planName } from '@/util/format'

/**
 * The customer account: each license's status, plan, seats, billing period, paid-through date and next anniversary,
 * when a trial ends and how to buy its plan, the yearly confirmation of the company's figures when it is due, the design
 * partner discount, the license's clusters with adding them and every key issued for each with copy and
 * download and how to install it on the system console's License page, and the Stripe Customer Portal for payment
 * details, receipts and seats.
 */
const CLUSTER_ID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

const toast = useToast()
const account = ref<CustomerAccount | null>(null)
const loading = ref(true)
const openingPortal = ref(false)

// the license whose figures are being confirmed
const confirming = ref<LicenseWithKeys | null>(null)
const annualRevenue = ref<number | null>(null)
const capitalRaised = ref<number | null>(null)
const submitting = ref(false)

// the license a cluster is being added to
const addingTo = ref<LicenseWithKeys | null>(null)
const clusterId = ref('')
const clusterName = ref('')
const adding = ref(false)
const clusterReady = computed(() => CLUSTER_ID.test(clusterId.value.trim()) && clusterName.value.trim().length > 0)

const subscribed = computed(() => account.value?.licenses.some(l => l.license.plan !== 'FREE'
    && (l.license.status === 'ACTIVE' || l.license.status === 'PAST_DUE')) ?? false)
const hasFreeLicense = computed(() => account.value?.licenses.some(l => l.license.plan === 'FREE' && l.license.status === 'ACTIVE') ?? false)
const pendingApplication = computed(() => account.value?.designPartnerApplications.some(a => a.status === 'PENDING') ?? false)
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

/** How many clusters hold a seat of the license. */
function seated(entry: LicenseWithKeys): number {
  return entry.clusters.filter(cluster => cluster.releasedAt === null).length
}

/** Whether a cluster can be added to the license now: it is active or a trial and has a seat free. */
function canAddCluster(entry: LicenseWithKeys): boolean {
  return (entry.license.status === 'ACTIVE' || entry.license.status === 'TRIAL') && seated(entry) < entry.license.seats
}

function startAddingCluster(entry: LicenseWithKeys) {
  addingTo.value = entry
  clusterId.value = ''
  clusterName.value = ''
}

// the drawer stays open while an add is in flight, so the add's completion closes the drawer it was made from
function closeAddCluster() {
  if (!adding.value) {
    addingTo.value = null
  }
}

async function submitCluster() {
  if (!addingTo.value || !clusterReady.value) {
    return
  }
  const entry = addingTo.value
  adding.value = true
  try {
    await customerAccountService.addCluster(entry.license.id, clusterId.value.trim(), clusterName.value.trim())
    toast.add({ severity: 'success', summary: 'Cluster added', detail: 'Its key is below. Paste it into the cluster\'s License page.', life: 6000 })
    addingTo.value = null
    await load()
  } catch (err) {
    showErrorToast(toast, 'The cluster could not be added', err, { life: 8000 })
  } finally {
    adding.value = false
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
    <PageHeader :title="account?.customer.legalName ?? 'Your account'" description="Your Kinotic Enterprise licenses, their clusters and every key issued for them.">
      <template #actions>
        <Button v-if="account?.customer.stripeCustomerId" label="Billing, receipts and seats" icon="pi pi-credit-card" severity="secondary" outlined
                :loading="openingPortal" @click="openPortal" />
        <RouterLink v-if="account && !subscribed" to="/buy?plan=small&billing=monthly">
          <Button :label="account.trialAvailable ? 'Try or buy a plan' : 'Buy a plan'" icon="pi pi-shopping-cart" />
        </RouterLink>
      </template>
    </PageHeader>

    <div v-if="loading" class="app-muted-text text-sm">Loading…</div>
    <div v-else-if="account" class="flex flex-col gap-8">
      <section v-if="account.licenses.length === 0" class="app-card-surface rounded-xl border p-5 text-sm">
        <p class="app-heading-text">No license yet.</p>
        <p class="app-muted-text mt-2">
          <span v-if="pendingRequest">Your free license request is being reviewed.</span>
          <span v-else>
            <RouterLink to="/buy?plan=small&billing=monthly" class="underline">{{ account.trialAvailable ? 'Try a paid plan free for three months or buy one' : 'Buy a plan' }}</RouterLink>, or
            <RouterLink to="/free" class="underline">request a free license</RouterLink> if your company qualifies.
          </span>
        </p>
      </section>

      <section v-if="account.designPartner" class="app-card-surface rounded-xl border p-5 text-sm">
        <h2 class="app-heading-text font-medium">Design partner</h2>
        <p v-if="account.designPartner.discountEndsAt" class="app-muted-text mt-2">
          Your subscription is half off until {{ formatDate(account.designPartner.discountEndsAt) }}. After that you pay your
          locked price, on the same terms, for as long as your license stays active.
        </p>
        <p v-else class="app-muted-text mt-2">
          You are approved as a design partner.
          <RouterLink :to="`/design-partner/checkout?application=${account.designPartner.applicationId}`" class="font-medium underline">
            Start your subscription at half off</RouterLink>.
        </p>
      </section>
      <section v-else-if="pendingApplication" class="app-card-surface rounded-xl border p-5 text-sm">
        <p class="app-heading-text">Your design partner application is being reviewed.</p>
      </section>

      <section v-for="entry in account.licenses" :key="entry.license.id" class="flex flex-col gap-4">
        <div class="app-card-surface rounded-xl border p-5">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <h2 class="app-heading-text text-lg font-medium">{{ planName(entry.license.plan) }} plan</h2>
            <LicenseStatusTag :status="entry.license.status" />
          </div>
          <dl class="mt-4 grid gap-4 text-sm sm:grid-cols-3">
            <div>
              <dt class="app-muted-text">Clusters</dt>
              <dd class="app-heading-text mt-1">{{ seated(entry) }} of {{ entry.license.seats }} {{ entry.license.seats === 1 ? 'seat' : 'seats' }}</dd>
            </div>
            <div v-if="entry.license.billingPeriod">
              <dt class="app-muted-text">Billing</dt>
              <dd class="app-heading-text mt-1">{{ billingPeriodName(entry.license.billingPeriod) }}</dd>
            </div>
            <div v-if="entry.paidThrough">
              <dt class="app-muted-text">Paid through</dt>
              <dd class="app-heading-text mt-1">{{ formatDate(entry.paidThrough) }}</dd>
            </div>
            <div v-if="entry.license.status === 'TRIAL' || entry.license.status === 'EXPIRED'">
              <dt class="app-muted-text">Trial {{ entry.license.status === 'TRIAL' ? 'ends' : 'ended' }}</dt>
              <dd class="app-heading-text mt-1">{{ formatDate(entry.license.termEnd) }}</dd>
            </div>
            <div v-else>
              <dt class="app-muted-text">Next anniversary</dt>
              <dd class="app-heading-text mt-1">{{ formatDate(entry.license.termEnd) }}</dd>
            </div>
            <div v-if="entry.license.cancelsAt">
              <dt class="app-muted-text">Subscription</dt>
              <dd class="app-heading-text mt-1">Ends {{ formatDate(entry.license.cancelsAt) }}</dd>
            </div>
            <div v-if="entry.license.status === 'PAST_DUE'">
              <dt class="app-muted-text">Payment</dt>
              <dd v-if="entry.license.lastPaymentAt" class="mt-1 text-orange-600 dark:text-orange-400">
                A payment failed; update your payment details to keep receiving keys. Unpaid for 30 days, the license lapses.
              </dd>
              <dd v-else class="app-heading-text mt-1">Waiting for your payment to clear; we email you the moment it does, and you can add your clusters then.</dd>
            </div>
          </dl>
          <Message v-if="entry.license.status === 'TRIAL'" severity="info" class="mt-4">
            Your trial of the {{ planName(entry.license.plan) }} plan ends on {{ formatDate(entry.license.termEnd) }}.
            <RouterLink :to="`/buy?plan=${entry.license.plan.toLowerCase()}&billing=monthly`" class="font-medium underline">Buy it</RouterLink>
            before then to keep your license and its price list.
          </Message>
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
        <div class="flex flex-wrap items-center justify-between gap-3">
          <h3 class="app-heading-text text-sm font-medium">Clusters</h3>
          <div class="flex flex-wrap items-center gap-3">
            <span v-if="!canAddCluster(entry) && seated(entry) >= entry.license.seats && entry.license.stripeSubscriptionId"
                  class="app-muted-text text-xs">Every seat is taken; add seats under Billing, receipts and seats.</span>
            <Button label="Add a cluster" icon="pi pi-plus" size="small" :disabled="!canAddCluster(entry)" @click="startAddingCluster(entry)" />
          </div>
        </div>
        <ClusterList :entry="entry" />
      </section>

      <section v-if="account.licenses.length" class="app-card-surface rounded-xl border p-5 text-sm">
        <h3 class="app-heading-text font-medium">Licensing a cluster</h3>
        <ol class="app-muted-text mt-2 list-decimal space-y-1 pl-5">
          <li>Sign in to the cluster's Kinotic system console as a system user, open its <strong>License</strong> page and copy its cluster id.</li>
          <li>Add the cluster above with that id. Its key works on that cluster alone.</li>
          <li>Copy the cluster's key, or download it, then paste it into the License page and install it. Kinotic shows your organization as the licensee and turns on every Enterprise feature.</li>
        </ol>
        <p class="app-muted-text mt-2">
          Each key expires seven days after the date your license is paid through. A cluster that can reach
          licenses.kinotic.ai installs each new key by itself; for one that cannot, copy its new key from here after each
          payment. A trial's key expires when the trial ends. Kinotic keeps its Enterprise features on for 14 days after a
          key expires.
        </p>
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

    <FormDrawer :visible="addingTo !== null" title="Add a cluster"
                description="The cluster id is on the License page of the cluster's Kinotic system console. The cluster takes one of the license's seats."
                @close="closeAddCluster">
      <form id="cluster-form" class="flex flex-col gap-5" @submit.prevent="submitCluster">
        <div class="flex flex-col gap-2">
          <label for="cluster-id" class="app-heading-text text-sm font-medium">Cluster id</label>
          <InputText id="cluster-id" v-model="clusterId" class="w-full font-mono" placeholder="00000000-0000-0000-0000-000000000000" />
        </div>
        <div class="flex flex-col gap-2">
          <label for="cluster-name" class="app-heading-text text-sm font-medium">Name</label>
          <InputText id="cluster-name" v-model="clusterName" class="w-full" placeholder="production" maxlength="100" />
        </div>
      </form>
      <template #footer>
        <Button type="button" label="Cancel" severity="secondary" outlined :disabled="adding" @click="closeAddCluster" />
        <Button type="submit" form="cluster-form" label="Add cluster" :disabled="!clusterReady" :loading="adding" />
      </template>
    </FormDrawer>

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
