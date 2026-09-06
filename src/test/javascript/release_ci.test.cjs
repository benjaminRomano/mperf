const {test} = require("node:test");
const assert = require("node:assert/strict");
const modulePromise = import("../../../scripts/check-source-ci.mjs");

test("release accepts only the exact main push and all required successful jobs", async () => {
  const {validateRun, validateJobs} = await modulePromise;
  const run = {head_sha: "abc", head_branch: "main", event: "push"};
  validateRun(run, "abc");
  for (const changed of [{head_sha: "other"}, {head_branch: "feature"}, {event: "pull_request"}]) {
    assert.throws(() => validateRun({...run, ...changed}, "abc"));
  }
  assert.throws(() => validateRun(undefined, "abc"));
  const jobs = ["Build (Gradle, JDK 21)", "Android emulator integration", "iOS Simulator integration"]
    .map(name => ({name, status: "completed", conclusion: "success", run_attempt: 1}));
  validateJobs(jobs);
  for (const conclusion of ["failure", "skipped", "cancelled", null]) {
    assert.throws(() => validateJobs([jobs[0], jobs[1], {...jobs[2], conclusion}]));
  }
  assert.throws(() => validateJobs(jobs.slice(0, 2)));
  assert.throws(() => validateJobs([...jobs, jobs[0]]));
  assert.throws(() => validateJobs([jobs[0], jobs[1], {...jobs[2], status: "in_progress"}]));
  const failed = {...jobs[2], conclusion: "failure"};
  validateJobs([jobs[0], jobs[1], failed, {...jobs[2], run_attempt: 2}]);
  assert.throws(() => validateJobs([...jobs, {...failed, run_attempt: 2}]));
  assert.throws(() => validateJobs([...jobs, {...jobs[2], run_attempt: undefined}]));
});
