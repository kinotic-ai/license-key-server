<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import SelectButton from 'primevue/selectbutton'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'
import { PageHeader, showErrorToast } from '@kinotic-ai/frontend-common'
import type { BillingPeriod, Plan, PlanPrice } from '@/api/model'
import { customerAccountService } from '@/api/services'
import CompanyFiguresFields from '@/components/CompanyFiguresFields.vue'
import { SESSION_STATE } from '@/states/LicenseSessionState'
import { SITE } from '@/states/site'
import TermsAcceptance from '@/components/TermsAcceptance.vue'
import { formatMoney, planName } from '@/util/format'

/**
 * Buying a paid plan for the signed-in customer's account. The pricing page links here with
 * `?plan=<small|medium|large>&billing=<monthly|annual>`. The customer gives its company's figures, which must match the
 * plan; when they set another plan, that one is offered instead, or Free. Then the click-through acceptance, and
 * Stripe Checkout, where the payment details are entered.
 */
const route = useRoute()
const router = useRouter()
const toast = useToast()

const PAID_PLANS: Plan[] = ['SMALL', 'MEDIUM', 'LARGE']
const PERIODS: { label: string, value: BillingPeriod }[] = [{ label: 'Monthly', value: 'MONTHLY' }, { label: 'Annual (save 10%)', value: 'ANNUAL' }]

const prices = ref<PlanPrice[]>([])
const loading = ref(true)
const annualRevenue = ref<number | null>(null)
const capitalRaised = ref<number | null>(null)
// the plan the figures set, when it is not the one chosen
const matching = ref<Plan | null>(null)
const accepted = ref(false)
const submitting = ref(false)

const plan = computed<Plan | null>(() => {
  const named = String(route.query.plan ?? '').toUpperCase() as Plan
  return PAID_PLANS.includes(named) ? named : null
})
const period = computed<BillingPeriod>(() => String(route.query.billing ?? '').toUpperCase() === 'ANNUAL' ? 'ANNUAL' : 'MONTHLY')
const price = computed(() => prices.value.find(p => p.plan === plan.value && p.billingPeriod === period.value) ?? null)
const figuresGiven = computed(() => annualRevenue.value !== null && (annualRevenue.value >= 1_000_000 || capitalRaised.value !== null))

watch([annualRevenue, capitalRaised, plan], () => { matching.value = null })

onMounted(async () => {
  try {
    prices.value = await customerAccountService.findPrices()
  } catch (err) {
    showErrorToast(toast, 'The prices could not be loaded', err)
  } finally {
    loading.value = false
  }
})

function choose(next: { plan?: Plan, billing?: BillingPeriod }) {
  router.replace({ query: { plan: (next.plan ?? plan.value ?? 'SMALL').toLowerCase(), billing: (next.billing ?? period.value).toLowerCase() } })
}

async function submit() {
  if (!plan.value || !figuresGiven.value || !accepted.value || submitting.value) {
    return
  }
  submitting.value = true
  try {
    const figures = { annualRevenue: annualRevenue.value!, capitalRaised: capitalRaised.value }
    const fits = await customerAccountService.findPlanFor(figures)
    if (fits !== plan.value) {
      matching.value = fits
      submitting.value = false
    } else {
      // payment details are entered on Stripe's page, never here
      window.location.assign(await customerAccountService.startCheckout({ plan: plan.value, billingPeriod: period.value, figures }, SITE.termsVersion))
    }
  } catch (err) {
    submitting.value = false
    showErrorToast(toast, 'The purchase could not be started', err)
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader :title="plan ? `Buy the ${planName(plan)} plan` : 'Buy a plan'"
                description="Your plan is set by your company's annual revenue. Stripe bills it; cancel any time, and the key you hold works until it expires." />
    <div v-if="loading" class="app-muted-text text-sm">Loading prices…</div>
    <form v-else class="flex flex-col gap-5" @submit.prevent="submit">
      <section class="app-card-surface flex flex-col gap-4 rounded-xl border p-5">
        <div class="flex flex-wrap gap-2">
          <Button v-for="choice in PAID_PLANS" :key="choice" type="button" :label="planName(choice)" size="small"
                  :severity="choice === plan ? 'primary' : 'secondary'" :outlined="choice !== plan" @click="choose({ plan: choice })" />
        </div>
        <SelectButton :model-value="period" :options="PERIODS" option-label="label" option-value="value" :allow-empty="false"
                      @update:model-value="choose({ billing: $event })" />
        <p v-if="price" class="app-heading-text text-2xl font-semibold tabular-nums">
          {{ formatMoney(price.amount, price.currency) }}
          <span class="app-muted-text text-sm font-normal">{{ period === 'ANNUAL' ? 'a year' : 'a month' }}</span>
        </p>
        <p v-else-if="plan" class="app-muted-text text-sm">This plan is not on sale right now.</p>
        <p v-else class="app-muted-text text-sm">Choose a plan.</p>
      </section>

      <section class="app-card-surface flex flex-col gap-4 rounded-xl border p-5">
        <h2 class="app-heading-text font-medium">Your company</h2>
        <CompanyFiguresFields v-model:annual-revenue="annualRevenue" v-model:capital-raised="capitalRaised" />
        <Message v-if="matching === 'FREE'" severity="info">
          Your company qualifies for a free license.
          <RouterLink to="/free" class="font-medium underline">Request a free license</RouterLink> instead.
        </Message>
        <Message v-else-if="matching" severity="warn">
          Your figures fit the {{ planName(matching) }} plan.
          <a href="#" class="font-medium underline" @click.prevent="choose({ plan: matching! })">Switch to {{ planName(matching) }}</a>
        </Message>
        <p class="app-muted-text text-sm">
          You confirm these figures again once a year, and your plan moves with them at the price list you start on.
          The contact accepting the terms is {{ SESSION_STATE.participant?.name }} &lt;{{ SESSION_STATE.participant?.email }}&gt;.
        </p>
      </section>

      <TermsAcceptance v-model="accepted" />
      <div class="flex justify-end">
        <Button type="submit" label="Continue to payment" icon="pi pi-arrow-right" icon-pos="right"
                :disabled="!accepted || !price || !figuresGiven" :loading="submitting" />
      </div>
    </form>
  </div>
</template>
