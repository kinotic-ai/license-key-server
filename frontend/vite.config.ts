import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

// https://vite.dev/config/
export default defineConfig({
    plugins: [vue()],
    resolve: {
        alias: {
            '@': path.resolve(import.meta.dirname, './src')
        }
    },
    server: {
        // The public site on 5173 and, with dev:staff, the staff dashboard on 5174: the server tells each which it is
        // from the Host header, which the proxy passes through unchanged.
        port: 5173,
        host: true,
        open: false,
        headers: {
            'Cache-Control': 'no-store'
        },
        proxy: {
            '/api': { target: 'http://localhost:58503', changeOrigin: false, ws: true }
        }
    },
    build: {
        sourcemap: true
    },
    define: {
        __VUE_OPTIONS_API__: true,
        __VUE_PROD_DEVTOOLS__: false
    }
})
