import { defineConfig } from "vite";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { writeFileSync } from "node:fs";

const root = dirname(fileURLToPath(import.meta.url));

const WORKBENCH_EMBED = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Live Gherkin Blocks</title>
  <link rel="stylesheet" href="./snap-editor.css">
  <style>
    html, body, #editor {
      margin: 0;
      height: 100%;
      width: 100%;
      background: #f4f6f8;
    }
    #editor { min-height: 240px; }
    .snap-editor-host, .snap-canvas { background: #f4f6f8; }
  </style>
</head>
<body>
  <div id="editor">Loading block editor…</div>
  <script src="./snap-editor.js"></script>
</body>
</html>
`;

export default defineConfig({
  base: "./",
  root: ".",
  publicDir: "public",
  server: {
    host: true,
    port: 5173,
  },
  build: {
    lib: {
      entry: resolve(root, "src/embed.ts"),
      name: "SnapEditorEmbed",
      formats: ["iife"],
      fileName: () => "snap-editor.js",
    },
    cssCodeSplit: false,
    emptyOutDir: true,
    rollupOptions: {
      output: {
        assetFileNames: "snap-editor.[ext]",
      },
    },
  },
  plugins: [
    {
      name: "workbench-classic-embed",
      closeBundle() {
        writeFileSync(resolve(root, "dist/embed.html"), WORKBENCH_EMBED);
      },
    },
  ],
});
