/**
 * Embeds the committed Roborazzi screenshot baselines into
 * `docs/app_screen_flow.html` as 380 px WebP thumbnails.
 *
 * Run by `.github/workflows/screen-flow.yml` on push to main, after the
 * screen-flow tests have recorded the `docs-screen-*` baselines:
 *
 *   node tools/update-screen-flow-shots.mjs
 *
 * Rules the script enforces:
 *   - Every node is either mapped to an existing baseline, or is in
 *     DEVICE_ONLY (a device capture with no reproducible baseline).
 *   - `node.image` is only claimed after its thumbnail was produced, so the
 *     HTML can never point at a file it does not display.
 *   - A missing baseline or a failed conversion aborts without writing, rather
 *     than silently leaving the previous (stale) thumbnail in place.
 *   - ImageMagick 7 (`magick`) and ImageMagick 6 (`convert`) both work; the
 *     runner's image package provides `convert`.
 */
import fs from "fs";
import { execSync } from "child_process";

const HTML_PATH = "docs/app_screen_flow.html";

// One node id -> committed baseline. Screen-flow captures are named
// docs-screen-<node-id>.png; the five legacy entries are pre-existing
// baselines kept as-is. Keep the keys in sync with the NODES array.
const mapping = {
  servers: "feature/servers/src/test/screenshots/docs-screen-servers.png",
  add_server: "feature/servers/src/test/screenshots/docs-screen-add_server.png",
  server_status: "feature/servers/src/test/screenshots/docs-screen-server_status.png",
  edit_server: "feature/servers/src/test/screenshots/docs-screen-edit_server.png",
  event_inspector: "feature/servers/src/test/screenshots/docs-screen-event_inspector.png",
  home: "feature/sessions/src/test/screenshots/docs-screen-home.png",
  new_session: "feature/composer/src/test/screenshots/docs-screen-new_session.png",
  session_list: "feature/sessions/src/test/screenshots/docs-screen-session-list.png",
  pending_requests: "feature/sessions/src/test/screenshots/requests-inbox.png",
  session: "feature/sessions/src/test/screenshots/docs-screen-session.png",
  review: "feature/review/src/test/screenshots/docs-screen-review.png",
  review_file: "feature/review/src/test/screenshots/review-file-viewer.png",
  shells: "feature/execution/src/test/screenshots/docs-screen-shells.png",
  terminal: "feature/execution/src/test/screenshots/docs-screen-terminal.png",
  subagents: "feature/execution/src/test/screenshots/docs-screen-subagents.png",
  worktrees: "feature/execution/src/test/screenshots/docs-screen-worktrees.png",
  session_terminals: "feature/execution/src/test/screenshots/docs-screen-session_terminals.png",
  project_settings: "feature/execution/src/test/screenshots/docs-screen-project_settings.png",
  instructions: "feature/admin/src/test/screenshots/instructions.png",
  connect: "feature/integrations/src/test/screenshots/docs-screen-connect.png",
  providers: "feature/integrations/src/test/screenshots/docs-screen-providers.png",
  mcp: "feature/integrations/src/test/screenshots/docs-screen-mcp.png",
  plugins: "feature/integrations/src/test/screenshots/docs-screen-plugins.png",
  web_search: "feature/integrations/src/test/screenshots/docs-screen-web_search.png",
  config: "feature/admin/src/test/screenshots/docs-screen-config.png",
  config_editor: "feature/admin/src/test/screenshots/config-editor-valid.png",
  definition: "feature/admin/src/test/screenshots/definition-agent.png",
  catalog: "feature/admin/src/test/screenshots/docs-screen-catalog.png",
  permissions: "feature/admin/src/test/screenshots/docs-screen-permissions.png",
  maintenance: "feature/admin/src/test/screenshots/docs-screen-maintenance.png",
  usage: "feature/insights/src/test/screenshots/docs-screen-usage.png",
};

// Nodes with no reproducible baseline. `pair_device` renders a real CameraX
// scanner, which Robolectric cannot drive; `changelog` lives in :app, which has
// no Robolectric test wiring. Both keep the device capture embedded when the
// canvas was authored, and their `image` reference is cleared so the HTML never
// points at a file that is not in the repository.
const DEVICE_ONLY = new Set(["changelog", "pair_device"]);

function imageMagickCommand() {
  for (const command of ["magick", "convert"]) {
    try {
      execSync(`${command} -version`, { stdio: "ignore" });
      return command;
    } catch {
      // try the next entry point
    }
  }
  console.error("ImageMagick is required (magick or convert) but was not found.");
  process.exit(1);
}

const magick = imageMagickCommand();

console.log("Reading", HTML_PATH);
const html = fs.readFileSync(HTML_PATH, "utf8");

const nodesMatch = html.match(/const NODES = (\[[\s\S]*?\]);\s*(?:const EDGES|let|function)/);
if (!nodesMatch) {
  console.error("Could not find const NODES = [...] in HTML!");
  process.exit(1);
}

const nodes = JSON.parse(nodesMatch[1]);
console.log(`Parsed ${nodes.length} nodes from HTML.`);

const failures = [];
for (const node of nodes) {
  if (DEVICE_ONLY.has(node.id)) {
    node.image = "";
    console.log(`[KEEP] ${node.id} (${node.title}): device capture, no CI baseline`);
    continue;
  }

  const targetImage = mapping[node.id];
  if (!targetImage) {
    failures.push(`${node.id}: no mapping entry`);
    continue;
  }
  if (!fs.existsSync(targetImage)) {
    failures.push(`${node.id}: ${targetImage} does not exist (record the baseline first)`);
    continue;
  }

  let webpBuf;
  try {
    webpBuf = execSync(`${magick} "${targetImage}" -resize 380x -quality 80 webp:-`);
  } catch (err) {
    failures.push(`${node.id}: converting ${targetImage} failed: ${err.message}`);
    continue;
  }

  // Claim the file only after its thumbnail was produced.
  node.image = targetImage;
  node.thumbData = `data:image/webp;base64,${webpBuf.toString("base64")}`;
  console.log(`[OK] ${node.id} (${node.title}) <- ${targetImage} (${webpBuf.length} bytes webp)`);
}

if (failures.length > 0) {
  console.error("\nRefusing to write the HTML; fix these first:");
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}

const newNodesJson = JSON.stringify(nodes);
const updatedHtml = html.replace(nodesMatch[1], newNodesJson);

fs.writeFileSync(HTML_PATH, updatedHtml, "utf8");
console.log(`\nSuccessfully updated ${HTML_PATH}! File size: ${fs.statSync(HTML_PATH).size} bytes`);
