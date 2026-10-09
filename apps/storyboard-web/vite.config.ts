import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  return {
    plugins: [react()],
    server: {
      fs: { allow: ['../..'] },
      proxy: { '/api': { target: env.JAVA_API_PROXY_TARGET || 'http://127.0.0.1:18084', changeOrigin: true } }
    }
  }
})
