<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputNumber from 'primevue/inputnumber'
import SelectButton from 'primevue/selectbutton'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'
import { Building2, Check, Layers, Lock } from '@lucide/vue'
import { DashboardSection, PageHeader, TINTS, showErrorToast } from '@kinotic-ai/frontend-common'
import type { BillingPeriod, CompanyFigures, Plan, PlanPrice } from '@/api/model'
import { customerAccountService } from '@/api/services'
import CompanyFiguresFields from '@/components/CompanyFiguresFields.vue'
import { SESSION_STATE } from '@/states/LicenseSessionState'
import { SITE } from '@/states/site'
import TermsAcceptance from '@/components/TermsAcceptance.vue'
import { billingPeriodName, formatMoney, planName } from '@/util/format'

/**
 * Buying a paid plan for the signed-in customer's account, or starting a three-month trial of it when the account may
 * have one. The customer gives its company's figures first, which set the plan: the three paid plans are shown with the
 * one the figures set marked, and a company the figures make Free is sent to the free license request. The customer
 * then chooses the billing period, which the pricing page may name with `?billing=<monthly|annual>`, and how many
 * clusters the license covers, each billed at the plan's price. Then the click-through acceptance, and either the
 * trial, for one cluster, which the account then adds, or Stripe Checkout, where the payment details are entered.
 */
const route = useRoute()
const router = useRouter()
const toast = useToast()

const PAID_PLANS: Plan[] = ['SMALL', 'MEDIUM', 'LARGE']
const PERIODS: { label: string, value: BillingPeriod }[] = [{ label: 'Monthly', value: 'MONTHLY' }, { label: 'Annual (save 10%)', value: 'ANNUAL' }]
// the annual revenue each paid plan is for, as the terms set it
const PLAN_REVENUE: Record<Plan, string> = {
  FREE: 'Under $1M in annual revenue',
  SMALL: '$1M to under $20M in annual revenue',
  MEDIUM: '$20M to $100M in annual revenue',
  LARGE: 'Over $100M in annual revenue'
}

const prices = ref<PlanPrice[]>([])
const loading = ref(true)
const seats = ref<number | null>(1)
const annualRevenue = ref<number | null>(null)
const capitalRaised = ref<number | null>(null)
// the plan the figures set, Free included; null until the figures are given
const fit = ref<Plan | null>(null)
const accepted = ref(false)
const submitting = ref(false)
const startingTrial = ref(false)
const trialAvailable = ref(false)

const plan = computed<Plan | null>(() => fit.value !== 'FREE' ? fit.value : null)
const period = computed<BillingPeriod>(() => String(route.query.billing ?? '').toUpperCase() === 'ANNUAL' ? 'ANNUAL' : 'MONTHLY')
const price = computed(() => plan.value ? priceOf(plan.value) : null)
const planNote = computed(() => {
  let ret = 'Enter your annual revenue above to see your plan.'
  if (plan.value) {
    ret = `Your figures put you on the ${planName(plan.value)} plan.`
  } else if (fit.value === 'FREE') {
    ret = 'Your company qualifies for a free license, so it has no plan to buy.'
  }
  return ret
})
const periodUnit = computed(() => period.value === 'ANNUAL' ? 'a year' : 'a month')
const seatsGiven = computed(() => seats.value !== null && Number.isInteger(seats.value) && seats.value >= 1)
const figures = computed<CompanyFigures | null>(() => {
  const given = annualRevenue.value !== null && (annualRevenue.value >= 1_000_000 || capitalRaised.value !== null)
  return given ? { annualRevenue: annualRevenue.value!, capitalRaised: capitalRaised.value } : null
})

watch(figures, async given => {
  fit.value = null
  if (given) {
    try {
      const found = await customerAccountService.findPlanFor(given)
      // an answer for figures the customer has since changed is dropped
      if (figures.value === given) {
        fit.value = found
      }
    } catch (err) {
      showErrorToast(toast, 'Your plan could not be worked out', err)
    }
  }
})

onMounted(async () => {
  // the prices stand on their own; an account that cannot be read offers no trial
  const account = customerAccountService.getAccount()
    .then(found => { trialAvailable.value = found.trialAvailable })
    .catch(() => { trialAvailable.value = false })
  try {
    prices.value = await customerAccountService.findPrices()
  } catch (err) {
    showErrorToast(toast, 'The prices could not be loaded', err)
  } finally {
    await account
    loading.value = false
  }
})

