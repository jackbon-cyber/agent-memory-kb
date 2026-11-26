import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

/**
 * 前端测试配置。
 *
 * <p>单独于 `vite.config.ts`：构建配置关心产物落点，测试配置关心运行环境。
 *
 * <p>`environment: 'jsdom'` —— 组件测试需要 DOM。
 */
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts', 'src/**/*.spec.ts'],
    watch: false,
  },
})
