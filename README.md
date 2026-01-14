# multibranch-filter

## Introduction

Multibranch Filter Plugin adds a multibranch pipeline SCMSource trait that excludes branches which
have been inactive for a configurable number of days. It supports a whitelist and blacklist (as
regular expressions) so you can always include or always exclude specific branches.

Why I created this:
- Hide or skip discovery of stale branches without deleting them on the remote.
- Keep historical Jenkinsfiles in the repo while avoiding unnecessary indexing/builds.

Key behaviors:
- Inactivity is based on `SCMFileSystem.lastModified()` for the branch head.
  For Git, this corresponds to the commit timestamp of the HEAD revision.
- Blacklist takes precedence over whitelist.
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
   - **Whitelist**: newline- or comma-separated regular expressions to always include.
   - **Blacklist**: newline- or comma-separated regular expressions to always exclude.

Examples:

Whitelist master and main (default):
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
multibranchPipelineJob('ConfigurationManager') {
  branchSources {
    git {
      traits {
        inactiveBranchFilter {
          inactivityDays(10)
          whitelist('master\nmain\n.*release.*')
          blacklist('')
        }
      }
    }
  }
}
```

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)
