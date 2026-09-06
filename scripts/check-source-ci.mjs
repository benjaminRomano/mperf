import {execFileSync} from "node:child_process";
import {pathToFileURL} from "node:url";

const requiredJobs = ["Build (Gradle, JDK 21)", "Android emulator integration", "iOS Simulator integration"];

export function validateRun(run, sha) {
  if (run?.head_sha !== sha || run.head_branch !== "main" || run.event !== "push") {
    throw new Error("Release requires a CI push run for this exact commit on main");
  }
}

export function validateJobs(jobs) {
  for (const name of requiredJobs) {
    const history = jobs.filter(job => job.name === name);
    const latestAttempt = Math.max(0, ...history.map(job => job.run_attempt));
    const matches = history.filter(job => job.run_attempt === latestAttempt);
    if (matches.length !== 1 || matches[0].status !== "completed" || matches[0].conclusion !== "success") {
      throw new Error("Required source CI job did not pass: " + name);
    }
  }
}

function gh(...args) {
  return JSON.parse(execFileSync("gh", args, {encoding: "utf8"}));
}

function main() {
  const sha = process.argv[2];
  const repo = process.env.GITHUB_REPOSITORY;
  if (!/^[a-f0-9]{40}$/.test(sha ?? "") || !/^[\w.-]+\/[\w.-]+$/.test(repo ?? "")) {
    throw new Error("Usage: GITHUB_REPOSITORY=owner/repo node scripts/check-source-ci.mjs <commit-sha>");
  }
  const path = `repos/${repo}/actions/workflows/ci.yml/runs?head_sha=${sha}&branch=main&event=push&per_page=1`;
  const run = gh("api", path).workflow_runs[0];
  validateRun(run, sha);
  execFileSync("gh", ["run", "watch", String(run.id), "--repo", repo, "--exit-status", "--interval", "30"], {stdio: "inherit"});
  const completed = gh("api", `repos/${repo}/actions/runs/${run.id}`);
  validateRun(completed, sha);
  if (completed.status !== "completed" || completed.conclusion !== "success") throw new Error("Source CI did not pass");
  // A failed-jobs-only rerun keeps earlier successes. Check each job's latest execution,
  // never let an older success mask a newer failure, and include paginated attempt history.
  const pages = gh("api", `repos/${repo}/actions/runs/${run.id}/jobs?filter=all&per_page=100`, "--paginate", "--slurp");
  const jobs = pages.flatMap(page => page.jobs);
  validateJobs(jobs);
  console.log("Verified source CI:", completed.html_url);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
