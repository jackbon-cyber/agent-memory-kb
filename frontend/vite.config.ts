import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 知识库控制台的构建配置。
 *
 * <p>产物落在 Spring Boot 的静态资源目录 —— 同源部署，不需要跨域配置分叉两套：
 * `mvn package` 打出的 jar 里就带着界面，`java -jar` 起来后直接访问根路径即可。
 *
 * <p>开发期把 `/api` 代理到本地后端（8088），避免跨域。
 */
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    sourcemap: false,
  },
  server: {
    port: 5178,
    proxy: {
      '/api': {
        target: 'http://localhost:8088',
        changeOrigin: true,
      },
    },
  },
})
