const fs = require("node:fs");
const path = require("node:path");
const { after, before, test } = require("node:test");
const { assertFails, assertSucceeds, initializeTestEnvironment } = require("@firebase/rules-unit-testing");
const { get, ref, remove, set } = require("firebase/database");

const projectId = "demo-pokebinder";
let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId,
    database: {
      host: "127.0.0.1",
      port: 9000,
      rules: fs.readFileSync(path.join(__dirname, "../../database.rules.json"), "utf8")
    }
  });
});

after(async () => {
  await testEnv?.cleanup();
});

test("each account can read and write its own collection and profile only", async () => {
  await testEnv.clearDatabase();
  const alice = testEnv.authenticatedContext("alice").database();
  const bob = testEnv.authenticatedContext("bob").database();
  const card = { "Copy ID": "copy-1", Name: "pikachu" };

  await assertSucceeds(set(ref(alice, "users/alice/collection_v2/copy-1"), card));
  await assertSucceeds(get(ref(alice, "users/alice/collection_v2")));
  await assertSucceeds(set(ref(alice, "users/alice/profile"), { username: "Alice" }));
  await assertSucceeds(get(ref(alice, "users/alice/profile")));
  await assertSucceeds(set(ref(bob, "users/bob/collection_v2/copy-2"), { "Copy ID": "copy-2", Name: "eevee" }));
  await assertSucceeds(set(ref(bob, "users/bob/profile"), { username: "Bob" }));

  await assertFails(get(ref(alice, "users/bob/collection_v2")));
  await assertFails(set(ref(alice, "users/bob/collection_v2/copy-3"), { "Copy ID": "copy-3" }));
  await assertFails(remove(ref(alice, "users/bob/profile")));
  await assertFails(get(ref(bob, "users/alice/profile")));
  await assertFails(set(ref(bob, "users/alice/collection_v2/copy-4"), { "Copy ID": "copy-4" }));
  await assertFails(remove(ref(bob, "users/alice/collection_v2/copy-1")));
  await assertFails(set(ref(alice, "users/alice/collection_v2/wrong-key"), { "Copy ID": "copy-5" }));
});

test("unauthenticated and parent reads are denied; legacy collections are read-only", async () => {
  await testEnv.clearDatabase();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), "users/alice/collection"), [{ Name: "old card" }]);
  });

  const anonymous = testEnv.unauthenticatedContext().database();
  const alice = testEnv.authenticatedContext("alice").database();

  await assertFails(get(ref(anonymous, "users/alice/collection_v2")));
  await assertFails(set(ref(anonymous, "users/alice/profile"), { username: "intruder" }));
  await assertFails(get(ref(alice, "users")));
  await assertFails(get(ref(alice, "users/alice")));
  await assertSucceeds(get(ref(alice, "users/alice/collection")));
  await assertFails(set(ref(alice, "users/alice/collection"), [{ Name: "replacement" } ]));
});
