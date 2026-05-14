import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  // Replace `global` references inside npm packages at bundle time.
  // Some packages (e.g. recharts transitive deps) reference the Node.js
  // `global` variable which does not exist in browsers.
  define: {
    global: "globalThis",
  },
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: process.env.VITE_API_TARGET ?? "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: "dist",
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks: {
          react: ["react", "react-dom", "react-router-dom"],
          query: ["@tanstack/react-query"],
          charts: ["recharts"],
        },
      },
    },
  },
});
