# Automerge-kt

Automatically merge all pull requests in the repo with the `Automerge` label from oldest to newest.

## How it works


- If that PR has merge conflicts or a github status has failed (e.g. CI check failure or no approvals), the label on that PR is removed and the app exits.
- If that PR still has outstanding github statuses (that is, they are currently still running), nothing is done and the app exits.
- If that PR has no merge conflicts and all github statuses have passed, the PR is merged and the branch is deleted.

