<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { PageHeader } from '@kinotic-ai/frontend-common'
import { customerAccountService } from '@/api/services'

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
  <div class="mx-auto max-w-2xl">
    <PageHeader title="Your design partner subscription" />
    <p v-if="failure" class="app-card-surface rounded-xl border p-5 text-sm">
      {{ failure }} <RouterLink to="/account" class="underline">Open your account</RouterLink>.
    </p>
    <p v-else class="app-muted-text text-sm">Taking you to payment…</p>
  </div>
</template>
