# Automerge-kt

Automatically update and merge all github pull requests (PRs) in the repo with the `Automerge` label from oldest to newest.

## Configuration
Replace the placeholders in the [config.yml](https://github.com/MclaughlinSteve/automerge-kt/blob/master/src/main/resources/config.yml) with the information relative to your project (i.e. add a github token, the label that you want to use for automerging, and the url to as many repos as you want to run it across).

Also, if you want to change the rate that it runs, update the `INTERVAL` constant in [Main.kt](https://github.com/MclaughlinSteve/automerge-kt/blob/master/src/main/kotlin/Main.kt).

## Running it
`gradle run`

## How it works

It runs on an interval against the repositories specified in the config, and for each repository
it runs the following asynchronously (so that it can run all of the specified repositories in parallel):
- Finds the oldest PR with the `Automerge` label on it
- If that PR has merge conflicts or a github status has failed (e.g. CI check failure or no approvals), 
    the label on that PR is removed.
- If that PR still has outstanding github statuses (that is, they are currently still running), 
    nothing is done while it waits for the statuses to come back.
- If that PR has no merge conflicts and all github statuses have passed, 
    the PR is merged and the branch is deleted.

Note: It only works on a single PR per repository at a time so that it doesn't blow up your CI pipeline with builds 
for every labeled pull request (Including this because someone thought that it was calling update branch on every 
single labeled PR at once rather than dealing with PRs one-by-one from oldest to newest).


#### Acknowledgements

I based this on a similar project that a former coworker built using clojure that does essentially the same thing. 
I wanted to write something in kotlin and needed a few things specific to my new team.
Thanks to [Adam](https://github.com/AdamReifsneider) for the inspiration. 
Check out his project [here](https://github.com/AdamReifsneider/pull-automerge).

