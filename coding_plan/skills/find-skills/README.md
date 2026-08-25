# Find Skills — Skill

[![License: MIT](https://img.shields.io/badge/License-MIT-blue)](LICENSE)
[![Universal AI Agent Skill](https://img.shields.io/badge/Universal-AI_Agent_skill-orange)](#)
[![Category: General](https://img.shields.io/badge/Category-General-purple)](#)

| Field | Value |
| --- | --- |
| **Name** | `find-skills-skill` |
| **Description** | Helps users discover and install agent skills when they ask questions like "how do I do X", "find a skill for X", "is there a skill that can...", or express interest in extending capabilities. This skill should be used when the user is looking for functionality that might exist as an installable skill. |
| **Category** | general |
| **Version** | `1.0.0` |
| **Author** | Community |
| **License** | MIT |
| **Platforms** | Linux, macOS, Windows |

---

## What it is

Helps users discover and install agent skills when they ask questions like "how do I do X", "find a skill for X", "is there a skill that can...", or express interest in extending capabilities. This skill should be used when the user is looking for functionality that might exist as an installable skill.

This is a universal AI agent skill — platform-agnostic and usable inside any modern agent runtime that supports the skill file format (`SKILL.md`). It provides focused capabilities with proper configuration, reproducible outputs, and safe defaults.

**Important:** Use only on targets you own or have explicit permission to work with. Do not use for unauthorized system access, data scraping, or activities that violate applicable laws or terms of service.

---

## 🇬🇧 English

### Requirements

- A compatible AI agent runtime that supports skills (Hermes, OpenClaw, Claude Code, Codex, etc.)
- Python 3.10+ for skills that delegate to scripts
- Network access if the skill requires remote data fetching, API calls, or web scraping
- Write permissions to your project/output folder

### Installation

```bash
# Option A: Copy skill directory into your agent's skills folder
cp -r find-skills ~/.hermes/skills/find-skills/

# Option B: Install directly from this repository's raw SKILL.md URL
<agent-cli> skills install https://raw.githubusercontent.com/iizcm/find-skills-skill/main/SKILL.md

# Option C: Clone this entire repo into your skills directory
git clone https://github.com/iizcm/find-skills-skill.git ~/.hermes/skills/find-skills/
```

### Step-by-step usage

**Step 1** — Create a project folder:

```bash
mkdir -p ~/projects/example
cd ~/projects/example
```

**Step 2** — Load the skill into your agent:

```text
skill_view(name="find-skills")
```

The exact command varies by runtime. Some agents auto-load skills by matching task descriptions; others require explicit loading commands.

**Step 3** — Invoke a task with natural language:

```text
Use the find skills skill to <describe your specific task here>
```

Wrap your request as a clear, single-sentence instruction so the agent routes it to the right skill handler.

**Step 4** — Inspect outputs:

```bash
ls -la out/
cat out/*.log   # check logs
```

Most skills write outputs under an `out/` folder by default. Check logs, files, images, reports, and other artifacts there.

**Step 5** — Customize for your workflow:

- Edit the skill's `SKILL.md` frontmatter to change its name, tags, or trigger keywords
- Modify default parameters inside the skill body for permanent behavior changes
- Combine with other skills for compound automation (e.g., HTML generation + screenshot capture)

### Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Skill not found / not loaded | Not installed in active skills directory | Re-run install command above |
| Network timeout or API error | Internet blocked, proxy misconfigured, or rate-limited | Check connectivity; configure proxy if needed |
| Permission denied on output folder | Folder not writable by current user | `chmod` or run from user-owned directory |
| No output produced | Input format doesn't match expected format | Validate input against skill's documented format |
| Script execution error | Missing dependency or wrong Python version | `pip install <deps>` listed in skill references |

### Security & safety notes

- Do **not** embed private keys, seed phrases, API tokens, wallet addresses, or personal data in outputs or chat logs
- Placeholders in examples use `<YOUR_*>` notation — replace them before production use
- Always validate/simulate outputs before acting on them, especially for write/destructive actions
- This skill never stores credentials in plain text; use your runtime's secure credential store

---

## 🇮🇩 Bahasa Indonesia

### Persyaratan

- Runtime AI agent yang mendukung format skill (Hermes, OpenClaw, Claude Code, Codex, dll.)
- Python 3.10+ untuk skill yang menggunakan script eksternal
- Koneksi internet jika skill perlu mengambil data dari luar (API, web scraping, download)
- Izin tulis ke folder project/output Anda

### Instalasi

```bash
# Opsi A: Salin folder skill ke direktori skills agent
cp -r find-skills ~/.hermes/skills/find-skills/

# Opsi B: Pasang langsung dari URL SKILL.md repo ini
<agent-cli> skills install https://raw.githubusercontent.com/iizcm/find-skills-skill/main/SKILL.md

# Opsi C: Clone seluruh repo ini ke direktori skills
git clone https://github.com/iizcm/find-skills-skill.git ~/.hermes/skills/find-skills/
```

### Langkah penggunaan

**Langkah 1** — Buat folder proyek:

```bash
mkdir -p ~/projects/example
cd ~/projects/example
```

**Langkah 2** — Muat skill ke dalam agent:

```text
skill_view(name="find-skills")
```

Perintah tepat bergantung runtime. Beberapa agent otomatis-muat skill berdasarkan deskripsi tugas; lainnya perlu perintah eksplisit.

**Langkah 3** — Panggil tugas dengan bahasa alami:

```text
Gunakan skill find skills untuk <deskripsikan tugas spesifik Anda di sini>
```

Bungkus permintaan sebagai satu kalimat yang jelas agar agent merutekannya ke handler skill yang benar.

**Langkah 4** — Periksa hasil:

```bash
ls -la out/
cat out/*.log   # cek log
```

Kebanyakan skill menulis output di bawah folder `out/`. Cek log, file, gambar, laporan, dan artefak lain di sana.

**Langkah 5** — Sesuaikan untuk alur kerja Anda:

- Edit frontmatter `SKILL.md` untuk ganti nama, tag, atau kata kunci pemicu
- Ubah parameter default di dalam body skill untuk perubahan perilaku permanen
- Gabungkan dengan skill lain untuk otomasi compound (misal: generate HTML + screenshot)

### Troubleshooting (ID)

| Gejala | Kemungkinan penyebab | Solusi |
| --- | --- | --- |
| Skill tidak ditemukan | Belum terpasang di direktori skills aktif | Jalankan ulang perintah instalasi |
| Timeout / error API | Terblokir, proxy salah, atau rate limit | Cek koneksi; atur proxy jika perlu |
| Permission ditolak | Folder output tidak bisa ditulis | `chmod` atau jalankan dari folder milik user |
| Tidak ada output | Format input tidak sesuai | Sesuaikan input dengan format yang didokumentasikan |
| Error eksekusi script | Dependency kurang atau versi Python salah | `pip install <deps>` yang tercantum di referensi skill |

### Keamanan

- **Jangan** masukkan private key, mnemonic, token API, alamat wallet, atau data pribadi ke output atau chat log
- Contoh di dokumentasi menggunakan format `<YOUR_*>` — ganti sebelum dipakai di produksi
- Selalu validasi/simulasikan output sebelum digunakan, terutama untuk aksi destruktif/tulis
- Skill ini tidak menyimpan kredensial dalam plain text; gunakan penyimpanan kredensial aman runtime Anda

---

## Repository structure

```
find-skills-skill/
├── README.md              ← This file (bilingual documentation)
├── SKILL.md               ← Skill definition (frontmatter + usage)
├── references/            ← Reference docs, guides, checklists
│   ├── *.md
│   └── ...
├── scripts/               ← Executable scripts (Python, JS, Bash)
│   ├── *.py
│   ├── *.js
│   └── *.sh
├── templates/             ← Template files (if applicable)
│   └── ...
└── assets/                ← Static assets (if applicable)
    └── ...
```

All files from the original skill directory are included in this repository.

---

## Notes

- Update this README when the skill's interface, options, or behavior changes
- If you fork this repo, keep the MIT license and attribution intact
- Report bugs or suggest improvements via GitHub Issues on the original repo
- For questions about usage, refer to the `SKILL.md` file or the `references/` directory

---

## License

MIT — free to use, modify, and distribute.
