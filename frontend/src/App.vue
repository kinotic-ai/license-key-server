<script setup lang="ts">
import Toast from 'primevue/toast'
import ConfirmDialog from 'primevue/confirmdialog'
import { ConnectionOverlay } from '@kinotic-ai/frontend-common'
</script>

<template>
  <main>
    <Toast />
    <ConfirmDialog />
    <ConnectionOverlay />
    <router-view />
  </main>
</template>
