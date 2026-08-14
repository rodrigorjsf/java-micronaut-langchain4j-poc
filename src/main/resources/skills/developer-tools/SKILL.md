---
name: developer-tools
description: Software ecosystem lookups — GitHub repositories and profiles, Hacker News stories, Stack Overflow questions, and the current published version and licence of any npm, PyPI, crates.io or Maven Central package. Use for questions about a library version, a repository, or developer news.
---

# Developer tools

Eleven tools over four kinds of source: GitHub, Hacker News, Stack Overflow and
the package registries. The registries are the ones that matter most — a
published version is a fact that changes weekly, and a remembered version number
is how a user ends up installing a release that was yanked or never existed.
**Never answer a "what is the latest version" question from memory.**

## Choosing a tool

| The user gives you | Use |
|---|---|
| `owner/repo`, or a GitHub project by name | `get_github_repo` |
| a GitHub handle, or asks who maintains something | `get_github_user` |
| "what's on Hacker News", "tech news today" | `get_hackernews_front_page` |
| a topic, and wants what the community said about it | `search_hackernews` |
| a Hacker News id you already have | `get_hackernews_item` |
| a need for front-page ranking position specifically | `get_hackernews_top` |
| an error message, or a "how do I" programming problem | `search_stackoverflow` |
| a JavaScript or TypeScript package name | `get_npm_package` |
| a Python package name | `get_pypi_package` |
| a Rust crate name | `get_crate` |
| a Java or Kotlin dependency, or a Maven group/artifact | `search_maven_artifact` |

## How to combine them

- **Version, then context.** `get_npm_package`, `get_pypi_package`, `get_crate`
  and `search_maven_artifact` each answer in one call. Only reach for
  `get_github_repo` afterwards if the user asked about activity or maintenance —
  the registry already told you the version and the licence.
- **Front page in one call.** `get_hackernews_front_page` returns titles, scores
  and links directly. `get_hackernews_top` returns only ids, and turning ten ids
  into ten readable stories costs ten more calls, which exhausts the turn's tool
  budget before you can answer. Use `get_hackernews_top` only when the user
  actually needs "what is number one right now", then read at most two or three
  ids with `get_hackernews_item`.
- **Topic before ranking.** When the user names a subject rather than asking what
  is new, `search_hackernews` searches the whole archive; the front page only has
  today.
- **Maven needs the group.** `search_maven_artifact` with `a:micronaut-core`
  finds the artifact and its group id; once you have `g:io.micronaut` you can
  list a whole family. Plain words work too but rank badly.

## Rate limits — spend these deliberately

GitHub is limited to **60 requests per hour for this whole service, shared by
every user**, and Stack Overflow to **300 per day** on the same terms. There is
no per-user allowance, so one enthusiastic conversation can leave the tool dead
for everyone else for an hour.

- Call `get_github_repo` or `get_github_user` **at most once per turn**, and only
  when the user actually asked about the repository — not to decorate an answer a
  registry already gave you.
- **When a GitHub tool reports the service is unavailable or refused the
  request**, the overwhelmingly likely cause is that hourly budget. Tell the user
  the GitHub lookup is rate-limited and may work again within the hour, answer
  from what you already have, and name explicitly what you could not verify.
  **Do not try the other GitHub tool** — all of them share one budget, so the
  second call fails the same way and spends another request.
- Stack Overflow results carry `quota_remaining`, the calls left today for the
  whole service. When it is low, stop searching and answer from what you have.

## Reading the results

- **GitHub.** `stargazers_count` is popularity, not quality; `pushed_at` is the
  last push to any branch and is the honest activity signal. A missing
  `license.spdx_id` means unlicensed or unrecognised — say so rather than
  assuming open source. `open_issues_count` includes open pull requests.
- **Hacker News.** `points` is the score and `num_comments` the discussion size;
  `created_at` matters, because a highly-rated thread about a framework can be
  six years stale. `time` on an item is a Unix timestamp in seconds.
- **Stack Overflow.** `is_answered` means an accepted answer exists, `score` is
  the question's votes, not the answer's. These tools return question titles and
  links, never the answer body — read the title and say the discussion is there;
  do not present a summarised answer as if it came from the page.
- **Packages.** `version` is the current published release, not necessarily the
  one the user should install for their runtime — check `requires_python` on
  PyPI. Maven's `latestVersion` can be a release candidate. crates.io returns
  `max_stable_version`, which deliberately skips pre-releases.
- **Empty results** from any search tool mean the wording missed. Suggest a
  different term rather than concluding the library does not exist.

## What this skill does not cover

No source code, file contents, issues, pull requests, releases, commits or CI
status — the GitHub tools return repository metadata only. No private
repositories and nothing that needs a login. No package download counts on npm or
PyPI, no dependency trees, no vulnerability or CVE data, and no answer bodies
from Stack Overflow. Documentation lookups and encyclopedic questions belong to
the `knowledge-and-research` skill.

## When a tool fails

- **Not found** for a package or repository usually means a spelling or a
  scope/group mistake — `@babel/core` rather than `babel`, `io.micronaut` rather
  than `micronaut`. Ask the user to confirm the exact name; never guess a version
  for a package the registry does not know.
- **Rate limited** — do not retry in the same turn, and do not switch to a
  sibling tool on the same service. Say the source is busy and answer from what
  you already have.
- **Unavailable** — for GitHub read it as the hourly budget, as described above.
  For anything else, tell the user the source is temporarily down and move on;
  retrying inside the same turn will not help.
- **Invalid arguments** tells you what was wrong with the call. Fix it yourself —
  usually a name where an id belongs, or a repository passed as one string
  instead of an owner and a name.
