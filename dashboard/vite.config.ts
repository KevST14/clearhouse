import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5180,
    strictPort: true,
    // The traffic generator's API (see generator/).
    proxy: { "/api": "http://localhost:8090" },
  },
});
