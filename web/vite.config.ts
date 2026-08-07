import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  base: './', // 相对路径：gh-pages 项目页（/<repo>/ 子路径）也能直接跑
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    host: true, // 局域网手机调试
    port: 5178,
    strictPort: true,
  },
})
