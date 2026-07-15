# multibranch-filter

## Introduction

Multibranch Filter Plugin adds a multibranch pipeline SCMSource trait that excludes branches which
have been inactive for a configurable number of days. It supports an allow list and deny list (as
regular expressions) so you can always include or always exclude specific branches.

Why I created this:

- Hide or skip discovery of stale branches without deleting them on the remote.
- Keep historical Jenkinsfiles in the repo while avoiding unnecessary indexing/builds.

Key behaviors:

- Inactivity is based on the branch discovery probe's `lastModified()` value.
  For Git, this is the commit timestamp of the HEAD revision and is read from the
  scan-wide Git fetch rather than by creating a separate file-system checkout per branch.
- Deny list takes precedence over allow list.
- Change requests and tags are always included.
- If the SCM does not support `lastModified()`, the branch is kept.

## Getting started

1. Install the plugin and restart Jenkins.
2. Configure your Multibranch Pipeline job:
   - Job → Configure → Branch Sources → your SCM → Behaviors/Traits
   - Add **Filter inactive branches**
3. Configure the fields:
   - **Inactive after (days)**: number of days without activity before a branch is excluded.
     Set to `0` to disable inactivity filtering.
   - **Allow list**: newline-separated regular expressions to always include.
   - **Deny list**: newline-separated regular expressions to always exclude.

Examples:

Allow list master and main (default):

```
master
main
```

Exclude all WIP branches:

```
wip/.*
```

Job DSL example:

```
multibranchPipelineJob('X') {
  branchSources {
    branchSource {
      source {
        git {
          traits {
            inactiveBranchFilter {
              inactivityDays(10)
              allowlist('master\nmain\n.*release.*')
              denylist('')
            }
          }
        }
      }
    }
  }
}
```

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)
