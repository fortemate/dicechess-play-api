import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";

const docsRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const contractRoot = path.join(docsRoot, "public/contracts/stake-doubling/v1");
const schema = JSON.parse(await readFile(path.join(contractRoot, "schema.json"), "utf8"));
const manifest = JSON.parse(await readFile(path.join(contractRoot, "fixtures.json"), "utf8"));

/** Throw a readable contract-validation failure when an invariant does not hold. */
function assert(condition, message) {
  if (!condition) throw new Error(message);
}

assert(manifest.contract === schema.$id, `Fixture manifest names ${manifest.contract}; expected ${schema.$id}`);

const ajv = new Ajv2020({ allErrors: true, strict: true });
ajv.addSchema(schema);

/** Resolve a manifest reference to a named definition; a bare "#" would validate vacuously. */
function validatorFor(schemaRef) {
  const name = /^#\/\$defs\/([A-Za-z]+)$/.exec(schemaRef)?.[1];
  assert(name && schema.$defs[name], `Unknown schema reference: ${schemaRef}`);
  return ajv.getSchema(`${schema.$id}${schemaRef}`);
}

const fixtures = new Map();
for (const entry of manifest.fixtures) {
  assert(!fixtures.has(entry.path), `Duplicate fixture path: ${entry.path}`);
  assert(/^examples\/[a-z0-9-]+\.json$/.test(entry.path), `Fixture path must live under examples/: ${entry.path}`);
  const validate = validatorFor(entry.schemaRef);
  const value = JSON.parse(await readFile(path.join(contractRoot, entry.path), "utf8"));
  assert(validate(value), `${entry.path} failed ${entry.schemaRef}: ${ajv.errorsText(validate.errors)}`);
  fixtures.set(entry.path, { value, schemaRef: entry.schemaRef });
}

function fixture(fixturePath) {
  const entry = fixtures.get(fixturePath);
  assert(entry, `Missing fixture ${fixturePath}`);
  return entry.value;
}

/** Key-order-independent serialisation for deep equality. */
const canonical = (value) =>
  JSON.stringify(value, (_key, node) =>
    node && typeof node === "object" && !Array.isArray(node)
      ? Object.fromEntries(Object.keys(node).sort().map((key) => [key, node[key]]))
      : node,
  );

/** Validate semantic relationships that JSON Schema cannot express as arithmetic or cross-field equality. */
function validateDecisionState(state, source) {
  const doubling = state.doubling;
  const decision = doubling.decision;

  assert(
    doubling.currentStake === doubling.initialStake * doubling.cubeValue,
    `${source}: currentStake must equal initialStake * cubeValue`,
  );
  assert(doubling.cubeValue <= doubling.maximumMultiplier, `${source}: cubeValue exceeds maximumMultiplier`);
  assert(decision.proposedStake === doubling.currentStake * 2, `${source}: proposedStake must be twice currentStake`);
  assert(state.activeSeat === decision.seat, `${source}: activeSeat must be the decision actor`);

  const dfenColour = state.dfen.split(" ")[1];
  assert(dfenColour === "w" || dfenColour === "b", `${source}: DFEN must carry an active colour`);
  const dfenSeat = dfenColour === "w" ? "White" : "Black";
  assert(dfenSeat === doubling.turnSeat, `${source}: DFEN active colour must match turnSeat`);

  if (decision.kind === "offer") {
    assert(decision.seat === doubling.turnSeat, `${source}: an offer decision belongs to the turn seat`);
    assert(doubling.cubeValue < doubling.maximumMultiplier, `${source}: an offer requires the cube below the cap`);
    assert(
      doubling.cubeOwner === null || doubling.cubeOwner === decision.seat,
      `${source}: only the cube owner may offer`,
    );
  } else {
    assert(decision.offeredBy === doubling.turnSeat, `${source}: turnSeat must preserve the offerer`);
    assert(decision.seat !== decision.offeredBy, `${source}: a seat cannot respond to its own offer`);
  }
}

for (const [fixturePath, { value, schemaRef }] of fixtures) {
  if (schemaRef === "#/$defs/StakedDecisionSnapshot") validateDecisionState(value, fixturePath);
  if (schemaRef.endsWith("Webhook")) {
    validateDecisionState(value.state, fixturePath);
    assert(value.seat === value.state.activeSeat, `${fixturePath}: webhook seat must match activeSeat`);
  }
}

// One canonical episode: opportunity -> offer -> accept | decline, told by states, events, commands, and webhooks.
const stateOpportunity = fixture("examples/state-opportunity.json");
const stateResponse = fixture("examples/state-response.json");
const opportunity = fixture("examples/event-opportunity.json").DoubleOpportunity;
const offered = fixture("examples/event-offered.json").DoubleOffered;
const accepted = fixture("examples/event-accepted.json").DoubleAccepted;
const declined = fixture("examples/event-declined.json").DoubleDeclined;
const decisionId = stateOpportunity.doubling.decision.id;

