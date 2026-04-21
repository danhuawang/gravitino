<!--
  Copyright 2024 Datastrato Pvt Ltd.
  This software is licensed under the Apache License version 2.
-->

This is a enterprise edition of Apache Gravitino, which builds on top of the open source version of Apache Gravitino.

## Building Datastrato Gravitino

Since the project is built using git submodule, there are a few extra steps to build the project.

Clone the repository with the `--recurse-submodules` flag to clone the submodules as well.

```shell
git clone --recurse-submodules git@github.com:datastrato/gravitino-enterprise.git
```

If you have already cloned the repository without the `--recurse-submodules` flag, you can run the following command to clone the submodules.

```shell
git submodule update --init --recursive
```

If you want to build a distribution package, please run:

```shell
./gradlew compileDistribution -x test
```

to build a distribution package.

Skip building and packaging both Web UIs:

```bash
./gradlew compileDistribution -PskipWebBuild=true -x test
```

Or compressed package:

```shell
./gradlew assembleDistribution -x test
```

to build a compressed distribution package.

The directory `distribution` contains the generated binary distribution package.

## Contributing to Datastrato Gravitino

Because the project contains a git submodule, there are some additional steps to be aware of when performing certain git operations.

### 1. checkout a remote branch

You should use the following command to checkout a remote branch:

```shell
git checkout <branch_name> --recurse-submodules
```

Or:

```shell
git checkout <branch_name> && git submodule update --init --recursive
```

### 2. update submodule

Note that you cannot modify the submodule codes directly, you can only change the reference of the submodule (checkout to a specific commit, tag, or branch).

If you want to update submodule to align with the remote parent project, you should use the following command:

```shell
git submodule update --init --recursive
```

If you want to update submodule to latest commit on the `.gitmodules` file specified branch, you should use the following command:

```shell
git submodule update --remote --rebase
```

If you want to update submodule to a specific commit, you should use the following command:

```shell
cd gravitino-internal
git checkout <commit_hash>
cd ..
git add gravitino-internal
git commit -m "update submodule to <commit_hash>"
```