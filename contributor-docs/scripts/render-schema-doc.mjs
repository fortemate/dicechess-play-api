// Render tbls' schema.json into the Starlight schema-reference page.
//
// Kept separate from generate-schema-docs.sh so the formatting is reviewable and stable:
// the output is committed, and CI fails on any diff, so an unstable renderer would produce
// phantom drift. Everything here must therefore be deterministic — sort, never rely on
// database enumeration order, and never emit a timestamp.

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const [, , schemaPath, outPath] = process.argv;
if (!schemaPath || !outPath) {
	console.error('usage: render-schema-doc.mjs <schema.json> <out.md>');
	process.exit(1);
}

const schema = JSON.parse(readFileSync(schemaPath, 'utf8'));

/** Flyway's own bookkeeping table is not part of the application schema. */
const isApplicationTable = (t) => !t.name.endsWith('flyway_schema_history');

const byName = (a, b) => a.name.localeCompare(b.name);
const bare = (name) => name.replace(/^public\./, '');

const tables = schema.tables.filter(isApplicationTable).sort(byName);
const applicationTableNames = new Set(tables.map((t) => bare(t.name)));

/**
 * Hand-written rationales for tables that carry no foreign key.
 *
 * Every table without a foreign key MUST have an entry here. If a migration adds an
 * FK-free table without a rationale, rendering will fail loudly so the prose is never
 * left quietly stale.
 */
const FK_FREE_RATIONALES = {
	admin_actions:
		'is an audit log: it must keep naming an admin who has since deleted their account, on a bot whose row may be long gone',
	bots: 'is the root of the bot identity graph; tokens, incarnation IDs, and webhook revisions are scoped to the bot row directly',
	client_reports:
		'holds browser-submitted reports for games that never had a `games` row on this server (kept separate from authoritative game data by design)',
	game_archive: 'must outlive the snapshots they describe',
	game_results: 'must outlive the snapshots they describe',
	games: 'holds active game state and is unlinked to allow purging ended games without cascading deletes across result archives',
	nickname_history:
		'must outlive the account a rename describes just as readily as the one it never touched — a foreign key to `users` would cascade away the audit trail and the hold on exactly the accounts whose history or vacated name matters most, an account that renamed and then vanished',
	released_nicknames:
		'must outlive the account a rename describes just as readily as the one it never touched — a foreign key to `users` would cascade away the audit trail and the hold on exactly the accounts whose history or vacated name matters most, an account that renamed and then vanished',
	showcase_claims:
		'holds short-lived rate-limiting claims that outlive or precede individual games',
	showcase_table:
		'is a singleton table tracking showcase table state without external entity references',
	users: 'is the root of the account graph the other user tables reference',
	webhook_admin_authority_generations:
		'tracks global authority heartbeat logs independent of individual bot rows',
	webhook_verification_budgets:
		'tracks rate-limiting verification budgets keyed by actor or IP, independent of persistent entity life cycles',
};

function hasForeignKey(table) {
	const tableName = bare(table.name);
	const inRelations = (schema.relations ?? []).some(
		(r) => bare(r.table) === tableName && applicationTableNames.has(bare(r.parent_table)),
	);
	const inConstraints = (table.constraints ?? []).some((c) => c.type === 'FOREIGN KEY');
	return inRelations || inConstraints;
}

function renderFkFreeNarrative() {
	const fkFreeTables = tables.filter((t) => !hasForeignKey(t)).sort(byName);

	const missing = fkFreeTables.filter((t) => !FK_FREE_RATIONALES[bare(t.name)]);
	if (missing.length > 0) {
		console.error(
			`Error: Missing rationale in FK_FREE_RATIONALES for FK-free table(s): ${missing.map((t) => bare(t.name)).join(', ')}`,
		);
		process.exit(1);
	}

	// Group consecutive tables that share identical rationales.
	const groups = [];
	for (const table of fkFreeTables) {
		const name = bare(table.name);
		const rationale = FK_FREE_RATIONALES[name];
		const last = groups[groups.length - 1];
		if (last && last.rationale === rationale) {
			last.names.push(name);
		} else {
			groups.push({ names: [name], rationale });
		}
	}

	const numberWords = [
		'Zero',
		'One',
		'Two',
		'Three',
		'Four',
		'Five',
		'Six',
		'Seven',
		'Eight',
		'Nine',
		'Ten',
		'Eleven',
		'Twelve',
		'Thirteen',
		'Fourteen',
		'Fifteen',
		'Sixteen',
		'Seventeen',
		'Eighteen',
		'Nineteen',
		'Twenty',
	];
	const countStr = numberWords[fkFreeTables.length] ?? String(fkFreeTables.length);

	const formattedGroups = groups.map((g) => {
		const tableList =
			g.names.length === 1
				? `\`${g.names[0]}\``
				: g.names.length === 2
					? `\`${g.names[0]}\` and \`${g.names[1]}\``
					: g.names.map((n) => `\`${n}\``).join(', ');
		return `${tableList} ${g.rationale}`;
	});

	const joinedList =
		formattedGroups.length > 1
			? formattedGroups.slice(0, -1).join(', ') + ' — and ' + formattedGroups[formattedGroups.length - 1]
			: formattedGroups[0];

	return `Only foreign keys appear as edges. ${countStr} tables carry no foreign key on purpose — ${joinedList}.`;
}

