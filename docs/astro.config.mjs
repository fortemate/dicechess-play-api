// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import mermaid from 'astro-mermaid';
import starlightOpenAPI, { openAPISidebarGroups } from 'starlight-openapi';

// Dedicated bot-developer domain, served from a Cloudflare Worker with static assets (#203).
// Contributor docs stay on the ADR-0012 pattern (jc.id.lv/<repo> project pages); this site
// is the external-facing product and gets its own host with a root base.
export default defineConfig({
	site: 'https://bots.fortemate.com',
	integrations: [
		mermaid(),
		starlight({
			title: 'Dice Chess Bot API',
			description:
				'Connect a bot to the Dice Chess play platform — REST, streaming, or a single serverless webhook. Anonymous in minutes, provably-fair dice, an automatic rating ladder.',
			logo: { src: './src/assets/logo.svg', alt: 'Dice Chess Bot API' },
			favicon: '/favicon.svg',
			customCss: ['./src/styles/theme.css'],
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/fortemate/dicechess-play-api' },
			],
			editLink: {
				baseUrl: 'https://github.com/fortemate/dicechess-play-api/edit/main/docs/',
			},
			lastUpdated: true,
			// The REST reference under /api/** is generated from public/openapi.yaml at build time,
			// so the formal contract can never drift from the hand-authored spec (SDKs generate from
			// the same file). The narrative guides below are the human-facing companion.
			plugins: [
				starlightOpenAPI([{ base: 'api', label: 'REST API (OpenAPI)', schema: './public/openapi.yaml' }]),
			],
			sidebar: [
				{ label: 'Overview', link: '/' },
				{
					label: 'Get Started',
					items: [
						{ label: 'A Bot in Five Minutes', link: '/quickstart/' },
						{ label: 'Authentication & Identity', link: '/authentication/' },
					],
				},
				{
					label: 'Playing',
					items: [
						{ label: 'Game Mechanics', link: '/game-mechanics/' },
						{ label: 'Connection Modes', link: '/connection-modes/' },
						{ label: 'Provably-Fair Dice', link: '/provably-fair/' },
						{ label: 'Play Against Your Bot', link: '/play-your-bot/' },
						{ label: 'Rating & Ladder', link: '/rating/' },
					],
				},
				{
					label: 'API Reference',
					items: [
						{ label: 'REST Endpoints', link: '/reference/rest/' },
						{ label: 'Event Streams', link: '/reference/streaming/' },
						{ label: 'Webhooks', link: '/reference/webhooks/' },
						{ label: 'Data Shapes', link: '/reference/data-shapes/' },
					],
				},
				{
					label: 'Specs & Tooling',
					items: [
						{ label: 'Machine-Readable Specs', link: '/specifications/' },
						{ label: 'Licensing for Bots', link: '/licensing/' },
					],
				},
				// Generated from public/openapi.yaml by starlight-openapi.
				...openAPISidebarGroups,
			],
		}),
	],
});