for (const [name, id] of [
  ["state-response", stateResponse.doubling.decision.id],
  ["event-opportunity", opportunity.decisionId],
  ["event-offered", offered.offerId],
  ["event-accepted", accepted.offerId],
  ["event-declined", declined.offerId],
  ["command-roll", fixture("examples/command-roll.json").RequestRoll.decisionId],
  ["command-offer", fixture("examples/command-offer.json").OfferDouble.decisionId],
  ["command-respond", fixture("examples/command-respond.json").RespondDouble.decisionId],
  ["webhook-opportunity-response", fixture("examples/webhook-opportunity-response.json").decisionId],
  ["webhook-decision-response", fixture("examples/webhook-decision-response.json").decisionId],
]) {
  assert(id === decisionId, `${name} must carry the episode's decision id ${decisionId}`);
}

// Versions: the opportunity is published at the pre-roll version, the offer commits the next one, the answer the one after.
assert(opportunity.v === stateOpportunity.version, "opportunity event must carry the opportunity state version");
assert(
  offered.v === stateOpportunity.version + 1 && stateResponse.version === offered.v,
  "DoubleOffered must commit the next version and match the response state",
);
assert(accepted.v === offered.v + 1 && declined.v === offered.v + 1, "the answer commits the version after DoubleOffered");

// Seats and cube ownership.
assert(opportunity.seat === stateOpportunity.doubling.turnSeat, "opportunity seat must be the turn seat");
assert(offered.by === opportunity.seat && offered.to !== offered.by, "the offer goes from the turn seat to the opponent");
assert(
  stateResponse.doubling.decision.offeredBy === offered.by && stateResponse.doubling.decision.seat === offered.to,
  "response state must name the offerer and the responder",
);
assert(accepted.by === offered.to && declined.by === offered.to, "only the responder accepts or declines");
assert(accepted.cubeOwner === accepted.by, "acceptance transfers cube ownership to the responder");
assert(accepted.cubeValue === stateOpportunity.doubling.cubeValue * 2, "acceptance doubles the cube");

// Stakes.
const { initialStake, currentStake } = stateOpportunity.doubling;
for (const [label, event] of [
  ["opportunity", opportunity],
  ["offered", offered],
  ["declined", declined],
]) {
  assert(event.currentStake === currentStake, `${label} event must carry the pre-offer stake`);
  assert(event.proposedStake === event.currentStake * 2, `${label} event has invalid stake math`);
}
assert(stateResponse.doubling.currentStake === currentStake, "an unanswered offer does not change the current stake");
assert(
  accepted.currentStake === offered.proposedStake && accepted.currentStake === initialStake * accepted.cubeValue,
  "accepted stake must equal the offered stake and initialStake * cubeValue",
);

// Clocks are as of the carrying event: the offer charges the turn owner; the responder is not charged before its phase.
const owner = offered.by.toLowerCase();
const responder = offered.to.toLowerCase();
assert(stateResponse.clocks[owner] <= stateOpportunity.clocks[owner], "the offer charges the turn owner's clock");
assert(
  stateResponse.clocks[responder] === stateOpportunity.clocks[responder],
  "the responder's clock is untouched until its phase begins",
);

// Webhook deliveries carry exactly the snapshot a stream or GET /games/{id} would serve.
assert(
  canonical(fixture("examples/webhook-opportunity.json").state) === canonical(stateOpportunity),
  "doubleOpportunity webhook state must equal the opportunity snapshot",
);
assert(
  canonical(fixture("examples/webhook-decision.json").state) === canonical(stateResponse),
  "doubleDecision webhook state must equal the response snapshot",
);

// Outcomes: the applied offer reports the committed version, a duplicate replays it, a conflict applies nothing.
const appliedOutcome = fixture("examples/decision-outcome.json");
const duplicateOutcome = fixture("examples/decision-duplicate.json");
const conflictOutcome = fixture("examples/decision-conflict.json");
assert(
  appliedOutcome.applied && appliedOutcome.version === offered.v && appliedOutcome.decisionId === decisionId,
  "applied outcome must report the committed offer version",
);
assert(
  duplicateOutcome.applied &&
    duplicateOutcome.duplicate &&
    duplicateOutcome.version === appliedOutcome.version &&
    duplicateOutcome.decisionId === decisionId,
  "duplicate outcome must replay the applied decision version and id",
);
assert(
  !conflictOutcome.applied && conflictOutcome.version === null && conflictOutcome.reason,
  "conflict outcome must fail with a reason and no committed version",
);

