# Contributing

We warmly welcome and greatly appreciate contributions from the
community. By participating you agree to the [code of
conduct](https://github.com/warehouse-pg/whpg-pxf/blob/release-6.x/CODE-OF-CONDUCT.md).
Overall, we follow WarehousePG's comprehensive contribution policy. Please
refer to it [here](https://github.com/warehouse-pg/warehouse-pg/blob/main/CONTRIBUTING.md)
for details.

## Getting Started

- Fork the PXF repository on GitHub.

- Clone the repository.

- Follow the README.md to set up your environment and run the tests.

- Create a change

    - Create a topic branch.

    - Make commits as logical units for ease of reviewing.

    - Follow similar coding styles as found throughout the code base.

    - Rebase with main often to stay in sync with upstream.

    - Add appropriate unit and automation tests.

    - Ensure a well written commit message as explained [here](https://chris.beams.io/posts/git-commit/) and [here](https://tbaggery.com/2008/04/19/a-note-about-git-commit-messages.html).

- Submit a pull request (PR).

    - The [PXF CI](.github/workflows/README.md) workflow runs automatically
      on pull requests targeting `main` and `release-6.x` (unit tests, CLI
      tests, a compile check for the integration-test tree, and static docs
      checks) — please keep it green.

    - Create a [pull request from your fork](https://help.github.com/en/github/collaborating-with-issues-and-pull-requests/.creating-a-pull-request-from-a-fork).

    - Address PR feedback with fixup and/or squash commits.
        ```
        git add .
        git commit --fixup <commit SHA> 
            Or
        git commit --squash <commit SHA>
        ```    

    - Once the PR is approved, project committers will merge it to main
      branch according to the product release schedule. They might further
      squash the commits in the PR if they deem necessary.

# Community

Connect with WarehousePG on:
* [Github](https://github.com/warehouse-pg/whpg-pxf/discussions)
