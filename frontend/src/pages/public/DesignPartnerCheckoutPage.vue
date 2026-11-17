<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import { CreditCard, TriangleAlert } from '@lucide/vue'
import { PageHeader, TINTS } from '@kinotic-ai/frontend-common'
import { customerAccountService } from '@/api/services'
import NoticeCard from '@/components/NoticeCard.vue'

/**
 * The page an approved design partner's email links to: it starts the discounted subscription of the application named
 * in `?application=` and sends the browser to Stripe Checkout, opening a fresh Checkout Session every time.
 */
const route = useRoute()
const failure = ref<string | null>(null)

onMounted(async () => {
  try {
    window.location.assign(await customerAccountService.startDesignPartnerCheckout(String(route.query.application ?? '')))
  } catch (err) {
    failure.value = err instanceof Error ? err.message : String(err)
  }
})
</script>

<template>
  <div class="max-w-3xl">
    <PageHeader title="Your design partner subscription" />
    <NoticeCard v-if="failure" :icon="TriangleAlert" :tint="TINTS.orange" title="The subscription could not be started.">
      {{ failure }}
      <template #actions>
        <RouterLink to="/account"><Button label="Back to your licenses" severity="secondary" outlined /></RouterLink>
      </template>
    </NoticeCard>
    <NoticeCard v-else :icon="CreditCard" :tint="TINTS.blue" title="Taking you to payment…">
      Stripe opens in a moment with your subscription at half off.
    </NoticeCard>
  </div>
</template>
