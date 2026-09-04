import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

/** 仅执行已隔离的旧版 Worktree Run 前端回归测试。 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: true,
    include: ['src/legacy/worktree-run/**/*.test.{ts,tsx}'],
  },
})
