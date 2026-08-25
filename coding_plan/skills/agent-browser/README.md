# Agent Browser Skill for Claude Code

A Claude Code skill wrapper for [agent-browser](https://github.com/vercel-labs/agent-browser), enabling the agent to browse the web, interact with pages, and extract data using a headless browser.

## 📦 Installation

```bash
# Clone this repo and run install
curl -fsSL https://raw.githubusercontent.com/BowTiedSwan/agent-browser-skill/main/install.sh | bash
```

## 🚀 Usage

Once installed, simply ask Claude:

> "Go to google.com and search for 'Claude Code'"
> "Take a screenshot of news.ycombinator.com"
> "Check the pricing on vercel.com"

## 🛠️ How it Works

This skill wraps the `agent-browser` CLI tool. It allows Claude to:
1.  **Open** URLs in a headless Chromium instance.
2.  **Snapshot** the page to get an accessibility tree with unique references (`@e1`, `@e2`).
3.  **Interact** with elements using these references (click, fill, hover).

## 📜 Credits

- **Original Tool**: [agent-browser](https://github.com/vercel-labs/agent-browser) by [Vercel](https://vercel.com) and [Chris Tate](https://x.com/ctatedev).
- **Skill Wrapper**: [Bowtiedswan](https://x.com/Bowtiedswan).
