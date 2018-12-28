const val LABEL_REMOVAL_DEFAULT = """
Uh oh! It looks like there was a problem trying to automerge this pull request.
Here are some possible reasons why the label may have been removed:
- There are outstanding reviews that need to be addressed before merging is possible
- There are merge conflicts with the base branch
- There are status checks failing

If none of those seem like the problem, try looking at the logs for more information.
"""

const val LABEL_REMOVAL_STATUS_CHECKS = """
Uh oh! It looks like there was a problem trying to automerge this pull request.

It seems likely that this is due to a cancelled or failing status check. Take a look at your statuses and
 get them passing before reapplying the automerge label.
"""

const val LABEL_REMOVAL_OPTIONAL_CHECKS = """
Uh oh! It looks like there was a problem trying to automerge this pull request.

It seems likely that this is due to a cancelled or failing non-required status check. Take a look at your
 statuses and get them passing before reapplying the automerge label.

Alternatively, you can set the `OPTIONAL_STATUSES` environment variable to `true` to ignore optional statuses
"""

const val LABEL_REMOVAL_MERGE_CONFLICTS = """
Uh oh! It looks like there was a problem trying to automerge this pull request.

It seems likely that there are merge conflicts with the base branch that can't automatically be resolved.
Resolve any conflicts with the base branch before reapplying the automerge label.
"""

const val LABEL_REMOVAL_OUTSTANDING_REVIEWS = """
Uh oh! It looks like there was a problem trying to automerge this pull request.

It seems likely that there are some outstanding reviews that still need to be addressed before merging is possible.
Address any remaining reviews before reapplying the automerge label
"""