// Analytics: zero-sum settlement of the decided stake; a draw settles nothing.
const analytics = fixture("examples/analytics-projection.json");
assert(analytics.white_money_delta + analytics.black_money_delta === 0, "play-credit settlement must be zero-sum");
if (analytics.termination === "draw_agreement") {
  assert(analytics.white_money_delta === 0, "a draw settles zero net credits");
} else {
  assert(Math.abs(analytics.white_money_delta) === analytics.final_stake_amount, "the decided stake changes hands in full");
}
assert(
  analytics.initial_stake_amount === initialStake && analytics.final_stake_amount === accepted.currentStake,
  "analytics projection must match the accepted stake chain",
);

// Fail-closed cases the schema must reject, and live shapes it must keep accepting.
function rejects(schemaRef, value, name) {
  assert(!validatorFor(schemaRef)(value), `schema accepted ${name}`);
}
function accepts(schemaRef, value, name) {
  const validate = validatorFor(schemaRef);
  assert(validate(value), `schema rejected ${name}: ${ajv.errorsText(validate.errors)}`);
}
const mutated = (base, mutate) => {
  const copy = structuredClone(base);
  mutate(copy);
  return copy;
};

const request = fixture("examples/create-play-bot.json");
rejects("#/$defs/PlayBotRequest", mutated(request, (r) => (r.rated = true)), "a rated stake");
rejects("#/$defs/PlayBotRequest", mutated(request, (r) => (r.stake.currency = "EUR")), "an external-value currency");
rejects("#/$defs/PlayBotRequest", mutated(request, (r) => (r.stake.maximumMultiplier = 3)), "an unsupported multiplier");
rejects("#/$defs/PlayBotRequest", mutated(request, (r) => (r.timeControl = { Fischer: { initialSeconds: 300 } })), "a malformed time control");
accepts("#/$defs/PlayBotRequest", mutated(request, (r) => (r.guestId = "6b3d8c1e-2f4a-4b5c-9d7e-0a1b2c3d4e5f")), "the live guestId member");
accepts("#/$defs/PlayBotRequest", mutated(request, (r) => delete r.rated), "the live rated default");
accepts("#/$defs/ChallengeRequest", mutated(fixture("examples/create-challenge.json"), (r) => delete r.timeControl), "the live Unlimited challenge default");

const doubling = stateOpportunity.doubling;
rejects("#/$defs/DoublingState", mutated(doubling, (d) => (d.cubeOwner = "White")), "an owned centered cube");
rejects("#/$defs/DoublingState", mutated(doubling, (d) => Object.assign(d, { cubeValue: 2, currentStake: 20 })), "an unowned doubled cube");

const snapshot = "#/$defs/StakedDecisionSnapshot";
rejects(snapshot, mutated(stateOpportunity, (s) => (s.dicePending = true)), "revealed dice during a decision");
rejects(snapshot, mutated(stateOpportunity, (s) => (s.legalMoves = {})), "published legal moves during a decision");
rejects(snapshot, mutated(stateOpportunity, (s) => (s.doubling.decision = null)), "a decision phase without a decision");
rejects(snapshot, mutated(stateOpportunity, (s) => (s.doubling.mayOfferDouble = false)), "an ineligible offer decision");
rejects(snapshot, mutated(stateResponse, (s) => (s.doubling.mayOfferDouble = true)), "a responder who may offer");
rejects(snapshot, mutated(stateOpportunity, (s) => (s.commit = "c0ffee")), "a truncated dice commitment");
rejects(snapshot, mutated(stateOpportunity, (s) => delete s.clocks.black), "a one-sided clock object");
rejects(
  "#/$defs/DoubleOpportunityWebhook",
  mutated(fixture("examples/webhook-opportunity.json"), (w) => (w.state = structuredClone(stateResponse))),
  "an opportunity delivery carrying a response decision",
);

const outcome = "#/$defs/DecisionOutcome";
rejects(outcome, mutated(appliedOutcome, (o) => (o.reason = "unexpected")), "an applied outcome with an error reason");
rejects(outcome, mutated(appliedOutcome, (o) => (o.version = null)), "an applied outcome without a version");
rejects(outcome, mutated(conflictOutcome, (o) => (o.duplicate = true)), "a rejected outcome as a duplicate replay");
rejects(outcome, mutated(conflictOutcome, (o) => (o.version = 1)), "a rejected outcome with a committed version");

const catalog = fixture("examples/capability-catalog.json");
rejects(
  "#/$defs/CapabilityCatalog",
  mutated(catalog, (c) => c.capabilities.push({ name: "doubling", status: "available", selectable: true })),
  "a selectable doubling entry",
);
rejects("#/$defs/CapabilityCatalog", mutated(catalog, (c) => (c.capabilities = [c.capabilities[0]])), "a catalog without doubling");

console.log(`Validated ${fixtures.size} stake-doubling contract fixtures and semantic invariants.`);