/** What one cluster costs on the plan for the billing period chosen; null when the plan is not on sale. */
function priceOf(choice: Plan): PlanPrice | null {
  return prices.value.find(p => p.plan === choice && p.billingPeriod === period.value) ?? null
}

function chooseBilling(billing: BillingPeriod) {
  router.replace({ query: { billing: billing.toLowerCase() } })
}

async function submit() {
  if (!plan.value || !figures.value || !seatsGiven.value || !accepted.value || submitting.value || startingTrial.value) {
    return
  }
  submitting.value = true
  try {
    // payment details are entered on Stripe's page, never here
    const request = { plan: plan.value, billingPeriod: period.value, figures: figures.value, seats: seats.value! }
    window.location.assign(await customerAccountService.startCheckout(request, SITE.termsVersion))
  } catch (err) {
    submitting.value = false
    showErrorToast(toast, 'The purchase could not be started', err)
  }
}

async function startTrial() {
  if (!plan.value || !figures.value || !accepted.value || submitting.value || startingTrial.value) {
    return
  }
  startingTrial.value = true
  try {
    await customerAccountService.startTrial({ plan: plan.value, figures: figures.value }, SITE.termsVersion)
    toast.add({ severity: 'success', summary: 'Your trial has started', detail: 'Add your cluster below to get its key.', life: 8000 })
    await router.push('/account')
  } catch (err) {
    showErrorToast(toast, 'The trial could not be started', err)
  } finally {
    startingTrial.value = false
  }
}
</script>

