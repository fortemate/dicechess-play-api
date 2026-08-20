// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import mermaid from 'astro-mermaid';

// Contributor documentation: the GitHub Pages project site, on the ADR-0012 host/base pattern
// (jc.id.lv/<repo>) that the engine and analytics docs also use. The *product* docs for
// third-party bot developers moved to their own domain, https://bots.jc.id.lv (#203) — this
// slot used to serve them, hence the redirects below.
//
// Everything here is public: GitHub Pages has no private mode. Nothing may be documented that
// is not already derivable from this public repository — no host topology, no env values.
export default defineConfig({
	site: 'https://jc.id.lv',
	base: '/dicechess-play-api',
	// The bot-docs slugs this site inherited. Astro emits a meta-refresh page for each in a
	// static build, so search results and stale bookmarks land on the moved page rather than on
	// unrelated contributor content. The root is deliberately absent: it is this site's own
	// index, which carries a prominent pointer to the Bot API instead.
	redirects: Object.fromEntries(
		[
			'quickstart',
			'authentication',
			'game-mechanics',
			'connection-modes',
			'provably-fair',
			'play-your-bot',
			'rating',
			'specifications',
			'licensing',
			'reference/rest',
			'reference/streaming',
			'reference/webhooks',
			'reference/data-shapes',
			'api',
		].map((slug) => [`/${slug}`, `https://bots.jc.id.lv/${slug}/`]),
	),
	integrations: [
		mermaid(),
		starlight({
			title: 'play-api Contributor Docs',
			description:
				'How the Dice Chess play-api server is built: architecture, database schema, concurrency doctrine, and testing conventions. For contributors — bot developers want bots.jc.id.lv instead.',
			favicon: '/favicon.svg',
			customCss: ['./src/styles/theme.css'],
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/fortemate/dicechess-play-api' },
			],
			editLink: {
				baseUrl: 'https://github.com/fortemate/dicechess-play-api/edit/main/contributor-docs/',
			},
			lastUpdated: true,
			sidebar: [
				{ label: 'Overview', link: '/' },
				{
					label: 'The Server',
					items: [
						{ label: 'Architecture', link: '/architecture/' },
						{ label: 'Concurrency Doctrine', link: '/concurrency/' },
						{ label: 'Configuration', link: '/configuration/' },
					],
				},
				{
					label: 'Data',
					items: [
						{ label: 'Database Schema', link: '/database/' },
						// Generated from the migrations by scripts/generate-schema-docs.sh.
						{ label: 'Schema Reference', link: '/reference/schema/' },
					],
				},
				{
					label: 'Working on It',
					items: [
						{ label: 'Development Setup', link: '/development/' },
						{ label: 'Testing', link: '/testing/' },
					],
				},
			],
		}),
	],
});
