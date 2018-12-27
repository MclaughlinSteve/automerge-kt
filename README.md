[![Build Status](https://travis-ci.com/MclaughlinSteve/automerge-kt.svg?branch=master)](https://travis-ci.com/MclaughlinSteve/automerge-kt)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/73e41539dc1144e69f79346d63a05931)](https://www.codacy.com/app/MclaughlinSteve/automerge-kt?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=MclaughlinSteve/automerge-kt&amp;utm_campaign=Badge_Grade)
[![Maintainability](https://api.codeclimate.com/v1/badges/5ec4d88711d37510eb50/maintainability)](https://codeclimate.com/github/MclaughlinSteve/automerge-kt/maintainability)
[![codecov](https://codecov.io/gh/MclaughlinSteve/automerge-kt/branch/master/graph/badge.svg)](https://codecov.io/gh/MclaughlinSteve/automerge-kt)
[![ktlint](https://img.shields.io/badge/code%20style-%E2%9D%A4-FF4081.svg)](https://ktlint.github.io/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

some change

# Github-automerge

Automatically update and merge all github pull requests (PRs) in the specified repos with the `Automerge` label from oldest to newest.

## Configuration
Set the `GITHUB_USER_TOKEN` environment variable to `your-user-token`

(Optional) Set the `AUTOMERGE_LABEL` environment variable with whatever label you're using to automerge. 
By default `Automerge` will be used.

(Optional) Set the `PRIORITY_LABEL` environment variable with whatever label you're using for tagging something as 
high priority. By default `Priority Automerge` will be used. The `Priority Automerge` label allows a PR to
take precedence in the merge order.

(Optional) Set the `MERGE_TYPE` environment variable to your desired merge type (Valid options are "merge", "squash",
 and "rebase"). This will determine the type of merge that is performed when merging pull requests. By default, "squash" 
 will be used

Replace the repository url in the [config.yml](src/main/resources/config.yml) with the information relative to your 
project (i.e. a list of the urls to as many repos as you want to run it across in parallel).

__For local running only__: If you want to change the rate that it runs, update the `INTERVAL` constant in 
[Main.kt](src/main/kotlin/Main.kt).
Note: This does not apply for running the job on AWS.

## Running it
`gradle run`

## Running it on AWS Lambda

-   Set your repositories in the `config.yml` file
  
-   Build a jar `./gradlew shadowJar`
  
-   Upload the jar in the "Function Code" section on lambda, choose "Java 8" as the runtime, 
  and set the handler to "MainKt::handleLambda"
  
-   Set your environment variables (Minimum `GITHUB_USER_TOKEN`)
    
-   Set your memory usage (I'm testing with 256 MB)

Recommended: Set the project to run on a periodic basis
-   Add a "CloudWatch Event trigger"

-   Set the rule type to "schedule expression" and set the expression (e.g. `rate(5, minute)` 
  This runs the function once every five minutes)
  
## How it works

It runs on an interval against the repositories specified in the config, and for each repository
it runs the following asynchronously (so that it can run all of the specified repositories in parallel):
-   Finds the oldest PR with the `Automerge` label on it (Or if there is a PR with the `Priority Automerge` label,
    it will grab the oldest PR with that label instead)

-   If that PR has merge conflicts or a github status has failed (e.g. CI check failure or no approvals), 
    the label on that PR is removed, and a comment is left on the PR with a guess about why the label was removed.

-   If that PR still has outstanding github statuses (that is, they are currently still running), 
    nothing is done while it waits for the statuses to come back.

-   If that PR has no merge conflicts and all github statuses have passed, 
    the PR is squash-merged and the branch is deleted.

Note: It only works on a single PR per repository at a time so that it doesn't blow up your CI pipeline with builds 
for every labeled pull request (Including this because someone thought that it was calling update branch on every 
single labeled PR at once rather than dealing with PRs one-by-one from oldest to newest).

#### Acknowledgements

I based this on a similar project that a former coworker built using clojure that does essentially the same thing. 
I wanted to write something in kotlin and wanted to learn something and build out some additional features.
Thanks to [Adam](https://github.com/AdamReifsneider) for the inspiration. 
Check out his project [here](https://github.com/AdamReifsneider/pull-automerge).
