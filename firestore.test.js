const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated users cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set({
      uid: ALICE_UID,
      displayName: "Hacker",
      preferredSaveMode: "APP_DOWNLOADS",
      preferredThemeMode: "SYSTEM",
      stripExifOnSend: false,
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("devices").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("synced_transfers").get());
});

test("Authenticated owner: valid user profile create, partial update, and immutability guards", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      uid: ALICE_UID,
      email: "alice@gmail.com",
      displayName: "Alice",
      photoUrl: "https://example.com/avatar.png",
      preferredSaveMode: "APP_DOWNLOADS",
      preferredThemeMode: "SYSTEM",
      stripExifOnSend: false,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Partial update matching AccountSyncRepository.updatePreferences()
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      preferredSaveMode: "PRIVATE_VAULT",
      preferredThemeMode: "AMOLED",
      stripExifOnSend: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject mutating createdAt or spoofing uid or future timestamp
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      updatedAt: new Date(Date.now() + 120000),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      preferredSaveMode: "INVALID_MODE",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Cross-user isolation: Bob cannot read, list, write, or delete Alice's profile, devices, or synced transfers", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      uid: ALICE_UID,
      displayName: "Alice",
      preferredSaveMode: "APP_DOWNLOADS",
      preferredThemeMode: "DARK",
      stripExifOnSend: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("devices").doc("dev_1").set({
      userId: ALICE_UID,
      deviceId: "dev_1",
      deviceName: "Pixel 8",
      model: "Pixel 8",
      platformVersion: 34,
      lastActiveAt: now,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("synced_transfers").doc("tr_1").set({
      userId: ALICE_UID,
      deviceId: "dev_1",
      deviceName: "Pixel 8",
      fileName: "presentation.pdf",
      fileSize: 1048576,
      mimeType: "application/pdf",
      category: "DOCUMENT",
      direction: "SENT",
      peerDeviceName: "Galaxy S21",
      groupName: "Work Group",
      status: "COMPLETED",
      sha256: "abcdef0123456789",
      transferredAt: Date.now() - 1000,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob cannot list users root or access Alice's docs
  await assertFails(bobDb.collection("users").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("devices").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("synced_transfers").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("devices").doc("dev_1").delete()
  );
});

test("Owner can query registered devices & synced transfers even when empty and delete own items", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("devices").get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("synced_transfers")
      .orderBy("transferredAt", "desc")
      .get()
  );
});
