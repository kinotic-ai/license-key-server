<script setup lang="ts">
import { computed, onMounted, ref, type Component } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'
import { BadgeCheck, CalendarCheck, CalendarClock, CalendarX, Gift, Handshake, Rocket, Server, ShoppingCart } from '@lucide/vue'
import { DashboardSection, FormDrawer, PageHeader, StatCard, TINTS, showErrorToast } from '@kinotic-ai/frontend-common'
import type { CustomerAccount, LicenseWithKeys, Plan } from '@/api/model'
import { customerAccountService } from '@/api/services'
import ClusterList from '@/components/ClusterList.vue'
import CompanyFiguresFields from '@/components/CompanyFiguresFields.vue'
import LicenseStatusTag from '@/components/LicenseStatusTag.vue'
import NoticeCard from '@/components/NoticeCard.vue'
import NumberedSteps from '@/components/NumberedSteps.vue'
import { billingPeriodName, formatDate, formatMoney, planName, statusLabel } from '@/util/format'

/**
 * The customer account: each license's status, plan, seats, billing period, paid-through date and next anniversary,
 * when a trial ends and how to buy its plan, the yearly confirmation of the company's figures when it is due, the design
 * partner discount, the license's clusters with adding them, started from a cluster's License page link that names the
 * cluster in its clusterId query parameter and the page to hand the key back to in its returnUrl, and every key issued
 * for each with copy and
 * download and how to install it on the system console's License page, and the Stripe Customer Portal for payment
 * details, receipts and seats.
 */
interface Tile {
  icon: Component
  tint: string
  label: string
  value: string
  detail: string
}

const CLUSTER_ID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

const LICENSING_STEPS = [
  { title: "Open the cluster's License page", detail: 'Sign in to its Kinotic system console as a system user.' },
  { title: 'Select License this cluster', detail: 'It brings you here with the cluster filled in.' },
  { title: 'Add the cluster', detail: 'Its key goes back to the console, which installs it. The cluster renews its key by itself from then on.' }
]

const toast = useToast()
const route = useRoute()
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
// the cluster id a cluster's system console License page sends with its "License this cluster" link, until it is added
const linkedClusterId = ref<string | null>(null)
// the License page the link came from, which the linked cluster's key is handed back to
const returnUrl = ref<URL | null>(null)
const addableLicenses = computed(() => account.value?.licenses.filter(canAddCluster) ?? [])
// the linked cluster when it holds a seat of one of the customer's licenses already, and that license
const linkedCluster = computed(() => account.value?.licenses.flatMap(entry => entry.clusters)
    .find(cluster => cluster.releasedAt === null && cluster.clusterId === linkedClusterId.value))
const linkedHolder = computed(() => account.value?.licenses.find(entry => entry.license.id === linkedCluster.value?.licenseId))
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

onMounted(async () => {
  await load()
  takeLinkedCluster()
})

/** Loads the account, returning whether it loaded. */
async function load(): Promise<boolean> {
  let loaded = false
  loading.value = true
  try {
    account.value = await customerAccountService.getAccount()
    loaded = true
  } catch (err) {
    showErrorToast(toast, 'Your account could not be loaded', err)
  } finally {
    loading.value = false
  }
  return loaded
}

// Takes the cluster id of the link: opens the add-cluster drawer when exactly one license has an open seat, and
// otherwise leaves the id for the drawer of whichever license the customer picks. The link stays in the address so a
// reload after an add whose account refresh failed offers the hand-back of the added cluster's key.
function takeLinkedCluster() {
  const linked = typeof route.query.clusterId === 'string' ? route.query.clusterId.trim().toLowerCase() : ''
  if (!account.value || !CLUSTER_ID.test(linked)) {
    return
  }
  returnUrl.value = parseReturnUrl(route.query.returnUrl)
  linkedClusterId.value = linked
  if (!linkedHolder.value && addableLicenses.value.length === 1) {
    startAddingCluster(addableLicenses.value[0]!)
  }
}

// only a web address can take the key back; anything else, such as a javascript: URL, leaves the key on this page
function parseReturnUrl(value: unknown): URL | null {
  let ret: URL | null = null
  if (typeof value === 'string') {
    try {
      const url = new URL(value)
      ret = url.protocol === 'https:' || url.protocol === 'http:' ? url : null
    } catch {
      ret = null
    }
  }
  return ret
}

