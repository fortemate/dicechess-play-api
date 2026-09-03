import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";

const docsRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const contractRoot = path.join(docsRoot, "public/contracts/stake-doubling/v1");
const schema = JSON.parse(await readFile(path.join(contractRoot, "schema.json"), "utf8"));
const manifest = JSON.parse(await readFile(path.join(contractRoot, "fixtures.json"), "utf8"));

if (manifest.$schema !== schema.$id) {
  throw new Error(`Fixture manifest names ${manifest.$schema}; expected ${schema.$id}`);
}

const ajv = new Ajv2020({ allErrors: true, strict: true });
ajv.addSchema(schema);

const fixtures = new Map();
for (const fixture of manifest.fixtures) {
  if (fixtures.has(fixture.path)) throw new Error(`Duplicate fixture path: ${fixture.path}`);

  const validate = ajv.getSchema(`${schema.$id}${fixture.schemaRef}`);
  if (!validate) throw new Error(`Unknown schema reference: ${fixture.schemaRef}`);

  const fixturePath = path.resolve(contractRoot, fixture.path);
  if (!fixturePath.startsWith(`${contractRoot}${path.sep}`)) {
    throw new Error(`Fixture escapes the contract directory: ${fixture.path}`);
  }

  const value = JSON.parse(await readFile(fixturePath, "utf8"));
  if (!validate(value)) {
    throw new Error(`${fixture.path} failed ${fixture.schemaRef}: ${ajv.errorsText(validate.errors)}`);
  }

  fixtures.set(fixture.path, value);
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function validateDecisionState(state, source) {
  const doubling = state.doubling;
  const decision = doubling.decision;

  assert(state.dicePending === false, `${source}: dice must remain unrevealed during a decision`);
  assert(state.legalMoves == null, `${source}: legal moves must not be published before the roll`);
  assert(
    doubling.currentStake === doubling.initialStake * doubling.cubeValue,
    `${source}: currentStake must equal initialStake * cubeValue`,
  );
  assert(
    doubling.cubeValue <= doubling.maximumMultiplier,
    `${source}: cubeValue exceeds maximumMultiplier`,
  );
  assert(
    doubling.cubeValue === 1 ? doubling.cubeOwner === null : doubling.cubeOwner !== null,
    `${source}: a centered cube must be unowned and a doubled cube must be owned`,
  );
  assert(decision !== null, `${source}: a fixture for a decision phase must include its decision`);
  assert(state.activeSeat === decision.seat, `${source}: activeSeat must be the decision actor`);

  const dfenSeat = state.dfen.split(" ")[1] === "w" ? "White" : "Black";
  assert(dfenSeat === doubling.turnSeat, `${source}: DFEN active colour must match turnSeat`);

  if (decision.kind === "offer") {
    assert(doubling.mayOfferDouble === true, `${source}: an offer decision must be eligible`);
    assert(decision.seat === doubling.turnSeat, `${source}: an offer decision belongs to the turn seat`);
    assert(
      decision.proposedStake === doubling.currentStake * 2,
      `${source}: offer proposedStake must be twice currentStake`,
    );
  } else {
    assert(doubling.mayOfferDouble === false, `${source}: a responder cannot offer another double`);
    assert(decision.offeredBy === doubling.turnSeat, `${source}: turnSeat must preserve the offerer`);
    assert(decision.seat !== decision.offeredBy, `${source}: a seat cannot respond to its own offer`);
    assert(state.activeSeat !== dfenSeat, `${source}: a response actor must differ from the chess turn seat`);
    assert(
      decision.proposedStake === doubling.currentStake * 2,
      `${source}: response proposedStake must be twice currentStake`,
    );
  }
}

for (const [fixturePath, value] of fixtures) {
  if (fixturePath.startsWith("examples/state-")) validateDecisionState(value, fixturePath);
  if (fixturePath.startsWith("examples/webhook-") && value.state) {
    validateDecisionState(value.state, fixturePath);
    assert(value.seat === value.state.activeSeat, `${fixturePath}: webhook seat must match activeSeat`);
    const expectedKind = value.type === "doubleOpportunity" ? "offer" : "response";
    assert(
      value.state.doubling.decision.kind === expectedKind,
      `${fixturePath}: webhook type does not match decision kind`,
    );
  }
}

const opportunity = fixtures.get("examples/event-opportunity.json").DoubleOpportunity;
const offered = fixtures.get("examples/event-offered.json").DoubleOffered;
const accepted = fixtures.get("examples/event-accepted.json").DoubleAccepted;
const declined = fixtures.get("examples/event-declined.json").DoubleDeclined;
assert(opportunity.proposedStake === opportunity.currentStake * 2, "opportunity event has invalid stake math");
assert(offered.proposedStake === offered.currentStake * 2, "offered event has invalid stake math");
assert(declined.proposedStake === declined.currentStake * 2, "declined event has invalid stake math");
assert(accepted.currentStake === offered.proposedStake, "accepted stake must equal the offered stake");
assert(declined.currentStake === offered.currentStake, "decline must retain the pre-offer stake");

const appliedOutcome = fixtures.get("examples/decision-outcome.json");
const duplicateOutcome = fixtures.get("examples/decision-duplicate.json");
const conflictOutcome = fixtures.get("examples/decision-conflict.json");
assert(appliedOutcome.applied && appliedOutcome.reason === null, "applied outcome must carry no error reason");
assert(
  duplicateOutcome.applied &&
    duplicateOutcome.duplicate &&
    duplicateOutcome.version === appliedOutcome.version &&
    duplicateOutcome.decisionId === appliedOutcome.decisionId,
  "duplicate outcome must replay the applied decision version and id",
);
assert(!conflictOutcome.applied && conflictOutcome.reason, "conflict outcome must fail with a reason");

const analytics = fixtures.get("examples/analytics-projection.json");
assert(
  analytics.white_money_delta + analytics.black_money_delta === 0,
  "play-credit settlement must be zero-sum",
);
assert(
  Math.abs(analytics.white_money_delta) === analytics.final_stake_amount,
  "winner delta must equal final stake",
);

const validatePlayBot = ajv.getSchema(`${schema.$id}#/$defs/PlayBotRequest`);
const validRequest = structuredClone(fixtures.get("examples/create-play-bot.json"));
for (const [name, mutate] of [
  ["rated stake", (request) => (request.rated = true)],
  ["external-value currency", (request) => (request.stake.currency = "EUR")],
  ["unsupported multiplier", (request) => (request.stake.maximumMultiplier = 3)],
]) {
  const invalidRequest = structuredClone(validRequest);
  mutate(invalidRequest);
  assert(!validatePlayBot(invalidRequest), `schema accepted forbidden ${name}`);
}

console.log(`Validated ${fixtures.size} stake-doubling contract fixtures and semantic invariants.`);
