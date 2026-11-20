import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";
import { readFileSync } from "node:fs";
import { Agent } from "node:https";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "");
  const tls = env.LOCAL_HTTPS_PFX
    ? {
        pfx: readFileSync(env.LOCAL_HTTPS_PFX),
        passphrase: env.LOCAL_HTTPS_PFX_PASSWORD,
      }
    : undefined;
  const proxy = {
    "/api": {
      target: env.VITE_API_TARGET || "http://127.0.0.1:8080",
      changeOrigin: true,
      ...(env.LOCAL_HTTPS_CA
        ? {
            agent: new Agent({
              ca: readFileSync(env.LOCAL_HTTPS_CA),
              rejectUnauthorized: true,
            }),
          }
        : {}),
    },
  };
  return {
    plugins: [vue()],
    server: { proxy, https: tls },
    preview: { proxy, https: tls },
    build: { chunkSizeWarningLimit: 1500 },
  };
});