<template>
  <div class="max-w-5xl">
    <PageHeader :title="trialAvailable ? 'Try or buy a plan' : 'Buy a plan'"
                description="Your plan is set by your company's annual revenue, and you pay it for each cluster. Stripe bills it; cancel any time, and the keys you hold work until they expire." />
    <div v-if="loading" class="app-muted-text text-sm">Loading prices…</div>
    <div v-else class="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
      <form id="buy-form" class="flex min-w-0 flex-col gap-5" @submit.prevent="submit">
        <DashboardSection :icon="Building2" :tint="TINTS.blue" title="Your company" description="Your annual revenue sets your plan, and you confirm it again once a year.">
          <div class="flex flex-col gap-4 p-5">
            <CompanyFiguresFields v-model:annual-revenue="annualRevenue" v-model:capital-raised="capitalRaised" />
            <Message v-if="fit === 'FREE'" severity="info">
              Your company qualifies for a free license.
              <RouterLink to="/free" class="font-medium underline">Request a free license</RouterLink> instead.
            </Message>
            <p class="app-muted-text text-sm">
              The contact accepting the terms is {{ SESSION_STATE.participant?.name }} &lt;{{ SESSION_STATE.participant?.email }}&gt;.
            </p>
          </div>
        </DashboardSection>

        <DashboardSection :icon="Layers" :tint="TINTS.green" title="Plan"
                          :description="planNote">
          <div class="flex flex-col gap-5 p-5">
            <ul class="grid gap-3 sm:grid-cols-3">
              <li v-for="choice in PAID_PLANS" :key="choice" :aria-current="choice === plan ? 'true' : undefined"
                  :class="['flex flex-col rounded-xl border p-4',
                           choice === plan ? 'border-surface-950 ring-1 ring-surface-950 dark:border-surface-0 dark:ring-surface-0' : 'app-card-surface',
                           plan && choice !== plan ? 'opacity-60' : '']">
                <span class="flex items-center justify-between gap-2">
                  <span class="app-heading-text font-medium">{{ planName(choice) }}</span>
                  <span v-if="choice === plan" class="app-heading-text flex items-center gap-1 text-xs font-medium">
                    <Check :size="14" :stroke-width="2" aria-hidden="true" /> Your plan
                  </span>
                </span>
                <span class="app-muted-text mt-1 flex-1 text-xs">{{ PLAN_REVENUE[choice] }}</span>
                <span v-if="priceOf(choice)" class="app-heading-text mt-3 text-lg font-semibold tabular-nums">
                  {{ formatMoney(priceOf(choice)!.amount, priceOf(choice)!.currency) }}
                  <span class="app-muted-text text-xs font-normal">a cluster {{ periodUnit }}</span>
                </span>
                <span v-else class="app-muted-text mt-3 text-xs">Not on sale right now</span>
              </li>
            </ul>
            <div class="flex flex-wrap items-end gap-x-8 gap-y-4">
              <div class="flex flex-col gap-2">
                <span class="app-heading-text text-sm font-medium">Billing</span>
                <SelectButton :model-value="period" :options="PERIODS" option-label="label" option-value="value" :allow-empty="false"
                              @update:model-value="chooseBilling" />
              </div>
              <div class="flex flex-col gap-2">
                <label for="seats" class="app-heading-text text-sm font-medium">Clusters</label>
                <InputNumber input-id="seats" v-model="seats" :min="1" :max-fraction-digits="0" show-buttons class="w-36" />
              </div>
            </div>
            <p class="app-muted-text text-sm">
              Each Kinotic cluster gets a key of its own. Add or remove clusters later from your account. Your plan
              moves with your revenue at the price list you start on.
            </p>
          </div>
        </DashboardSection>

        <TermsAcceptance v-model="accepted" />
      </form>

      <aside class="app-card-surface flex flex-col gap-4 rounded-xl border p-5 text-sm lg:sticky lg:top-0">
        <h2 class="app-heading-text font-medium">Summary</h2>
        <dl class="flex flex-col gap-2">
          <div class="flex justify-between gap-3"><dt class="app-muted-text">Plan</dt><dd class="app-heading-text">{{ plan ? planName(plan) : 'Set by your revenue' }}</dd></div>
          <div class="flex justify-between gap-3"><dt class="app-muted-text">Billing</dt><dd class="app-heading-text">{{ billingPeriodName(period) }}</dd></div>
          <div class="flex justify-between gap-3"><dt class="app-muted-text">Clusters</dt><dd class="app-heading-text tabular-nums">{{ seatsGiven ? seats : '—' }}</dd></div>
          <div v-if="price" class="flex justify-between gap-3">
            <dt class="app-muted-text">Each cluster</dt>
            <dd class="app-heading-text tabular-nums">{{ formatMoney(price.amount, price.currency) }} {{ periodUnit }}</dd>
          </div>
        </dl>
        <div class="app-surface-divider flex items-baseline justify-between gap-3 border-t pt-4">
          <span class="app-heading-text font-medium">Total</span>
          <span v-if="price && seatsGiven" class="app-heading-text text-2xl font-semibold tabular-nums">
            {{ formatMoney(price.amount * seats!, price.currency) }}
            <span class="app-muted-text text-xs font-normal">{{ periodUnit }}</span>
          </span>
          <span v-else class="app-muted-text">{{ plan && !price ? 'This plan is not on sale right now' : '—' }}</span>
        </div>
        <div class="flex gap-3 rounded-lg bg-surface-50 p-3 dark:bg-surface-800/60">
          <span :class="['flex h-8 w-8 shrink-0 items-center justify-center rounded-lg', TINTS.green]">
            <Lock :size="16" :stroke-width="1.75" aria-hidden="true" />
          </span>
          <div>
            <div class="app-heading-text font-medium">Your prices are locked in for life</div>
            <p class="app-muted-text mt-1 text-xs">
              Today's price list stays yours for as long as your license stays active: never canceled, and any failed
              payment settled within 30 days. If your revenue moves you to another plan, you pay that plan's price from
              the same list.
            </p>
          </div>
        </div>
        <Button type="submit" form="buy-form" label="Continue to payment" icon="pi pi-arrow-right" icon-pos="right" fluid
                :disabled="!accepted || !price || !seatsGiven || startingTrial" :loading="submitting" />
        <Button v-if="trialAvailable" type="button" label="Start a 3-month trial" severity="secondary" outlined fluid
                :disabled="!accepted || !plan || submitting" :loading="startingTrial" @click="startTrial" />
        <p class="app-subtle-text text-xs">
          Payment details are entered on Stripe's page.<template v-if="trialAvailable"> A trial covers one cluster and asks for no payment details; buy the plan before it ends to keep your license.</template>
        </p>
      </aside>
    </div>
  </div>
</template>