/** Sends the browser back to the License page the link came from, with the cluster's newest key in the URL fragment. */
function handBackKey(licensedClusterId: string) {
  const key = account.value?.licenses.flatMap(entry => entry.keys).find(issued => issued.licensedClusterId === licensedClusterId)
  if (!returnUrl.value || !key) {
    return
  }
  const target = new URL(returnUrl.value)
  target.hash = `key=${encodeURIComponent(key.key)}`
  window.location.assign(target.toString())
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

/** The license's figures at a glance: its plan, its seats, and the dates it is paid through, renews or ends on. */
function tiles(entry: LicenseWithKeys): Tile[] {
  const license = entry.license
  const open = license.seats - seated(entry)
  const ret: Tile[] = [
    {
      icon: BadgeCheck, tint: TINTS.green, label: 'Plan', value: planName(license.plan),
      detail: license.billingPeriod ? `${billingPeriodName(license.billingPeriod)} billing` : statusLabel(license.status)
    },
    {
      icon: Server, tint: TINTS.blue, label: 'Clusters', value: `${seated(entry)} of ${license.seats}`,
      detail: open > 0 ? `${open} ${open === 1 ? 'seat' : 'seats'} open` : 'Every seat is taken'
    }
  ]
  if (entry.paidThrough) {
    ret.push({ icon: CalendarCheck, tint: TINTS.purple, label: 'Paid through', value: formatDate(entry.paidThrough), detail: 'Each key expires seven days later' })
  }
  if (license.status === 'TRIAL' || license.status === 'EXPIRED') {
    ret.push({
      icon: CalendarClock, tint: TINTS.orange, label: license.status === 'TRIAL' ? 'Trial ends' : 'Trial ended', value: formatDate(license.termEnd),
      detail: 'Buy the plan to keep the license'
    })
  } else {
    ret.push({ icon: CalendarClock, tint: TINTS.sky, label: 'Next anniversary', value: formatDate(license.termEnd), detail: 'Your figures are confirmed each year' })
  }
  if (license.cancelsAt) {
    ret.push({ icon: CalendarX, tint: TINTS.orange, label: 'Subscription ends', value: formatDate(license.cancelsAt), detail: 'Its keys work until they expire' })
  }
  return ret
}

/** Whether a cluster can be added to the license now: it is active or a trial and has an open seat. */
function canAddCluster(entry: LicenseWithKeys): boolean {
  return (entry.license.status === 'ACTIVE' || entry.license.status === 'TRIAL') && seated(entry) < entry.license.seats
}

function startAddingCluster(entry: LicenseWithKeys) {
  addingTo.value = entry
  clusterId.value = linkedClusterId.value ?? ''
  clusterName.value = linkedClusterId.value && returnUrl.value ? returnUrl.value.hostname : ''
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
    const added = await customerAccountService.addCluster(entry.license.id, clusterId.value.trim(), clusterName.value.trim())
    const handingBack = returnUrl.value !== null && added.clusterId === linkedClusterId.value
    if (!handingBack) {
      toast.add({ severity: 'success', summary: 'Cluster added', detail: 'Its key is below. Paste it into the cluster\'s License page.', life: 6000 })
    }
    addingTo.value = null
    linkedClusterId.value = null
    const loaded = await load()
    if (handingBack && loaded) {
      handBackKey(added.id)
    } else if (handingBack) {
      toast.add({ severity: 'warn', summary: 'Cluster added', detail: `Reload this page to send its key to ${returnUrl.value!.host}.`, life: 10000 })
    }
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
    <PageHeader :title="account?.customer.legalName ?? 'Your account'" description="Your Kinotic Enterprise licenses and the clusters they cover.">
      <template #actions>
        <Button v-if="account?.customer.stripeCustomerId" label="Billing, receipts and seats" icon="pi pi-credit-card" severity="secondary" outlined
                :loading="openingPortal" @click="openPortal" />
        <RouterLink v-if="account && !subscribed && account.licenses.length" to="/buy">
          <Button :label="account.trialAvailable ? 'Try or buy a plan' : 'Buy a plan'" icon="pi pi-shopping-cart" />
        </RouterLink>
      </template>
    </PageHeader>

    <div v-if="loading" class="app-muted-text text-sm">Loading…</div>
    <div v-else-if="account" class="flex flex-col gap-6">
      <template v-if="linkedClusterId && !addingTo">
        <Message v-if="linkedHolder" severity="info">
          Cluster <span class="font-mono">{{ linkedClusterId }}</span> holds a seat of your {{ planName(linkedHolder.license.plan) }} plan
          already; its key is below.
          <Button v-if="returnUrl" class="mt-2" size="small" :label="`Send its key to ${returnUrl.host}`"
                  @click="handBackKey(linkedCluster!.id)" />
        </Message>
        <Message v-else-if="addableLicenses.length === 0" severity="warn">
          No license has an open seat for cluster <span class="font-mono">{{ linkedClusterId }}</span>. Buy a plan, or add seats under
          Billing, receipts and seats.
        </Message>
        <Message v-else severity="info">
          Select Add a cluster on the license that should cover cluster <span class="font-mono">{{ linkedClusterId }}</span>.
        </Message>
      </template>

      <template v-if="account.licenses.length === 0">
        <Message v-if="pendingRequest" severity="info">
          Your free license request is being reviewed. We email you the result, and your license appears here once it is approved.
        </Message>
        <section v-else>
          <h2 class="app-heading-text font-medium">Get your first license</h2>
          <p class="app-muted-text mt-1 text-sm">A license turns on every Kinotic Enterprise feature on the clusters you add to it.</p>
          <div class="mt-4 grid gap-4 lg:grid-cols-3">
            <div v-if="account.trialAvailable" class="app-card-surface flex flex-col gap-3 rounded-xl border p-5">
              <span :class="['flex h-9 w-9 items-center justify-center rounded-lg', TINTS.green]"><Rocket :size="18" :stroke-width="1.75" aria-hidden="true" /></span>
              <h3 class="app-heading-text font-medium">Try a paid plan</h3>
              <p class="app-muted-text flex-1 text-sm">Free for three months on one cluster, with no payment details.</p>
              <RouterLink to="/buy"><Button label="Start a trial" /></RouterLink>
            </div>
            <div class="app-card-surface flex flex-col gap-3 rounded-xl border p-5">
              <span :class="['flex h-9 w-9 items-center justify-center rounded-lg', TINTS.blue]"><ShoppingCart :size="18" :stroke-width="1.75" aria-hidden="true" /></span>
              <h3 class="app-heading-text font-medium">Buy a plan</h3>
              <p class="app-muted-text flex-1 text-sm">Priced by your company's annual revenue, for each cluster. Pay monthly or yearly and cancel any time.</p>
              <RouterLink to="/buy"><Button label="See the plans" severity="secondary" outlined /></RouterLink>
            </div>
            <div class="app-card-surface flex flex-col gap-3 rounded-xl border p-5">
              <span :class="['flex h-9 w-9 items-center justify-center rounded-lg', TINTS.purple]"><Gift :size="18" :stroke-width="1.75" aria-hidden="true" /></span>
              <h3 class="app-heading-text font-medium">Request a free license</h3>
              <p class="app-muted-text flex-1 text-sm">For companies under $1M in annual revenue that have raised under $5M.</p>
              <RouterLink to="/free"><Button label="Request a free license" severity="secondary" outlined /></RouterLink>
            </div>
          </div>
        </section>
      </template>

      <NoticeCard v-if="account.designPartner" :icon="Handshake" :tint="TINTS.green" title="Design partner">
        <template v-if="account.designPartner.discountEndsAt">
          Your subscription is half off until {{ formatDate(account.designPartner.discountEndsAt) }}. After that you pay your
          locked price, on the same terms, for as long as your license stays active.
        </template>
        <template v-else>You are approved as a design partner, with your subscription at half off for three years.</template>
        <template v-if="!account.designPartner.discountEndsAt" #actions>
          <RouterLink :to="`/design-partner/checkout?application=${account.designPartner.applicationId}`">
            <Button label="Start your subscription at half off" />
          </RouterLink>
        </template>
      </NoticeCard>
      <Message v-else-if="pendingApplication" severity="info">Your design partner application is being reviewed.</Message>

      <section v-for="entry in account.licenses" :key="entry.license.id" class="flex flex-col gap-4">
        <div class="flex flex-wrap items-center gap-3">
          <h2 class="app-heading-text text-lg font-medium">{{ planName(entry.license.plan) }} plan</h2>
          <LicenseStatusTag :status="entry.license.status" />
        </div>
        <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <StatCard v-for="tile in tiles(entry)" :key="tile.label" :icon="tile.icon" :tint="tile.tint" :label="tile.label"
                    :value="tile.value" :detail="tile.detail" />
        </div>
        <Message v-if="entry.license.status === 'PAST_DUE' && entry.license.lastPaymentAt" severity="warn">
          A payment failed; update your payment details under Billing, receipts and seats to keep receiving keys. Unpaid for
          30 days, the license lapses.
        </Message>
        <Message v-else-if="entry.license.status === 'PAST_DUE'" severity="info">
          Waiting for your payment to clear; we email you the moment it does, and you can add your clusters then.
        </Message>
        <Message v-if="entry.license.status === 'TRIAL'" severity="info">
          Your trial of the {{ planName(entry.license.plan) }} plan ends on {{ formatDate(entry.license.termEnd) }}.
          <RouterLink to="/buy" class="font-medium underline">Buy it</RouterLink>
          before then to keep your license and its price list.
        </Message>
        <Message v-if="entry.confirmationDueFor" severity="info">
          Please confirm your company's figures for your anniversary on {{ formatDate(entry.confirmationDueFor) }}.
          <a href="#" class="font-medium underline" @click.prevent="startConfirming(entry)">Confirm your figures</a>
        </Message>
        <Message v-if="planToBuy(entry)" severity="warn">
          Your figures now set the {{ planName(planToBuy(entry)) }} plan.
          <RouterLink to="/buy" class="font-medium underline">Buy it</RouterLink>
          by {{ formatDate(entry.license.termEnd) }} to keep your license and its price list.
        </Message>
        <DashboardSection :icon="Server" :tint="TINTS.blue" title="Clusters" :count="seated(entry)"
                          description="Each cluster holds a seat and has a key of its own.">
          <template #actions>
            <span v-if="!canAddCluster(entry) && seated(entry) >= entry.license.seats && entry.license.stripeSubscriptionId"
                  class="app-muted-text hidden text-xs md:inline">Add seats under Billing, receipts and seats.</span>
            <Button label="Add a cluster" icon="pi pi-plus" size="small" :disabled="!canAddCluster(entry)" @click="startAddingCluster(entry)" />
          </template>
          <ClusterList :entry="entry" />
        </DashboardSection>
        <ul v-if="entry.confirmations.length" class="app-muted-text space-y-1 text-xs">
          <li v-for="confirmation in entry.confirmations" :key="confirmation.id">
            Confirmed for {{ formatDate(confirmation.anniversary) }}: {{ formatMoney(confirmation.figures.annualRevenue, 'usd') }} revenue<span
              v-if="confirmation.figures.capitalRaised !== null">, {{ formatMoney(confirmation.figures.capitalRaised, 'usd') }} raised</span>
            · {{ planName(confirmation.plan) }}
          </li>
        </ul>
      </section>

      <section v-if="account.licenses.length" class="app-card-surface rounded-xl border p-5 text-sm">
        <h3 class="app-heading-text font-medium">Licensing a cluster</h3>
        <NumberedSteps :steps="LICENSING_STEPS" class="mt-4 grid gap-5 md:grid-cols-3" />
        <p class="app-subtle-text app-surface-divider mt-5 border-t pt-4 text-xs">
          For a cluster that cannot reach licenses.kinotic.ai, copy the cluster id from its License page, add the cluster
          here, then copy or download its key and install it on that page, and again after each payment. Each key expires
          seven days after the date your license is paid through, and a trial's key when the trial ends. Kinotic keeps
          its Enterprise features on for 14 days after a key expires.
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
                description="The cluster id is on the License page of the cluster's Kinotic system console. The cluster takes one of the license's seats for good."
                @close="closeAddCluster">
      <form id="cluster-form" class="flex flex-col gap-5" @submit.prevent="submitCluster">
        <Message v-if="returnUrl && clusterId.trim().toLowerCase() === linkedClusterId" severity="warn">
          Its key goes to the system console at <span class="font-medium">{{ returnUrl.host }}</span>, which installs it. Add the cluster
          only if that console is yours.
        </Message>
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
