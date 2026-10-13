import fs from "fs";
import { execSync } from "child_process";

const HTML_PATH = "docs/app_screen_flow.html";

const mapping = {
  servers: "tmp/ui-shots/b-servers-after-connect.png",
  add_server: "tmp/ui-shots/b6-add-server-sheet.png",
  server_status: "tmp/ui-shots/veritas-status.png",
  edit_server: "tmp/ui-shots/veritas-edit-password.png",
  event_inspector: "tmp/ui-shots/event-inspector.png",
  changelog: "tmp/ui-shots/m4-changelog.png.png",
  pair_device: "tmp/ui-shots/b9-pair.png.png",
  home: "tmp/ui-shots/veritas-home.png",
  new_session: "tmp/ui-shots/new-session.png",
  session_list: "tmp/ui-shots/veritas-all-sessions.png",
  pending_requests: "feature/sessions/src/test/screenshots/requests-inbox.png",
  session: "tmp/ui-shots/t1-session.png.png",
  review: "tmp/ui-shots/review-diff-uncommitted.png",
  review_file: "feature/review/src/test/screenshots/review-file-viewer.png",
  shells: "tmp/ui-shots/shells-running.png",
  terminal: "tmp/ui-shots/terminal-chrome.png",
  subagents: "tmp/ui-shots/subagents-tree.png",
  worktrees: "tmp/ui-shots/worktrees-list.png",
  session_terminals: "tmp/ui-shots/session-terminals-off.png",
  project_settings: "tmp/ui-shots/project-settings.png",
  instructions: "feature/admin/src/test/screenshots/instructions.png",
  connect: "tmp/ui-shots/veritas-accounts.png",
  providers: "tmp/ui-shots/veritas-providers.png",
  mcp: "tmp/ui-shots/veritas-mcp.png",
  plugins: "tmp/ui-shots/veritas-plugins.png",
  web_search: "tmp/ui-shots/veritas-web-search.png",
  config: "tmp/ui-shots/veritas-configuration.png",
  config_editor: "feature/admin/src/test/screenshots/config-editor-valid.png",
  definition: "feature/admin/src/test/screenshots/definition-agent.png",
  catalog: "tmp/ui-shots/veritas-agents.png",
  permissions: "tmp/ui-shots/veritas-permissions.png",
  maintenance: "tmp/ui-shots/veritas-maintenance.png",
  usage: "tmp/ui-shots/k2-usage-screen.png.png"
};

console.log("Reading", HTML_PATH);
const html = fs.readFileSync(HTML_PATH, "utf8");

const nodesMatch = html.match(/const NODES = (\[[\s\S]*?\]);\s*(?:const EDGES|let|function)/);
if (!nodesMatch) {
  console.error("Could not find const NODES = [...] in HTML!");
  process.exit(1);
}

const nodes = JSON.parse(nodesMatch[1]);
console.log(`Parsed ${nodes.length} nodes from HTML.`);

for (let i = 0; i < nodes.length; i++) {
  const node = nodes[i];
  const targetImage = mapping[node.id] || node.image;
  if (!fs.existsSync(targetImage)) {
    console.warn(`[WARN] Target image does not exist for node ${node.id}: ${targetImage}`);
    continue;
  }

  node.image = targetImage;

  // Convert to 380px wide WebP
  try {
    const webpBuf = execSync(`magick "${targetImage}" -resize 380x -quality 80 webp:-`);
    const b64 = webpBuf.toString("base64");
    node.thumbData = `data:image/webp;base64,${b64}`;
    console.log(`[OK] Updated node ${node.id} (${node.title}) <- ${targetImage} (${webpBuf.length} bytes webp)`);
  } catch (err) {
    console.error(`Error converting ${targetImage}:`, err.message);
  }
}

const newNodesJson = JSON.stringify(nodes);
const updatedHtml = html.replace(nodesMatch[1], newNodesJson);

fs.writeFileSync(HTML_PATH, updatedHtml, "utf8");
console.log(`\nSuccessfully updated ${HTML_PATH}! File size: ${fs.statSync(HTML_PATH).size} bytes`);
