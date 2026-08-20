# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.x.x   | :white_check_mark: |

## Reporting a Vulnerability

Please do not report security vulnerabilities through public GitHub issues or public pull requests.

This service is the authoritative game server: it validates moves, generates the dice, and
holds credentials (the analytics ingest token, and — once the Bot API ships — per-team bot
tokens). If you discover a vulnerability (e.g. token leakage, dice predictability or bias,
move-legality bypass, authorization bypass between players/seats, WebSocket origin/CSRF
issues, or denial of service), please report it privately.

Use the **GitHub Private Vulnerability Reporting** feature:
1. On GitHub, navigate to the main page of the repository.
2. Under the repository name, click **Security**.
3. In the left sidebar, click **Advisories**.
4. Click **Report a vulnerability**.

Alternatively, contact the maintainer directly at **jegors.cemisovs@gmail.com**.

We will acknowledge your report within 48 hours and coordinate a fix and disclosure timeline with you.