/**
 * Cardinality of a foreign key, derived rather than assumed: if the child's FK columns are
 * themselves covered by a primary key or unique constraint, at most one child row can exist
 * per parent (1:1); otherwise the parent may have many (1:many). Both of today's foreign keys
 * happen to be the 1:1 shape, so hardcoding would look right until the first plain
 * one-to-many migration silently rendered wrong.
 */
function cardinality(childTable, fkColumns) {
	const covered = (childTable?.constraints ?? []).some(
		(c) =>
			(c.type === 'PRIMARY KEY' || c.type === 'UNIQUE') &&
			fkColumns.length > 0 &&
			fkColumns.every((col) => (c.columns ?? []).includes(col)) &&
			(c.columns ?? []).length === fkColumns.length,
	);
	return covered ? '||--o|' : '||--o{';
}

/** Relations tbls inferred from foreign keys, as mermaid ER edges. */
function erDiagram() {
	const names = new Set(tables.map((t) => bare(t.name)));
	const byTableName = new Map(tables.map((t) => [bare(t.name), t]));

	const edges = (schema.relations ?? [])
		.map((r) => ({ child: bare(r.table), parent: bare(r.parent_table), columns: r.columns ?? [] }))
		.filter((e) => names.has(e.child) && names.has(e.parent))
		.map((e) => `    ${e.parent} ${cardinality(byTableName.get(e.child), e.columns)} ${e.child} : ""`)
		.sort();

	// Entities are declared as bare names: mermaid's ER grammar wants at least one attribute
	// inside a `{ }` block, and we deliberately keep the diagram to relationships only — the
	// columns are right below in the per-table sections.
	return ['```mermaid', 'erDiagram', ...[...names].sort().map((n) => `    ${n}`), ...new Set(edges), '```'].join(
		'\n',
	);
}

/** A column's constraint markers, e.g. "PK", "FK → bots(team, name)". */
function markers(table, column) {
	const out = [];
	for (const c of table.constraints ?? []) {
		if (!(c.columns ?? []).includes(column.name)) continue;
		if (c.type === 'PRIMARY KEY') out.push('PK');
		else if (c.type === 'UNIQUE') out.push('unique');
		else if (c.type === 'FOREIGN KEY' && c.referenced_table)
			out.push(`FK → ${bare(c.referenced_table)}(${(c.referenced_columns ?? []).join(', ')})`);
	}
	return out.join(', ');
}

const escapePipes = (s) => (s ?? '').replaceAll('|', '\\|');

function tableSection(table) {
	const lines = [`### \`${bare(table.name)}\``, ''];

	if (table.comment) lines.push(table.comment, '');

	lines.push(
		'| Column | Type | Null | Default | Key |',
		'| --- | --- | --- | --- | --- |',
		...table.columns.map((c) =>
			[
				'',
				`\`${c.name}\``,
				`\`${escapePipes(c.type)}\``,
				c.nullable ? 'yes' : 'no',
				c.default === null || c.default === undefined ? '—' : `\`${escapePipes(String(c.default))}\``,
				markers(table, c) || '—',
				'',
			].join(' | ').trim(),
		),
		'',
	);

	const checks = (table.constraints ?? [])
		.filter((c) => c.type === 'CHECK')
		.sort(byName);
	if (checks.length) {
		lines.push('Check constraints:', '');
		lines.push(...checks.map((c) => `- \`${escapePipes(c.def)}\``), '');
	}

	const indexes = (table.indexes ?? []).sort(byName);
	if (indexes.length) {
		lines.push('Indexes:', '');
		lines.push(...indexes.map((i) => `- \`${i.name}\` — \`${escapePipes(i.def)}\``), '');
	}

	return lines.join('\n');
}

const page = `---
title: Schema Reference
description: Every table, column, constraint, and index in the play-api database, generated from the Flyway migrations.
---

<!--
  GENERATED FILE — do not edit by hand.
  Produced by scripts/generate-schema-docs.sh from the Flyway migrations in
  src/main/resources/db/migration/. Run \`mise run contrib-docs:schema\` after adding a
  migration; CI regenerates this file and fails if the committed copy differs.

  This is an HTML comment, not an MDX one: the page is .md, where {/* … */} renders as
  literal text on the page.
-->


:::note[Generated from the migrations]
This page is derived by applying every Flyway migration to a real Postgres and introspecting
the result, so it cannot drift from the code. It is the *what*; the
[Database Schema](/dicechess-play-api/database/) page is the *why* — read that one first.

Regenerate with \`mise run contrib-docs:schema\` after adding a migration.
:::

## Entity relationships

${erDiagram()}

${renderFkFreeNarrative()}

## Tables

${tables.map(tableSection).join('\n')}`;

mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, page.replace(/\n{3,}/g, '\n\n').trimEnd() + '\n', 'utf8');
console.log(`wrote ${outPath} (${tables.length} tables)`);
